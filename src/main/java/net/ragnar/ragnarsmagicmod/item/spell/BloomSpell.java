package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FlowerbedBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.*;

/**
 * Tome of Bloom. A spiral of petals rises around you and a wave of blossom rolls out across the ground,
 * flowers springing up in drifts of colour to a little melody, under a gentle fall of cherry petals.
 * Not useful. Very pretty.
 */
public class BloomSpell implements Spell {
    private static final int RADIUS = 9;
    private static final double WAVE_SPEED = 0.35;   // blocks per tick
    private static final int LINGER_TICKS = 50;      // petals keep drifting after the wave
    private static final int PATCHES = 6;

    /** A flower, and the colour its petals burst in. */
    private record Flower(BlockState state, Vector3f color, boolean tall) {
        Flower(Block block, float r, float g, float b) {
            this(block.getDefaultState(), new Vector3f(r, g, b), block instanceof TallPlantBlock);
        }
    }

    private static final List<Flower> FLOWERS = List.of(
            new Flower(Blocks.DANDELION, 1.0f, 0.9f, 0.2f),
            new Flower(Blocks.POPPY, 0.9f, 0.1f, 0.1f),
            new Flower(Blocks.BLUE_ORCHID, 0.2f, 0.7f, 1.0f),
            new Flower(Blocks.ALLIUM, 0.75f, 0.45f, 0.95f),
            new Flower(Blocks.AZURE_BLUET, 0.95f, 0.95f, 1.0f),
            new Flower(Blocks.RED_TULIP, 0.95f, 0.2f, 0.15f),
            new Flower(Blocks.ORANGE_TULIP, 1.0f, 0.55f, 0.1f),
            new Flower(Blocks.WHITE_TULIP, 1.0f, 1.0f, 1.0f),
            new Flower(Blocks.PINK_TULIP, 1.0f, 0.65f, 0.8f),
            new Flower(Blocks.OXEYE_DAISY, 1.0f, 1.0f, 0.85f),
            new Flower(Blocks.CORNFLOWER, 0.3f, 0.45f, 1.0f),
            new Flower(Blocks.LILY_OF_THE_VALLEY, 1.0f, 1.0f, 1.0f),
            new Flower(Blocks.TORCHFLOWER, 1.0f, 0.5f, 0.2f),
            new Flower(Blocks.PINK_PETALS, 1.0f, 0.7f, 0.85f));

    private static final List<Flower> TALL_FLOWERS = List.of(
            new Flower(Blocks.SUNFLOWER, 1.0f, 0.85f, 0.1f),
            new Flower(Blocks.LILAC, 0.85f, 0.6f, 0.95f),
            new Flower(Blocks.ROSE_BUSH, 0.9f, 0.1f, 0.15f),
            new Flower(Blocks.PEONY, 1.0f, 0.7f, 0.85f));

    private static final Flower GRASS = new Flower(Blocks.SHORT_GRASS, 0.5f, 0.8f, 0.3f);

    // Pentatonic steps on the chime note block, climbing as the wave spreads
    private static final int[] MELODY = {6, 8, 10, 13, 15, 18, 20, 22, 25, 22, 20};

    private record Planting(BlockPos pos, Flower flower, int tick) {}

    private static final class Bloom {
        final ServerWorld world;
        final UUID caster;
        final Vec3d center;
        final List<Planting> plantings;
        final int waveTicks;
        int age = 0;
        int next = 0;

        Bloom(ServerWorld world, UUID caster, Vec3d center, List<Planting> plantings) {
            this.world = world;
            this.caster = caster;
            this.center = center;
            this.plantings = plantings;
            this.waveTicks = (int) Math.ceil(RADIUS / WAVE_SPEED) + 4;
        }
    }

    private static final List<Bloom> BLOOMS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Bloom> it = BLOOMS.iterator();
            while (it.hasNext()) {
                Bloom b = it.next();
                if (b.world == world && !tick(b)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        Vec3d c = player.getPos();
        List<Planting> plantings = plan(sw, player, c);
        BLOOMS.add(new Bloom(sw, player.getUuid(), c, plantings));

        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FLOWERING_AZALEA_PLACE, SoundCategory.PLAYERS, 1.2f, 0.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ALLAY_AMBIENT_WITH_ITEM, SoundCategory.PLAYERS, 0.7f, 1.2f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.5f);
        sw.spawnParticles(ParticleTypes.CHERRY_LEAVES, c.x, c.y + 1.0, c.z, 30, 0.8, 0.6, 0.8, 0.02);
        return true;
    }

