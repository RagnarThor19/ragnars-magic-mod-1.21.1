package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.CloneEntity;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.network.CloneSwapPayload;
import net.ragnar.ragnarsmagicmod.network.CloneTimerPayload;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.util.Aim;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Clones: three perfect copies of you step out of your body and follow you around, copying what you do.
 * While they're out, casting again is free and skips the cooldown:
 * - at a creature: every clone draws your best weapon and hunts it down
 * - at one of your clones (or right-clicking it): you swap bodies with it
 * - at nothing: they drop what they're doing and fall back in around you
 * - while sneaking: they shatter, one after another
 * Otherwise they last {@link #LIFETIME_TICKS}, flickering for the last few seconds before they fade. The caster
 * sees a countdown under the crosshair, and the tome's cooldown only starts once the clones are gone.
 * They also join in on anything you hit or anything that hits you.
 */
public class ClonesSpell implements Spell {
    private static final int CLONE_COUNT = 3;
    private static final double RANGE = 48.0;
    private static final double ASSIST_RANGE = 24.0;
    private static final int SWAP_COOLDOWN_TICKS = 8;
    private static final int CALM_TICKS = 100; // after a regroup, don't pick a fight again straight away
    private static final int LIFETIME_TICKS = 20 * 24;
    private static final int WARN_TICKS = 20 * 3; // they start to flicker this long before they go

    private static final DustParticleEffect SHIMMER = new DustParticleEffect(new Vector3f(0.55f, 0.75f, 1.0f), 1.2f);
    private static final DustParticleEffect MARK = new DustParticleEffect(new Vector3f(1.0f, 0.2f, 0.25f), 1.4f);
    private static final BlockStateParticleEffect GLASS =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());

    private static final class Squad {
        final ServerWorld world;
        final UUID owner;
        final List<CloneEntity> clones = new ArrayList<>();
        final ArrayDeque<CloneEntity> dismissing = new ArrayDeque<>();
        final long summonedAt;
        LivingEntity order;
        long calmUntil;
        long lastSwap = -SWAP_COOLDOWN_TICKS;
        // The caster as of last tick, to spot jumps and swings to copy
        boolean wasOnGround = true;
        boolean wasSwinging;
        double lastY;

        Squad(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
            this.summonedAt = world.getTime();
        }
    }

    /** Squads by caster. */
    private static final Map<UUID, Squad> SQUADS = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(ClonesSpell::tick);
    }

    public static boolean isLiveClone(CloneEntity clone) {
        Squad s = clone.getOwnerUuid().map(SQUADS::get).orElse(null);
        return s != null && s.clones.contains(clone);
    }

    private static boolean hasClones(PlayerEntity player) {
        if (player.getWorld().isClient) {
            // The client has no squad list; any clone of ours in sight will do
            return !player.getWorld().getEntitiesByClass(CloneEntity.class, player.getBoundingBox().expand(64.0),
                    c -> c.isOwnedBy(player)).isEmpty();
        }
        Squad s = SQUADS.get(player.getUuid());
        return s != null && !s.clones.isEmpty();
    }

    // Once your clones are out, giving them orders costs nothing and ignores the cooldown
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return hasClones(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return player.isSneaking() || hasClones(player) ? 0 : tomeCost;
    }

    // The cooldown only starts once the clones are gone (see finish)
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;

        Squad squad = SQUADS.get(player.getUuid());
        if (squad != null && (squad.world != sw || squad.clones.isEmpty())) {
            disband(squad);
            finish(player);
            squad = null;
        }

        if (player.isSneaking()) {
            if (squad == null || !squad.dismissing.isEmpty()) return false;
            dismiss(squad, player);
            return true;
        }
        if (squad == null) {
            summon(sw, player);
            return true;
        }
        if (!squad.dismissing.isEmpty()) return false;

        // Swapping needs the crosshair right on the clone; aim assist is only for picking targets, and skips your
        // own clones so one fighting next to the enemy doesn't steal the cast
        Entity direct = Aim.target(sw, player, RANGE, 0.0, e -> e instanceof LivingEntity);
        Entity aimed = direct instanceof CloneEntity mine && mine.isOwnedBy(player) ? direct
                : Aim.target(sw, player, RANGE, 4.0, e -> e instanceof LivingEntity && !(e instanceof CloneEntity c && c.isOwnedBy(player)));
        if (aimed instanceof CloneEntity clone && clone.isOwnedBy(player)) {
            swapInto(sp, clone);
        } else if (aimed instanceof LivingEntity target) {
            command(squad, player, target);
        } else {
            regroup(squad, player);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Summoning
    // ---------------------------------------------------------------------

    private static void summon(ServerWorld world, PlayerEntity player) {
        Squad squad = new Squad(world, player.getUuid());
        squad.lastY = player.getY();
        float yaw = player.getYaw();
        for (int i = 0; i < CLONE_COUNT; i++) {
            CloneEntity clone = ModEntities.CLONE.create(world);
            if (clone == null) continue;
            clone.copyFrom(player, i);
            // They step out of you, each toward its own side
            double a = Math.toRadians(yaw + (i == 0 ? 90 : i == 1 ? -90 : 180));
            clone.setVelocity(-Math.sin(a) * 0.55, 0.3, Math.cos(a) * 0.55);
            TempEntities.track(clone);
            world.spawnEntity(clone);
            squad.clones.add(clone);
        }
        SQUADS.put(player.getUuid(), squad);
        if (player instanceof ServerPlayerEntity sp) CloneTimerPayload.send(sp, LIFETIME_TICKS);

        Vec3d p = player.getPos();
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.3f, 0.9f);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.3f);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.6f, 1.4f);

        // A mirror-flash where you stand: magic drawn in, then a ring bursting out as the copies split off
        world.spawnParticles(ParticleTypes.FLASH, p.x, p.y + 1.0, p.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI * 2 / 32;
            world.spawnParticles(ParticleTypes.ENCHANT, p.x + Math.cos(a) * 1.2, p.y + 0.1, p.z + Math.sin(a) * 1.2,
                    0, -Math.cos(a), 0.7, -Math.sin(a), 1.0);
            world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y + 0.2, p.z, 0, Math.cos(a) * 0.25, 0.02, Math.sin(a) * 0.25, 1.0);
        }
        for (double y = 0.1; y < player.getHeight(); y += 0.2) {
            world.spawnParticles(SHIMMER, p.x, p.y + y, p.z, 3, 0.3, 0.02, 0.3, 0);
        }
        ShakePayload.around(world, p, 4.0, 0.35f, 8);
    }

    // ---------------------------------------------------------------------
    // Orders
    // ---------------------------------------------------------------------

    private static void command(Squad squad, PlayerEntity player, LivingEntity target) {
        squad.order = target;
        squad.calmUntil = 0;
        ServerWorld world = squad.world;
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false, false));
        for (CloneEntity c : squad.clones) {
            aim(c, player, target);
            c.swingHand(Hand.MAIN_HAND); // pointing it out
            world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, c.getX(), c.getEyeY() + 0.6, c.getZ(), 1, 0.1, 0.05, 0.1, 0);
        }

        Vec3d t = target.getPos();
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.PLAYERS, 0.9f, 1.5f);
        world.playSound(null, t.x, t.y, t.z, SoundEvents.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.PLAYERS, 1.0f, 1.2f);
        // A red ring snaps shut around the marked target
        double r = target.getWidth() * 0.5 + 0.8;
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2 / 28;
            world.spawnParticles(MARK, t.x + Math.cos(a) * r, t.y + 0.1, t.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
        world.spawnParticles(ParticleTypes.CRIT, t.x, t.y + target.getHeight() * 0.6, t.z, 15, 0.3, 0.4, 0.3, 0.3);
    }

    private static void regroup(Squad squad, PlayerEntity player) {
        squad.order = null;
        squad.calmUntil = squad.world.getTime() + CALM_TICKS;
        for (CloneEntity c : squad.clones) {
            c.setTarget(null);
            squad.world.spawnParticles(SHIMMER, c.getX(), c.getY() + 1.0, c.getZ(), 8, 0.25, 0.5, 0.25, 0);
        }
        squad.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.PLAYERS, 0.8f, 1.4f);
    }

    /** Sneak-cast: the clones freeze, then shatter one after another. */
    private static void dismiss(Squad squad, PlayerEntity player) {
        squad.order = null;
        for (CloneEntity c : squad.clones) {
            c.setTarget(null);
            c.getNavigation().stop();
            c.setAiDisabled(true);
            squad.dismissing.add(c);
        }
        squad.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.PLAYERS, 1.0f, 0.6f);
    }

    private static void aim(CloneEntity clone, PlayerEntity owner, LivingEntity target) {
        if (clone.getTarget() == target) return;
        clone.setTarget(target);
        if (target != null) clone.mirrorGear(owner, true);
    }

    // ---------------------------------------------------------------------
    // Swapping bodies
    // ---------------------------------------------------------------------

    /** You and {@code clone} trade places; the camera glides across (see CloneClient). */
    public static void swapInto(ServerPlayerEntity player, CloneEntity clone) {
        Squad squad = SQUADS.get(player.getUuid());
        if (squad == null || !squad.clones.contains(clone) || !squad.dismissing.isEmpty()) return;
        if (clone.getWorld() != player.getWorld()) return;
        ServerWorld world = squad.world;
        long now = world.getTime();
        if (now - squad.lastSwap < SWAP_COOLDOWN_TICKS) return;
        squad.lastSwap = now;

        Vec3d from = player.getPos();
        Vec3d to = clone.getPos();
        float cloneFall = clone.fallDistance;

        clone.getNavigation().stop();
        clone.refreshPositionAndAngles(from.x, from.y, from.z, player.getYaw(), player.getPitch());
        clone.setHeadYaw(player.getHeadYaw());
        clone.setBodyYaw(player.getBodyYaw());
        clone.setVelocity(Vec3d.ZERO);
        clone.fallDistance = player.fallDistance;

        // You keep looking the way you were looking - only your body moves
        player.teleport(world, to.x, to.y, to.z, Set.of(), player.getYaw(), player.getPitch());
        player.fallDistance = cloneFall;
        CloneSwapPayload.send(player);

        for (Vec3d at : new Vec3d[]{from, to}) {
            world.spawnParticles(SHIMMER, at.x, at.y + 1.0, at.z, 18, 0.3, 0.6, 0.3, 0);
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, 15, 0.3, 0.6, 0.3, 0.05);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        }
        world.playSound(null, to.x, to.y, to.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 1.2f);
        // A thin streak of light between the two bodies
        Vec3d span = to.subtract(from);
        int steps = (int) Math.min(60, span.length() * 3);
        for (int i = 1; i < steps; i++) {
            Vec3d p = from.add(span.multiply(i / (double) steps)).add(0, 1.0, 0);
            world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    /** A clone left too far behind blinks back to its caster. */
    public static void catchUp(ServerWorld world, CloneEntity clone, PlayerEntity owner) {
        world.spawnParticles(ParticleTypes.POOF, clone.getX(), clone.getY() + 1.0, clone.getZ(), 8, 0.25, 0.5, 0.25, 0.01);
        clone.getNavigation().stop();
        clone.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ(), owner.getYaw(), 0f);
        clone.setVelocity(Vec3d.ZERO);
        world.spawnParticles(SHIMMER, owner.getX(), owner.getY() + 1.0, owner.getZ(), 10, 0.3, 0.6, 0.3, 0);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(ServerWorld world) {
        if (SQUADS.isEmpty()) return;
        Iterator<Squad> it = SQUADS.values().iterator();
        while (it.hasNext()) {
            Squad s = it.next();
            if (s.world != world) continue;
            PlayerEntity owner = world.getPlayerByUuid(s.owner);
            s.clones.removeIf(c -> c.isRemoved() || !c.isAlive());

            if (owner == null || !owner.isAlive()) {
                it.remove();
                s.clones.forEach(c -> fade(world, c));
                // They may have gone to another dimension rather than logged off
                finish(owner != null ? owner : world.getServer().getPlayerManager().getPlayer(s.owner));
                continue;
            }

            // Dismissing: one shatters every couple of ticks
            if (!s.dismissing.isEmpty()) {
                if ((world.getTime() - s.summonedAt) % 2 == 0) {
                    CloneEntity c = s.dismissing.poll();
                    s.clones.remove(c);
                    if (!c.isRemoved()) shatter(world, c);
                }
                if (s.dismissing.isEmpty()) {
                    it.remove();
                    finish(owner);
                }
                continue;
            }
            if (s.clones.isEmpty()) {
                it.remove();
                finish(owner);
                continue;
            }

            // Time's up: they flicker as a warning, then dissolve
            long left = LIFETIME_TICKS - (world.getTime() - s.summonedAt);
            if (left <= 0) {
                it.remove();
                s.clones.forEach(c -> fade(world, c));
                world.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                        SoundCategory.PLAYERS, 0.8f, 0.5f);
                finish(owner);
                continue;
            }
            if (left <= WARN_TICKS && left % (left <= 20 ? 3 : 6) == 0) {
                for (CloneEntity c : s.clones) {
                    world.spawnParticles(SHIMMER, c.getX(), c.getY() + 1.0, c.getZ(), 4, 0.25, 0.6, 0.25, 0);
                }
            }

            // Standing orders: the marked target until it's dead (then a little victory hop)
            if (s.order != null && (!s.order.isAlive() || s.order.isRemoved() || s.order.getWorld() != world
                    || s.order.squaredDistanceTo(owner) > RANGE * RANGE)) {
                if (s.order.isDead()) {
                    for (CloneEntity c : s.clones) c.mimicJump(world.random.nextInt(4));
                }
                s.order = null;
            }
            if (s.order != null && world.getTime() % 10 == 0) {
                s.order.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false, false));
            }

            boolean jumped = s.wasOnGround && !owner.isOnGround() && owner.getY() - s.lastY > 0.05;
            boolean swung = owner.handSwinging && !s.wasSwinging;
            for (CloneEntity c : s.clones) {
                LivingEntity want = s.order != null ? s.order : ownTarget(s, c, owner);
                aim(c, owner, want);
                if (world.getTime() % 5 == 0) c.mirrorGear(owner, c.getTarget() != null);
                if (c.getTarget() == null) {
                    // Monkey see, monkey do - each a beat behind
                    if (jumped) c.mimicJump(1 + world.random.nextInt(4));
                    if (swung) c.mimicSwing(world.random.nextInt(4));
                }
            }
            s.wasOnGround = owner.isOnGround();
            s.wasSwinging = owner.handSwinging;
            s.lastY = owner.getY();
        }
    }

    /** With no orders, a clone fights back, defends you, and joins in on whatever you're hitting. */
    private static LivingEntity ownTarget(Squad s, CloneEntity clone, PlayerEntity owner) {
        if (s.world.getTime() < s.calmUntil) return null;
        if (isFair(clone.getAttacker(), owner)) return clone.getAttacker();
        if (isFair(owner.getAttacker(), owner)) return owner.getAttacker();
        LivingEntity hit = owner.getAttacking();
        if (owner.age - owner.getLastAttackTime() < 100 && isFair(hit, owner)) return hit;
        // Keep going at whatever it's already fighting
        return isFair(clone.getTarget(), owner) ? clone.getTarget() : null;
    }

    private static boolean isFair(LivingEntity e, PlayerEntity owner) {
        if (e == null || e == owner || !e.isAlive() || e.squaredDistanceTo(owner) > ASSIST_RANGE * ASSIST_RANGE) return false;
        if (e instanceof CloneEntity c && c.isOwnedBy(owner)) return false;
        if (e instanceof Tameable pet && owner.getUuid().equals(pet.getOwnerUuid())) return false;
        return !(e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator()));
    }

    /** The clones are all gone: clear the caster's timer and only now start the tome's cooldown. */
    private static void finish(PlayerEntity owner) {
        if (owner == null) return;
        if (owner instanceof ServerPlayerEntity sp) CloneTimerPayload.send(sp, 0);
        TomeItem tome = ModItems.TOME_OF_CLONES;
        ItemStack staff = StaffItem.findStaffWith(owner, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(owner.getWorld(), owner, staff, tome, tome.getCooldown());
        else owner.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    private static void disband(Squad squad) {
        SQUADS.remove(squad.owner);
        for (CloneEntity c : squad.clones) if (!c.isRemoved()) fade(squad.world, c);
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    /** A clone killed or dismissed bursts like breaking glass. */
    public static void shatter(ServerWorld world, CloneEntity clone) {
        Vec3d p = clone.getPos();
        world.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 1.2f + world.random.nextFloat() * 0.3f);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.7f, 0.7f);
        world.spawnParticles(GLASS, p.x, p.y + 1.0, p.z, 50, 0.3, 0.6, 0.3, 0.15);
        world.spawnParticles(SHIMMER, p.x, p.y + 1.0, p.z, 20, 0.35, 0.7, 0.35, 0);
        world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y + 1.0, p.z, 8, 0.2, 0.4, 0.2, 0.08);
        TempEntities.discard(clone);
    }

    /** Caster gone: the clones quietly dissolve. */
    private static void fade(ServerWorld world, CloneEntity clone) {
        Vec3d p = clone.getPos();
        for (double y = 0.1; y < clone.getHeight(); y += 0.15) {
            world.spawnParticles(SHIMMER, p.x, p.y + y, p.z, 2, 0.25, 0.02, 0.25, 0);
        }
        world.spawnParticles(ParticleTypes.POOF, p.x, p.y + 1.0, p.z, 8, 0.25, 0.5, 0.25, 0.01);
        TempEntities.discard(clone);
    }
}
