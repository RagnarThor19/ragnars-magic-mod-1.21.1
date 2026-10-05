package net.ragnar.ragnarsmagicmod.sight;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import org.jetbrains.annotations.Nullable;

/**
 * The kinds of ore the Tome of Sight tells apart, cheapest first. Each has its own colour for its outline and its own
 * note on a pentatonic scale, so a sweep through a cave plays a little tune - and the rare ones ring out highest.
 */
public enum OreKind {
    COAL("Coal", 0x8C8F99, 0, false),
    COPPER("Copper", 0xE0784A, 2, false),
    IRON("Iron", 0xE8C2A0, 4, false),
    QUARTZ("Quartz", 0xF2EEE6, 7, false),
    OTHER("Ore", 0xB0D0C8, 7, false),
    REDSTONE("Redstone", 0xFF2A2A, 9, false),
    LAPIS("Lapis", 0x3A6BFF, 12, false),
    GOLD("Gold", 0xFFD23A, 14, false),
    EMERALD("Emerald", 0x2BFF7A, 16, true),
    DIAMOND("Diamond", 0x5CF4FF, 19, true),
    DEBRIS("Ancient Debris", 0xC07A5A, 21, true);

    public final String label;
    public final int rgb;
    /** Semitones above the lowest note. */
    public final int note;
    public final boolean rare;

    OreKind(String label, int rgb, int note, boolean rare) {
        this.label = label;
        this.rgb = rgb;
        this.note = note;
        this.rare = rare;
    }

    public float red() { return ((rgb >> 16) & 0xFF) / 255f; }
    public float green() { return ((rgb >> 8) & 0xFF) / 255f; }
    public float blue() { return (rgb & 0xFF) / 255f; }

    /** Note block pitch for this kind's note. */
    public float pitch() {
        return (float) (0.5 * Math.pow(2.0, note / 12.0));
    }

    /** Which kind of ore {@code state} is, or null if it isn't one. */
    @Nullable
    public static OreKind of(BlockState state) {
        if (state.isOf(Blocks.ANCIENT_DEBRIS)) return DEBRIS;
        if (state.isIn(BlockTags.DIAMOND_ORES)) return DIAMOND;
        if (state.isIn(BlockTags.EMERALD_ORES)) return EMERALD;
        if (state.isIn(BlockTags.GOLD_ORES)) return GOLD;
        if (state.isIn(BlockTags.LAPIS_ORES)) return LAPIS;
        if (state.isIn(BlockTags.REDSTONE_ORES)) return REDSTONE;
        if (state.isIn(BlockTags.IRON_ORES)) return IRON;
        if (state.isIn(BlockTags.COPPER_ORES)) return COPPER;
        if (state.isIn(BlockTags.COAL_ORES)) return COAL;
        if (state.isOf(Blocks.NETHER_QUARTZ_ORE)) return QUARTZ;
        if (state.isIn(ConventionalBlockTags.ORES)) return OTHER; // other mods' ores
        return null;
    }
}
