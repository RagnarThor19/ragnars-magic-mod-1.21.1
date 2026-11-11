package net.ragnar.ragnarsmagicmod.leaping;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
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
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Leaping: a passive tome. While it's in your inventory (or on a staff you carry), crouch - standing or
 * crouch-walking - to wind up a leap, then press
 * jump (still crouching) to let it go. The longer you've been crouched, the higher it goes: from a normal jump up to
 * {@link #MAX_HEIGHT} blocks after {@link #FULL_CHARGE_TICKS}. Hold forward as you jump to leap forward as well.
 * <p>
 * Crouching on its own never launches anyone, so it stays out of the way of sneaking at edges and building. The wind-up
 * only shows once you've crouched for {@link #MIN_CHARGE_TICKS}: the horse jump bar fills where the XP bar is, with
 * one soft ding when it's full (see LeapingClient). Landing back at the height you leapt from never hurts; only a fall
 * further than the leap itself starts to.
 * <p>
 * The client moves the player (as it does for every jump); the server hands out the fall protection and lets everyone
 * else hear it. To remove it: delete this package, {@code Leaping.register()} in RagnarsMagicMod,
 * {@code LeapingClient.init()} in RagnarsMagicModClient, the leap bar in InGameHudMixin, {@code LEAPING} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_leaping.json} and its en_us.json line.
 */
public final class Leaping {
    private Leaping() {}

    public static final float MAX_HEIGHT = 15f;
    /** A normal jump's height: what the least wind-up gives. */
    public static final float BASE_HEIGHT = 1.25f;
    /** 2 seconds of crouching for the full leap. */
    public static final int FULL_CHARGE_TICKS = 40;
    /** Crouching for less than this does nothing at all (and shows nothing). */
    public static final int MIN_CHARGE_TICKS = 8;
    /** How long the fall protection can last, in case a leap never comes down anywhere we notice. */
    private static final int PROTECT_MAX_TICKS = 20 * 20;

    private static final Identifier SAFE_FALL = Identifier.of(RagnarsMagicMod.MOD_ID, "leap_safe_fall");
    /** Players mid-leap: the server tick they leapt on, and how many ticks in a row they've been safely down since. */
    private static final Map<UUID, int[]> LEAPING = new HashMap<>();
    /**
     * Ticks to stay down before the protection goes. The server can hear "on the ground" a moment before it deals with
     * the landing itself, so it waits until the fall has really been handled (fall distance back to 0) for a few ticks.
     */
    private static final int SETTLE_TICKS = 3;

    public static final TomeItem TOME_OF_LEAPING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_leaping"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.LEAPING, 0
            ).setCooldown(0)
    );

    /** Client -> server: I've just leapt, aiming for {@code height} blocks. */
    public record LeapPayload(float height) implements CustomPayload {
        public static final Id<LeapPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "leap"));
        public static final PacketCodec<RegistryByteBuf, LeapPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.FLOAT, LeapPayload::height, LeapPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.LEAPING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_LEAPING);
        Spells.register(SpellId.LEAPING, new PassiveSpell("crouch to wind up a leap, then jump to let it go."));
        PayloadTypeRegistry.playC2S().register(LeapPayload.ID, LeapPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(LeapPayload.ID, (payload, context) -> leap(context.player(), payload.height()));
        ServerTickEvents.END_SERVER_TICK.register(Leaping::tick);
    }

    // ---------------------------------------------------------------------
    // The numbers (shared by both sides)
    // ---------------------------------------------------------------------

    /** How high a leap wound up for {@code chargeTicks} goes. */
    public static float heightFor(int chargeTicks) {
        float c = MathHelper.clamp(chargeTicks / (float) FULL_CHARGE_TICKS, 0f, 1f);
        return BASE_HEIGHT + (MAX_HEIGHT - BASE_HEIGHT) * c;
    }

    /** How high {@code vy} carries a player (vanilla's gravity and drag), in blocks. */
    public static double riseFrom(double vy) {
        double y = 0, top = 0;
        for (int i = 0; i < 200 && vy > 0; i++) {
            y += vy;
            top = Math.max(top, y);
            vy = (vy - 0.08) * 0.98;
        }
        return top;
    }

    /** The upward speed that carries a player {@code height} blocks higher. */
    public static double velocityFor(double height) {
        if (height <= 0) return 0;
        double lo = 0, hi = 4;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (riseFrom(mid) < height) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    // ---------------------------------------------------------------------
    // Server
    // ---------------------------------------------------------------------

    /**
     * Server: {@code player} has leapt for {@code height}. Protects them from the fall back down (landing back where they
     * started is free; a longer fall hurts by however much further it went), and lets everyone near hear it. Public so
     * tests can call it.
     */
    public static void leap(ServerPlayerEntity player, float height) {
        if (!player.isAlive() || player.isSpectator() || !PassiveTomes.has(player, TOME_OF_LEAPING)) return;
        float h = MathHelper.clamp(Float.isFinite(height) ? height : 0f, 0f, MAX_HEIGHT);
        player.fallDistance = 0f;
        EntityAttributeInstance safe = player.getAttributeInstance(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        if (safe != null) {
            safe.removeModifier(SAFE_FALL);
            // Vanilla's 3 blocks already cover a little; top it up to the whole leap (and half a block of slack)
            double extra = Math.max(0.0, h + 0.5 - 3.0);
            if (extra > 0) safe.addTemporaryModifier(new EntityAttributeModifier(SAFE_FALL, extra, EntityAttributeModifier.Operation.ADD_VALUE));
        }
        LEAPING.put(player.getUuid(), new int[]{player.getServer().getTicks(), 0});

        // The leaper hears and sees their own on their side straight away; this is for everyone else
        ServerWorld sw = player.getServerWorld();
        double x = player.getX(), y = player.getY(), z = player.getZ();
        float k = h / MAX_HEIGHT;
        sw.playSound(player, x, y, z, SoundEvents.ENTITY_GOAT_LONG_JUMP, SoundCategory.PLAYERS, 0.5f + 0.3f * k, 1.2f - 0.25f * k);
        for (ServerPlayerEntity viewer : sw.getPlayers()) {
            if (viewer == player || viewer.squaredDistanceTo(player) > 64 * 64) continue;
            sw.spawnParticles(viewer, ParticleTypes.POOF, false, x, y + 0.05, z, (int) (4 + 8 * k), 0.25, 0.02, 0.25, 0.03);
            if (h >= MAX_HEIGHT) sw.spawnParticles(viewer, ParticleTypes.CLOUD, false, x, y + 0.1, z, 10, 0.3, 0.02, 0.3, 0.04);
        }
    }

    /** The fall protection lasts until they're back on the ground (or in water, on a ladder, mounted, flying...). */
    private static void tick(MinecraftServer server) {
        if (LEAPING.isEmpty()) return;
        int now = server.getTicks();
        LEAPING.entrySet().removeIf(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) return true;
            int[] leap = e.getValue();
            int since = now - leap[0];
            boolean down = since > 3 && p.fallDistance == 0f && (p.isOnGround() || p.isTouchingWater() || p.isInLava()
                    || p.isClimbing() || p.hasVehicle() || p.getAbilities().flying);
            leap[1] = down ? leap[1] + 1 : 0;
            if (leap[1] >= SETTLE_TICKS || since > PROTECT_MAX_TICKS || !p.isAlive()) {
                clear(p);
                return true;
            }
            return false;
        });
    }

    /** Takes a leap's fall protection away again. */
    public static void clear(PlayerEntity player) {
        EntityAttributeInstance safe = player.getAttributeInstance(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        if (safe != null) safe.removeModifier(SAFE_FALL);
    }
}
