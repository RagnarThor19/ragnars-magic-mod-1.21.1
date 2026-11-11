package net.ragnar.ragnarsmagicmod.gliding;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.util.FallGuard;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Gliding: a passive tome. While it's in your inventory (or on a staff you carry), hold jump while you're
 * falling to glide: your fall is caught and slowed to {@link #FALL_SPEED}, and holding forward carries you along the
 * way you're looking at {@link #GLIDE_SPEED}. Let go of jump and you drop as normal. Nothing you fell before or while
 * gliding hurts.
 * <p>
 * It only starts once you've dropped {@link #START_DROP} blocks below the top of your jump, so ordinary jumps (even
 * holding jump to hop along) never glide. It goes with the other movement tomes: Jumping's air jumps are taps and
 * gliding is a hold, so you can triple jump and then glide; it picks up after a leap; and a wall you're climbing
 * always comes first.
 * <p>
 * The client moves the player (see GlidingClient); the server takes away the fall while they glide and lets everyone
 * else hear it open. To remove it: delete this package, {@code Gliding.register()} in RagnarsMagicMod,
 * {@code GlidingClient.init()} in RagnarsMagicModClient, {@code GLIDING} in SpellId, it in PassiveTomes.register,
 * {@code models/item/tome_of_gliding.json} and its en_us.json line.
 */
public final class Gliding {
    private Gliding() {}

    /** How fast you sink while gliding, blocks a tick (Slow Falling is about 0.06, a normal fall tops out near 3.9). */
    public static final double FALL_SPEED = 0.09;
    /** How fast you glide along with forward held (a sprint is about 0.28). */
    public static final double GLIDE_SPEED = 0.34;
    /** How much a fall is slowed each tick when you open up: a soft catch, not a sudden stop. */
    public static final double CATCH = 0.15;
    /** You have to have dropped this far below the top of your jump before it'll start. */
    public static final double START_DROP = 2.0;

    public static final TomeItem TOME_OF_GLIDING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_gliding"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.GLIDING, 0
            ).setCooldown(0)
    );

    /** Client -> server: I've started ({@code on}) or stopped gliding. */
    public record GlidePayload(boolean on) implements CustomPayload {
        public static final Id<GlidePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "glide"));
        public static final PacketCodec<RegistryByteBuf, GlidePayload> CODEC =
                PacketCodec.tuple(PacketCodecs.BOOL, GlidePayload::on, GlidePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static final Identifier SAFE_FALL = Identifier.of(RagnarsMagicMod.MOD_ID, "glide_safe_fall");

    /** Ticks in a row they have to have been down before the server calls the glide over. */
    private static final int SETTLE_TICKS = 3;

    /** Players gliding (server side), with how many ticks in a row they've been down. */
    private static final Map<UUID, int[]> GLIDING = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.GLIDING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_GLIDING);
        Spells.register(SpellId.GLIDING, new PassiveSpell("hold jump while falling to glide. Hold forward to glide where you're looking."));
        PayloadTypeRegistry.playC2S().register(GlidePayload.ID, GlidePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GlidePayload.ID, (payload, context) -> set(context.player(), payload.on()));
        ServerTickEvents.END_SERVER_TICK.register(Gliding::tick);
    }

    /** True if {@code player} can glide at all: alive, not spectating, and carrying the tome. */
    public static boolean canGlide(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && PassiveTomes.has(player, TOME_OF_GLIDING);
    }

    /** Server: whether {@code player} is gliding right now. */
    public static boolean isGliding(PlayerEntity player) {
        return GLIDING.containsKey(player.getUuid());
    }

    /** Server: {@code player} has started or stopped gliding. Public so tests can call it. */
    public static void set(ServerPlayerEntity player, boolean on) {
        if (!on || !canGlide(player)) {
            stop(player);
            return;
        }
        if (GLIDING.putIfAbsent(player.getUuid(), new int[1]) != null) return;
        FallGuard.hold(player, SAFE_FALL);

        // The glider hears and sees their own on their side; this is for everyone else
        ServerWorld sw = player.getServerWorld();
        double x = player.getX(), y = player.getY() + player.getHeight() * 0.6, z = player.getZ();
        sw.playSound(player, x, y, z, SoundEvents.ITEM_ARMOR_EQUIP_ELYTRA.value(), SoundCategory.PLAYERS, 0.6f, 1.25f);
        sw.playSound(player, x, y, z, SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 0.25f, 1.5f);
        for (ServerPlayerEntity viewer : sw.getPlayers()) {
            if (viewer == player || viewer.squaredDistanceTo(player) > 64 * 64) continue;
            sw.spawnParticles(viewer, ParticleTypes.CLOUD, false, x, y, z, 6, 0.3, 0.05, 0.3, 0.03);
        }
    }

    private static void stop(ServerPlayerEntity player) {
        GLIDING.remove(player.getUuid());
        FallGuard.release(player, SAFE_FALL);
    }

    /**
     * Gliding ends on its own once they've put the tome away, or are really down (or in water, mounted, flying...). The
     * server can hear "on the ground" for a moment while they're still in the air, so like Leaping it waits until
     * they've been down, with the landing dealt with, for {@link #SETTLE_TICKS} in a row.
     */
    private static void tick(MinecraftServer server) {
        if (GLIDING.isEmpty()) return;
        GLIDING.entrySet().removeIf(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) return true;
            boolean down = p.fallDistance == 0f && (p.isOnGround() || p.isTouchingWater() || p.hasVehicle() || p.getAbilities().flying);
            int[] settled = e.getValue();
            settled[0] = down ? settled[0] + 1 : 0;
            if (!canGlide(p) || settled[0] >= SETTLE_TICKS) {
                FallGuard.release(p, SAFE_FALL);
                return true;
            }
            return false;
        });
    }
}
