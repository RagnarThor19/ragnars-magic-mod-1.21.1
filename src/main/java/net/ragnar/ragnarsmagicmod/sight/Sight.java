package net.ragnar.ragnarsmagicmod.sight;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.logs.Logs;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Tome of Sight: a sonar wave rolls out from you to {@link #RADIUS} blocks, and every ore it washes over lights up
 * through the rock in its own colour, pinging its own note from where it sits, while a tally of what you found builds
 * up on screen. Only the caster sees the ores (the server sends them to the caster alone). Everything for it lives in
 * this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code sight} and {@code sight/client})</li>
 *   <li>delete {@code Sight.register()} in RagnarsMagicMod and {@code SightClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code SIGHT} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_sight.json} and the {@code sight} line in en_us.json</li>
 * </ol>
 */
public final class Sight {
    private Sight() {}

    public static final double RADIUS = 10.0;
    /** More than this many ores in one sweep and only the nearest are shown. */
    public static final int MAX_ORES = 4096;

    public static final TomeItem TOME_OF_SIGHT = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_sight"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.SIGHT, 10
            ).setCooldown(20 * 18) // 18 seconds
    );

    /** One ore the wave found. */
    public record Found(BlockPos pos, OreKind kind) {}

    /** Server -> caster: a wave went out from {@code origin} and found these ores, nearest first. */
    public record ScanPayload(Vector3f origin, List<Found> ores) implements CustomPayload {
        public static final Id<ScanPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "sight_scan"));
        public static final PacketCodec<RegistryByteBuf, ScanPayload> CODEC = PacketCodec.of(ScanPayload::write, ScanPayload::read);

        private void write(RegistryByteBuf buf) {
            buf.writeVector3f(origin);
            buf.writeVarInt(ores.size());
            for (Found f : ores) {
                buf.writeLong(f.pos().asLong());
                buf.writeByte(f.kind().ordinal());
            }
        }

        private static ScanPayload read(RegistryByteBuf buf) {
            Vector3f origin = buf.readVector3f();
            int n = Math.min(buf.readVarInt(), MAX_ORES);
            OreKind[] kinds = OreKind.values();
            List<Found> ores = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BlockPos pos = BlockPos.fromLong(buf.readLong());
                int k = buf.readByte();
                ores.add(new Found(pos, kinds[Math.floorMod(k, kinds.length)]));
            }
            return new ScanPayload(origin, ores);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.SIGHT, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_SIGHT);
        Spells.register(SpellId.SIGHT, new SightSpell());
        PayloadTypeRegistry.playS2C().register(ScanPayload.ID, ScanPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Logs.TOME_OF_LOGS, TOME_OF_SIGHT));
    }
}
