package net.ragnar.ragnarsmagicmod.frostbite;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Frostbite: a passive tome. While it's in your inventory (or on a staff you carry), each melee hit or shot you
 * land on an ordinary vanilla creature (see {@link #ordinary}) or another player has a {@link #CHANCE} chance to freeze
 * it solid for {@link #FROZEN_TICKS} ticks; and whenever one of them hits or shoots you, there's a {@link #RETALIATE}
 * chance it freezes instead. Frozen, it's held in a block of ice, can't move or think (MobEntityMixin) and a creeper
 * can't go off (CreeperEntityMixin); the frost bites it as it holds, and the ice shatters with a last bite.
 * <p>
 * Nothing freezes in the Nether (or any other dimension too hot for water), nor anything that can't freeze in powder
 * snow: strays, snow golems, polar bears, or a player in leather armour. Once thawed, a creature can't be refrozen for
 * {@link #REFREEZE_TICKS} ticks, so it can't be locked down for good.
 * <p>
 * To remove it: delete this package, its two lines in MobEntityMixin and CreeperEntityMixin, {@code Frostbite.register()}
 * in RagnarsMagicMod, {@code FROSTBITE} in SpellId, it in PassiveTomes.register, {@code models/item/tome_of_frostbite.json}
 * and its en_us.json line.
 */
public final class Frostbite {
    private Frostbite() {}

    /** The chance each hit you land freezes what it hit. */
    public static final float CHANCE = 0.5f;
    /** The chance each hit you take freezes whoever dealt it. */
    public static final float RETALIATE = 0.2f;
    public static final int FROZEN_TICKS = 30;     // 1.5s in the ice
    public static final int REFREEZE_TICKS = 40;   // 2s before it can be frozen again
    private static final int BITE_EVERY = 10;
    private static final float BITE = 1.0f;

    private static final BlockState ICE = Blocks.ICE.getDefaultState();
    private static final BlockStateParticleEffect ICE_CHIPS = new BlockStateParticleEffect(ParticleTypes.BLOCK, ICE);
    private static final Identifier FROZEN_MODIFIER = Identifier.of(RagnarsMagicMod.MOD_ID, "frostbite_frozen");

    public static final TomeItem TOME_OF_FROSTBITE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_frostbite"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.FROSTBITE, 0
            ).setCooldown(0)
    );

    private static final class Frozen {
        final LivingEntity entity;
        final DisplayEntity.BlockDisplayEntity shell;
        final UUID by;
        final Vec3d pos;
        final float yaw, headYaw, bodyYaw;
        int age = 0;

        Frozen(LivingEntity entity, DisplayEntity.BlockDisplayEntity shell, UUID by) {
            this.entity = entity;
            this.shell = shell;
            this.by = by;
            this.pos = entity.getPos();
            this.yaw = entity.getYaw();
            this.headYaw = entity.getHeadYaw();
            this.bodyYaw = entity.getBodyYaw();
        }
    }

    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();       // by victim
    private static final Map<UUID, Long> THAWED_AT = new HashMap<>();      // by victim, world time

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.FROSTBITE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_FROSTBITE);
        Spells.register(SpellId.FROSTBITE, new PassiveSpell("your hits sometimes freeze what they strike, and whatever hits you sometimes freezes - never in the Nether."));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseTaken, taken, blocked) -> {
            if (taken <= 0f || !entity.isAlive() || !isHit(source)) return;
            LivingEntity attacker = (LivingEntity) source.getAttacker();
            if (attacker == entity) return;
            if (attacker instanceof ServerPlayerEntity p && PassiveTomes.has(p, TOME_OF_FROSTBITE)
                    && p.getRandom().nextFloat() < CHANCE) freeze(entity, p);
            if (entity instanceof ServerPlayerEntity p && attacker.isAlive() && PassiveTomes.has(p, TOME_OF_FROSTBITE)
                    && p.getRandom().nextFloat() < RETALIATE) freeze(attacker, p);
        });
        ServerTickEvents.END_WORLD_TICK.register(Frostbite::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(Frostbite::thawEverything);
    }

    /** A melee blow or a shot from a living creature: what these tomes answer to (not their own frost or venom). */
    public static boolean isHit(DamageSource source) {
        if (!(source.getAttacker() instanceof LivingEntity)) return false;
        return source.getSource() instanceof ProjectileEntity || source.isOf(DamageTypes.PLAYER_ATTACK)
                || source.isOf(DamageTypes.MOB_ATTACK) || source.isOf(DamageTypes.MOB_ATTACK_NO_AGGRO);
    }

    /** Players, and the game's own creatures that aren't bosses (no dragon, wither, warden or elder guardian, no modded mobs). */
    public static boolean ordinary(LivingEntity e) {
        if (e instanceof PlayerEntity p) return !p.isSpectator() && !p.isCreative();
        if (!(e instanceof MobEntity)) return false;
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity || e instanceof WardenEntity || e instanceof ElderGuardianEntity) return false;
        return Registries.ENTITY_TYPE.getId(e.getType()).getNamespace().equals(Identifier.DEFAULT_NAMESPACE);
    }

    /** Water freezes here (not in the Nether, or anywhere else as hot). */
    public static boolean coldEnough(World world) {
        return !world.getDimension().ultrawarm();
    }

    /** True while {@code entity} is frozen solid by the tome (its AI is paused by MobEntityMixin). */
    public static boolean isFrozen(Entity entity) {
        return FROZEN.containsKey(entity.getUuid());
    }

    /**
     * Freezes {@code target} solid, on behalf of {@code by}, if it can be: returns whether it did. The dice are rolled by
     * the caller, so tests can call this directly.
     */
    public static boolean freeze(LivingEntity target, ServerPlayerEntity by) {
        if (!(target.getWorld() instanceof ServerWorld world) || !target.isAlive() || !ordinary(target)) return false;
        // Skeletons opt out of freezing in vanilla (powder snow turns them into strays instead), but they still freeze here
        boolean freezable = target instanceof SkeletonEntity || target.canFreeze();
        if (!coldEnough(world) || !freezable || isFrozen(target)) return false;
        Long thawed = THAWED_AT.get(target.getUuid());
        if (thawed != null && world.getTime() - thawed < REFREEZE_TICKS) return false;

        Frozen f = new Frozen(target, encase(world, target), by.getUuid());
        FROZEN.put(target.getUuid(), f);
        lock(target);
        target.setFrozenTicks(Math.max(target.getFrozenTicks(), target.getMinFreezeDamageTicks() + 20));
        if (target instanceof CreeperEntity creeper) creeper.setFuseSpeed(-1);

        // A crackle of frost racing over them as the ice closes
        Vec3d c = target.getBoundingBox().getCenter();
        double w = target.getWidth() * 0.5, h = target.getHeight() * 0.5;
        world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 18, w + 0.1, h, w + 0.1, 0.04);
        world.spawnParticles(ICE_CHIPS, c.x, c.y, c.z, 14, w, h, w, 0.05);
        world.spawnParticles(ParticleTypes.ITEM_SNOWBALL, c.x, c.y, c.z, 8, w, h, w, 0.05);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_POWDER_SNOW_PLACE, SoundCategory.PLAYERS, 0.9f, 0.8f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 0.5f, 1.2f);
        return true;
    }

    /** A block of ice around the creature, sized to its hitbox and set a little askew. */
    private static DisplayEntity.BlockDisplayEntity encase(ServerWorld world, LivingEntity e) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
        if (d == null) return null;
        float width = e.getWidth() + 0.3f;
        float height = e.getHeight() + 0.2f;
        float tilt = (world.random.nextFloat() - 0.5f) * 0.3f;
        d.setBlockState(ICE);
        d.setTeleportDuration(1);
        d.setViewRange(2.0f);
        d.refreshPositionAndAngles(e.getX(), e.getY(), e.getZ(), 0f, 0f);
        d.setTransformation(new AffineTransformation(new Matrix4f()
                .translate(0, -0.08f, 0)
                .rotateY(tilt)
                .scale(width, height, width)
                .translate(-0.5f, 0, -0.5f)));
        TempEntities.track(d);
        world.spawnEntity(d);
        return d;
    }

    private static void lock(LivingEntity e) {
        for (RegistryEntry<EntityAttribute> a : List.of(EntityAttributes.GENERIC_MOVEMENT_SPEED, EntityAttributes.GENERIC_JUMP_STRENGTH,
                EntityAttributes.GENERIC_FLYING_SPEED)) {
            EntityAttributeInstance inst = e.getAttributeInstance(a);
            if (inst != null && !inst.hasModifier(FROZEN_MODIFIER)) {
                inst.addTemporaryModifier(new EntityAttributeModifier(FROZEN_MODIFIER, -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
    }

    private static void unlock(LivingEntity e) {
        for (RegistryEntry<EntityAttribute> a : List.of(EntityAttributes.GENERIC_MOVEMENT_SPEED, EntityAttributes.GENERIC_JUMP_STRENGTH,
                EntityAttributes.GENERIC_FLYING_SPEED)) {
            EntityAttributeInstance inst = e.getAttributeInstance(a);
            if (inst != null) inst.removeModifier(FROZEN_MODIFIER);
        }
    }

    private static void tick(ServerWorld world) {
        if (!THAWED_AT.isEmpty()) THAWED_AT.values().removeIf(t -> world.getTime() - t > REFREEZE_TICKS || t > world.getTime());
        if (FROZEN.isEmpty()) return;
        Iterator<Frozen> it = FROZEN.values().iterator();
        while (it.hasNext()) {
            Frozen f = it.next();
            LivingEntity e = f.entity;
            if (e.getWorld() != world && !e.isRemoved()) continue;
            if (!e.isAlive() || e.isRemoved()) {
                it.remove();
                unlock(e);
                shatter(world, f, false);
                continue;
            }
            f.age++;
            if (f.age >= FROZEN_TICKS) {
                it.remove();
                unlock(e);
                THAWED_AT.put(e.getUuid(), world.getTime());
                shatter(world, f, true);
                continue;
            }

            // Held exactly where the ice caught them
            e.setFrozenTicks(Math.max(e.getFrozenTicks(), e.getMinFreezeDamageTicks() + 10));
            if (e instanceof PlayerEntity) {
                e.setVelocity(0, Math.min(0, e.getVelocity().y), 0);
                e.velocityModified = true;
                if (f.shell != null) f.shell.setPosition(e.getX(), e.getY(), e.getZ());
            } else {
                e.setPosition(f.pos.x, f.pos.y, f.pos.z);
                e.setVelocity(Vec3d.ZERO);
                e.setYaw(f.yaw);
                e.setHeadYaw(f.headYaw);
                e.setBodyYaw(f.bodyYaw);
                e.fallDistance = 0;
                if (e instanceof CreeperEntity creeper) creeper.setFuseSpeed(-1);
            }

            // Frost smoking off the ice, and the cold biting in
            if (world.random.nextInt(3) == 0) {
                world.spawnParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getBodyY(0.3 + world.random.nextDouble() * 0.7), e.getZ(),
                        1, e.getWidth() * 0.55, 0.05, e.getWidth() * 0.55, 0.01);
            }
            if (f.age % BITE_EVERY == 0) bite(world, f);
        }
    }

    /** The cold bites: a little freeze damage, without eating into the hits landing on them. */
    private static void bite(ServerWorld world, Frozen f) {
        LivingEntity e = f.entity;
        if (e.timeUntilRegen > 10) return; // still flinching from a real hit: skip this bite rather than blunt it
        int before = e.timeUntilRegen;
        e.damage(freezeDamage(world, f.by), BITE);
        e.timeUntilRegen = before;
        world.spawnParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getBodyY(0.5), e.getZ(), 6, e.getWidth() * 0.4, e.getHeight() * 0.3, e.getWidth() * 0.4, 0.06);
    }

    /** Freeze damage that's credited to whoever froze them (kills drop XP and loot as theirs). */
    private static DamageSource freezeDamage(ServerWorld world, UUID by) {
        Entity attacker = by == null ? null : world.getPlayerByUuid(by);
        return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(DamageTypes.FREEZE), attacker);
    }

    /** The ice bursts apart, with one last bite if they lasted the whole freeze. */
    private static void shatter(ServerWorld world, Frozen f, boolean lastBite) {
        LivingEntity e = f.entity;
        Vec3d c = new Vec3d(e.getX(), e.getY() + e.getHeight() * 0.5, e.getZ());
        double w = e.getWidth() * 0.5 + 0.15, h = e.getHeight() * 0.5;
        world.spawnParticles(ICE_CHIPS, c.x, c.y, c.z, 30, w, h, w, 0.15);
        world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 10, w, h * 0.8, w, 0.06);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.7f, 1.3f + world.random.nextFloat() * 0.3f);
        if (f.shell != null) TempEntities.discard(f.shell);
        if (lastBite) bite(world, f);
    }

    private static void thawEverything(MinecraftServer server) {
        for (Frozen f : FROZEN.values()) {
            unlock(f.entity);
            if (f.shell != null) TempEntities.discard(f.shell);
        }
        FROZEN.clear();
        THAWED_AT.clear();
    }

}
