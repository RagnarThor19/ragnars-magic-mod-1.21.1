package net.ragnar.ragnarsmagicmod.wizard;

import net.minecraft.entity.SpawnLocationTypes;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Sends Wandering Wizards out the same way the game sends wandering traders, only half as often. Once a day (24000
 * ticks) there's a 25% chance - rising to 50%, then 75%, on days with no visitor - that one sets off toward a random
 * player, and then only one time in {@link #ONE_IN} (the trader's is one in 10), so he's twice as rare. He turns up
 * within 48 blocks of that player with his horse, and leaves after two days. Overworld only, and only while
 * {@code doTraderSpawning} and {@code doMobSpawning} are on.
 */
public final class WizardSpawner {
    private WizardSpawner() {}

    public static final int ONE_IN = 20;
    private static final int DAY = 24000;
    private static final int CHECK_EVERY = 1200;
    private static final int RANGE = 48;
    /** How long he stays, as the trader: two days. */
    public static final int STAY = 48000;

    private static int timer = CHECK_EVERY;

    public static void tick(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD) return;
        if (--timer > 0) return;
        timer = CHECK_EVERY;
        if (!world.getGameRules().getBoolean(GameRules.DO_TRADER_SPAWNING) || !world.getGameRules().getBoolean(GameRules.DO_MOB_SPAWNING)) return;

        State state = State.of(world);
        state.delay -= CHECK_EVERY;
        state.markDirty();
        if (state.delay > 0) return;
        state.delay = DAY;
        int chance = state.chance;
        state.chance = MathHelper.clamp(state.chance + 25, 25, 75);
        if (world.random.nextInt(100) > chance) return;
        if (world.random.nextInt(ONE_IN) != 0) return;
        if (trySpawn(world) != null) state.chance = 25;
    }

    /** Sends one out toward a random player now; returns him, or null if there was nowhere for him to stand. */
    @Nullable
    public static WanderingWizardEntity trySpawn(ServerWorld world) {
        PlayerEntity player = world.getRandomAlivePlayer();
        if (player == null) return null;
        BlockPos pos = findSpot(world, player.getBlockPos());
        if (pos == null) return null;
        WanderingWizardEntity wizard = WanderingWizard.WANDERING_WIZARD.spawn(world, pos, SpawnReason.EVENT);
        if (wizard == null) return null;
        wizard.setDespawnDelay(STAY);
        wizard.setWanderTarget(player.getBlockPos());
        wizard.setPositionTarget(pos, 16);
        return wizard;
    }

    @Nullable
    private static BlockPos findSpot(ServerWorld world, BlockPos around) {
        for (int i = 0; i < 10; i++) {
            int x = around.getX() + world.random.nextInt(RANGE * 2) - RANGE;
            int z = around.getZ() + world.random.nextInt(RANGE * 2) - RANGE;
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (SpawnLocationTypes.ON_GROUND.isSpawnPositionOk(world, pos, WanderingWizard.WANDERING_WIZARD) && roomFor(world, pos)) {
                return pos;
            }
        }
        return null;
    }

    /** Room for him (and a horse beside him) to stand. */
    private static boolean roomFor(ServerWorld world, BlockPos pos) {
        for (BlockPos p : BlockPos.iterate(pos, pos.add(1, 2, 1))) {
            if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) return false;
        }
        return true;
    }

    /** When the next one may come, and how likely, kept with the world. */
    static final class State extends PersistentState {
        int delay = DAY;
        int chance = 25;

        static final Type<State> TYPE = new Type<>(State::new, State::read, null);

        static State of(ServerWorld world) {
            return world.getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_wandering_wizard");
        }

        static State read(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State s = new State();
            s.delay = nbt.getInt("Delay");
            s.chance = nbt.getInt("Chance");
            return s;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            nbt.putInt("Delay", delay);
            nbt.putInt("Chance", chance);
            return nbt;
        }
    }
}
