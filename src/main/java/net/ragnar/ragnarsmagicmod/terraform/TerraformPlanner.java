package net.ragnar.ragnarsmagicmod.terraform;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what a Tome of Terraforming cast will do, the same way on the client (for the preview) and the server
 * (for real). The land is treated as columns: in each one the ground is the highest natural terrain block with
 * open air, water or a little plant above it. Every mode turns those ground heights into new ones; then each column
 * is cut down or built up to its new height, keeping its own layers - its top block, the few blocks of soil under it,
 * and the rock below that.
 * <p>
 * Columns that don't end in plain terrain (a build, a tree trunk, a torch, lava) keep their height, and so does
 * anything the cutting or piling would run into on its way (an ore, a cave's ceiling, a leafy canopy): only terrain
 * is ever removed, and the ground only ever rises into open space.
 */
public final class TerraformPlanner {
    private TerraformPlanner() {}

    /** How far away you can aim. */
    public static final double REACH = 64;
    /** How far above and below the aimed spot each column is searched for its ground. */
    static final int SCAN_UP = 40, SCAN_DOWN = 48;
    /** No column moves further than this in one cast. */
    public static final int MAX_CHANGE = 40;
    /** The ramp can't run longer than this. */
    public static final int MAX_RAMP = 64;
    /** Ticks for the reshaping to ripple out from the centre to the edge. */
    public static final int SPREAD = 14;

    /** Where the cast is aimed: the block, and the height of the ground there (what Flatten levels to). */
    public record Target(BlockPos anchor, int height) {}

    /**
     * One column of land: where it is, its ground now and after, and what it's made of (top block, the soil just
     * under it, and the rock below). {@code wet}: water stands over it, so what's cut away fills with water.
     */
    public record Column(int x, int z, int oldTop, int newTop, BlockState top, BlockState sub, BlockState deep,
                         boolean wet, int delay) {
        public boolean changed() {
            return newTop != oldTop;
        }
    }

    /** What a cast will do: every column the brush touches, and how much earth goes and comes. */
    public record Plan(Target target, List<Column> columns, int fill, int cut, int changed) {
        public int net() {
            return fill - cut;
        }
    }

    // ---------------------------------------------------------------------
    // Aiming
    // ---------------------------------------------------------------------

    /** What {@code player} is aiming at, or null. */
    public static Target target(World world, PlayerEntity player, float tickDelta) {
        HitResult hit = player.raycast(REACH, tickDelta, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return null;
        return targetAt(world, bhr.getBlockPos());
    }

    /**
     * The target for an aimed block: a terrain block gives its own height (even halfway up a cliff); anything else
     * (a flower, snow, a torch) gives the ground just under it. Null if there's no ground there to speak of.
     */
    public static Target targetAt(World world, BlockPos aimed) {
        if (Terrain.isTerrain(world.getBlockState(aimed))) return new Target(aimed.toImmutable(), aimed.getY());
        BlockPos.Mutable p = aimed.mutableCopy();
        for (int i = 0; i < 6; i++) {
            p.move(0, -1, 0);
            if (Terrain.isTerrain(world.getBlockState(p))) return new Target(aimed.toImmutable(), p.getY());
        }
        return null;
    }

    /** The ground under {@code from} (searched up to {@code depth} down, through air, water, plants and leaves). */
    static int groundBelow(World world, BlockPos from, int depth) {
        BlockPos.Mutable p = from.mutableCopy();
        for (int i = 0; i <= depth; i++, p.move(0, -1, 0)) {
            if (Terrain.isTerrain(world.getBlockState(p))) return p.getY();
        }
        return from.getY() - 1;
    }

    // ---------------------------------------------------------------------
    // Finding the ground
    // ---------------------------------------------------------------------

    private enum Kind { GROUND, FIXED }

    /** A column as found: its ground (or the height of whatever pins it), and its layers. */
    private static final class Found {
        final int x, z, h;
        final Kind kind;
        boolean wet;
        BlockState top, sub, deep;
        double weight, along, distance;
        int newTop;

        Found(int x, int z, int h, Kind kind) {
            this.x = x;
            this.z = z;
            this.h = h;
            this.kind = kind;
        }
    }

    private static Found scan(World world, int x, int z, int fromY, int toY) {
        BlockPos.Mutable p = new BlockPos.Mutable(x, fromY, z);
        int bottom = Math.max(toY, world.getBottomY());
        for (int y = Math.min(fromY, world.getTopY() - 1); y >= bottom; y--) {
            p.setY(y);
            BlockState s = world.getBlockState(p);
            if (Terrain.isOpen(s) || Terrain.isDecoration(s) || Terrain.isCanopy(s)) continue;
            if (!Terrain.isTerrain(s)) return new Found(x, z, y, Kind.FIXED); // a build, a trunk, lava...
            p.setY(y + 1);
            BlockState above = world.getBlockState(p);
            // Leaves sitting right on it (a bush, a tree's skirt) pin it, as does lava
            if (Terrain.isCanopy(above) || Terrain.isLava(above)) return new Found(x, z, y, Kind.FIXED);
            Found f = new Found(x, z, y, Kind.GROUND);
            f.wet = Terrain.isWater(above) || above.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
            layers(world, f);
            return f;
        }
        return null;
    }

    /** The column's top block, the soil just under it, and the rock deeper down, as they'll be rebuilt. */
    private static void layers(World world, Found f) {
        BlockState top = world.getBlockState(new BlockPos(f.x, f.h, f.z));
        if (top.isOf(Blocks.FARMLAND)) top = Blocks.DIRT.getDefaultState();
        BlockState under = world.getBlockState(new BlockPos(f.x, f.h - 1, f.z));
        BlockState sub = Terrain.isTerrain(under) && !Terrain.isCover(under) ? under
                : Terrain.isCover(top) ? Terrain.buried(top) : top;
        BlockState low = world.getBlockState(new BlockPos(f.x, f.h - 4, f.z));
        BlockState deep = Terrain.isTerrain(low) && !Terrain.isCover(low) ? low : sub;
        f.top = top;
        f.sub = sub;
        f.deep = deep;
    }

    // ---------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------

    public static Plan plan(World world, PlayerEntity player, TerraformSettings settings, Target target) {
        TerraformMode mode = settings.mode;
        int r = settings.radius;
        int ax = target.anchor().getX(), az = target.anchor().getZ(), T = target.height();
        Map<Long, Found> cols = new HashMap<>();

        // The ramp's ends: where you stand, and where you aim
        int px = 0, pz = 0, feet = T;
        double len = 0;
        if (mode == TerraformMode.RAMP) {
            px = player.getBlockX();
            pz = player.getBlockZ();
            feet = groundBelow(world, player.getBlockPos(), 32);
            len = Math.min(Math.sqrt((double) (ax - px) * (ax - px) + (double) (az - pz) * (az - pz)), MAX_RAMP);
        }

        // Every column the brush covers, with how strongly (weight) and how far out (for the ripple)
        int top = Math.max(T, feet) + SCAN_UP, bottom = Math.min(T, feet) - SCAN_DOWN;
        if (mode == TerraformMode.RAMP) {
            double dx = ax - px, dz = az - pz, d = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
            double ux = dx / d, uz = dz / d;
            int minX = Math.min(px, ax) - r, maxX = Math.max(px, ax) + r, minZ = Math.min(pz, az) - r, maxZ = Math.max(pz, az) + r;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double rx = x - px, rz = z - pz;
                    double t = MathHelper.clamp(rx * ux + rz * uz, 0, len);
                    double lateral = Math.hypot(rx - ux * t, rz - uz * t);
                    double dn = lateral / (r + 0.5);
                    if (dn > 1) continue;
                    Found f = scan(world, x, z, top, bottom);
                    if (f == null) continue;
                    f.weight = edgeWeight(dn, settings.softEdge);
                    f.along = len < 1e-6 ? 1 : t / len;
                    f.distance = f.along;
                    cols.put(key(x, z), f);
                }
            }
        } else {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    double dn = settings.square ? Math.max(Math.abs(dx), Math.abs(dz)) / (r + 0.5)
                            : Math.sqrt(dx * dx + dz * dz) / (r + 0.5);
                    if (dn > 1) continue;
                    Found f = scan(world, ax + dx, az + dz, top, bottom);
                    if (f == null) continue;
                    f.distance = dn;
                    f.weight = (mode == TerraformMode.RAISE || mode == TerraformMode.LOWER) && settings.softEdge
                            ? 1 : edgeWeight(dn, settings.softEdge);
                    cols.put(key(ax + dx, az + dz), f);
                }
            }
        }

        // Each mode's idea of where the ground should be
        Map<Long, Double> smooth = mode == TerraformMode.SMOOTH ? blur(cols, settings.strength())
                : mode == TerraformMode.TERRACE ? blur(cols, 1) : null;
        int strength = settings.strength();
        for (Found f : cols.values()) {
            if (f.kind != Kind.GROUND) {
                f.newTop = f.h;
                continue;
            }
            double goal = switch (mode) {
                case FLATTEN -> T;
                case SMOOTH -> smooth.get(key(f.x, f.z));
                case RAISE -> f.h + strength * (settings.softEdge ? bell(f.distance) : 1);
                case LOWER -> f.h - strength * (settings.softEdge ? bell(f.distance) : 1);
                case TERRACE -> T + Math.round((smooth.get(key(f.x, f.z)) - T) / strength) * (double) strength;
                case CLIFF -> f.h >= T ? f.h : Math.min(f.h, T - strength);
                case RAMP -> feet + (T - feet) * f.along;
                case ROUGHEN -> f.h + strength * noise(f.x, f.z);
            };
            int wanted = (int) Math.round(f.h + (goal - f.h) * f.weight);
            wanted = MathHelper.clamp(wanted, f.h - MAX_CHANGE, f.h + MAX_CHANGE);
            wanted = MathHelper.clamp(wanted, world.getBottomY() + 1, world.getTopY() - 3);
            f.newTop = wanted;
        }

        // Cut and pile, stopping at anything that isn't plain terrain / open space
        List<Column> out = new ArrayList<>(cols.size());
        int fill = 0, cut = 0, changed = 0;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (Found f : cols.values()) {
            if (f.kind != Kind.GROUND) continue;
            int newTop = f.newTop;
            if (newTop < f.h) {
                for (int y = f.h; y > newTop; y--) {
                    if (!Terrain.isTerrain(world.getBlockState(p.set(f.x, y, f.z)))) {
                        newTop = y;
                        break;
                    }
                }
                cut += f.h - newTop;
            } else if (newTop > f.h) {
                for (int y = f.h + 1; y <= newTop; y++) {
                    BlockState s = world.getBlockState(p.set(f.x, y, f.z));
                    if (!Terrain.isOpen(s) && !Terrain.isDecoration(s)) {
                        newTop = y - 1;
                        break;
                    }
                }
                fill += newTop - f.h;
            }
            if (newTop != f.h) changed++;
            int delay = (int) Math.round(MathHelper.clamp(f.distance, 0, 1) * (mode == TerraformMode.RAMP ? SPREAD * 1.5 : SPREAD));
            out.add(new Column(f.x, f.z, f.h, newTop, f.top, f.sub, f.deep, f.wet, delay));
        }
        out.sort(Comparator.comparingInt(Column::delay));
        return new Plan(target, out, fill, cut, changed);
    }

    // ---------------------------------------------------------------------
    // Shaping helpers
    // ---------------------------------------------------------------------

    /** Full strength in the middle, easing off smoothly to nothing at the edge (soft), or full right up to it (hard). */
    static double edgeWeight(double d, boolean soft) {
        if (!soft) return d <= 1 ? 1 : 0;
        if (d <= 0.55) return 1;
        double u = MathHelper.clamp((1 - d) / 0.45, 0, 1);
        return u * u * (3 - 2 * u);
    }

    /** A hill's profile: 1 at the centre, 0 at the edge, round on top and flared at the foot. */
    static double bell(double d) {
        double k = 1 - MathHelper.clamp(d, 0, 1) * MathHelper.clamp(d, 0, 1);
        return k * k;
    }

    /** The ground heights averaged with their neighbours, {@code passes} times; pinned columns hold their height. */
    private static Map<Long, Double> blur(Map<Long, Found> cols, int passes) {
        Map<Long, Double> h = new HashMap<>();
        for (Map.Entry<Long, Found> e : cols.entrySet()) h.put(e.getKey(), (double) e.getValue().h);
        for (int pass = 0; pass < passes; pass++) {
            Map<Long, Double> next = new HashMap<>(h.size());
            for (Map.Entry<Long, Found> e : cols.entrySet()) {
                Found f = e.getValue();
                if (f.kind != Kind.GROUND) {
                    next.put(e.getKey(), h.get(e.getKey()));
                    continue;
                }
                double sum = 0;
                int n = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Double v = h.get(key(f.x + dx, f.z + dz));
                        if (v == null) continue;
                        sum += v;
                        n++;
                    }
                }
                next.put(e.getKey(), sum / n);
            }
            h = next;
        }
        return h;
    }

    /** Smooth, natural-looking rolls in [-1, 1]: broad swells with smaller ripples on them, the same every time. */
    static double noise(int x, int z) {
        return MathHelper.clamp(0.7 * valueNoise(x / 7.0, z / 7.0) + 0.45 * valueNoise(x / 3.0 + 57.3, z / 3.0 + 91.7), -1, 1);
    }

    private static double valueNoise(double x, double z) {
        int x0 = MathHelper.floor(x), z0 = MathHelper.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = MathHelper.lerp(fx, hash(x0, z0), hash(x0 + 1, z0));
        double b = MathHelper.lerp(fx, hash(x0, z0 + 1), hash(x0 + 1, z0 + 1));
        return MathHelper.lerp(fz, a, b);
    }

    private static double hash(int x, int z) {
        long h = x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 32767.5 - 1;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
