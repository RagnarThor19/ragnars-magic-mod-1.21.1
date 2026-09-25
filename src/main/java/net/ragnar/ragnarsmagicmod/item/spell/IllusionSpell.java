package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.network.IllusionPayload;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Leave a perfect copy of yourself behind and slip away as a ghost for a few seconds. Mobs lose
 * you and go for the copy instead; the moment one of them lands a hit on it, the illusion
 * shatters and you're pulled back into sight.
 */
public class IllusionSpell implements Spell {
    private static final int GHOST_TICKS = 60;      // 3 seconds
    private static final double LURE_RADIUS = 32.0; // mobs this close are fooled by the copy

    private static final DustParticleEffect WISP = new DustParticleEffect(new Vector3f(0.8f, 0.92f, 1.0f), 0.9f);
    private static final DustParticleEffect SHIMMER = new DustParticleEffect(new Vector3f(0.55f, 0.75f, 1.0f), 1.2f);

    private static final class Ghost {
        final ServerWorld world;
        final UUID player;
        final IllusionEntity decoy;
        int ticksLeft = GHOST_TICKS;

        Ghost(ServerWorld world, UUID player, IllusionEntity decoy) {
            this.world = world;
            this.player = player;
            this.decoy = decoy;
        }
    }

    /** Ghosts by caster. */
    private static final Map<UUID, Ghost> GHOSTS = new HashMap<>();
    private static boolean registered = false;

    /** True while {@code entity} is a caster in ghost form - mobs can't see or pick them. */
    public static boolean isGhost(Entity entity) {
        return entity instanceof PlayerEntity && GHOSTS.containsKey(entity.getUuid());
    }