    // ---------------------------------------------------------------------
    // Where everything will grow
    // ---------------------------------------------------------------------

    private static List<Planting> plan(ServerWorld world, PlayerEntity player, Vec3d c) {
        Random rand = world.getRandom();

        // Drifts of colour: each patch has its own favourite flower
        List<Vec3d> patchCenters = new ArrayList<>();
        List<Flower> patchFlowers = new ArrayList<>();
        for (int i = 0; i < PATCHES; i++) {
            double a = rand.nextDouble() * Math.PI * 2, d = rand.nextDouble() * RADIUS;
            patchCenters.add(c.add(Math.cos(a) * d, 0, Math.sin(a) * d));
            patchFlowers.add(FLOWERS.get(rand.nextInt(FLOWERS.size())));
        }

        List<Planting> out = new ArrayList<>();
        BlockPos origin = player.getBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > RADIUS + 0.5) continue;
                // Thick near the middle, thinning out toward the rim
                double density = 0.7 * (1.0 - Math.pow(dist / (RADIUS + 1), 3)) + 0.05;
                if (rand.nextDouble() > density) continue;

                BlockPos spot = findSpot(world, origin.add(dx, 0, dz));
                if (spot == null) continue;

                Flower flower = choose(rand, Vec3d.ofCenter(spot), patchCenters, patchFlowers, world, spot);
                if (flower == null) continue;
                int tick = (int) (dist / WAVE_SPEED) + rand.nextInt(3);
                out.add(new Planting(spot, flower, tick));
            }
        }
        out.sort(Comparator.comparingInt(Planting::tick));
        return out;
    }

    /** The air block on top of the ground at this column, if something can grow there. */
    private static BlockPos findSpot(ServerWorld world, BlockPos column) {
        BlockPos.Mutable pos = column.mutableCopy().move(Direction.UP, 3);
        for (int i = 0; i < 8; i++) {
            BlockState here = world.getBlockState(pos);
            BlockState below = world.getBlockState(pos.down());
            boolean open = here.isAir() || here.isOf(Blocks.SHORT_GRASS) || here.isOf(Blocks.FERN);
            if (open && !below.isAir() && Blocks.POPPY.getDefaultState().canPlaceAt(world, pos)) return pos.toImmutable();
            pos.move(Direction.DOWN);
        }
        return null;
    }

    private static Flower choose(Random rand, Vec3d at, List<Vec3d> centers, List<Flower> flowers, ServerWorld world, BlockPos pos) {
        double roll = rand.nextDouble();
        if (roll < 0.03) return GRASS; // a rare tuft
        if (roll < 0.09) {
            Flower tall = TALL_FLOWERS.get(rand.nextInt(TALL_FLOWERS.size()));
            return world.getBlockState(pos.up()).isAir() ? tall : null;
        }
        if (roll < 0.30) return FLOWERS.get(rand.nextInt(FLOWERS.size())); // a scattering of everything
        // Otherwise: the nearest patch's flower
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            double d = centers.get(i).squaredDistanceTo(at.x, centers.get(i).y, at.z);
            if (d < bestD) { bestD = d; best = i; }
        }
        return flowers.get(best);
    }

    // ---------------------------------------------------------------------
    // The show
    // ---------------------------------------------------------------------

    /** Returns false when it's over. */
    private static boolean tick(Bloom b) {
        ServerWorld world = b.world;
        Random rand = world.getRandom();
        int age = b.age++;
        Vec3d c = b.center;
        PlayerEntity caster = world.getPlayerByUuid(b.caster);
        Vec3d spiralAt = caster != null && caster.squaredDistanceTo(c) < RADIUS * RADIUS ? caster.getPos() : c;

        // 1) The spiral: two strands of petal-coloured light winding up around the caster
        if (age < 30) {
            float rise = age / 30f;
            for (int strand = 0; strand < 2; strand++) {
                double a = age * 0.45 + strand * Math.PI;
                double r = 1.1 - rise * 0.5;
                Flower f = FLOWERS.get((age / 3 + strand * 5) % FLOWERS.size());
                world.spawnParticles(new DustParticleEffect(f.color(), 1.1f),
                        spiralAt.x + Math.cos(a) * r, spiralAt.y + 0.1 + rise * 2.6, spiralAt.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
            if (age % 4 == 0) {
                world.spawnParticles(ParticleTypes.CHERRY_LEAVES, spiralAt.x, spiralAt.y + 2.4, spiralAt.z, 2, 0.4, 0.2, 0.4, 0);
            }
        }

        // 2) The melody, one note each time the wave moves on
        if (age % 3 == 0 && age / 3 < MELODY.length) {
            float pitch = (float) Math.pow(2.0, (MELODY[age / 3] - 12) / 12.0);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, pitch);
        }

        // 3) The wave of blossom
        int popped = 0;
        while (b.next < b.plantings.size() && b.plantings.get(b.next).tick() <= age) {
            if (plant(world, b.plantings.get(b.next), rand, popped < 2)) popped++;
            b.next++;
        }
        if (age <= b.waveTicks) {
            // A shimmer along the leading edge of the wave
            double r = Math.min(RADIUS, age * WAVE_SPEED);
            int points = (int) (r * 5) + 4;
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points + rand.nextDouble() * 0.3;
                world.spawnParticles(ParticleTypes.SPORE_BLOSSOM_AIR, c.x + Math.cos(a) * r, c.y + 0.3, c.z + Math.sin(a) * r, 1, 0.1, 0.1, 0.1, 0);
            }
        }

        // 4) Petals drifting down over everything
        int fall = age <= b.waveTicks ? 7 : Math.max(0, 7 - (age - b.waveTicks) / 7);
        for (int i = 0; i < fall; i++) {
            double a = rand.nextDouble() * Math.PI * 2, d = Math.sqrt(rand.nextDouble()) * RADIUS;
            world.spawnParticles(ParticleTypes.CHERRY_LEAVES, c.x + Math.cos(a) * d, c.y + 3.5 + rand.nextDouble() * 2.5,
                    c.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
        }
        if (rand.nextInt(2) == 0) {
            double a = rand.nextDouble() * Math.PI * 2, d = rand.nextDouble() * RADIUS;
            world.spawnParticles(ParticleTypes.SPORE_BLOSSOM_AIR, c.x + Math.cos(a) * d, c.y + 0.5 + rand.nextDouble() * 2,
                    c.z + Math.sin(a) * d, 1, 0.2, 0.2, 0.2, 0);
        }

        // 5) The last chord
        if (age == b.waveTicks) {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.2f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 0.6f, (float) Math.pow(2.0, (18 - 12) / 12.0));
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.PLAYERS, 0.6f, 1.3f);
            for (int i = 0; i < 40; i++) {
                double a = 2 * Math.PI * i / 40;
                world.spawnParticles(ParticleTypes.CHERRY_LEAVES, c.x + Math.cos(a) * RADIUS, c.y + 1.0, c.z + Math.sin(a) * RADIUS, 1, 0.2, 0.3, 0.2, 0);
            }
        }
        return age < b.waveTicks + LINGER_TICKS;
    }

    /** Grows one flower, with a burst of petals in its colour. Returns true if it made a sound. */
    private static boolean plant(ServerWorld world, Planting p, Random rand, boolean sound) {
        BlockPos pos = p.pos();
        BlockState here = world.getBlockState(pos);
        if (!(here.isAir() || here.isOf(Blocks.SHORT_GRASS) || here.isOf(Blocks.FERN))) return false;

        Flower f = p.flower();
        BlockState state = f.state();
        if (state.isOf(Blocks.PINK_PETALS)) {
            state = state.with(FlowerbedBlock.FLOWER_AMOUNT, 1 + rand.nextInt(4))
                    .with(FlowerbedBlock.FACING, Direction.Type.HORIZONTAL.random(rand));
        }
        if (!state.canPlaceAt(world, pos)) return false;
        if (f.tall()) {
            if (!world.getBlockState(pos.up()).isAir()) return false;
            TallPlantBlock.placeAt(world, state, pos, Block.NOTIFY_ALL);
        } else {
            world.setBlockState(pos, state, Block.NOTIFY_ALL);
        }

        double x = pos.getX() + 0.5, y = pos.getY() + 0.4, z = pos.getZ() + 0.5;
        world.spawnParticles(new DustParticleEffect(f.color(), 0.8f), x, y, z, 5, 0.25, 0.2, 0.25, 0);
        if (rand.nextInt(3) == 0) world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0.2, 0.2, 0.2, 0);
        if (rand.nextInt(4) == 0) world.spawnParticles(ParticleTypes.CHERRY_LEAVES, x, y + 0.4, z, 1, 0.2, 0.1, 0.2, 0);
        if (sound && rand.nextInt(2) == 0) {
            world.playSound(null, pos, SoundEvents.BLOCK_PINK_PETALS_PLACE, SoundCategory.BLOCKS, 0.5f, 0.9f + rand.nextFloat() * 0.4f);
            return true;
        }
        return false;
    }
}
