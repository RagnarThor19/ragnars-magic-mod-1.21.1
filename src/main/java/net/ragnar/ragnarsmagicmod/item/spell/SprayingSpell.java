package net.ragnar.ragnarsmagicmod.item.spell;

import net.ragnar.ragnarsmagicmod.util.DeflectableShots;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.network.SprayPayload;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Spraying. A halo of golden arrows forms around the caster's view, turning to face wherever they look, then
 * fires on its own like a machine gun: an arrow every {@link #FIRE_INTERVAL} ticks for {@link #FIRE_TICKS}, each
 * from a different place around the ring. The arrows fly very fast and almost dead straight, and every one of them
 * lands its {@link #DAMAGE} (they don't bounce off the target's hurt cooldown). While it fires the caster is a bit
 * slower and feels a slight recoil. The cooldown starts once the barrage is over.
 *
 * The halo is drawn by SprayingClient; the server works out the same slot positions to launch the arrows from.
 */
public class SprayingSpell implements Spell {
    public static final int SLOTS = 12;
    public static final int SLOT_STEP = 5;          // firing order around the ring: slot = (shot * 5) % 12
    public static final int SUMMON_TICKS = 10;
    public static final int FIRE_TICKS = 50;        // 2.5s
    public static final int FIRE_INTERVAL = 1;      // 20 arrows a second
    public static final double HALO_AHEAD = 1.0;
    public static final double HALO_RADIUS = 0.75;
    public static final double HALO_SPIN = 0.06;    // radians per tick

    private static final double AIM_RANGE = 90.0;
    private static final double SPEED = 4.5;
    private static final double GRAVITY = 0.004;
    private static final double SPREAD = 0.012;
    private static final float DAMAGE = 10.0f;
    private static final int FLIGHT_TICKS = 22;
    private static final int STUCK_TICKS = 20;
    private static final float SCALE = 0.7f;
    private static final double RECOIL = 0.0075;
    private static final Identifier SLOW_ID = Identifier.of(RagnarsMagicMod.MOD_ID, "spraying_slow");
    // In ModelTransformationMode.NONE the arrow sprite's tip points up-right in item space...
    private static final Vector3f TIP_LOCAL = new Vector3f(1, 1, 0).normalize();
    // ...but item display entities are drawn turned half a turn around the vertical, which puts it up-left
    private static final Vector3f TIP_DISPLAY = new Vector3f(-1, 1, 0).normalize();

    private static final class Barrage {
        final ServerWorld world;
        final UUID owner;
        int age;
        int shots;
        long lastDing;

        Barrage(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
        }
    }

    private static final class Flying implements DeflectableShots.Shot {
        final ServerWorld world;
        final DisplayEntity.ItemDisplayEntity display;
        UUID owner;
        Vec3d pos, vel;
        int age;
        int stuck = -1;

        Flying(ServerWorld world, DisplayEntity.ItemDisplayEntity display, UUID owner, Vec3d pos, Vec3d vel) {
            this.world = world;
            this.display = display;
            this.owner = owner;
            this.pos = pos;
            this.vel = vel;
        }

        // A Tome of Deflection pane sends it back while it's flying, as the deflector's
        @Override public ServerWorld world() { return world; }
        @Override public Vec3d pos() { return pos; }
        @Override public Vec3d deflectVelocity() { return stuck >= 0 ? Vec3d.ZERO : vel; }
        @Override public UUID deflectOwner() { return owner; }
        @Override public double deflectGravity() { return GRAVITY; }

        @Override
        public void deflect(PlayerEntity deflector, Vec3d at, Vec3d dir, double speed, LivingEntity aimedAt) {
            owner = deflector.getUuid();
            vel = dir.multiply(SPEED);
            pos = at;
            age = 0;
            display.setTransformation(transformFor(vel, SCALE, true));
            move(this);
        }
    }

    private static final Map<UUID, Barrage> BARRAGES = new HashMap<>();
    private static final List<Flying> FLYING = new ArrayList<>();

    static {
        DeflectableShots.register(() -> FLYING);
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(SprayingSpell::tick);
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts once the barrage is over (see finish)
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (BARRAGES.containsKey(player.getUuid())) return false;

        BARRAGES.put(player.getUuid(), new Barrage(sw, player.getUuid()));
        SprayPayload.broadcast(sp, SUMMON_TICKS, FIRE_TICKS);
        EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null && !speed.hasModifier(SLOW_ID)) {
            speed.addTemporaryModifier(new EntityAttributeModifier(SLOW_ID, -0.25, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }

        Vec3d c = player.getEyePos();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_CROSSBOW_LOADING_END.value(), SoundCategory.PLAYERS, 1.2f, 0.8f);
        return true;
    }

    // ---------------------------------------------------------------------
    // The halo
    // ---------------------------------------------------------------------

    /** Where a slot of the halo sits: on a ring in front of the eyes, square to the view, slowly turning. */
    public static Vec3d slotPosition(Vec3d eye, Vec3d look, int slot, float age) {
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        double a = age * HALO_SPIN + slot * (Math.PI * 2 / SLOTS);
        return eye.add(look.multiply(HALO_AHEAD)).add(right.multiply(Math.cos(a) * HALO_RADIUS)).add(up.multiply(Math.sin(a) * HALO_RADIUS));
    }

    /** Exactly what the crosshair is on: the first creature or block along the look ray, or far away. */
    public static Vec3d aimPoint(World world, LivingEntity caster, Vec3d eye, Vec3d look) {
        Vec3d end = eye.add(look.multiply(AIM_RANGE));
        BlockHitResult block = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, caster));
        if (block.getType() != HitResult.Type.MISS) end = block.getPos();
        EntityHitResult entity = ProjectileUtil.raycast(caster, eye, end, new Box(eye, end).expand(1.0),
                e -> e.isAlive() && !e.isSpectator() && e.canHit(), eye.squaredDistanceTo(end));
        return entity != null ? entity.getPos().add(0, entity.getEntity().getHeight() * 0.5, 0) : end;
    }

    /** Points the arrow along {@code direction}: for an item display entity, or (with {@code display} false) for drawing the item directly. */
    public static AffineTransformation transformFor(Vec3d direction, float scale, boolean display) {
        Vector3f dir = direction.lengthSquared() > 1.0e-6 ? direction.normalize().toVector3f() : new Vector3f(0, 0, 1);
        Quaternionf rotation = new Quaternionf().rotationTo(display ? TIP_DISPLAY : TIP_LOCAL, dir);
        return new AffineTransformation(new Vector3f(), rotation, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    public static ItemStack arrowStack() {
        ItemStack arrow = new ItemStack(Items.SPECTRAL_ARROW);
        arrow.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return arrow;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(ServerWorld world) {
        if (!BARRAGES.isEmpty()) {
            Iterator<Barrage> it = BARRAGES.values().iterator();
            while (it.hasNext()) {
                Barrage b = it.next();
                if (b.world != world) continue;
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(b.owner);
                boolean gone = player == null || !player.isAlive() || player.getWorld() != world;
                if (gone || b.age >= SUMMON_TICKS + FIRE_TICKS) {
                    it.remove();
                    finish(player, gone);
                    continue;
                }
                int fireAge = b.age - SUMMON_TICKS;
                if (fireAge >= 0 && fireAge % FIRE_INTERVAL == 0) fire(b, player);
                b.age++;
            }
        }
        if (!FLYING.isEmpty()) {
            Iterator<Flying> it = FLYING.iterator();
            while (it.hasNext()) {
                Flying f = it.next();
                if (f.world == world && !tickFlying(f)) {
                    TempEntities.discard(f.display);
                    it.remove();
                }
            }
        }
    }

    private static void fire(Barrage b, ServerPlayerEntity player) {
        ServerWorld world = b.world;
        int slot = (b.shots * SLOT_STEP) % SLOTS;
        b.shots++;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        Vec3d from = slotPosition(eye, look, slot, b.age);
        Vec3d aim = aimPoint(world, player, eye, look);
        Vec3d dir = aim.subtract(from);
        if (dir.lengthSquared() < 1.0e-4) dir = look;
        dir = dir.normalize().add(world.random.nextGaussian() * SPREAD, world.random.nextGaussian() * SPREAD, world.random.nextGaussian() * SPREAD).normalize();
        Vec3d vel = dir.multiply(SPEED);

        DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(world);
        if (d != null) {
            d.setItemStack(arrowStack());
            d.setTransformationMode(ModelTransformationMode.NONE);
            d.setTeleportDuration(1);
            d.setViewRange(3.0f);
            d.refreshPositionAndAngles(from.x, from.y, from.z, 0f, 0f);
            d.setTransformation(transformFor(vel, SCALE, true));
            TempEntities.track(d);
            world.spawnEntity(d);
            FLYING.add(new Flying(world, d, player.getUuid(), from, vel));
        }

        // A spark where it leaves the halo
        world.spawnParticles(ParticleTypes.WAX_OFF, from.x, from.y, from.z, 2, 0.05, 0.05, 0.05, 0.02);
        world.spawnParticles(ParticleTypes.CRIT, from.x, from.y, from.z, 3, 0.05, 0.05, 0.05, 0.2);

        float pitch = 1.4f + world.random.nextFloat() * 0.35f;
        world.playSound(null, from.x, from.y, from.z, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 0.55f, pitch);
        if (b.shots % 4 == 0) {
            world.playSound(null, from.x, from.y, from.z, SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 0.45f, 1.7f);
        }
        if (b.shots % 6 == 1) {
            world.playSound(null, from.x, from.y, from.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 0.5f, 1.8f);
        }

        // A slight shove back (the view kick itself is done by the client)
        Vec3d push = new Vec3d(-look.x, 0, -look.z).normalize().multiply(RECOIL);
        player.addVelocity(push.x, 0, push.z);
        player.velocityModified = true;
    }

    /** The barrage is over: the halo dissolves, speed comes back, and the cooldown starts. */
    private static void finish(ServerPlayerEntity player, boolean cutShort) {
        if (player == null) return;
        EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SLOW_ID);
        if (cutShort) SprayPayload.broadcast(player, 0, 0);

        ServerWorld world = player.getServerWorld();
        Vec3d c = player.getEyePos();
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.8f);
        Vec3d look = player.getRotationVector();
        for (int i = 0; i < SLOTS; i++) {
            Vec3d p = slotPosition(c, look, i, SUMMON_TICKS + FIRE_TICKS);
            world.spawnParticles(ParticleTypes.WAX_OFF, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.02);
        }

        TomeItem tome = ModItems.TOME_OF_SPRAYING;
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(world, player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    /** Returns false once the arrow is finished. */
    private static boolean tickFlying(Flying f) {
        ServerWorld world = f.world;
        f.age++;
        if (f.stuck >= 0) {
            return ++f.stuck < STUCK_TICKS;
        }
        if (f.age > FLIGHT_TICKS) return false;

        Vec3d from = f.pos;
        Vec3d to = from.add(f.vel);
        f.vel = f.vel.add(0, -GRAVITY, 0);

        // First creature along the path takes the arrow
        Box sweep = new Box(from, to).expand(0.5);
        LivingEntity hitEntity = null;
        double hitDist = Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, sweep, e -> e.isAlive() && !e.getUuid().equals(f.owner) && !e.isSpectator())) {
            var clip = e.getBoundingBox().expand(0.15).raycast(from, to);
            if (clip.isEmpty()) continue;
            double d = from.squaredDistanceTo(clip.get());
            if (d < hitDist) {
                hitDist = d;
                hitEntity = e;
            }
        }
        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
        boolean wallFirst = wall.getType() == HitResult.Type.BLOCK && (hitEntity == null || from.squaredDistanceTo(wall.getPos()) < hitDist);

        if (wallFirst) {
            f.pos = wall.getPos().subtract(f.vel.normalize().multiply(0.3));
            move(f);
            f.stuck = 0;
            world.playSound(null, f.pos.x, f.pos.y, f.pos.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.6f, 1.3f + world.random.nextFloat() * 0.3f);
            world.spawnParticles(ParticleTypes.CRIT, f.pos.x, f.pos.y, f.pos.z, 4, 0.1, 0.1, 0.1, 0.15);
            return true;
        }
        if (hitEntity != null) {
            strike(world, f, hitEntity);
            return false;
        }

        f.pos = to;
        move(f);
        f.display.setTransformation(transformFor(f.vel, SCALE, true));
        Vec3d mid = from.lerp(to, 0.5);
        world.spawnParticles(ParticleTypes.CRIT, mid.x, mid.y, mid.z, 1, 0.02, 0.02, 0.02, 0.0);
        if (f.age % 2 == 0) world.spawnParticles(ParticleTypes.WAX_OFF, from.x, from.y, from.z, 1, 0.02, 0.02, 0.02, 0.0);
        return true;
    }

    private static void move(Flying f) {
        f.display.setPosition(f.pos.x, f.pos.y, f.pos.z);
        f.display.setStartInterpolation(0);
        f.display.setInterpolationDuration(1);
    }

    private static void strike(ServerWorld world, Flying f, LivingEntity target) {
        PlayerEntity owner = world.getPlayerByUuid(f.owner);
        // A stream of arrows would otherwise bounce off the target's hurt cooldown after each hit
        target.timeUntilRegen = 0;
        target.damage(world.getDamageSources().mobProjectile(f.display, owner), DAMAGE);
        Vec3d push = f.vel.normalize().multiply(0.08);
        target.addVelocity(push.x, 0.02, push.z);
        target.velocityModified = true;

        Vec3d c = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 8, 0.2, 0.3, 0.2, 0.3);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, c.x, c.y, c.z, 5, 0.2, 0.3, 0.2, 0.3);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.8f, 1.1f);

        // The arrow "ding" for the caster, not on every single hit or it'd be a drone
        Barrage b = BARRAGES.get(f.owner);
        if (owner != null && (b == null || world.getTime() - b.lastDing >= 3)) {
            if (b != null) b.lastDing = world.getTime();
            world.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.45f, 1.2f);
        }
    }
}
