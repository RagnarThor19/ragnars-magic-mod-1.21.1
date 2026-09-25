package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CaveVines;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Tome of Vines. A ripple runs through the ground around the caster; wherever it reaches a creature, thorny
 * vines burst up, coil around it and drag it down. Held fast, squeezed and poisoned for three seconds, then
 * the vines snap apart.
 */
public class VinesSpell implements Spell {
    private static final double RADIUS = 7.0;
    private static final int HOLD_TICKS = 60;          // 3 seconds stuck
    private static final int GROW_TICKS = 6;
    private static final int RETRACT_TICKS = 6;
    private static final int WRITHE_EVERY = 4;
    private static final float GRAB_DAMAGE = 4.0f;
    private static final float SQUEEZE_DAMAGE = 2.0f;  // every second held
    private static final int POISON_TICKS = 100;
    private static final int SEGMENTS = 3;

    private static final Identifier ROOTED = Identifier.of("ragnarsmagicmod", "vine_grip");
    private static final DustParticleEffect SAP = new DustParticleEffect(new Vector3f(0.25f, 0.55f, 0.12f), 1.0f);

    /** One coiling vine: where it comes out of the ground and how it curls. */
    private record Tendril(double angle, double twist, double phase, DisplayEntity.BlockDisplayEntity[] segments) {}

    private static final class Grab {
        final ServerWorld world;
        final UUID owner;
        final LivingEntity target;
        final int delay;        // ticks until the ripple reaches it
        Vec3d anchor;           // where it's held, on the ground
        DisplayEntity.BlockDisplayEntity roots;
        final List<Tendril> tendrils = new ArrayList<>();
        int age = 0;
        boolean gripped = false;
        int releaseAt = HOLD_TICKS;

        Grab(ServerWorld world, UUID owner, LivingEntity target, int delay) {
            this.world = world;
            this.owner = owner;
            this.target = target;
            this.delay = delay;
        }
    }

    private record Ripple(ServerWorld world, Vec3d center, int age) {}

    private static final List<Grab> GRABS = new ArrayList<>();
    /** Everything currently wrapped up: no AI, no attacks, no explosions (see MobEntityMixin, CreeperEntityMixin). */
    private static final Set<LivingEntity> HELD = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<Ripple> RIPPLES = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            List<Ripple> next = new ArrayList<>();
            Iterator<Ripple> ripples = RIPPLES.iterator();
            while (ripples.hasNext()) {
                Ripple r = ripples.next();
                if (r.world() != world) continue;
                ripples.remove();
                tickRipple(r);
                if (r.age() < RADIUS + 1) next.add(new Ripple(r.world(), r.center(), r.age() + 1));
            }
            RIPPLES.addAll(next);

