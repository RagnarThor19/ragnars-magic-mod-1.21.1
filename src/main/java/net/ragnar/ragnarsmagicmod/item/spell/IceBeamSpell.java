package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.entity.CloneEntity;
import net.ragnar.ragnarsmagicmod.network.IceBeamPayload;
import net.ragnar.ragnarsmagicmod.sound.ModSoundEvents;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Ice Beam. Little blocks of ice gather at the staff while a dotted line marks where it'll land, then a thin
 * icy beacon beam lances out wherever you look. Every creature it touches is frozen solid in a block of ice - held
 * in place, AI stopped - and shatters out a few seconds after the beam leaves it. The block it's pointed at turns to
 * ice (and thaws back later), water freezes over, lava sets to stone and fire goes out.
 *
 * The server does the aiming, damage and freezing; IceBeamClient draws the beam itself from the payload, so it
 * follows your view smoothly and stays thin enough to aim past.
 */
public class IceBeamSpell implements Spell {
    public static final int CHARGE_TICKS = 24;  // 1.2s
    public static final int FIRE_TICKS = 40;    // 2.0s
    public static final double RANGE = 48.0;

    private static final double HIT_RADIUS = 0.2;  // how close the beam has to pass to a hitbox
    private static final float DAMAGE = 12.0f;     // per hit; hurt cooldowns let one through every half second
    private static final float SHATTER_DAMAGE = 6.0f;
    private static final int FROZEN_TICKS = 80;    // held for 4s after the beam last touched them
    private static final int FROST_TICKS = 120;    // frozen blocks thaw back after 6-8s
    private static final int MAX_FROST = 400;

    private static final Identifier FROZEN_MODIFIER = Identifier.of(RagnarsMagicMod.MOD_ID, "ice_beam_frozen");
    private static final BlockState ICE = Blocks.ICE.getDefaultState();
    private static final BlockStateParticleEffect ICE_CHIPS = new BlockStateParticleEffect(ParticleTypes.BLOCK, ICE);
    private static final DustParticleEffect FROST = new DustParticleEffect(new Vector3f(0.7f, 0.9f, 1.0f), 1.0f);

    private static final class Beam {
        final ServerWorld world;
        final UUID caster;
        int age;

        Beam(ServerWorld world, UUID caster) {
            this.world = world;
            this.caster = caster;
        }
    }

    private static final class Frozen {
        final LivingEntity entity;
        final DisplayEntity.BlockDisplayEntity shell;
        final Vec3d pos;
        final float yaw, headYaw, bodyYaw;
        UUID caster;
        long until;

        Frozen(LivingEntity entity, DisplayEntity.BlockDisplayEntity shell) {
            this.entity = entity;
            this.shell = shell;
            this.pos = entity.getPos();
            this.yaw = entity.getYaw();
            this.headYaw = entity.getHeadYaw();
            this.bodyYaw = entity.getBodyYaw();
        }
    }

    private record Frost(BlockState original, long restoreAt) {}

