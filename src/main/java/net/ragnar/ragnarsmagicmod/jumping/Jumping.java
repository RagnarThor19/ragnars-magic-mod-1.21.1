package net.ragnar.ragnarsmagicmod.jumping;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.ricochet.Ricochet;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Jumping: a passive tome. While it's on a staff you're carrying, you can jump twice more in mid-air - a
 * triple jump - as long as each one comes within {@link #WINDOW} ticks of the jump before. Walking off a ledge isn't
 * a jump, so it won't catch a fall. Each air jump costs {@link #XP_PER_JUMP} XP. Everything for it lives in this
 * package.
 * <p>
 * Movement is the client's to decide, so the client does the jump (see JumpingClient) and tells the server, which
 * checks it, takes the XP, clears the fall and shows it to everyone else.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code jumping} and {@code jumping/client})</li>
 *   <li>delete {@code Jumping.register()} in RagnarsMagicMod and {@code JumpingClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code JUMPING} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_jumping.json} and the {@code jumping} line in en_us.json</li>
 * </ol>
 */
public final class Jumping {
    private Jumping() {}

    public static final int AIR_JUMPS = 2;
    /** An air jump has to come within a second of the jump before it. */
    public static final int WINDOW = 20;
    public static final int XP_PER_JUMP = 1;

    public static final TomeItem TOME_OF_JUMPING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_jumping"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.JUMPING, XP_PER_JUMP
            ).setCooldown(0)
    );

    /** Client -> server: I just did air jump number {@code n} (1 or 2). */
    public record AirJumpPayload(int n) implements CustomPayload {
        public static final Id<AirJumpPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "air_jump"));
        public static final PacketCodec<RegistryByteBuf, AirJumpPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, AirJumpPayload::n, AirJumpPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Air jumps each player has used since they last stood on something (server side). */
    private static final Map<UUID, Integer> USED = new HashMap<>();

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.JUMPING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_JUMPING);
        Spells.register(SpellId.JUMPING, new JumpingSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Ricochet.TOME_OF_RICOCHET, TOME_OF_JUMPING));

        PayloadTypeRegistry.playC2S().register(AirJumpPayload.ID, AirJumpPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AirJumpPayload.ID, (payload, context) -> airJump(context.player()));
        ServerTickEvents.END_SERVER_TICK.register(server -> USED.entrySet().removeIf(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            return p == null || !p.isAlive() || p.isOnGround() || p.isTouchingWater() || p.isClimbing() || p.hasVehicle();
        }));
    }

    /** True if {@code player} can air jump at all right now: tome on a staff they carry, and XP to pay for it. */
    public static boolean canAirJump(PlayerEntity player) {
        if (player.isSpectator() || player.getAbilities().flying) return false;
        if (StaffItem.findStaffWith(player, TOME_OF_JUMPING).isEmpty()) return false;
        return player.isCreative() || player.experienceLevel > 0 || player.experienceProgress > 0f;
    }

    private static void airJump(ServerPlayerEntity player) {
        if (!canAirJump(player)) return;
        int used = USED.getOrDefault(player.getUuid(), 0);
        if (used >= AIR_JUMPS) return;
        USED.put(player.getUuid(), ++used);

        if (!player.isCreative()) player.addExperience(-XP_PER_JUMP);
        player.fallDistance = 0f;
        player.incrementStat(net.minecraft.stat.Stats.USED.getOrCreateStat(TOME_OF_JUMPING));

        // The jumper sees and hears their own jump straight away on their side; this is for everyone else
        ServerWorld sw = player.getServerWorld();
        double x = player.getX(), y = player.getY(), z = player.getZ();
        boolean last = used == AIR_JUMPS;
        sw.playSound(player, x, y, z, SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.7f, last ? 1.6f : 1.3f);
        sw.playSound(player, x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, last ? 1.9f : 1.4f);
        for (ServerPlayerEntity viewer : sw.getPlayers()) {
            if (viewer == player || viewer.squaredDistanceTo(player) > 64 * 64) continue;
            sw.spawnParticles(viewer, ParticleTypes.CLOUD, false, x, y, z, 14, 0.35, 0.02, 0.35, 0.06);
            if (last) sw.spawnParticles(viewer, ParticleTypes.END_ROD, false, x, y, z, 10, 0.2, 0.05, 0.2, 0.08);
        }
    }
}
