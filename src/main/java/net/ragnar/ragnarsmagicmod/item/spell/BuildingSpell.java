package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.ragnar.ragnarsmagicmod.network.BuildingPayloads;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer;
import net.ragnar.ragnarsmagicmod.util.building.BuildPlanner;
import net.ragnar.ragnarsmagicmod.util.building.BuildSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tome of Building. Right-click builds the outlined shape out of blocks from your inventory. The shape, sizes and
 * block mix are chosen in the Building menu on the client, which sends them along with where you aimed; the
 * server checks everything again, then places the blocks over a few ticks, taking each one from your inventory as
 * it goes in. Only empty space (air, grass, water...) is ever built into.
 */
public class BuildingSpell implements Spell {
    /** Ticks a block spends growing in before it is really placed. */
    public static final int GROW_TICKS = 3;
    /** Even the biggest build finishes spreading out within this many ticks. */
    private static final int SPREAD_TICKS = 10;
    /** A request must arrive this recently before the cast to count. */
    private static final int REQUEST_MAX_AGE = 10;

    /** Set on the client: sends what the player is aiming at to the server when the staff is used. */
    public static Consumer<PlayerEntity> clientCast;

    private record Pending(BuildPlanner.Target target, BuildSettings settings, int tick) {}

    private record Step(BlockPos pos, BlockState state, Item item, int at) {}

    private static final class Job {
        final PlayerEntity player;
        final ServerWorld world;
        final List<Step> steps;
        int next;
        int age;

