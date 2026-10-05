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
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;

/**
 * Photo shoot for the Tome of Upside Down: flips for real in a room with a patterned ceiling, walks along it, and
 * saves screenshots of the roll, standing on the ceiling, and the third-person view. Only runs with
 * -Dragnarsmagicmod.upsidesmoke=true (./gradlew runUpsideSmoke).
 */
public class UpsideDownClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.upsidesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "upside_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void cast(MinecraftClient client) {
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(UpsideDown.TOME_OF_UPSIDE_DOWN));
        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
                onServer(client, (world, sp) -> {
                    sp.getAbilities().invulnerable = true;
                    sp.sendAbilitiesUpdate();
                });
            }
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                // A long hall: grass floor, a checkered ceiling 5 up with lanterns hanging from it, glass walls
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-8, 0, -4), ORIGIN.add(8, 8, 24))) {
                    int dy = pos.getY() - ORIGIN.getY();
                    boolean wall = Math.abs(pos.getX() - ORIGIN.getX()) == 8;
                    var state = dy == 0 ? Blocks.GRASS_BLOCK.getDefaultState()
                            : dy == 6 ? ((pos.getX() + pos.getZ()) % 2 == 0 ? Blocks.QUARTZ_BLOCK : Blocks.POLISHED_DEEPSLATE).getDefaultState()
                            : dy < 6 && wall ? Blocks.GLASS.getDefaultState() : Blocks.AIR.getDefaultState();
                    world.setBlockState(pos, state);
                }
                for (int z = 2; z < 24; z += 5) world.setBlockState(ORIGIN.add(0, 5, z), Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
                world.setBlockState(ORIGIN.add(2, 1, 6), Blocks.OAK_LOG.getDefaultState());
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.GOLDEN_STAFF);
                ((StaffItem) ModItems.GOLDEN_STAFF).insertTome(staff, UpsideDown.TOME_OF_UPSIDE_DOWN);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                sp.setExperienceLevel(50);
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(10f);
            }
            case 72 -> shot(client, "0_before");
            case 75 -> cast(client);
            case 78 -> shot(client, "1a_rolling");
            case 82 -> shot(client, "1b_rolling");
            case 95 -> shot(client, "2a_on_ceiling");
            // Walk forward along the ceiling for a second
            case 100 -> client.options.forwardKey.setPressed(true);
            case 120 -> {
                client.options.forwardKey.setPressed(false);
                shot(client, "2b_walked");
            }
            case 125 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            case 130 -> shot(client, "3a_third_person");
            case 132 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 137 -> shot(client, "3b_third_person_front");
            case 140 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[UPSIDE SMOKE] flipped={} y={} onGround={}", UpsideDown.isFlipped(p), p.getY(), p.isOnGround());
            }
            case 145 -> cast(client);
            case 170 -> shot(client, "4_back_down");
            case 175 -> {
                RagnarsMagicMod.LOGGER.info("[UPSIDE SMOKE] done flipped={} y={}", UpsideDown.isFlipped(p), p.getY());
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