    private static final Map<UUID, Beam> BEAMS = new HashMap<>();      // by caster
    private static final Map<UUID, Frozen> FROZEN = new HashMap<>();   // by victim
    private static final Map<RegistryKey<World>, Map<BlockPos, Frost>> FROSTED = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(IceBeamSpell::tick);
        // Nothing the spell froze may outlive the server: put the blocks back and let everyone go
        ServerLifecycleEvents.SERVER_STOPPING.register(IceBeamSpell::thawEverything);
    }

    /** True while {@code entity} is locked in a block of ice (its AI is paused by MobEntityMixin). */
    public static boolean isFrozen(Entity entity) {
        return FROZEN.containsKey(entity.getUuid());
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (BEAMS.containsKey(player.getUuid())) return false;

        BEAMS.put(player.getUuid(), new Beam(sw, player.getUuid()));
        IceBeamPayload.broadcast(sp, CHARGE_TICKS, FIRE_TICKS);
        // Channelling: you can shuffle about, not run
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, CHARGE_TICKS + FIRE_TICKS, 1, false, false, false));

        Vec3d p = player.getEyePos();
        // A guardian winding up its beam, pitched up, over the icy whine
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.PLAYERS, 1.4f, 1.7f);
        sw.playSound(null, p.x, p.y, p.z, ModSoundEvents.ICE_BEAM_CHARGE, SoundCategory.PLAYERS, 0.8f, 1.0f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.7f, 2.0f);
        return true;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(ServerWorld world) {
        if (!BEAMS.isEmpty()) {
            Iterator<Beam> it = BEAMS.values().iterator();
            while (it.hasNext()) {
                Beam b = it.next();
                if (b.world != world) continue;
                PlayerEntity caster = world.getPlayerByUuid(b.caster);
                if (caster == null || !caster.isAlive()) {
                    it.remove();
                    if (caster instanceof ServerPlayerEntity sp) IceBeamPayload.broadcast(sp, 0, 0);
                    silence(world, caster != null ? caster.getPos() : null);
                    continue;
                }
                b.age++;
                if (b.age <= CHARGE_TICKS) {
                    charge(world, caster, b.age);
                } else if (b.age <= CHARGE_TICKS + FIRE_TICKS) {
                    if (b.age == CHARGE_TICKS + 1) ignite(world, caster);
                    fire(world, caster, b.age - CHARGE_TICKS);
                } else {
                    it.remove();
                    powerDown(world, caster);
                }
            }
        }
        if (!FROZEN.isEmpty()) tickFrozen(world);
        Map<BlockPos, Frost> frosted = FROSTED.get(world.getRegistryKey());
        if (frosted != null && !frosted.isEmpty()) tickFrost(world, frosted);
    }

    /** The build-up: a scale of chimes climbing toward the shot. */
    private static void charge(ServerWorld world, PlayerEntity caster, int age) {
        float p = age / (float) CHARGE_TICKS;
        Vec3d at = caster.getEyePos();
        if (age % 4 == 0) {
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 0.9f + 1.1f * p);
        }
        if (age == CHARGE_TICKS - 3) {
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.0f, 1.8f);
        }
        // Frost creeping over the ground at your feet
        if (age % 2 == 0) {
            double r = 1.6 * (1.0 - p) + 0.4;
            for (int i = 0; i < 10; i++) {
                double a = world.random.nextDouble() * Math.PI * 2;
                world.spawnParticles(FROST, caster.getX() + Math.cos(a) * r, caster.getY() + 0.05, caster.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }
    }

    /** The moment it fires: the crack, the roar, and a kick back into the caster. */
    private static void ignite(ServerWorld world, PlayerEntity caster) {
        Vec3d at = caster.getEyePos();
        world.playSound(null, at.x, at.y, at.z, ModSoundEvents.ICE_BEAM_FIRE, SoundCategory.PLAYERS, 2.0f, 1.0f);
        world.playSound(null, at.x, at.y, at.z, ModSoundEvents.ICE_BEAM, SoundCategory.PLAYERS, 1.8f, 1.15f);
        world.playSound(null, at.x, at.y, at.z, ModSoundEvents.ICE_BEAM_SUSTAIN, SoundCategory.PLAYERS, 0.9f, 1.0f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.8f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 1.6f);

        Vec3d back = caster.getRotationVector().multiply(-0.35);
        caster.addVelocity(back.x, Math.max(0.0, back.y) * 0.5, back.z);
        caster.velocityModified = true;
    }

    private static void fire(ServerWorld world, PlayerEntity caster, int fireAge) {
        Vec3d eye = caster.getEyePos();
        Vec3d dir = caster.getRotationVector();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(dir.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.SOURCE_ONLY, caster));
        Vec3d end = hit.getType() == HitResult.Type.MISS ? eye.add(dir.multiply(RANGE)) : hit.getPos();

        hitEntities(world, caster, eye, end);
        if (hit.getType() == HitResult.Type.BLOCK) freezeBlock(world, caster, hit);

        if (fireAge % 4 == 0) {
            world.playSound(null, end.x, end.y, end.z, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 0.7f, 1.7f + world.random.nextFloat() * 0.3f);
        }
        if (fireAge % 6 == 3) {
            world.playSound(null, end.x, end.y, end.z, SoundEvents.BLOCK_POWDER_SNOW_PLACE, SoundCategory.PLAYERS, 0.6f, 1.5f);
        }
    }

    /** The beam winds down with a falling whine. */
    private static void powerDown(ServerWorld world, PlayerEntity caster) {
        silence(world, caster.getPos());
        Vec3d at = caster.getEyePos();
        world.playSound(null, at.x, at.y, at.z, ModSoundEvents.ICE_BEAM_END, SoundCategory.PLAYERS, 1.4f, 1.0f);
    }

    /** Cuts the long roar off when the beam stops, rather than letting it ring on after. */
    private static void silence(ServerWorld world, Vec3d near) {
        if (near == null) return;
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(near) > 80 * 80) continue;
            p.networkHandler.sendPacket(new StopSoundS2CPacket(ModSoundEvents.ICE_BEAM.getId(), SoundCategory.PLAYERS));
            p.networkHandler.sendPacket(new StopSoundS2CPacket(ModSoundEvents.ICE_BEAM_SUSTAIN.getId(), SoundCategory.PLAYERS));
        }
    }

    // ---------------------------------------------------------------------
    // Creatures
    // ---------------------------------------------------------------------

    private static void hitEntities(ServerWorld world, PlayerEntity caster, Vec3d from, Vec3d to) {
        Box sweep = new Box(from, to).expand(1.0);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, sweep,
                e -> e != caster && e.isAlive() && !e.isSpectator())) {
            if (e instanceof CloneEntity c && c.isOwnedBy(caster)) continue;
            if (e instanceof Tameable pet && caster.getUuid().equals(pet.getOwnerUuid())) continue;
            Box box = e.getBoundingBox().expand(HIT_RADIUS);
            if (!box.contains(from) && box.raycast(from, to).isEmpty()) continue;
            strike(world, caster, e);
        }
    }

    private static DamageSource freezeDamage(ServerWorld world, Entity attacker) {
        RegistryEntry<net.minecraft.entity.damage.DamageType> type =
                world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(DamageTypes.FREEZE);
        return new DamageSource(type, attacker);
    }

    private static void strike(ServerWorld world, PlayerEntity caster, LivingEntity e) {
        e.damage(freezeDamage(world, caster), DAMAGE);
        if (!e.isAlive()) return;
        e.setFrozenTicks(Math.max(e.getFrozenTicks(), e.getMinFreezeDamageTicks() + 20));

        Frozen f = FROZEN.get(e.getUuid());
        if (f == null) {
            if (!canEncase(e)) return;
            f = new Frozen(e, encase(world, e));
            FROZEN.put(e.getUuid(), f);
            lock(e);
            Vec3d c = e.getBoundingBox().getCenter();
            world.playSound(null, c.x, c.y, c.z, ModSoundEvents.ICE_BEAM_FREEZE, SoundCategory.PLAYERS, 1.3f, 0.9f + world.random.nextFloat() * 0.2f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 0.8f);
            world.spawnParticles(ICE_CHIPS, c.x, c.y, c.z, 40, e.getWidth() * 0.5, e.getHeight() * 0.5, e.getWidth() * 0.5, 0.1);
            world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 25, e.getWidth() * 0.6, e.getHeight() * 0.5, e.getWidth() * 0.6, 0.05);
            world.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 6, e.getWidth() * 0.4, e.getHeight() * 0.4, e.getWidth() * 0.4, 0.05);
        }
        f.caster = caster.getUuid();
        f.until = world.getTime() + FROZEN_TICKS;
    }

    /** Bosses and creatures that don't freeze just take the damage. */
    private static boolean canEncase(LivingEntity e) {
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity || e.getWidth() > 3.0f) return false;
        return !e.getType().isIn(EntityTypeTags.FREEZE_IMMUNE_ENTITY_TYPES);
    }

    /** A block of ice around the creature, sized to its hitbox and set a little askew. */
    private static DisplayEntity.BlockDisplayEntity encase(ServerWorld world, LivingEntity e) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
        if (d == null) return null;
        float width = e.getWidth() + 0.4f;
        float height = e.getHeight() + 0.3f;
        float tilt = (world.random.nextFloat() - 0.5f) * 0.35f;
        d.setBlockState(ICE);
        d.setTeleportDuration(1);
        d.setViewRange(2.5f);
        d.refreshPositionAndAngles(e.getX(), e.getY(), e.getZ(), 0f, 0f);
        d.setTransformation(new AffineTransformation(new Matrix4f()
                .translate(0, -0.12f, 0)
                .rotateY(tilt)
                .scale(width, height, width)
                .translate(-0.5f, 0, -0.5f)));
        TempEntities.track(d);
        world.spawnEntity(d);
        return d;
    }

    private static void lock(LivingEntity e) {
        addModifier(e, EntityAttributes.GENERIC_MOVEMENT_SPEED);
        addModifier(e, EntityAttributes.GENERIC_JUMP_STRENGTH);
    }

    private static void addModifier(LivingEntity e, RegistryEntry<EntityAttribute> attribute) {
        EntityAttributeInstance inst = e.getAttributeInstance(attribute);
        if (inst != null && !inst.hasModifier(FROZEN_MODIFIER)) {
            inst.addTemporaryModifier(new EntityAttributeModifier(FROZEN_MODIFIER, -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void unlock(LivingEntity e) {
        for (RegistryEntry<EntityAttribute> attribute : List.of(EntityAttributes.GENERIC_MOVEMENT_SPEED, EntityAttributes.GENERIC_JUMP_STRENGTH)) {
            EntityAttributeInstance inst = e.getAttributeInstance(attribute);
            if (inst != null) inst.removeModifier(FROZEN_MODIFIER);
        }
    }

    private static void tickFrozen(ServerWorld world) {
        long now = world.getTime();
        Iterator<Frozen> it = FROZEN.values().iterator();
        while (it.hasNext()) {
            Frozen f = it.next();
            LivingEntity e = f.entity;
            if (e.getWorld() != world && !e.isRemoved()) continue;
            if (!e.isAlive() || e.isRemoved()) {
                it.remove();
                unlock(e);
                breakShell(world, f);
                continue;
            }
            if (now >= f.until) {
                it.remove();
                thaw(world, f);
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

            // Frost smoking off the ice
            if (world.random.nextInt(4) == 0) {
                world.spawnParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getBodyY(world.random.nextDouble()), e.getZ(),
                        1, e.getWidth() * 0.5, 0.1, e.getWidth() * 0.5, 0.01);
            }
            // A warning crack in the last second
            if (f.until - now == 20) {
                world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BLOCK_AMETHYST_CLUSTER_HIT, SoundCategory.PLAYERS, 0.8f, 1.6f);
            }
        }
    }

    /** Time's up: the ice bursts apart and takes a last bite out of them. */
    private static void thaw(ServerWorld world, Frozen f) {
        LivingEntity e = f.entity;
        unlock(e);
        breakShell(world, f);
        world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.3f);
        Entity caster = f.caster == null ? null : world.getPlayerByUuid(f.caster);
        e.timeUntilRegen = 0;
        e.damage(freezeDamage(world, caster), SHATTER_DAMAGE);
    }

    private static void breakShell(ServerWorld world, Frozen f) {
        LivingEntity e = f.entity;
        Vec3d c = new Vec3d(e.getX(), e.getY() + e.getHeight() * 0.5, e.getZ());
        world.spawnParticles(ICE_CHIPS, c.x, c.y, c.z, 50, e.getWidth() * 0.5 + 0.2, e.getHeight() * 0.5, e.getWidth() * 0.5 + 0.2, 0.2);
        world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 15, e.getWidth() * 0.5, e.getHeight() * 0.4, e.getWidth() * 0.5, 0.08);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 1.0f + world.random.nextFloat() * 0.3f);
        if (f.shell != null) TempEntities.discard(f.shell);
    }

    // ---------------------------------------------------------------------
    // Blocks
    // ---------------------------------------------------------------------

    private static void freezeBlock(ServerWorld world, PlayerEntity caster, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (!caster.canModifyBlocks() || !world.canPlayerModifyAt(caster, pos)) return;
        BlockState state = world.getBlockState(pos);

        // Fire on the face it's pointed at goes out
        BlockPos front = pos.offset(hit.getSide());
        if (world.getBlockState(front).isIn(BlockTags.FIRE)) {
            world.removeBlock(front, false);
            world.playSound(null, front, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.7f, 1.6f);
        }

        if (state.isOf(Blocks.WATER)) {
            // Water skins over with ice that melts away again by itself, like Frost Walker's
            for (BlockPos p : BlockPos.iterate(pos.add(-1, 0, -1), pos.add(1, 0, 1))) {
                BlockState s = world.getBlockState(p);
                if (s.isOf(Blocks.WATER) && s.getFluidState().isStill() && world.getBlockState(p.up()).isAir()) {
                    world.setBlockState(p, Blocks.FROSTED_ICE.getDefaultState());
                    world.scheduleBlockTick(p, Blocks.FROSTED_ICE, 60 + world.random.nextInt(60));
                }
            }
        } else if (state.isOf(Blocks.LAVA)) {
            world.setBlockState(pos, state.getFluidState().isStill() ? Blocks.OBSIDIAN.getDefaultState() : Blocks.COBBLESTONE.getDefaultState());
            world.playSound(null, pos, SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.BLOCKS, 1.0f, 1.2f);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.02);
        } else {
            frost(world, pos, state);
        }
    }

    /** Turns a plain solid block to ice for a few seconds. */
    private static void frost(ServerWorld world, BlockPos pos, BlockState state) {
        Map<BlockPos, Frost> frosted = FROSTED.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>());
        long restoreAt = world.getTime() + FROST_TICKS + world.random.nextInt(40);
        Frost existing = frosted.get(pos);
        if (existing != null) {
            // Still frozen from earlier: keep it frozen for longer
            frosted.put(pos, new Frost(existing.original(), Math.max(existing.restoreAt(), restoreAt)));
            return;
        }
        if (frosted.size() >= MAX_FROST || !canFrost(world, pos, state)) return;

        frosted.put(pos.toImmutable(), new Frost(state, restoreAt));
        world.setBlockState(pos, ICE, Block.NOTIFY_ALL);
        world.playSound(null, pos, SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.BLOCKS, 0.5f, 1.5f + world.random.nextFloat() * 0.4f);
        world.spawnParticles(ParticleTypes.SNOWFLAKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.4, 0.4, 0.4, 0.02);
    }

    /**
     * Only plain full blocks with nothing hanging off them: no containers, nothing unbreakable or very hard, and no
     * torch, plant, door or rail next to it that would pop off once its support turns to ice.
     */
    private static boolean canFrost(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
        if (state.isIn(BlockTags.ICE) || state.isOf(Blocks.FROSTED_ICE)) return false;
        float hardness = state.getHardness(world, pos);
        if (hardness < 0 || hardness >= 10 || !state.isFullCube(world, pos)) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = world.getBlockState(n);
            if (!ns.isAir() && ns.getFluidState().isEmpty() && !ns.isFullCube(world, n)) return false;
        }
        return true;
    }

    private static void tickFrost(ServerWorld world, Map<BlockPos, Frost> frosted) {
        long now = world.getTime();
        Iterator<Map.Entry<BlockPos, Frost>> it = frosted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Frost> entry = it.next();
            if (now < entry.getValue().restoreAt()) continue;
            BlockPos pos = entry.getKey();
            if (!world.isChunkLoaded(pos)) continue;
            it.remove();
            if (restore(world, pos, entry.getValue())) {
                world.spawnParticles(ICE_CHIPS, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 8, 0.35, 0.35, 0.35, 0.05);
                if (world.random.nextInt(3) == 0) {
                    world.playSound(null, pos, SoundEvents.BLOCK_GLASS_STEP, SoundCategory.BLOCKS, 0.5f, 1.4f);
                }
            }
        }
    }

    /** Puts the original block back, if what's there is still our ice (or the water it melted into). */
    private static boolean restore(ServerWorld world, BlockPos pos, Frost frost) {
        BlockState now = world.getBlockState(pos);
        if (!now.isOf(Blocks.ICE) && !now.isOf(Blocks.WATER)) return false;
        world.setBlockState(pos, frost.original(), Block.NOTIFY_ALL);
        return true;
    }

    private static void thawEverything(MinecraftServer server) {
        for (Map.Entry<RegistryKey<World>, Map<BlockPos, Frost>> entry : FROSTED.entrySet()) {
            ServerWorld world = server.getWorld(entry.getKey());
            if (world == null) continue;
            for (Map.Entry<BlockPos, Frost> f : entry.getValue().entrySet()) restore(world, f.getKey(), f.getValue());
        }
        FROSTED.clear();
        for (Frozen f : new ArrayList<>(FROZEN.values())) {
            unlock(f.entity);
            if (f.shell != null) TempEntities.discard(f.shell);
        }
        FROZEN.clear();
        BEAMS.clear();
    }
}
