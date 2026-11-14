package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * A rift into the void torn open in the ground (the Tome of the Void).
 * <ol>
 *   <li><b>Rupture</b> ({@link #RUPTURE_TICKS}): a crack-front of light races outward and the ground falls into the
 *       void behind it.</li>
 *   <li><b>Active</b> ({@link #ACTIVE_TICKS}): the ground inside is the void. Everything in it is dragged toward the
 *       centre, slowed and drained every {@link #PULSE_EVERY} ticks, feeding the caster.</li>
 *   <li><b>Collapse</b>: the rift snaps shut and throws everything out in a shockwave.</li>
 * </ol>
 * All of it is drawn by each client (VoidClient) from one {@link Opened} message sent when it's cast: the server
 * only times it, does the damage and plays the sounds. Nothing in the world is changed.
 */
public final class VoidZone {
    private VoidZone() {}

    // --- Shape & timing (the client draws to the same numbers) ---
    public static final int RADIUS = 6;
    public static final int RUPTURE_TICKS = 20;
    public static final int ACTIVE_TICKS = 100;           // 5s of void
    public static final int PULSE_EVERY = 10;

    // --- Effects on creatures ---
    private static final float PULSE_DAMAGE = 7.0f;         // magic, ignores armor
    private static final float COLLAPSE_DAMAGE = 12.0f;
    private static final double PULL_STRENGTH = 0.09;       // per tick, toward the centre
    private static final double COLLAPSE_KNOCKBACK = 1.6;
    private static final float DRAIN_HEAL = 1.0f;           // caster heals per victim per pulse
    private static final double VIEW_RANGE = 128.0;

    /**
     * Server -> clients: a rift has been torn open. {@code origin} is the block its ground was searched around,
     * {@code center} the middle of its floor, {@code from} the staff the thread of void came from, {@code caster}
     * whose it is (so their own soul isn't drawn out), {@code seed} for its debris.
     */
    public record Opened(BlockPos origin, Vec3d center, Vec3d from, int caster, long seed) implements CustomPayload {
        public static final Id<Opened> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "void_opened"));
        public static final PacketCodec<RegistryByteBuf, Opened> CODEC = PacketCodec.of(
                (p, buf) -> {
                    buf.writeBlockPos(p.origin);
                    vec(buf, p.center);
                    vec(buf, p.from);
                    buf.writeVarInt(p.caster);
                    buf.writeLong(p.seed);
                },
                buf -> new Opened(buf.readBlockPos(), vec(buf), vec(buf), buf.readVarInt(), buf.readLong()));

        private static void vec(RegistryByteBuf buf, Vec3d v) {
            buf.writeDouble(v.x);
            buf.writeDouble(v.y);
            buf.writeDouble(v.z);
        }

        private static Vec3d vec(RegistryByteBuf buf) {
            return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static final List<Zone> ZONES = new ArrayList<>();
    private static boolean registered = false;

    private static final class Zone {
        final ServerWorld world;
        final Vec3d center;              // on the floor, at the centre of the rift
        final UUID casterId;
        int age = 0;

        Zone(ServerWorld world, Vec3d center, UUID casterId) {
            this.world = world;
            this.center = center;
            this.casterId = casterId;
        }
    }

    /** Registers the message clients draw from. Call once at startup. */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(Opened.ID, Opened.CODEC);
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Zone> it = ZONES.iterator();
            while (it.hasNext()) {
                Zone zone = it.next();
                if (zone.world == world && !tick(zone)) it.remove();
            }
        });
    }

    public static void create(ServerWorld world, Vec3d centerPos, Vec3d from, PlayerEntity caster) {
        ensureRegistered();
        BlockPos center = BlockPos.ofFloored(centerPos);
        if (findSurface(world, center) == null && !anyGround(world, center)) return;

        BlockPos centerSurface = findSurface(world, center);
        double floorY = (centerSurface != null ? centerSurface.getY() : center.getY()) + 1.0;
        Vec3d c = new Vec3d(center.getX() + 0.5, floorY, center.getZ() + 0.5);
        ZONES.add(new Zone(world, c, caster.getUuid()));

        Opened msg = new Opened(center, c, from, caster.getId(), world.getRandom().nextLong());
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(c) <= VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(viewer, Opened.ID)) {
                ServerPlayNetworking.send(viewer, msg);
            }
        }
        world.playSound(null, center, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        world.playSound(null, center, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, center, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
    }

    private static boolean anyGround(ServerWorld world, BlockPos center) {
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                if (x * x + z * z <= RADIUS * RADIUS && findSurface(world, center.add(x, 0, z)) != null) return true;
            }
        }
        return false;
    }

    /** Advances one zone; returns false once it has collapsed. */
    private static boolean tick(Zone zone) {
        zone.age++;
        Vec3d c = zone.center;
        if (zone.age <= RUPTURE_TICKS) {
            if (zone.age % 5 == 0) zone.world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f + zone.age / 40f);
            if (zone.age == RUPTURE_TICKS) open(zone);
            return true;
        }
        if (zone.age <= RUPTURE_TICKS + ACTIVE_TICKS) {
            int activeAge = zone.age - RUPTURE_TICKS;
            pull(zone);
            if (activeAge % PULSE_EVERY == 0) pulse(zone);
            if (activeAge % 20 == 0) zone.world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.6f);
            if (activeAge % 40 == 1) zone.world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.PLAYERS, 1.2f, 0.5f);
            return true;
        }
        collapse(zone);
        return false;
    }

    private static void open(Zone zone) {
        Vec3d c = zone.center;
        zone.world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 2.0f, 0.5f);
        zone.world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 1.0f, 0.5f);
        zone.world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.8f, 0.7f);
        ShakePayload.around(zone.world, c, 20, 0.5f, 12);
    }

    private static void collapse(Zone zone) {
        ServerWorld world = zone.world;
        Vec3d c = zone.center;
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 2.5f, 0.4f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);
        ShakePayload.around(world, c, 24, 0.9f, 14);
        for (LivingEntity e : victims(zone, 1.5)) {
            e.damage(world.getDamageSources().magic(), COLLAPSE_DAMAGE);
            Vec3d out = e.getPos().subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() > 1.0e-4 ? out.normalize() : new Vec3d(1, 0, 0);
            e.addVelocity(out.x * COLLAPSE_KNOCKBACK, 0.7, out.z * COLLAPSE_KNOCKBACK);
            e.velocityModified = true;
        }
    }

    // ---------------------------------------------------------------------
    // Effects on entities
    // ---------------------------------------------------------------------

    /** Living things inside the rift, excluding the caster. {@code grow} widens the circle. */
    private static List<LivingEntity> victims(Zone zone, double grow) {
        double r = RADIUS + grow;
        Box box = new Box(zone.center.x - r, zone.center.y - 2.0, zone.center.z - r,
                zone.center.x + r, zone.center.y + 3.5, zone.center.z + r);
        return zone.world.getEntitiesByClass(LivingEntity.class, box, e -> {
            if (!e.isAlive() || e.isSpectator()) return false;
            if (zone.casterId != null && e.getUuid().equals(zone.casterId)) return false;
            if (e instanceof PlayerEntity p && p.isCreative()) return false;
            double dx = e.getX() - zone.center.x, dz = e.getZ() - zone.center.z;
            return dx * dx + dz * dz <= r * r;
        });
    }

    /** Drags everything toward the centre, harder the further out it is. */
    private static void pull(Zone zone) {
        for (LivingEntity e : victims(zone, 0.5)) {
            Vec3d in = zone.center.subtract(e.getPos()).multiply(1, 0, 1);
            double dist = in.length();
            if (dist < 0.6) continue;
            Vec3d v = in.multiply(PULL_STRENGTH * Math.min(1.0, dist / RADIUS + 0.35) / dist);
            e.addVelocity(v.x, -0.02, v.z);
            e.velocityModified = true;
        }
    }

    /** Drain pulse: damages and slows victims and feeds the caster. (Their souls being torn out is drawn by clients.) */
    private static void pulse(Zone zone) {
        ServerWorld world = zone.world;
        PlayerEntity caster = zone.casterId != null ? world.getPlayerByUuid(zone.casterId) : null;
        int drained = 0;
        for (LivingEntity e : victims(zone, 0.0)) {
            if (!e.damage(world.getDamageSources().magic(), PULSE_DAMAGE)) continue;
            drained++;
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 25, 1, false, false, true));
        }
        if (drained > 0) {
            world.playSound(null, zone.center.x, zone.center.y, zone.center.z,
                    SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);
            if (caster != null && caster.isAlive()) caster.heal(Math.min(4.0f, drained * DRAIN_HEAL));
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** The top solid block near {@code start} with open space over it (the client finds the floor the same way). */
    public static BlockPos findSurface(BlockView w, BlockPos start) {
        for (int i = 4; i >= -4; i--) {
            BlockPos p = start.up(i);
            BlockState state = w.getBlockState(p);
            BlockState above = w.getBlockState(p.up());
            if (state.isSolidBlock(w, p) && !above.isSolidBlock(w, p.up())) return p;
        }
        return null;
    }
}
