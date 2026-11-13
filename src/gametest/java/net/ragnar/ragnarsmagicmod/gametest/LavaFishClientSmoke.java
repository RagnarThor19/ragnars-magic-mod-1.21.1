package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.lavafish.LavaFish;
import net.ragnar.ragnarsmagicmod.lavafish.LavaFishEntity;

/**
 * Photo shoot for the lava fish: one flopping on land, one swimming in water and one in lava, side by side at night
 * so the glow shows, then by day. Only runs with -Dragnarsmagicmod.lavafishsmoke=true (./gradlew runLavaFishSmoke).
 */
public class LavaFishClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.lavafishsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "lavafish_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private static void fish(ServerWorld world, double x, double y, double z) {
        LavaFishEntity f = LavaFish.LAVA_FISH.create(world);
        f.refreshPositionAndAngles(x, y, z, 90f, 0f);
        world.spawnEntity(f);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> onServer(client, (world, sp) -> {
                sp.getAbilities().invulnerable = true;
                sp.sendAbilitiesUpdate();
            });
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(18000); // midnight, so the cracks glow
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-12, -3, -6), ORIGIN.add(12, 45, 14))) {
                    world.setBlockState(pos, pos.getY() < ORIGIN.getY() ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                // Left: a pool of water; middle: bare stone; right: a pool of lava (both pools sunk into the floor)
                for (int x = -6; x <= -3; x++) for (int z = 3; z <= 6; z++) for (int y = -2; y <= -1; y++) world.setBlockState(ORIGIN.add(x, y, z), Blocks.WATER.getDefaultState());
                for (int x = 3; x <= 6; x++) for (int z = 3; z <= 6; z++) for (int y = -2; y <= -1; y++) world.setBlockState(ORIGIN.add(x, y, z), Blocks.LAVA.getDefaultState());
                sp.teleport(world, 0.5, Y + 2.5, -1.5, 0f, 40f);
            });
            case 60 -> onServer(client, (world, sp) -> {
                fish(world, -4.5, Y - 1.2, 4.5);   // water
                fish(world, 0.5, Y, 3.5);          // land
                fish(world, 4.5, Y - 0.8, 4.5);    // lava
            });
            case 64 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(40f);
            }
            case 75 -> shot(client, "1_night_all_three");
            case 90 -> shot(client, "2_night_flopping");
            // Up close to the one on land
            case 95 -> onServer(client, (world, sp) -> sp.teleport(world, 0.5, Y + 0.3, 1.6, 0f, 35f));
            case 97 -> {
                p.setYaw(0f);
                p.setPitch(35f);
            }
            case 104 -> shot(client, "3_night_close");
            case 110 -> onServer(client, (world, sp) -> {
                world.setTimeOfDay(6000);
                sp.teleport(world, 0.5, Y + 2.5, -1.5, 0f, 40f);
            });
            case 112 -> {
                p.setYaw(0f);
                p.setPitch(40f);
            }
            case 122 -> shot(client, "4_day_all_three");
            case 130 -> {
                RagnarsMagicMod.LOGGER.info("[LAVA FISH SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
