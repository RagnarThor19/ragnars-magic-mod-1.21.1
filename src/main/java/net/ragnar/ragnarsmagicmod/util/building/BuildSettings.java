package net.ragnar.ragnarsmagicmod.util.building;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer.Layer;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer.Pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Tome of Building will make: shape, sizes, fill and the block mix. Lives on the client (saved to the
 * config folder) and is sent with every build, where the server sanitises it again.
 */
public final class BuildSettings {
    public static final int MAX_PALETTE = 4;
    public static final int MAX_WEIGHT = 5;

    public static final class Entry {
        public final Item item;
        public int weight;
        public Layer layer;

        public Entry(Item item, int weight, Layer layer) {
            this.item = item;
            this.weight = MathHelper.clamp(weight, 1, MAX_WEIGHT);
            this.layer = layer;
        }
    }

    public BuildShape shape = BuildShape.WALL;
    public int width = 3, height = 3, length = 3;
    public boolean hollow = false;
    public Pattern pattern = Pattern.RANDOM;
    public final List<Entry> palette = new ArrayList<>();

    /** Blocks that can be built with: plain blocks only, nothing that holds items or spans two blocks. */
    public static boolean isBuildable(Item item) {
        if (!(item instanceof BlockItem blockItem) || item == Items.AIR) return false;
        Block block = blockItem.getBlock();
        if (block.getDefaultState().isAir()) return false;
        return !(block instanceof BlockEntityProvider || block instanceof DoorBlock
                || block instanceof BedBlock || block instanceof TallPlantBlock);
    }

    public int effectiveWidth() { return shape.usesWidth() ? width : 1; }
    public int effectiveHeight() { return shape.usesHeight() ? height : 1; }
    public int effectiveLength() { return shape.usesLength() ? length : 1; }

    /** Keeps every size inside what the current shape allows. */
    public void clamp() {
        width = MathHelper.clamp(width, BuildShape.MIN_SIZE, Math.max(1, shape.maxWidth));
        height = MathHelper.clamp(height, BuildShape.MIN_SIZE, Math.max(1, shape.maxHeight));
        length = MathHelper.clamp(length, BuildShape.MIN_SIZE, Math.max(1, shape.maxLength));
    }

    /** Grows (or shrinks) every size the shape uses by one step. Returns true if anything changed. */
    public boolean resize(int delta) {
        int w = width, h = height, l = length;
        if (shape.usesWidth()) width += delta;
        if (shape.usesHeight()) height += delta;
        if (shape.usesLength()) length += delta;
        clamp();
        return w != width || h != height || l != length;
    }

    public boolean hasItem(Item item) {
        for (Entry e : palette) if (e.item == item) return true;
        return false;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("shape", shape.name());
        nbt.putInt("width", width);
        nbt.putInt("height", height);
        nbt.putInt("length", length);
        nbt.putBoolean("hollow", hollow);
        nbt.putString("pattern", pattern.name());
        NbtList list = new NbtList();
        for (Entry e : palette) {
            NbtCompound c = new NbtCompound();
            c.putString("item", Registries.ITEM.getId(e.item).toString());
            c.putInt("weight", e.weight);
            c.putString("layer", e.layer.name());
            list.add(c);
        }
        nbt.put("palette", list);
        return nbt;
    }

    /** Reads settings, dropping anything invalid, so it is safe to use on data sent by a client. */
    public static BuildSettings fromNbt(NbtCompound nbt) {
        BuildSettings s = new BuildSettings();
        if (nbt == null) return s;
        s.shape = parse(BuildShape.class, nbt.getString("shape"), BuildShape.WALL);
        if (nbt.contains("width")) s.width = nbt.getInt("width");
        if (nbt.contains("height")) s.height = nbt.getInt("height");
        if (nbt.contains("length")) s.length = nbt.getInt("length");
        s.hollow = nbt.getBoolean("hollow");
        s.pattern = parse(Pattern.class, nbt.getString("pattern"), Pattern.RANDOM);
        NbtList list = nbt.getList("palette", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size() && s.palette.size() < MAX_PALETTE; i++) {
            NbtCompound c = list.getCompound(i);
            Identifier id = Identifier.tryParse(c.getString("item"));
            if (id == null || !Registries.ITEM.containsId(id)) continue;
            Item item = Registries.ITEM.get(id);
            if (!isBuildable(item) || s.hasItem(item)) continue;
            s.palette.add(new Entry(item, c.getInt("weight"), parse(Layer.class, c.getString("layer"), Layer.BOTTOM)));
        }
        s.clamp();
        return s;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
