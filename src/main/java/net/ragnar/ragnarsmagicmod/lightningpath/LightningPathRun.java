package net.ragnar.ragnarsmagicmod.lightningpath;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One casting of the Lightning Path, run by the server: painting the path from where the caster looks, then
 * following the run - checking it, hurting what's in the way - and the arrival.
 */
public final class LightningPathRun {
    public static final int PAINT_TICKS = 30;
    /** How far the crosshair can paint from (it can still only see what's in view). */
    public static final double AIM_RANGE = 100;
    public static final float HIT_DAMAGE = 18f;
    public static final float ARRIVE_DAMAGE = 6f;
    public static final double ARRIVE_RADIUS = 3.5;
    /** After landing: no fall damage for this long. */
    private static final int AFTER_TICKS = 40;
    /** The shortest path worth running. */
    private static final double MIN_LENGTH = 3;

    private enum Stage { PAINT, RUN, AFTER }

    private final ServerWorld world;
    private final UUID casterId;
    private Stage stage = Stage.PAINT;
    private int age;
    private final PathBuilder path;
    private int runTicks, runLimit;
    @Nullable private Vec3d lastPos;
    private final Set<UUID> struck = new HashSet<>();

    private static final List<LightningPathRun> ACTIVE = new ArrayList<>();

    private LightningPathRun(ServerWorld world, ServerPlayerEntity caster) {
        this.world = world;
        this.casterId = caster.getUuid();
        this.path = new PathBuilder(caster.getPos());
    }

    @Nullable
    public static LightningPathRun of(PlayerEntity player) {
        for (LightningPathRun r : ACTIVE) if (r.casterId.equals(player.getUuid())) return r;
        return null;
    }

    public boolean painting() {
        return stage == Stage.PAINT;
    }

    public boolean running() {
        return stage == Stage.RUN;
    }

    public PathBuilder path() {
        return path;
    }

