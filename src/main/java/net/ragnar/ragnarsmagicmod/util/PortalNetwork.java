package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.PortalPayloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The portals opened by the Tome of Portals. Each player owns at most one pair: the magenta portal and the
 * dark purple one. Anything that walks, falls or flies into one comes out of the other with its momentum turned to match,
 * even across dimensions. Pairs are saved with the world and stay until their owner closes them.
 *
 * <p>Kept cheap on purpose: a portal only does anything while its own chunk is ticking, it only looks at the few
 * entities right next to it, it never loads chunks, and all the visuals are drawn by the clients.
 */
public final class PortalNetwork {
    private PortalNetwork() {}

    /** Half the portal's size across and along its long axis, in blocks. */
    public static final double HALF_WIDTH = 0.55;
    public static final double HALF_HEIGHT = 1.0;

    /** Magenta for the first portal of a pair, dark purple for the second. */
    public static final int[] COLORS = {0xFF2FD2, 0x6A1FC2};

    private static final double REACH = 32.0;
    private static final int TRAVEL_COOLDOWN = 10;

    /**
     * One portal: a 1x2 oval centred on {@code center}, lying flat against a surface. {@code normal} points out
     * of its face; {@code up} runs along its long axis.
     */
    public record Portal(RegistryKey<World> world, Vec3d center, Direction normal, Direction up) {
        public Vec3d n() { return Vec3d.of(normal.getVector()); }
        public Vec3d u() { return Vec3d.of(up.getVector()); }
        public Vec3d r() { return u().crossProduct(n()); }

        NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("world", world.getValue().toString());
            nbt.putDouble("x", center.x);
            nbt.putDouble("y", center.y);
            nbt.putDouble("z", center.z);
            nbt.putByte("normal", (byte) normal.getId());
            nbt.putByte("up", (byte) up.getId());
            return nbt;
        }

