package net.ragnar.ragnarsmagicmod.crows;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Crows: pick a spot up to {@link #RANGE} blocks away and travel there as a murder of crows. For two seconds
 * crows gather out of the sky and spiral in around you, tighter and faster, until you're lost inside a churning
 * column of black wings - then they burst apart and you're gone. Over where you were looking a second flock is already
 * circling down; it closes into a column, and when it scatters you're standing there. About five seconds in all, and
 * you can't move until you've arrived. While you're among the crows nothing can hurt you.
 * <p>
 * The server keeps the timeline (where you are, when you're hidden, the sounds); the crows, feathers and ink are drawn
 * on each client (CrowsClient) from a single start packet, so they cost the server nothing. Everything for it lives in
 * this package, plus the mixins in {@code mixin/crows}.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code crows} and {@code crows/client}) and {@code mixin/crows} (and their lines in
 *       ragnarsmagicmod.mixins.json)</li>
 *   <li>delete {@code Crows.register()} in RagnarsMagicMod and {@code CrowsClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code CROWS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_crows.json} and the {@code crows} line in en_us.json</li>
 * </ol>
 */
public final class Crows {
    private Crows() {}

    public static final double RANGE = 30;
    public static final int XP_COST = 8;
    public static final int COOLDOWN = 20 * 12;

    // The timeline, in ticks from the cast
    /** The charge-up: crows gather and circle in. */
    public static final int CHARGE_TICKS = 40;
    /** The column has closed: you're gone from sight. */
    public static final int VANISH = 52;
    /** The first flock bursts apart and you're moved to the far side. */
    public static final int LEAVE = 58;
    /** The second column is at its thickest: you're there again. */
    public static final int APPEAR = 80;
    /** The second flock scatters. */
    public static final int SCATTER = 82;
    public static final int DURATION = 100;

    /** How far away people still see and hear it. */
    static final double VIEW_RANGE = 96;
    /** How far below the spot you picked the crows will still set you down on the ground. */
    private static final double MAX_DROP = 24;