        Job(PlayerEntity player, ServerWorld world, List<Step> steps) {
            this.player = player;
            this.world = world;
            this.steps = steps;
        }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final List<Job> JOBS = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tickJobs());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PENDING.clear();
            JOBS.clear();
        });
    }

    /**
     * The XP is only charged on the server, where we know whether the build really went ahead. The client always
     * claims the click, so charging there too would show XP as spent when the server turned the build down.
     */
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return player.getWorld().isClient ? 0 : tomeCost;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) {
            if (clientCast != null) clientCast.accept(player);
            // Claim the click so a block in the other hand isn't placed as well
            return true;
        }
        if (!(world instanceof ServerWorld sw)) return false;

        Pending pending = PENDING.remove(player.getUuid());
        if (pending == null || sw.getServer().getTicks() - pending.tick() > REQUEST_MAX_AGE) {
            player.sendMessage(Text.literal("Aim at a block to build (up to " + (int) BuildPlanner.REACH + " away).")
                    .formatted(Formatting.GRAY), true);
            return false;
        }
        return build(sw, player, pending.target(), pending.settings());
    }

    /** The client said where it aimed and with which settings (client -> server, just before the cast). */
    public static void onRequest(ServerPlayerEntity player, BuildingPayloads.Request payload) {
        Direction face = Direction.byId(payload.face());
        Direction forward = Direction.byId(payload.forward());
        if (!forward.getAxis().isHorizontal()) forward = player.getHorizontalFacing();
        BuildSettings settings = BuildSettings.fromNbt(payload.settings());
        PENDING.put(player.getUuid(), new Pending(new BuildPlanner.Target(payload.anchor(), face, forward),
                settings, player.getServer() == null ? 0 : player.getServer().getTicks()));
    }

    /**
     * Checks and starts a build. Returns true if it started. Public so the game tests can drive it directly.
     */
    public static boolean build(ServerWorld world, PlayerEntity player, BuildPlanner.Target target, BuildSettings settings) {
        if (!player.getAbilities().allowModifyWorld) {
            player.sendMessage(Text.literal("You can't build here.").formatted(Formatting.RED), true);
            return false;
        }
        double reach = BuildPlanner.REACH + 4;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target.anchor())) > reach * reach) {
            player.sendMessage(Text.literal("Too far away to build.").formatted(Formatting.RED), true);
            return false;
        }

        BuildPlanner.Plan plan = BuildPlanner.plan(world, player, settings, target);
        List<BuildSettings.Entry> palette = plan.palette();
        if (palette.isEmpty()) {
            player.sendMessage(Text.literal("No blocks chosen: pick some in the Building menu.").formatted(Formatting.YELLOW), true);
            return false;
        }

        List<BuildPlanner.Cell> free = new ArrayList<>();
        for (BuildPlanner.Cell c : plan.free()) {
            if (world.isChunkLoaded(c.pos()) && world.canPlayerModifyAt(player, c.pos())) free.add(c);
        }
        if (free.isEmpty()) {
            player.sendMessage(Text.literal("No room to build there.").formatted(Formatting.GRAY), true);
            return false;
        }

        int n = free.size(), k = palette.size();
        boolean creative = player.isCreative();
        int[] available = null;
        if (!creative) {
            available = new int[k];
            int total = 0;
            for (int j = 0; j < k; j++) total += available[j] = BuildPlanner.count(player, palette.get(j).item);
            if (total < n) {
                player.sendMessage(Text.literal("Not enough blocks: need " + n + ", have " + total + ".")
                        .formatted(Formatting.RED), true);
                return false;
            }
        }

        float[] heights = new float[n];
        int[] parity = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = free.get(i).pos();
            heights[i] = free.get(i).height();
            parity[i] = p.getX() + p.getY() + p.getZ();
        }
        int[] weights = new int[k];
        BlockMixer.Layer[] layers = new BlockMixer.Layer[k];
        for (int j = 0; j < k; j++) {
            weights[j] = palette.get(j).weight;
            layers[j] = palette.get(j).layer;
        }
        int[] pick = BlockMixer.assign(heights, parity, settings.pattern, weights, layers, available,
                new Random(world.getRandom().nextLong()));
        if (pick == null) {
            player.sendMessage(Text.literal("Not enough blocks for that.").formatted(Formatting.RED), true);
            return false;
        }

        int spread = Math.min(SPREAD_TICKS, (n + 1) / 2);
        List<Step> steps = new ArrayList<>(n);
        List<BuildingPayloads.Ghost> ghosts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Item item = palette.get(pick[i]).item;
            BlockState state = ((BlockItem) item).getBlock().getDefaultState();
            int delay = n <= 1 ? 0 : i * spread / n;
            steps.add(new Step(free.get(i).pos(), state, item, delay + GROW_TICKS));
            ghosts.add(new BuildingPayloads.Ghost(free.get(i).pos(), Block.getRawIdFromState(state), delay));
        }
        JOBS.add(new Job(player, world, steps));

        // Everyone nearby sees the blocks grow in
        Set<ServerPlayerEntity> watchers = new LinkedHashSet<>(PlayerLookup.tracking(world, target.anchor()));
        if (player instanceof ServerPlayerEntity sp) watchers.add(sp);
        for (ServerPlayerEntity watcher : watchers) BuildingPayloads.sendAnimate(watcher, watcher == player, ghosts);

        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 0.6f, 1.5f + world.getRandom().nextFloat() * 0.2f);
        return true;
    }

    private static void tickJobs() {
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            PlayerEntity player = job.player;
            if (player.isRemoved() || !player.isAlive() || player.getWorld() != job.world) {
                it.remove();
                continue;
            }
            job.age++;
            BlockPos soundPos = null;
            BlockSoundGroup sound = null;
            while (job.next < job.steps.size() && job.steps.get(job.next).at() <= job.age) {
                Step step = job.steps.get(job.next++);
                // Something may have moved in since the cast: never build over it
                if (!BuildPlanner.isFree(job.world, step.pos()) || !job.world.canPlayerModifyAt(player, step.pos())) continue;
                BlockState state = step.state();
                if (state.contains(Properties.WATERLOGGED)) {
                    state = state.with(Properties.WATERLOGGED, job.world.getFluidState(step.pos()).getFluid() == Fluids.WATER);
                }
                if (!state.canPlaceAt(job.world, step.pos())) continue;
                if (!player.isCreative() && !BuildPlanner.takeOne(player, step.item())) {
                    player.sendMessage(Text.literal("Ran out of ").append(step.item().getName()).append(".")
                            .formatted(Formatting.RED), true);
                    job.next = job.steps.size();
                    break;
                }
                job.world.setBlockState(step.pos(), state, Block.NOTIFY_ALL);
                job.world.emitGameEvent(GameEvent.BLOCK_PLACE, step.pos(), GameEvent.Emitter.of(player, state));
                player.incrementStat(Stats.USED.getOrCreateStat(step.item()));
                if (soundPos == null) {
                    soundPos = step.pos();
                    sound = state.getSoundGroup();
                }
            }
            if (soundPos != null) {
                // One placement sound per tick, rising a little as the build goes up
                float progress = job.next / (float) job.steps.size();
                job.world.playSound(null, soundPos, sound.getPlaceSound(), SoundCategory.BLOCKS,
                        (sound.getVolume() + 1f) / 2f * 0.8f, sound.getPitch() * (0.8f + progress * 0.3f));
            }
            if (job.next >= job.steps.size()) it.remove();
        }
    }
}