    public static boolean isLiveDecoy(IllusionEntity decoy) {
        for (Ghost g : GHOSTS.values()) if (g.decoy == decoy) return true;
        return false;
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(IllusionSpell::tick);
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        // Recasting while already a ghost starts over from here
        Ghost old = GHOSTS.remove(player.getUuid());
        if (old != null && !old.decoy.isRemoved()) fade(old.world, old.decoy);

        IllusionEntity decoy = ModEntities.ILLUSION.create(sw);
        if (decoy == null) return false;
        decoy.copyFrom(player);
        TempEntities.track(decoy);

        Ghost ghost = new Ghost(sw, player.getUuid(), decoy);
        GHOSTS.put(player.getUuid(), ghost);
        sw.spawnEntity(decoy);

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, GHOST_TICKS, 0, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, GHOST_TICKS, 0, false, false, true));

        lureMobs(sw, player, decoy, true);
        if (player instanceof ServerPlayerEntity sp) IllusionPayload.send(sp, GHOST_TICKS);

        // The split: a mirror-flash where you stood, and you step out of your own body
        Vec3d p = player.getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.2f, 1.0f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 0.8f, 1.3f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            double r = 0.9;
            sw.spawnParticles(ParticleTypes.ENCHANT, p.x + Math.cos(a) * r, p.y + 0.1, p.z + Math.sin(a) * r,
                    0, -Math.cos(a), 0.6, -Math.sin(a), 1.0);
        }
        for (double y = 0.1; y < player.getHeight(); y += 0.2) {
            sw.spawnParticles(SHIMMER, p.x, p.y + y, p.z, 2, 0.25, 0.02, 0.25, 0);
        }
        sw.spawnParticles(ParticleTypes.END_ROD, p.x, p.y + 1.0, p.z, 12, 0.3, 0.6, 0.3, 0.04);
        return true;
    }

    private static void tick(ServerWorld world) {
        if (GHOSTS.isEmpty()) return;
        Iterator<Ghost> it = GHOSTS.values().iterator();
        while (it.hasNext()) {
            Ghost g = it.next();
            if (g.world != world) continue;

            PlayerEntity player = world.getPlayerByUuid(g.player);
            if (player == null || !player.isAlive() || g.decoy.isRemoved()) {
                it.remove();
                if (!g.decoy.isRemoved()) fade(world, g.decoy);
                if (player != null) reveal(player, g, false);
                continue;
            }

            g.ticksLeft--;
            if (g.ticksLeft <= 0) {
                it.remove();
                fade(world, g.decoy);
                reveal(player, g, false);
                continue;
            }

            // Keep every nearby threat fixed on the copy
            if (g.ticksLeft % 5 == 0) lureMobs(world, player, g.decoy, false);

            // A faint ghostly wake at your feet (mobs don't notice it; other players might)
            if (g.ticksLeft % 2 == 0) {
                world.spawnParticles(WISP, player.getX(), player.getY() + 0.15, player.getZ(), 1, 0.2, 0.05, 0.2, 0);
            }
            if (g.ticksLeft % 6 == 0) {
                world.spawnParticles(ParticleTypes.SOUL, player.getX(), player.getY() + 0.9, player.getZ(), 1, 0.2, 0.4, 0.2, 0.01);
            }
            // The copy gives off the faintest shimmer, only up close
            if (g.ticksLeft % 10 == 0) {
                Vec3d d = g.decoy.getPos();
                world.spawnParticles(ParticleTypes.ENCHANT, d.x, d.y + 1.2, d.z, 2, 0.3, 0.5, 0.3, 0.2);
            }
        }
    }

    /** Called by the decoy when something hits it: it shatters and the caster is revealed. */
    public static void onDecoyHit(ServerWorld world, IllusionEntity decoy, DamageSource source) {
        Ghost ghost = null;
        for (Ghost g : GHOSTS.values()) {
            if (g.decoy == decoy) { ghost = g; break; }
        }
        if (ghost == null) { decoy.discard(); return; }
        if (source.getAttacker() != null && source.getAttacker().getUuid().equals(ghost.player)) return; // you can't break your own spell

        GHOSTS.remove(ghost.player);
        shatter(world, decoy);
        PlayerEntity player = world.getPlayerByUuid(ghost.player);
        if (player != null) reveal(player, ghost, true);
    }

    /** Point every hostile (and everything already chasing the caster) at the decoy. */
    private static void lureMobs(ServerWorld world, PlayerEntity player, IllusionEntity decoy, boolean announce) {
        Box area = decoy.getBoundingBox().expand(LURE_RADIUS);
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, area, m -> m != decoy && m.isAlive())) {
            LivingEntity current = mob.getTarget();
            boolean chasingCaster = current == player;
            boolean idleHostile = current == null && mob instanceof Monster
                    && mob.squaredDistanceTo(decoy) < 16 * 16;
            if (!chasingCaster && !idleHostile) continue;

            mob.setTarget(decoy);
            // Brain-driven mobs (piglins, hoglins...) keep their target in memory instead
            if (mob.getBrain().hasMemoryModule(MemoryModuleType.ATTACK_TARGET)) {
                mob.getBrain().remember(MemoryModuleType.ATTACK_TARGET, decoy);
            }
            if (announce && chasingCaster) {
                // A little puff over their head: they've lost you
                world.spawnParticles(ParticleTypes.POOF, mob.getX(), mob.getEyeY() + 0.5, mob.getZ(), 3, 0.15, 0.1, 0.15, 0.01);
            }
        }
    }

    private static void reveal(PlayerEntity player, Ghost g, boolean forced) {
        removeOurEffect(player, StatusEffects.INVISIBILITY, g.ticksLeft);
        removeOurEffect(player, StatusEffects.SPEED, g.ticksLeft);
        if (player instanceof ServerPlayerEntity sp) IllusionPayload.send(sp, 0);

        ServerWorld world = g.world;
        Vec3d p = player.getPos();
        if (forced) {
            // Yanked back into sight
            world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 0.8f, 1.4f);
            world.spawnParticles(SHIMMER, p.x, p.y + 1.0, p.z, 20, 0.3, 0.6, 0.3, 0);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y + 1.0, p.z, 20, 0.3, 0.6, 0.3, 0.05);
        } else {
            world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.6f, 0.7f);
            world.spawnParticles(SHIMMER, p.x, p.y + 1.0, p.z, 12, 0.3, 0.6, 0.3, 0);
        }
    }

    /** Take away the invisibility/speed the spell gave, but not a longer one from somewhere else. */
    private static void removeOurEffect(PlayerEntity player, RegistryEntry<StatusEffect> effect, int ticksLeft) {
        StatusEffectInstance inst = player.getStatusEffect(effect);
        if (inst != null && inst.getDuration() <= ticksLeft + 2) player.removeStatusEffect(effect);
    }

    /** The decoy is hit: it bursts like breaking glass. */
    private static void shatter(ServerWorld world, IllusionEntity decoy) {
        Vec3d p = decoy.getPos();
        world.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 1.3f);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 0.6f);
        BlockStateParticleEffect glass = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
        world.spawnParticles(glass, p.x, p.y + 1.0, p.z, 60, 0.3, 0.6, 0.3, 0.15);
        world.spawnParticles(SHIMMER, p.x, p.y + 1.0, p.z, 25, 0.35, 0.7, 0.35, 0);
        world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y + 1.0, p.z, 10, 0.2, 0.4, 0.2, 0.08);
        TempEntities.discard(decoy);
    }

    /** Time's up: the decoy quietly dissolves into mist. */
    private static void fade(ServerWorld world, IllusionEntity decoy) {
        Vec3d p = decoy.getPos();
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.8f, 0.5f);
        for (double y = 0.1; y < decoy.getHeight(); y += 0.15) {
            world.spawnParticles(SHIMMER, p.x, p.y + y, p.z, 2, 0.25, 0.02, 0.25, 0);
        }
        world.spawnParticles(ParticleTypes.POOF, p.x, p.y + 1.0, p.z, 8, 0.25, 0.5, 0.25, 0.01);
        TempEntities.discard(decoy);
    }
}
