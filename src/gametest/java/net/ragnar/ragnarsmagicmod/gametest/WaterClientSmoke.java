package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.water.WaterSpell;

import java.util.ArrayList;
import java.util.List;

/**
 * Tome of Water, played for real: two husks in the line of fire and a stone wall behind them. Casts, logs the husks'
 * health, and screenshots the ball in flight and its pop at the wall. Only runs with -Dragnarsmagicmod.watersmoke=true
 * (./gradlew runWaterSmoke).
 */
public class WaterClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 110, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int CAST = 100;
    private int tick = -1;
    private final List<HuskEntity> husks = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.watersmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[WATER SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "water_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        int since = tick - CAST;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
            }
            case 40 -> onServer(client, (world, sp) -> {
                world.getServer().setDifficulty(Difficulty.NORMAL, true);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-25, 0, -25), ORIGIN.add(25, 40, 25))) {
                    boolean wall = pos.getZ() == 9 && pos.getY() <= ORIGIN.getY() + 4 && Math.abs(pos.getX()) <= 5;
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState()
                            : wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getInventory().clear();
                sp.teleport(world, 0.5, Y, -4.5, 0f, 0f);
                sp.setHealth(sp.getMaxHealth());
                sp.getAbilities().invulnerable = true;
            });
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(0f);
            }
            case 80 -> onServer(client, (world, sp) -> {
                for (double z : new double[]{1.5, 5.5}) {
                    HuskEntity h = EntityType.HUSK.create(world);
                    h.refreshPositionAndAngles(0.5, Y, z, 180f, 0f);
                    h.setAiDisabled(true);
                    world.spawnEntity(h);
                    husks.add(h);
                }
            });
            case CAST -> onServer(client, (world, sp) -> {
                sp.setYaw(0f);
                sp.setPitch(0f);
                log("cast: {}", new WaterSpell().cast(world, sp, ItemStack.EMPTY));
                // Step off to the side to watch it fly (the ball goes on without you)
                sp.teleport(world, 7.5, Y + 0.5, 2.5, 90f, 8f);
            });
            case CAST + 1 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(90f);
                p.setPitch(8f);
            }
            case CAST + 80 -> {
                for (HuskEntity h : husks) log("husk health {} / {}", h.getHealth(), h.getMaxHealth());
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
        if (since == 2 || since == 6 || since == 12 || since == 18 || since == 24 || (since >= 28 && since <= 44 && since % 2 == 0)) {
            shot(client, String.format("t%02d", since));
        }
    }
}