    /** Starts painting. */
    public static LightningPathRun begin(ServerPlayerEntity caster) {
        LightningPathRun run = new LightningPathRun(caster.getServerWorld(), caster);
        ACTIVE.add(run);
        Vec3d c = caster.getPos();
        run.world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_CHARGE, SoundCategory.PLAYERS, 1.5f, 1.4f);
        run.world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_CROSSBOW_QUICK_CHARGE_3.value(), SoundCategory.PLAYERS, 1.2f, 0.7f);
        run.broadcastPath(caster);
        return run;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    static void tickAll(ServerWorld world) {
        if (ACTIVE.isEmpty()) return;
        Iterator<LightningPathRun> it = ACTIVE.iterator();
        while (it.hasNext()) {
            LightningPathRun r = it.next();
            if (r.world != world) continue;
            if (!r.tick()) it.remove();
        }
    }

    /** One tick. False once it's all over. */
    public boolean tick() {
        ServerPlayerEntity caster = world.getServer().getPlayerManager().getPlayer(casterId);
        if (caster == null || !caster.isAlive() || caster.getServerWorld() != world) return false;
        age++;
        caster.fallDistance = 0;
        switch (stage) {
            case PAINT -> {
                paint(caster);
                if (age >= PAINT_TICKS) takeOff(caster);
            }
            case RUN -> {
                caster.extinguish();
                Vec3d now = caster.getPos();
                if (lastPos != null) strike(caster, lastPos, now);
                lastPos = now;
                // The runner's client moves them; if it never says it's done, finish here
                if (++runTicks > runLimit) arrive(caster);
            }
            case AFTER -> {
                if (age > AFTER_TICKS) return false;
            }
        }
        return true;
    }

    /** Wherever the crosshair is on the ground right now goes into the path. */
    private void paint(ServerPlayerEntity caster) {
        Vec3d eye = caster.getEyePos();
        Vec3d look = caster.getRotationVec(1f);
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(look.multiply(AIM_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, caster));
        if (hit.getType() == HitResult.Type.MISS) return;
        Vec3d spot = hit.getPos();
        if (hit.getSide() != Direction.UP) {
            // (Only off the side of something - never up through a ceiling)
            Vec3d climbed = hit.getSide() == Direction.DOWN ? null : climb(caster, spot, look);
            if (climbed != null) {
                // The side of a step or a hill: up onto the top of it
                spot = climbed;
            } else {
                // A real wall: drop down to the ground just in front of it
                Vec3d from = spot.subtract(look.multiply(0.4));
                BlockHitResult down = world.raycast(new RaycastContext(from, from.add(0, -24, 0),
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, caster));
                if (down.getType() == HitResult.Type.MISS) return;
                spot = down.getPos();
            }
        }
        int before = path.points().size();
        if (path.add(spot) && path.points().size() > before && path.points().size() % 3 == 0) {
            // A tick of static as the line crackles out
            world.playSound(null, spot.x, spot.y, spot.z, SoundEvents.BLOCK_COPPER_GRATE_HIT, SoundCategory.PLAYERS, 0.6f, 1.6f + world.random.nextFloat() * 0.4f);
        }
        if (age % 2 == 0) broadcastPath(caster);
    }

    /**
     * The top of whatever the crosshair hit the side of, if it's no more than {@link #CLIMB} blocks up and there's
     * room to stand on it - so paths can run up steps and hills. Null if it's a real wall.
     */
    @Nullable
    private Vec3d climb(ServerPlayerEntity caster, Vec3d side, Vec3d look) {
        Vec3d into = side.add(look.multiply(1, 0, 1).normalize().multiply(0.3));
        Vec3d above = new Vec3d(into.x, side.y + CLIMB + 0.2, into.z);
        BlockHitResult top = world.raycast(new RaycastContext(above, above.add(0, -(CLIMB + 0.4), 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, caster));
        if (top.getType() == HitResult.Type.MISS || top.getSide() != Direction.UP || top.getPos().y < side.y - 0.01) return null;
        Vec3d spot = top.getPos();
        return world.isSpaceEmpty(caster, caster.getDimensions(caster.getPose()).getBoxAt(spot)) ? spot : null;
    }

    /** How high a step or slope the path will climb onto. */
    private static final double CLIMB = 3.0;

    private void broadcastPath(ServerPlayerEntity caster) {
        LightningPath.sendNear(world, caster.getPos(), caster,
                new LightningPath.PaintPayload(caster.getId(), PAINT_TICKS - age, LightningPath.pack(path.points())));
    }

    /** Right-click again while painting: go now. */
    public void goNow(ServerPlayerEntity caster) {
        if (stage == Stage.PAINT) {
            paint(caster);
            takeOff(caster);
        }
    }

    private void takeOff(ServerPlayerEntity caster) {
        // Never end up standing in lava
        while (path.points().size() > 1 && isLava(path.points().get(path.points().size() - 1))) path.removeLast();
        Vec3d c = caster.getPos();
        if (path.length() < MIN_LENGTH) {
            stage = Stage.AFTER;
            age = AFTER_TICKS - 5;
            LightningPath.sendNear(world, c, caster, new LightningPath.RunPayload(caster.getId(), List.of()));
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDER_EYE_DEATH, SoundCategory.PLAYERS, 1f, 0.6f);
            return;
        }
        PathBuilder.Route route = PathBuilder.route(path.points());
        runLimit = (int) Math.ceil(route.length() / LightningPath.SPEED) + 30;
        runTicks = 0;
        lastPos = c;
        stage = Stage.RUN;
        LightningPath.sendNear(world, c, caster, new LightningPath.RunPayload(caster.getId(), LightningPath.pack(path.points())));

        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_COPPER_BULB_TURN_OFF, SoundCategory.PLAYERS, 2f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 2.5f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_CHARGE, SoundCategory.PLAYERS, 2f, 2.0f);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 1, c.z, 40, 0.3, 0.6, 0.3, 0.6);
        world.spawnParticles(ParticleTypes.FLASH, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
        ShakePayload.around(world, c, 6, 0.3f, 6);
    }

    private boolean isLava(Vec3d p) {
        FluidState f = world.getFluidState(BlockPos.ofFloored(p.x, p.y - 0.05, p.z));
        FluidState g = world.getFluidState(BlockPos.ofFloored(p));
        return f.isIn(FluidTags.LAVA) || g.isIn(FluidTags.LAVA);
    }

    // ---------------------------------------------------------------------
    // Hurting things on the way
    // ---------------------------------------------------------------------

    /** Everything the runner tore through between {@code from} and {@code to} takes the full hit, once. */
    public List<LivingEntity> strike(ServerPlayerEntity caster, Vec3d from, Vec3d to) {
        List<LivingEntity> hit = new ArrayList<>();
        Vec3d a = from.add(0, 0.9, 0), b = to.add(0, 0.9, 0);
        Box swept = new Box(a, b).expand(1.5);
        for (Entity e : world.getOtherEntities(caster, swept, e -> canHurt(e, caster) && !struck.contains(e.getUuid()))) {
            Box box = e.getBoundingBox().expand(0.8);
            if (!box.contains(a) && box.raycast(a, b).isEmpty()) continue;
            LivingEntity victim = (LivingEntity) e;
            struck.add(victim.getUuid());
            hit.add(victim);
            victim.timeUntilRegen = 0;
            victim.damage(world.getDamageSources().indirectMagic(caster, caster), HIT_DAMAGE);
            // Flung aside, off the path
            Vec3d dir = b.subtract(a);
            Vec3d side = new Vec3d(-dir.z, 0, dir.x);
            Vec3d off = victim.getPos().subtract(a).multiply(1, 0, 1);
            if (side.lengthSquared() > 1e-6) {
                side = side.normalize();
                if (side.dotProduct(off) < 0) side = side.multiply(-1);
            }
            victim.setVelocity(victim.getVelocity().add(side.multiply(1.1)).add(dir.lengthSquared() > 1e-6 ? dir.normalize().multiply(0.6) : Vec3d.ZERO).add(0, 0.55, 0));
            victim.velocityModified = true;
            charge(victim);
            Vec3d c = victim.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 30, victim.getWidth() * 0.4, victim.getHeight() * 0.4, victim.getWidth() * 0.4, 0.6);
            world.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 1.8f, 1.3f);
            if (victim instanceof ServerPlayerEntity sp) shake(sp, 0.7f, 10);
        }
        if (!hit.isEmpty()) shake(caster, 0.25f, 5);
        return hit;
    }

    /** Lightning charges creepers, like the real thing. */
    private void charge(LivingEntity e) {
        if (e instanceof CreeperEntity creeper && !creeper.shouldRenderOverlay() && creeper.isAlive()) {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
            if (bolt != null) {
                bolt.setPosition(creeper.getPos());
                creeper.onStruckByLightning(world, bolt);
            }
        }
    }

    private boolean canHurt(Entity e, ServerPlayerEntity caster) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof PlayerEntity p && p.isCreative()) return false;
        if (e instanceof IllusionEntity) return false;
        if (e instanceof TameableEntity pet && casterId.equals(pet.getOwnerUuid())) return false;
        return !e.isTeammate(caster);
    }

    // ---------------------------------------------------------------------
    // Arriving
    // ---------------------------------------------------------------------

    /** The end of the run: a crack of thunder, a jolt to whatever's close, and a bolt striking where they stop. */
    public void arrive(ServerPlayerEntity caster) {
        if (stage != Stage.RUN) return;
        Vec3d at = caster.getPos();
        if (lastPos != null) strike(caster, lastPos, at);
        stage = Stage.AFTER;
        age = 0;
        caster.fallDistance = 0;

        Vec3d mid = at.add(0, 1, 0);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(mid, mid).expand(ARRIVE_RADIUS + 1), e -> canHurt(e, caster))) {
            Vec3d c = e.getBoundingBox().getCenter();
            double d = c.distanceTo(mid);
            if (d > ARRIVE_RADIUS) continue;
            if (!struck.contains(e.getUuid())) {
                e.timeUntilRegen = 0;
                e.damage(world.getDamageSources().indirectMagic(caster, caster), ARRIVE_DAMAGE);
            }
            Vec3d out = c.subtract(mid).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            e.setVelocity(e.getVelocity().add(out.multiply(0.9 * (1 - d / ARRIVE_RADIUS) + 0.3)).add(0, 0.35, 0));
            e.velocityModified = true;
        }
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, mid.x, mid.y, mid.z, 90, 0.4, 0.6, 0.4, 0.9);
        world.spawnParticles(ParticleTypes.FLASH, mid.x, mid.y, mid.z, 2, 0.2, 0.2, 0.2, 0);
        world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.1, at.z, 20, 0.8, 0.05, 0.8, 0.12);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST_FAR, SoundCategory.PLAYERS, 4f, 0.6f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 2.5f, 0.5f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ENDER_EYE_DEATH, SoundCategory.PLAYERS, 1.5f, 1.4f);
        ShakePayload.around(world, at, 10, 0.5f, 10);
        LightningPath.sendNear(world, at, caster, new LightningPath.ArrivePayload(caster.getId(), at.toVector3f()));
    }

    // ---------------------------------------------------------------------

    static void forget(ServerPlayerEntity player) {
        ACTIVE.removeIf(r -> r.casterId.equals(player.getUuid()));
    }

    static void forgetAll() {
        ACTIVE.clear();
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (ServerPlayNetworking.canSend(player, ShakePayload.ID)) ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
    }

    /** For tests: how far through painting it is. */
    public int age() {
        return age;
    }
}
