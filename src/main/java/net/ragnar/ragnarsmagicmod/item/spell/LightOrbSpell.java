package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A little fairy light that rides above your shoulder for {@link #LIFETIME_TICKS}, lighting the way with real block
 * light. It never passes through walls: in tight tunnels it tucks in close to your head instead. Cast again to send
 * it flying to where you're looking, where it stays and lights up the area (handy for caves and ravines); cast once
 * more to call it back. It also makes hostile mobs near it glow, so nothing sneaks up on you in the dark.
 */
public class LightOrbSpell implements Spell {
    private static final int LIFETIME_TICKS = 20 * 60 * 5;   // 5 minutes
    private static final int FADE_WARNING = 20 * 10;
    private static final int LIGHT_LEVEL = 15;

    // Following: a spot just above and behind the right shoulder
    private static final double SHOULDER_BACK = 0.6;
    private static final double SHOULDER_SIDE = 0.7;
    private static final double SHOULDER_UP = 0.45;
    private static final double FOLLOW = 0.3;               // fraction of the gap closed each tick
    private static final double WALL_MARGIN = 0.35;          // how far it keeps off walls
    private static final double SNAP_DISTANCE = 16.0;        // teleported/fell far: jump straight back

    // Sending it out
    private static final double SEND_RANGE = 32.0;
    private static final double SEND_SPEED = 1.0;
    private static final double MAX_ANCHOR_DISTANCE = 96.0;  // wander further than this and it comes home

    private static final double REVEAL_RADIUS = 12.0;
    private static final int REVEAL_INTERVAL = 20;

    private static final float CORE_SCALE = 0.2f;
    private static final DustParticleEffect GLOW = new DustParticleEffect(new Vector3f(1.0f, 0.95f, 0.6f), 0.9f);

    private static final class Orb {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.BlockDisplayEntity core;
        Vec3d pos;
        Vec3d anchor;       // where it was sent, or null while following
        boolean arrived;
        BlockPos lightPos;
        int age = 0;

        Orb(ServerWorld world, UUID owner, DisplayEntity.BlockDisplayEntity core, Vec3d pos) {
            this.world = world;
            this.owner = owner;
            this.core = core;
            this.pos = pos;
        }
    }

    private static final Map<UUID, Orb> ORBS = new HashMap<>();
    // Players whose current cast summoned a new orb (full cost and cooldown) rather than steering one
    private static final Set<UUID> SUMMONING = new HashSet<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            for (Orb orb : new ArrayList<>(ORBS.values())) {
                if (orb.world == world && !tick(orb)) remove(orb, true);
            }
        });
        // Never leave light blocks behind in the world
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Orb orb : new ArrayList<>(ORBS.values())) remove(orb, false);
        });
    }

    private static Orb activeOrb(PlayerEntity player) {
        Orb orb = ORBS.get(player.getUuid());
        return orb != null && orb.world == player.getWorld() ? orb : null;
    }

    // Steering an orb you already have is free
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return activeOrb(player) != null ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return SUMMONING.remove(player.getUuid()) ? tomeCooldown : 10;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Orb orb = activeOrb(player);
        if (orb == null) {
            summon(sw, player);
            SUMMONING.add(player.getUuid());
            return true;
        }
        if (orb.anchor == null) send(orb, player);
        else recall(orb, player);
        return true;
    }

    // ---------------------------------------------------------------------
    // Summon / send / recall
    // ---------------------------------------------------------------------

    private static void summon(ServerWorld world, PlayerEntity player) {
        Orb old = ORBS.get(player.getUuid());
        if (old != null) remove(old, false);

        Vec3d start = shoulderSpot(world, player);
        DisplayEntity.BlockDisplayEntity core = EntityType.BLOCK_DISPLAY.create(world);
        if (core == null) return;
        core.setBlockState(Blocks.PEARLESCENT_FROGLIGHT.getDefaultState());
        core.setBrightness(Brightness.FULL);
        core.setTeleportDuration(2);
        core.setViewRange(1.5f);
        core.refreshPositionAndAngles(start.x, start.y, start.z, 0f, 0f);
        core.setTransformation(coreTransform(0));
        TempEntities.track(core);
        world.spawnEntity(core);

        Orb orb = new Orb(world, player.getUuid(), core, start);
        ORBS.put(player.getUuid(), orb);
        updateLight(orb);

        world.spawnParticles(ParticleTypes.END_ROD, start.x, start.y, start.z, 12, 0.1, 0.1, 0.1, 0.05);
        world.playSound(null, start.x, start.y, start.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5f, 1.5f);
        world.playSound(null, start.x, start.y, start.z, SoundEvents.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.PLAYERS, 0.8f, 1.4f);
        player.sendMessage(Text.literal("Cast again to send the light where you look.").formatted(Formatting.YELLOW), true);
    }

    private static void send(Orb orb, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        BlockHitResult hit = orb.world.raycast(new RaycastContext(eye, eye.add(look.multiply(SEND_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = hit.getType() == HitResult.Type.MISS ? eye.add(look.multiply(SEND_RANGE))
                : hit.getPos().subtract(look.multiply(0.6));
        orb.anchor = end;
        orb.arrived = false;
        orb.world.playSound(null, orb.pos.x, orb.pos.y, orb.pos.z, SoundEvents.ENTITY_ALLAY_ITEM_THROWN, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    private static void recall(Orb orb, PlayerEntity player) {
        orb.anchor = null;
        // Too far, or no clear way back: blink home rather than drift through the rock
        Vec3d home = shoulderSpot(orb.world, player);
        if (orb.pos.distanceTo(home) > 6.0 || !clear(orb.world, orb.pos, home)) {
            orb.world.spawnParticles(ParticleTypes.END_ROD, orb.pos.x, orb.pos.y, orb.pos.z, 10, 0.15, 0.15, 0.15, 0.05);
            orb.pos = home;
            orb.core.refreshPositionAndAngles(home.x, home.y, home.z, 0f, 0f);
            orb.world.spawnParticles(ParticleTypes.END_ROD, home.x, home.y, home.z, 10, 0.15, 0.15, 0.15, 0.05);
        }
        orb.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.PLAYERS, 1.0f, 1.3f);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    /** Returns false when the orb should go out. */
    private static boolean tick(Orb orb) {
        ServerWorld world = orb.world;
        PlayerEntity player = world.getPlayerByUuid(orb.owner);
        if (player == null || !player.isAlive() || ++orb.age > LIFETIME_TICKS) return false;
        if (orb.age == LIFETIME_TICKS - FADE_WARNING) {
            player.sendMessage(Text.literal("Your light is fading...").formatted(Formatting.GOLD), true);
        }

        if (orb.anchor == null) {
            // Ride along above the shoulder, never through a wall
            Vec3d home = shoulderSpot(world, player);
            if (orb.pos.distanceTo(home) > SNAP_DISTANCE) {
                orb.pos = home;
            } else {
                Vec3d next = orb.pos.lerp(home, FOLLOW);
                orb.pos = clip(world, player.getEyePos(), next);
            }
        } else {
            if (orb.anchor.distanceTo(player.getPos()) > MAX_ANCHOR_DISTANCE) {
                recall(orb, player);
            } else if (!orb.arrived) {
                Vec3d to = orb.anchor.subtract(orb.pos);
                if (to.length() <= SEND_SPEED) {
                    orb.pos = orb.anchor;
                    orb.arrived = true;
                    world.spawnParticles(ParticleTypes.END_ROD, orb.pos.x, orb.pos.y, orb.pos.z, 15, 0.2, 0.2, 0.2, 0.06);
                    world.playSound(null, orb.pos.x, orb.pos.y, orb.pos.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 1.8f);
                } else {
                    orb.pos = orb.pos.add(to.normalize().multiply(SEND_SPEED));
                    world.spawnParticles(ParticleTypes.END_ROD, orb.pos.x, orb.pos.y, orb.pos.z, 1, 0.02, 0.02, 0.02, 0.0);
                }
            }
        }

        // Gentle bob, drawn by the display only so the light itself doesn't jitter between blocks
        double bob = Math.sin(orb.age * 0.12) * 0.06;
        orb.core.setPosition(orb.pos.x, orb.pos.y + bob, orb.pos.z);
        orb.core.setTransformation(coreTransform(orb.age));
        orb.core.setStartInterpolation(0);
        orb.core.setInterpolationDuration(2);
        updateLight(orb);

        // A soft glow; it flickers as it runs out
        boolean fading = orb.age > LIFETIME_TICKS - FADE_WARNING;
        if (!fading || world.random.nextInt(3) != 0) {
            world.spawnParticles(GLOW, orb.pos.x, orb.pos.y + bob, orb.pos.z, 1, 0.12, 0.12, 0.12, 0.0);
        }
        if (world.random.nextInt(6) == 0) {
            world.spawnParticles(ParticleTypes.WAX_OFF, orb.pos.x, orb.pos.y + bob, orb.pos.z, 1, 0.15, 0.15, 0.15, 0.0);
        }

        if (orb.age % REVEAL_INTERVAL == 0) {
            for (HostileEntity mob : world.getEntitiesByClass(HostileEntity.class,
                    new net.minecraft.util.math.Box(orb.pos, orb.pos).expand(REVEAL_RADIUS), HostileEntity::isAlive)) {
                mob.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, REVEAL_INTERVAL + 10, 0, false, false));
            }
        }
        return true;
    }

    private static void remove(Orb orb, boolean effects) {
        ORBS.remove(orb.owner);
        clearLight(orb.world, orb.lightPos);
        orb.lightPos = null;
        if (effects) {
            orb.world.spawnParticles(GLOW, orb.pos.x, orb.pos.y, orb.pos.z, 12, 0.2, 0.2, 0.2, 0.0);
            orb.world.spawnParticles(ParticleTypes.END_ROD, orb.pos.x, orb.pos.y, orb.pos.z, 6, 0.1, 0.1, 0.1, 0.03);
            orb.world.playSound(null, orb.pos.x, orb.pos.y, orb.pos.z, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 0.8f, 1.6f);
        }
        TempEntities.discard(orb.core);
    }

    // ---------------------------------------------------------------------
    // Placement helpers
    // ---------------------------------------------------------------------

    /** Above and behind the right shoulder, pulled in toward the head if a wall is in the way. */
    private static Vec3d shoulderSpot(ServerWorld world, PlayerEntity player) {
        double yaw = Math.toRadians(player.getHeadYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Vec3d eye = player.getEyePos();
        Vec3d want = eye.add(forward.multiply(-SHOULDER_BACK)).add(right.multiply(SHOULDER_SIDE)).add(0, SHOULDER_UP, 0);
        return clip(world, eye, want);
    }

    /** {@code to}, or the point just short of the first wall on the way there from {@code from}. */
    private static Vec3d clip(ServerWorld world, Vec3d from, Vec3d to) {
        Vec3d path = to.subtract(from);
        double len = path.length();
        if (len < 1.0e-4) return to;
        Vec3d dir = path.multiply(1.0 / len);
        BlockHitResult hit = world.raycast(new RaycastContext(from, to.add(dir.multiply(WALL_MARGIN)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        if (hit.getType() == HitResult.Type.MISS) return to;
        double safe = Math.max(0, from.distanceTo(hit.getPos()) - WALL_MARGIN);
        return from.add(dir.multiply(Math.min(len, safe)));
    }

    private static boolean clear(ServerWorld world, Vec3d from, Vec3d to) {
        return world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent())).getType() == HitResult.Type.MISS;
    }

    private static AffineTransformation coreTransform(int age) {
        return new AffineTransformation(new Matrix4f()
                .rotateY(age * 0.08f)
                .rotateX(0.6f)
                .rotateZ(0.6f)
                .scale(CORE_SCALE)
                .translate(-0.5f, -0.5f, -0.5f));
    }

    /** Keeps a light block in the air block the orb is in (or the nearest open neighbour). */
    private static void updateLight(Orb orb) {
        BlockPos want = openSpotNear(orb, BlockPos.ofFloored(orb.pos));
        if (want != null && want.equals(orb.lightPos)) return;
        clearLight(orb.world, orb.lightPos);
        orb.lightPos = null;
        if (want != null) {
            BlockState state = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, LIGHT_LEVEL);
            orb.world.setBlockState(want, state, 3);
            orb.lightPos = want;
        }
    }

    private static BlockPos openSpotNear(Orb orb, BlockPos pos) {
        if (canHoldLight(orb, pos)) return pos;
        for (Direction d : Direction.values()) {
            if (canHoldLight(orb, pos.offset(d))) return pos.offset(d);
        }
        return null;
    }

    private static boolean canHoldLight(Orb orb, BlockPos pos) {
        // Only empty air, or the light we already placed; never someone else's light block
        return orb.world.getBlockState(pos).isAir() || pos.equals(orb.lightPos);
    }

    private static void clearLight(ServerWorld world, BlockPos pos) {
        if (pos != null && world.getBlockState(pos).isOf(Blocks.LIGHT)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        }
    }
}