    public static final TomeItem TOME_OF_CROWS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_crows"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.CROWS, XP_COST
            ).setCooldown(COOLDOWN)
    );

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    /** Server -> players near either end: {@code casterId} is travelling by crow from {@code from} to {@code to} (feet). */
    public record StartPayload(int casterId, Vector3f from, Vector3f to, long seed) implements CustomPayload {
        public static final Id<StartPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "crows_start"));
        public static final PacketCodec<RegistryByteBuf, StartPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, StartPayload::casterId,
                PacketCodecs.VECTOR3F, StartPayload::from,
                PacketCodecs.VECTOR3F, StartPayload::to,
                PacketCodecs.VAR_LONG, StartPayload::seed,
                StartPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> the caster: you've just been moved (so the camera can fly over with the crows). */
    public record MovedPayload() implements CustomPayload {
        public static final Id<MovedPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "crows_moved"));
        public static final PacketCodec<RegistryByteBuf, MovedPayload> CODEC = PacketCodec.unit(new MovedPayload());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players near either end: {@code casterId}'s journey was cut short (they're visible again). */
    public record CancelPayload(int casterId) implements CustomPayload {
        public static final Id<CancelPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "crows_cancel"));
        public static final PacketCodec<RegistryByteBuf, CancelPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, CancelPayload::casterId,
                CancelPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    /** One journey. Public so tests can follow it. */
    public static final class Journey {
        public final ServerWorld world;
        public final Vec3d from, to;
        final int casterId;
        public int age;

        Journey(ServerWorld world, Vec3d from, Vec3d to, int casterId) {
            this.world = world;
            this.from = from;
            this.to = to;
            this.casterId = casterId;
        }

        public boolean hidden() {
            return age >= VANISH && age < APPEAR;
        }
    }

    private static final Map<UUID, Journey> JOURNEYS = new HashMap<>();
    /** Who is hidden among the crows right now: server side by UUID, client side by entity id (kept by CrowsClient). */
    private static final Set<UUID> SERVER = new HashSet<>();
    public static final Set<Integer> CLIENT = new HashSet<>();

    /** True while {@code entity} is hidden among the crows. */
    public static boolean isHidden(Entity entity) {
        if (!(entity instanceof PlayerEntity)) return false;
        return entity.getWorld().isClient ? CLIENT.contains(entity.getId()) : SERVER.contains(entity.getUuid());
    }

    /** The journey {@code player} is on, if any. */
    @Nullable
    public static Journey of(PlayerEntity player) {
        return JOURNEYS.get(player.getUuid());
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.CROWS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_CROWS);
        Spells.register(SpellId.CROWS, new CrowsSpell());
        // Last of the advanced tomes (after Sleep Potions), just before the master ones start
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Sleep.TOME_OF_SLEEP_POTIONS, TOME_OF_CROWS));

        PayloadTypeRegistry.playS2C().register(StartPayload.ID, StartPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MovedPayload.ID, MovedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CancelPayload.ID, CancelPayload.CODEC);
        ServerTickEvents.END_WORLD_TICK.register(Crows::tickAll);

        // Among the crows, nothing can touch you (but /kill and the void still work)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !isHidden(entity) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY));

        // Never leave anyone floating, or invisible
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (JOURNEYS.remove(handler.player.getUuid()) != null) restore(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID id : JOURNEYS.keySet()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) restore(p);
            }
            JOURNEYS.clear();
            SERVER.clear();
        });
    }

    // ---------------------------------------------------------------------
    // Casting
    // ---------------------------------------------------------------------

    /** Sets off for where {@code player} is looking. False if they're already on their way or there's nowhere to go. */
    static boolean cast(ServerWorld world, ServerPlayerEntity player) {
        if (JOURNEYS.containsKey(player.getUuid())) return false;
        Vec3d to = destination(world, player);
        if (to == null) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_PARROT_HURT, SoundCategory.PLAYERS, 0.7f, 0.5f);
            return false;
        }
        Vec3d from = player.getPos();
        Journey j = new Journey(world, from, to, player.getId());
        JOURNEYS.put(player.getUuid(), j);
        player.setVelocity(0, Math.min(0, player.getVelocity().y), 0);
        player.velocityModified = true;

        StartPayload payload = new StartPayload(player.getId(), from.toVector3f(), to.toVector3f(), world.random.nextLong());
        for (ServerPlayerEntity p : world.getPlayers()) {
            boolean near = p.getPos().squaredDistanceTo(from) < VIEW_RANGE * VIEW_RANGE || p.getPos().squaredDistanceTo(to) < VIEW_RANGE * VIEW_RANGE;
            if ((near || p == player) && ServerPlayNetworking.canSend(p, StartPayload.ID)) ServerPlayNetworking.send(p, payload);
        }

        // The call goes out: a rustle of wings and something answering from far off
        Vec3d c = from.add(0, 1, 0);
        sound(world, c, SoundEvents.ITEM_ARMOR_EQUIP_ELYTRA.value(), 1.0f, 0.6f);
        sound(world, c, SoundEvents.ENTITY_PARROT_IMITATE_ILLUSIONER, 0.9f, 0.55f);
        sound(world, to.add(0, 1, 0), SoundEvents.ENTITY_PARROT_IMITATE_ILLUSIONER, 0.6f, 0.5f);
        return true;
    }

    /**
     * Where your feet go: the spot you're looking at up to {@link #RANGE} blocks away (or that far out into the air),
     * stepped back until you fit and then set down on the ground below if there's any near. Null if nowhere fits.
     */
    @Nullable
    public static Vec3d destination(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d dir = player.getRotationVector();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(dir.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d spot = hit.getType() == HitResult.Type.MISS ? eye.add(dir.multiply(RANGE)) : hit.getPos();
        double reach = spot.distanceTo(eye);
        for (double back = 0.4; back <= reach; back += 0.5) {
            Vec3d at = spot.subtract(dir.multiply(back));
            for (double lift : new double[]{0, -0.9, 0.6, -1.5, 1.2}) {
                Vec3d feet = at.add(0, lift, 0);
                if (fits(world, player, feet)) return settle(world, player, feet);
            }
        }
        return null;
    }

    /** Lowers {@code feet} onto the ground beneath, if there's ground within {@link #MAX_DROP} blocks. */
    private static Vec3d settle(ServerWorld world, PlayerEntity player, Vec3d feet) {
        BlockHitResult down = world.raycast(new RaycastContext(feet.add(0, 0.05, 0), feet.subtract(0, MAX_DROP, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        if (down.getType() == HitResult.Type.MISS) return feet;
        Vec3d ground = down.getPos().add(0, 0.001, 0);
        return fits(world, player, ground) ? ground : feet;
    }

    private static boolean fits(ServerWorld world, PlayerEntity player, Vec3d feet) {
        return world.isSpaceEmpty(player, player.getDimensions(player.getPose()).getBoxAt(feet).contract(1.0e-4));
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    /** Public so tests can run it by hand. */
    public static void tickAll(ServerWorld world) {
        if (JOURNEYS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Journey>> it = JOURNEYS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Journey> e = it.next();
            Journey j = e.getValue();
            if (j.world != world) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(e.getKey());
            if (player == null || !player.isAlive() || player.getWorld() != world || player.isSpectator()) {
                it.remove();
                SERVER.remove(e.getKey());
                if (player != null) restore(player);
                cancel(world, j);
                continue;
            }
            if (!tick(world, player, j)) {
                it.remove();
            }
        }
    }

    /** One tick of one journey. False once it's over. */
    private static boolean tick(ServerWorld world, ServerPlayerEntity player, Journey j) {
        int t = ++j.age;
        Vec3d a = j.from.add(0, 1, 0), b = j.to.add(0, 1, 0);
        player.fallDistance = 0;

        // Caws, more and more of them as the flock thickens; at the far end once that flock starts to gather
        if (t < LEAVE && world.random.nextFloat() < 0.06f + 0.22f * t / LEAVE) caw(world, a);
        if (t > 30 && t < SCATTER && world.random.nextFloat() < 0.05f + 0.2f * (t - 30) / (SCATTER - 30)) caw(world, b);
        // The roar of wings as each column closes
        if (t == 18 || t == 34 || t == 46) sound(world, a, SoundEvents.ENTITY_BAT_LOOP, 0.9f + t / 60f, 0.55f + t / 200f);
        if (t == 50 || t == 64 || t == 74) sound(world, b, SoundEvents.ENTITY_BAT_LOOP, 1.0f + (t - 50) / 40f, 0.6f + (t - 50) / 120f);

        if (t == VANISH) {
            SERVER.add(player.getUuid());
            sound(world, a, SoundEvents.BLOCK_CHORUS_FLOWER_DEATH, 1.0f, 0.6f);
            sound(world, a, SoundEvents.ENTITY_PARROT_IMITATE_WITHER_SKELETON, 0.6f, 0.5f);
        }
        if (t == LEAVE) {
            // The flock bursts apart, and you're carried off inside it
            burst(world, a);
            player.stopRiding();
            player.setNoGravity(true);
            player.networkHandler.requestTeleport(j.to.x, j.to.y, j.to.z, player.getYaw(), player.getPitch());
            player.setVelocity(Vec3d.ZERO);
            player.velocityModified = true;
            if (ServerPlayNetworking.canSend(player, MovedPayload.ID)) ServerPlayNetworking.send(player, new MovedPayload());
        }
        if (t > LEAVE && t < APPEAR) {
            // Held still inside the far column until it opens
            player.setVelocity(Vec3d.ZERO);
            if (player.squaredDistanceTo(j.to) > 0.25) player.networkHandler.requestTeleport(j.to.x, j.to.y, j.to.z, player.getYaw(), player.getPitch());
        }
        if (t == APPEAR) {
            restore(player);
            player.fallDistance = 0;
            // Set down out in the open air: the crows let go gently
            if (!world.getBlockState(player.getBlockPos().down()).isSolidBlock(world, player.getBlockPos().down()) && !player.isOnGround()) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 50, 0, false, false));
            }
            sound(world, b, SoundEvents.BLOCK_CHORUS_FLOWER_DEATH, 1.0f, 1.1f);
        }
        if (t == SCATTER) burst(world, b);
        if (t == DURATION - 6) sound(world, b.add(0, 6, 0), SoundEvents.ENTITY_PARROT_HURT, 0.5f, 0.42f);
        return t < DURATION;
    }

    private static void burst(ServerWorld world, Vec3d at) {
        sound(world, at, SoundEvents.ENTITY_BAT_TAKEOFF, 1.4f, 0.5f);
        sound(world, at, SoundEvents.ENTITY_BAT_TAKEOFF, 1.2f, 0.7f);
        sound(world, at, SoundEvents.ENTITY_BAT_TAKEOFF, 1.0f, 0.95f);
        sound(world, at, SoundEvents.BLOCK_WOOL_BREAK, 1.2f, 0.55f);
        sound(world, at, SoundEvents.ENTITY_HORSE_BREATHE, 1.0f, 0.5f);
        caw(world, at);
        caw(world, at);
    }

    /** A crow's caw: a parrot's squawk, pitched right down until it rasps. */
    private static void caw(ServerWorld world, Vec3d at) {
        SoundEvent s = world.random.nextInt(3) == 0 ? SoundEvents.ENTITY_PARROT_HURT : SoundEvents.ENTITY_PARROT_AMBIENT;
        Vec3d p = at.add((world.random.nextDouble() - 0.5) * 6, world.random.nextDouble() * 3, (world.random.nextDouble() - 0.5) * 6);
        sound(world, p, s, 0.55f + world.random.nextFloat() * 0.35f, 0.42f + world.random.nextFloat() * 0.16f);
    }

    private static void sound(ServerWorld world, Vec3d at, SoundEvent s, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, volume, pitch);
    }

    private static void cancel(ServerWorld world, Journey j) {
        CancelPayload payload = new CancelPayload(j.casterId);
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (ServerPlayNetworking.canSend(p, CancelPayload.ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    /** Visible again, and gravity back. */
    private static void restore(ServerPlayerEntity player) {
        SERVER.remove(player.getUuid());
        player.setNoGravity(false);
    }
}
