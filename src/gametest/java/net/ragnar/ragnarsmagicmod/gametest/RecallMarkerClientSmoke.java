package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of Recalling's mark, set for real through the staff: popping in, close up and from a few
 * steps away, by day and at night. Only runs with -Dragnarsmagicmod.recallsmoke=true (./gradlew runRecallSmoke).
 */
public class RecallMarkerClientSmoke implements ClientModInitializer {
    private static final int Y = 201;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.recallsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "recall_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private void view(MinecraftClient client, ClientPlayerEntity p, double x, double y, double z, float yaw, float pitch) {
        onServer(client, (world, sp) -> sp.teleport(world, x, y, z, yaw, pitch));
        p.setYaw(yaw);
        p.setPitch(pitch);
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
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(new BlockPos(-20, Y - 1, -20), new BlockPos(20, Y + 10, 20))) {
                    world.setBlockState(pos, pos.getY() == Y - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, (TomeItem) ModItems.TOME_RECALLING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> client.options.setPerspective(Perspective.FIRST_PERSON);
            case 55 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND); // set the mark at our feet
            case 57 -> view(client, p, 0.5, Y + 0.6, -1.6, 0f, 18f);
            case 59 -> shot(client, "1_popping_in");
            case 75 -> shot(client, "2_close_day");
            case 80 -> view(client, p, 0.5, Y + 1, -5.5, 0f, 12f);
            case 90 -> shot(client, "3_few_steps_day");
            case 95 -> onServer(client, (world, sp) -> world.setTimeOfDay(18000));
            case 100 -> view(client, p, 0.5, Y + 0.6, -1.6, 0f, 18f);
            case 115 -> shot(client, "4_close_night");
            case 120 -> view(client, p, 1.8, Y + 0.4, -0.9, 55f, 10f);
            case 134 -> shot(client, "5_low_angle_night");
            case 140 -> view(client, p, 0.5, Y + 1, -7.5, 0f, 8f);
            case 150 -> shot(client, "6_from_afar_night");
            case 160 -> {
                RagnarsMagicMod.LOGGER.info("[RECALL SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