        static Portal fromNbt(NbtCompound nbt) {
            Identifier id = Identifier.tryParse(nbt.getString("world"));
            if (id == null) return null;
            return new Portal(RegistryKey.of(RegistryKeys.WORLD, id),
                    new Vec3d(nbt.getDouble("x"), nbt.getDouble("y"), nbt.getDouble("z")),
                    Direction.byId(nbt.getByte("normal")), Direction.byId(nbt.getByte("up")));
        }
    }

    /** A player's portals. The second one is null until it has been opened. */
    public static final class Pair {
        Portal first;
        Portal second;

        Pair(Portal first) { this.first = first; }

        public boolean linked() { return second != null; }
    }

    // ---------------------------------------------------------------------
    // Saved data
    // ---------------------------------------------------------------------

    static final class State extends PersistentState {
        final Map<UUID, Pair> pairs = new LinkedHashMap<>();

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            NbtList list = new NbtList();
            pairs.forEach((owner, pair) -> {
                NbtCompound entry = new NbtCompound();
                entry.putUuid("owner", owner);
                entry.put("first", pair.first.toNbt());
                if (pair.second != null) entry.put("second", pair.second.toNbt());
                list.add(entry);
            });
            nbt.put("pairs", list);
            return nbt;
        }

        static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State state = new State();
            NbtList list = nbt.getList("pairs", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                if (!entry.containsUuid("owner")) continue;
                Portal first = Portal.fromNbt(entry.getCompound("first"));
                if (first == null) continue;
                Pair pair = new Pair(first);
                if (entry.contains("second")) pair.second = Portal.fromNbt(entry.getCompound("second"));
                state.pairs.put(entry.getUuid("owner"), pair);
            }
            return state;
        }
    }

    private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);
    private static State state;

    private static State state(MinecraftServer server) {
        if (state == null) {
            state = server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_portals");
        }
        return state;
    }

    // ---------------------------------------------------------------------
    // Travel bookkeeping
    // ---------------------------------------------------------------------

    /** Where an entity near a portal was last tick, so a player's speed can be worked out on the server. */
    private record Track(Vec3d pos, Vec3d motion, long tick) {}

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    /**
     * The portal an entity last came out of, and until when it can't go through anything. Out of a floor or
     * ceiling portal it also can't drop straight back into it (or it would bounce between two floor portals
     * forever): it has to get off it first. As soon as it's no longer over the portal it can use it again.
     */
    private record Arrival(Portal exit, long until) {}

    private static final Map<UUID, Arrival> ARRIVED = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PortalNetwork::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(handler.getPlayer(), true));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            state = null;
            TRACKS.clear();
            ARRIVED.clear();
        });
    }

    // ---------------------------------------------------------------------
    // Owning portals
    // ---------------------------------------------------------------------

    /** 0 = no portals, 1 = one waiting for its twin, 2 = a linked pair. */
    public static int stage(MinecraftServer server, UUID owner) {
        Pair pair = state(server).pairs.get(owner);
        if (pair == null) return 0;
        return pair.linked() ? 2 : 1;
    }

    /** Opens {@code portal} as the owner's first portal, or as the twin of the one already open. */
    public static void open(MinecraftServer server, UUID owner, Portal portal) {
        State s = state(server);
        Pair pair = s.pairs.get(owner);
        if (pair == null) s.pairs.put(owner, new Pair(portal));
        else pair.second = portal;
        s.markDirty();
        syncAll(server);
    }

    /** Closes the owner's portals. Returns the ones that were open. */
    public static List<Portal> close(MinecraftServer server, UUID owner) {
        State s = state(server);
        Pair pair = s.pairs.remove(owner);
        List<Portal> closed = new ArrayList<>();
        if (pair == null) return closed;
        s.markDirty();
        closed.add(pair.first);
        if (pair.second != null) closed.add(pair.second);
        syncAll(server);
        return closed;
    }

    /** The owner's first portal, if it is still waiting for a twin. */
    public static Portal waiting(MinecraftServer server, UUID owner) {
        Pair pair = state(server).pairs.get(owner);
        return pair == null || pair.linked() ? null : pair.first;
    }

    // ---------------------------------------------------------------------
    // Placing
    // ---------------------------------------------------------------------

    /**
     * Where a portal would open for the player's aim: flat against the wall, floor or ceiling they're looking at,
     * or standing in mid-air in front of them if they're looking at nothing. Null (and a message) if there's no
     * room for it there.
     */
    public static Portal aim(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(look.multiply(REACH)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Direction facing = player.getHorizontalFacing();

        if (hit.getType() == HitResult.Type.MISS) {
            // Nothing there: hang it in the air a few blocks ahead, facing the caster
            BlockPos at = BlockPos.ofFloored(player.getPos().add(Vec3d.of(facing.getVector()).multiply(3.0)));
            Portal p = new Portal(world.getRegistryKey(), new Vec3d(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5),
                    facing.getOpposite(), Direction.UP);
            return fits(world, p) ? p : noRoom(player);
        }

        Direction side = hit.getSide();
        BlockPos block = hit.getBlockPos();
        Vec3d on = hit.getPos();

        if (side.getAxis().isHorizontal()) {
            // A wall: sit on the face, centred on the block, two blocks tall
            double x = side.getAxis() == Direction.Axis.X ? on.x : block.getX() + 0.5;
            double z = side.getAxis() == Direction.Axis.Z ? on.z : block.getZ() + 0.5;
            // Prefer resting on the ground: start from the block looked at, or the one under it if that's open air
            BlockPos below = block.offset(side).down();
            boolean floorBelow = !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
            int[] bottoms = floorBelow ? new int[]{block.getY(), block.getY() - 1} : new int[]{block.getY() - 1, block.getY()};
            for (int bottom : bottoms) {
                Portal p = new Portal(world.getRegistryKey(), new Vec3d(x, bottom + 1.0, z), side, Direction.UP);
                if (fits(world, p)) return p;
            }
            return noRoom(player);
        }

        // A floor or ceiling: lying flat, its long side running the way the caster faces
        Vec3d mid = new Vec3d(block.getX() + 0.5, on.y, block.getZ() + 0.5);
        Vec3d along = Vec3d.of(facing.getVector()).multiply(0.5);
        for (Vec3d center : new Vec3d[]{mid.add(along), mid.subtract(along)}) {
            Portal p = new Portal(world.getRegistryKey(), center, side, facing);
            if (fits(world, p)) return p;
        }
        return noRoom(player);
    }

    private static Portal noRoom(PlayerEntity player) {
        player.sendMessage(Text.literal("There's no room for a portal there."), true);
        return null;
    }

    /** Both blocks in front of the portal's face must be open. */
    private static boolean fits(ServerWorld world, Portal p) {
        for (double s : new double[]{-0.5, 0.5}) {
            BlockPos pos = BlockPos.ofFloored(p.center().add(p.u().multiply(s)).add(p.n().multiply(0.5)));
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) return false;
        }
        return true;
    }

    /** True if {@code a} would overlap {@code b}. */
    public static boolean tooClose(Portal a, Portal b) {
        return a.world().equals(b.world()) && a.center().squaredDistanceTo(b.center()) < 1.6 * 1.6;
    }

    // ---------------------------------------------------------------------
    // Travelling
    // ---------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        State s = state(server);
        long now = server.getTicks();
        if (now % 20 == 0) TRACKS.values().removeIf(t -> t.tick() < now - 2);
        // Done travelling: forget it, unless it's still over the floor or ceiling portal it came out of
        ARRIVED.entrySet().removeIf(entry -> {
            Arrival a = entry.getValue();
            if (a.until() > now) return false;
            if (!a.exit().normal().getAxis().isVertical()) return true;
            ServerWorld world = server.getWorld(a.exit().world());
            Entity e = world == null ? null : world.getEntity(entry.getKey());
            return e == null || !over(a.exit(), e);
        });
        if (s.pairs.isEmpty()) return;

        // Copy first: a traveller can't change the pairs, but keep the loop safe from anything that does
        for (Pair pair : new ArrayList<>(s.pairs.values())) {
            if (!pair.linked()) continue;
            watch(server, pair.first, pair.second, 1, now);
            watch(server, pair.second, pair.first, 0, now);
        }
    }

    /** Sends anything touching {@code from} out of {@code to}. */
    private static void watch(MinecraftServer server, Portal from, Portal to, int exitSlot, long now) {
        ServerWorld world = server.getWorld(from.world());
        if (world == null || !world.shouldTickEntity(BlockPos.ofFloored(from.center()))) return;

        Box near = new Box(from.center(), from.center()).expand(HALF_HEIGHT + 1.5);
        for (Entity e : world.getOtherEntities((Entity) null, near, PortalNetwork::canTravel)) {
            Vec3d center = e.getBoundingBox().getCenter();
            Vec3d motion = motionOf(e, center, now);
            Arrival arrival = ARRIVED.get(e.getUuid());
            if (arrival != null && (arrival.until() > now || arrival.exit().equals(from) && over(from, e))) continue;

            Vec3d at = entering(from, e, center, motion);
            if (at != null) travel(server, e, at, motion, from, to, exitSlot, now);
        }
    }

    private static boolean canTravel(Entity e) {
        if (!e.isAlive() || e.isSpectator() || e.hasPassengers() || !e.canUsePortals(false)) return false;
        if (e instanceof EnderDragonPart) return false;
        // An arrow already stuck in the wall stays there
        return !(e instanceof PersistentProjectileEntity) || e.getVelocity().lengthSquared() > 0.0025;
    }

    /**
     * How far the entity moves per tick. A player's velocity isn't known on the server, so theirs comes from how
     * far they moved since last tick.
     */
    private static Vec3d motionOf(Entity e, Vec3d center, long now) {
        if (!(e instanceof PlayerEntity)) return e.getVelocity();
        Track last = TRACKS.get(e.getUuid());
        if (last != null && last.tick() == now) return last.motion(); // already worked out at the other portal
        Vec3d motion = last != null && last.tick() == now - 1 ? center.subtract(last.pos()) : Vec3d.ZERO;
        TRACKS.put(e.getUuid(), new Track(center, motion, now));
        return motion;
    }

    /**
     * If the entity is going into the portal this tick, where its centre is as it goes in; otherwise null. It
     * has to be in front of the face, within the oval, and not moving away. Fast things (arrows) are caught by
     * where they'll be next tick, before they hit the wall behind.
     */
    private static Vec3d entering(Portal p, Entity e, Vec3d center, Vec3d motion) {
        Vec3d n = p.n();
        double reach = extent(e, n) + 0.2;
        double towards = -motion.dotProduct(n);
        if (towards < -0.05) return null; // moving away

        double depth = center.subtract(p.center()).dotProduct(n);
        Vec3d at = center;
        if (depth > reach) {
            if (e instanceof PlayerEntity || !(e instanceof ProjectileEntity) || towards <= 0) return null;
            double t = (depth - reach) / towards;
            if (t > 1.0) return null;
            at = center.add(motion.multiply(t));
            depth = reach;
        }
        if (depth < -0.1) return null; // already behind it

        Vec3d d = at.subtract(p.center());
        return Math.abs(d.dotProduct(p.r())) <= HALF_WIDTH + 0.1 && Math.abs(d.dotProduct(p.u())) <= HALF_HEIGHT + 0.2
                ? at : null;
    }

    /**
     * True if any part of the entity is over (or under) the floor or ceiling portal {@code p}, close enough to land
     * back on it - so it hasn't stepped off yet.
     */
    private static boolean over(Portal p, Entity e) {
        Vec3d d = e.getBoundingBox().getCenter().subtract(p.center());
        double depth = d.dotProduct(p.n());
        return depth > -0.1 && depth < 4.0
                && Math.abs(d.dotProduct(p.r())) < HALF_WIDTH + extent(e, p.r())
                && Math.abs(d.dotProduct(p.u())) < HALF_HEIGHT + extent(e, p.u());
    }

    /** Half the entity's size measured along {@code axis}. */
    private static double extent(Entity e, Vec3d axis) {
        return (Math.abs(axis.x) + Math.abs(axis.z)) * e.getWidth() / 2 + Math.abs(axis.y) * e.getHeight() / 2;
    }

    /**
     * Turns a direction going into {@code from} into the same direction coming out of {@code to}: through the
     * face, and turned half around so left stays left.
     */
    private static Vec3d carry(Vec3d v, Portal from, Portal to) {
        double across = v.dotProduct(from.r()), along = v.dotProduct(from.u()), into = v.dotProduct(from.n());
        return to.r().multiply(-across).add(to.u().multiply(along)).add(to.n().multiply(-into));
    }

    private static void travel(MinecraftServer server, Entity e, Vec3d at, Vec3d motion, Portal from, Portal to,
                               int exitSlot, long now) {
        ServerWorld dest = server.getWorld(to.world());
        if (dest == null) return;
        boolean isPlayer = e instanceof ServerPlayerEntity;
        Vec3d n = to.n();

        // Where it comes out: just off the face, keeping its offset across the portal but kept inside the oval
        Vec3d d = at.subtract(from.center());
        double across = MathHelper.clamp(-d.dotProduct(from.r()), -Math.max(0, HALF_WIDTH - extent(e, to.r())),
                Math.max(0, HALF_WIDTH - extent(e, to.r())));
        double along = MathHelper.clamp(d.dotProduct(from.u()), -Math.max(0, HALF_HEIGHT - extent(e, to.u())),
                Math.max(0, HALF_HEIGHT - extent(e, to.u())));
        Vec3d center = to.center().add(to.r().multiply(across)).add(to.u().multiply(along))
                .add(n.multiply(extent(e, n) + 0.3));
        Vec3d feet = center.subtract(0, e.getHeight() / 2.0, 0);

        // Non-players only go somewhere that's already loaded: portals never load chunks
        if (!isPlayer && !dest.shouldTickEntity(BlockPos.ofFloored(feet))) return;

        // Out of a low ceiling or a tight spot: shuffle up or down to somewhere it won't suffocate
        for (double shift : new double[]{0, 0.5, -0.5, 1.0, -1.0}) {
            Vec3d tryFeet = feet.add(0, shift, 0);
            if (dest.isSpaceEmpty(e, e.getBoundingBox().offset(tryFeet.subtract(e.getPos())))) {
                center = center.add(0, shift, 0);
                feet = tryFeet;
                break;
            }
        }

        // Momentum carries through, and always out of the far side
        Vec3d velocity = carry(motion, from, to);
        double minOut = to.normal() == Direction.UP ? 0.42 : to.normal() == Direction.DOWN ? 0.05 : 0.25;
        double out = velocity.dotProduct(n);
        if (out < minOut) velocity = velocity.add(n.multiply(minOut - out));
        if (velocity.length() > 3.0) velocity = velocity.normalize().multiply(3.0);

        // Turn the view the same way
        Vec3d look = carry(e.getRotationVector(), from, to);
        float yaw = e.getYaw();
        if (look.horizontalLengthSquared() > 0.0025) {
            yaw = (float) (MathHelper.atan2(-look.x, look.z) * MathHelper.DEGREES_PER_RADIAN);
        }
        float pitch = (float) (-Math.asin(MathHelper.clamp(look.y, -1.0, 1.0)) * MathHelper.DEGREES_PER_RADIAN);

        ServerWorld origin = (ServerWorld) e.getWorld();
        origin.playSound(null, from.center().x, from.center().y, from.center().z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.PLAYERS, 0.7f, 1.4f + origin.random.nextFloat() * 0.2f);

        e.fallDistance = 0f;
        Entity arrived = e.teleportTo(new TeleportTarget(dest, feet, velocity, yaw, pitch, TeleportTarget.NO_OP));
        if (arrived == null) return;
        arrived.setVelocity(velocity);
        arrived.velocityModified = true;
        arrived.fallDistance = 0f;
        arrived.setHeadYaw(yaw);
        arrived.setBodyYaw(yaw);
        ARRIVED.put(arrived.getUuid(), new Arrival(to, now + TRAVEL_COOLDOWN));
        TRACKS.remove(arrived.getUuid());

        dest.playSound(null, center.x, center.y, center.z, SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT,
                SoundCategory.PLAYERS, 0.6f, 1.5f + dest.random.nextFloat() * 0.2f);
        dest.spawnParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z, 12,
                0.25, 0.4, 0.25, 0.05);
        if (arrived instanceof ServerPlayerEntity player) PortalPayloads.sendTraveled(player, COLORS[exitSlot]);
    }

    // ---------------------------------------------------------------------
    // Telling the clients
    // ---------------------------------------------------------------------

    private static List<PortalPayloads.View> views(MinecraftServer server) {
        List<PortalPayloads.View> views = new ArrayList<>();
        state(server).pairs.forEach((owner, pair) -> {
            views.add(PortalPayloads.View.of(owner, 0, pair.first, pair.linked()));
            if (pair.second != null) views.add(PortalPayloads.View.of(owner, 1, pair.second, true));
        });
        return views;
    }

    private static void syncAll(MinecraftServer server) {
        List<PortalPayloads.View> views = views(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PortalPayloads.sendSync(player, false, views);
        }
    }

    private static void syncTo(ServerPlayerEntity player, boolean snapshot) {
        PortalPayloads.sendSync(player, snapshot, views(player.getServer()));
    }
}