            Iterator<Grab> grabs = GRABS.iterator();
            while (grabs.hasNext()) {
                Grab g = grabs.next();
                if (g.world == world && !tickGrab(g)) grabs.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        Vec3d c = player.getPos();
        RIPPLES.add(new Ripple(sw, c, 0));

        Box area = player.getBoundingBox().expand(RADIUS, 3.0, RADIUS);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator())) {
            if (isFriendly(e, player) || e instanceof EnderDragonEntity) continue;
            double d = Math.sqrt(e.squaredDistanceTo(c.x, e.getY(), c.z));
            if (d > RADIUS + e.getWidth() / 2) continue;
            if (isGrabbed(e)) continue;
            GRABS.add(new Grab(sw, player.getUuid(), e, 1 + (int) d));
        }

        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ROOTS_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_MOSS_PLACE, SoundCategory.PLAYERS, 1.5f, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_MANGROVE_ROOTS_PLACE, SoundCategory.PLAYERS, 1.2f, 0.5f);
        sw.spawnParticles(ParticleTypes.COMPOSTER, c.x, c.y + 0.2, c.z, 20, 0.6, 0.1, 0.6, 0.02);
        return true;
    }

    private static boolean isFriendly(LivingEntity e, PlayerEntity owner) {
        if (e == owner || e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && owner.getUuid().equals(pet.getOwnerUuid())) return true;
        return e.isTeammate(owner);
    }

    private static boolean isGrabbed(LivingEntity e) {
        for (Grab g : GRABS) if (g.target == e) return true;
        return false;
    }

    /** True while the vines have hold of {@code e}: it can't think, act, attack or explode. */
    public static boolean isHeld(LivingEntity e) {
        return !HELD.isEmpty() && HELD.contains(e);
    }

    // ---------------------------------------------------------------------
    // The ripple through the ground
    // ---------------------------------------------------------------------

    private static void tickRipple(Ripple r) {
        ServerWorld world = r.world();
        double radius = Math.min(r.age(), RADIUS);
        if (radius <= 0) return;
        int points = (int) (radius * 10);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points + world.getRandom().nextDouble() * 0.2;
            double x = r.center().x + Math.cos(a) * radius;
            double z = r.center().z + Math.sin(a) * radius;
            Vec3d ground = findGround(world, new Vec3d(x, r.center().y + 1.0, z));
            if (ground == null) continue;
            BlockState below = world.getBlockState(BlockPos.ofFloored(ground.x, ground.y - 0.5, ground.z));
            if (!below.isAir()) {
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, below),
                        ground.x, ground.y + 0.1, ground.z, 2, 0.1, 0.05, 0.1, 0.1);
            }
            if (i % 3 == 0) world.spawnParticles(ParticleTypes.COMPOSTER, ground.x, ground.y + 0.15, ground.z, 1, 0.05, 0.05, 0.05, 0);
        }
        world.playSound(null, r.center().x, r.center().y, r.center().z, SoundEvents.BLOCK_ROOTED_DIRT_BREAK,
                SoundCategory.PLAYERS, 0.8f, 0.6f + r.age() * 0.1f);
    }

    /** The top of the first solid surface at or up to 4 blocks below {@code from}. */
    private static Vec3d findGround(ServerWorld world, Vec3d from) {
        BlockPos.Mutable pos = BlockPos.ofFloored(from).mutableCopy();
        for (int i = 0; i < 5; i++) {
            BlockState s = world.getBlockState(pos);
            if (!s.isAir() && s.isSideSolidFullSquare(world, pos, Direction.UP)) {
                return new Vec3d(from.x, pos.getY() + 1.0, from.z);
            }
            pos.move(Direction.DOWN);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // The grab
    // ---------------------------------------------------------------------

    /** Returns false once the vines are gone. */
    private static boolean tickGrab(Grab g) {
        g.age++;
        if (g.age < g.delay) return true;
        LivingEntity e = g.target;
        int t = g.age - g.delay; // ticks since the vines broke the surface

        if (!g.gripped) {
            if (!e.isAlive() || e.isRemoved()) return false;
            Vec3d ground = findGround(g.world, e.getPos().add(0, 0.5, 0));
            if (ground == null) return false; // nothing to grow out of
            g.anchor = ground;
            g.gripped = true;
            spawnVines(g);
            root(e, true);
            HELD.add(e);
            // Drop whatever it was in the middle of: a drawn bow, a charging crossbow, a lit fuse
            if (e instanceof MobEntity mob) {
                mob.clearActiveItem();
                mob.getNavigation().stop();
            }
            PlayerEntity owner = g.world.getPlayerByUuid(g.owner);
            hurt(g.world, e, owner, GRAB_DAMAGE);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, POISON_TICKS, 1), owner);

            Vec3d a = g.anchor;
            BlockState below = g.world.getBlockState(BlockPos.ofFloored(a.x, a.y - 0.5, a.z));
            if (!below.isAir()) {
                g.world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, below), a.x, a.y + 0.2, a.z, 30, 0.4, 0.1, 0.4, 0.15);
            }
            g.world.spawnParticles(SAP, a.x, a.y + 0.5, a.z, 12, 0.4, 0.4, 0.4, 0);
            g.world.playSound(null, a.x, a.y, a.z, SoundEvents.BLOCK_CAVE_VINES_PLACE, SoundCategory.PLAYERS, 1.5f, 0.6f);
            g.world.playSound(null, a.x, a.y, a.z, SoundEvents.BLOCK_ROOTS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
            g.world.playSound(null, a.x, a.y, a.z, SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1.2f, 0.8f);
        }

        boolean alive = e.isAlive() && !e.isRemoved();
        if (!alive && t < g.releaseAt) g.releaseAt = t; // nothing left to hold: let go now
        int releaseAt = g.releaseAt;

        if (t < releaseAt) {
            hold(g, e);
            if (t < GROW_TICKS) pose(g, t + 1, (t + 1) / (float) GROW_TICKS, 1);
            else if (t % WRITHE_EVERY == 0) pose(g, t, 1f, WRITHE_EVERY);

            if (t > 0 && t % 20 == 0) {
                hurt(g.world, e, g.world.getPlayerByUuid(g.owner), SQUEEZE_DAMAGE);
                g.world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BLOCK_CAVE_VINES_STEP, SoundCategory.PLAYERS, 1.2f, 0.6f);
                g.world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 0.8f, 1.0f);
                g.world.spawnParticles(SAP, e.getX(), e.getBodyY(0.5), e.getZ(), 8, 0.3, 0.4, 0.3, 0);
            }
            if (t % 5 == 0) {
                g.world.spawnParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, e.getX(), e.getBodyY(0.9), e.getZ(), 1, 0.3, 0.2, 0.3, 0);
            }
            return true;
        }

        if (t == releaseAt) {
            root(e, false);
            HELD.remove(e);
            Vec3d a = g.anchor;
            g.world.playSound(null, a.x, a.y, a.z, SoundEvents.BLOCK_CAVE_VINES_BREAK, SoundCategory.PLAYERS, 1.5f, 0.7f);
            g.world.playSound(null, a.x, a.y, a.z, SoundEvents.BLOCK_ROOTS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.9f);
            BlockStateParticleEffect vine = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.CAVE_VINES_PLANT.getDefaultState());
            g.world.spawnParticles(vine, a.x, a.y + g.target.getHeight() * 0.5, a.z, 40, 0.4, g.target.getHeight() * 0.3, 0.4, 0.1);
        }
        int r = t - releaseAt;
        if (r < RETRACT_TICKS) {
            if (r == 0) pose(g, t, 0f, RETRACT_TICKS);
            return true;
        }
        discardVines(g);
        return false;
    }

    /** Keeps the victim pinned to the spot the vines came up at. */
    private static void hold(Grab g, LivingEntity e) {
        Vec3d a = g.anchor;
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
        e.fallDistance = 0;
        if (e instanceof MobEntity mob) mob.getNavigation().stop();
        if (e instanceof CreeperEntity creeper) creeper.setFuseSpeed(-1); // the swell dies down
        if (e.getPos().squaredDistanceTo(a) > 0.04) {
            if (e instanceof ServerPlayerEntity sp) {
                sp.networkHandler.requestTeleport(a.x, a.y, a.z, 0f, 0f, EnumSet.of(PositionFlag.Y_ROT, PositionFlag.X_ROT));
            } else {
                e.refreshPositionAfterTeleport(a.x, a.y, a.z);
            }
        }
    }

    /** Can't walk, can't jump, can't fly off while the vines have you. */
    private static void root(LivingEntity e, boolean on) {
        for (RegistryEntry<EntityAttribute> attr : List.of(EntityAttributes.GENERIC_MOVEMENT_SPEED,
                EntityAttributes.GENERIC_JUMP_STRENGTH, EntityAttributes.GENERIC_FLYING_SPEED)) {
            EntityAttributeInstance inst = e.getAttributeInstance(attr);
            if (inst == null) continue;
            inst.removeModifier(ROOTED);
            if (on) inst.addTemporaryModifier(new EntityAttributeModifier(ROOTED, -1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void hurt(ServerWorld world, LivingEntity e, PlayerEntity owner, float amount) {
        e.timeUntilRegen = 0;
        e.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().magic(), amount);
    }

    // ---------------------------------------------------------------------
    // The vines themselves
    // ---------------------------------------------------------------------

    private static void spawnVines(Grab g) {
        ServerWorld world = g.world;
        Random rand = world.getRandom();
        float w = g.target.getWidth();
        int count = w > 1.2f ? 6 : w < 0.5f ? 3 : 4;

        g.roots = display(world, Blocks.MANGROVE_ROOTS.getDefaultState(), g.anchor);
        double offset = rand.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            double angle = offset + i * Math.PI * 2 / count + (rand.nextDouble() - 0.5) * 0.5;
            double twist = (rand.nextBoolean() ? 1 : -1) * (0.45 + rand.nextDouble() * 0.3);
            DisplayEntity.BlockDisplayEntity[] segs = new DisplayEntity.BlockDisplayEntity[SEGMENTS];
            for (int k = 0; k < SEGMENTS; k++) {
                boolean tip = k == SEGMENTS - 1;
                BlockState state = (tip ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT).getDefaultState()
                        .with(CaveVines.BERRIES, rand.nextInt(5) == 0);
                segs[k] = display(world, state, g.anchor);
            }
            g.tendrils.add(new Tendril(angle, twist, rand.nextDouble() * Math.PI * 2, segs));
        }
        pose(g, 0, 0f, 1);
    }

    private static DisplayEntity.BlockDisplayEntity display(ServerWorld world, BlockState state, Vec3d at) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
        if (d == null) return null;
        d.setBlockState(state);
        d.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        d.setTransformation(new AffineTransformation(new Matrix4f().scale(0.001f)));
        TempEntities.track(d);
        world.spawnEntity(d);
        return d;
    }

    /**
     * Lays every vine out for tick {@code t}: each rises from the roots beside the victim and curls inward and
     * around it. {@code growth} 0..1 scales how far they've come out of the ground.
     */
    private static void pose(Grab g, int t, float growth, int interpolation) {
        LivingEntity e = g.target;
        double ringRadius = e.getWidth() / 2 + 0.3;
        double segLen = Math.max(0.5, e.getHeight() * 0.42);
        float width = (float) MathHelper.clamp(e.getWidth() * 0.9, 0.5, 1.1);

        if (g.roots != null) {
            float s = growth;
            set(g.roots, new Matrix4f()
                    .translate(0, -0.2f, 0)
                    .scale((float) (ringRadius * 2.4) * s, 0.45f * s, (float) (ringRadius * 2.4) * s)
                    .translate(-0.5f, 0, -0.5f), interpolation);
        }

        double[] tilts = {-0.2, 0.45, 1.05}; // lean out, then curl in over the body
        for (Tendril td : g.tendrils) {
            double sway = Math.sin(t * 0.35 + td.phase()) * 0.12;
            Vector3f base = new Vector3f((float) (Math.cos(td.angle()) * ringRadius), 0f, (float) (Math.sin(td.angle()) * ringRadius));
            for (int k = 0; k < SEGMENTS; k++) {
                // Segments come out one after another
                float segGrowth = MathHelper.clamp(growth * SEGMENTS - k, 0f, 1f);
                double heading = td.angle() + Math.PI + td.twist() * k; // point inward, spiralling round
                double tilt = tilts[k] + sway;
                Vector3f dir = new Vector3f(
                        (float) (Math.cos(heading) * Math.sin(tilt)),
                        (float) Math.cos(tilt),
                        (float) (Math.sin(heading) * Math.sin(tilt))).normalize();
                float len = (float) segLen * segGrowth;

                Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), dir);
                set(td.segments()[k], new Matrix4f()
                        .translate(base)
                        .rotate(rot)
                        .scale(width * Math.max(segGrowth, 0.001f), Math.max(len, 0.001f), width * Math.max(segGrowth, 0.001f))
                        .translate(-0.5f, 0, -0.5f), interpolation);
                base.add(new Vector3f(dir).mul(len * 0.92f));
            }
        }
    }

    private static void set(DisplayEntity.BlockDisplayEntity d, Matrix4f m, int interpolation) {
        if (d == null) return;
        d.setTransformation(new AffineTransformation(m));
        d.setStartInterpolation(0);
        d.setInterpolationDuration(interpolation);
    }

    private static void discardVines(Grab g) {
        HELD.remove(g.target);
        if (g.roots != null) TempEntities.discard(g.roots);
        for (Tendril td : g.tendrils) {
            for (DisplayEntity.BlockDisplayEntity d : td.segments()) if (d != null) TempEntities.discard(d);
        }
    }
}
