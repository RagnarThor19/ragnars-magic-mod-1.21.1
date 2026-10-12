package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Crows: casts at a spot 20 blocks ahead and photographs the whole journey from behind,
 * then casts again (back the other way) and photographs it from the front. Positions are logged and screenshots
 * saved. Only runs with -Dragnarsmagicmod.crowssmoke=true (./gradlew runCrowsSmoke).
 */
public class CrowsClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int CAST = 72, CAST2 = 260;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.crowssmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "crows_" + name + ".png", client.getFramebuffer(), t -> {});
        ClientPlayerEntity p = client.player;
        RagnarsMagicMod.LOGGER.info("[CROWS SMOKE] {}: pos {} {} {}, hidden {}", name,
                String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()), Crows.isHidden(p));
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
        int a = tick - CAST, b = tick - CAST2;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
            }
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(11500); // late afternoon
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-30, 0, -20), ORIGIN.add(30, 14, 50))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A dead tree and a few stones by where the crows set you down
                for (int y = 1; y <= 5; y++) world.setBlockState(ORIGIN.add(4, y, 22), Blocks.DARK_OAK_LOG.getDefaultState());
                world.setBlockState(ORIGIN.add(5, 4, 22), Blocks.DARK_OAK_LOG.getDefaultState());
                world.setBlockState(ORIGIN.add(3, 5, 22), Blocks.DARK_OAK_LOG.getDefaultState());
                world.setBlockState(ORIGIN.add(-3, 1, 19), Blocks.COBBLESTONE.getDefaultState());
                world.setBlockState(ORIGIN.add(-4, 1, 20), Blocks.MOSSY_COBBLESTONE.getDefaultState());
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setHealth(sp.getMaxHealth());
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Crows.TOME_OF_CROWS);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                sp.getItemCooldownManager().remove(Crows.TOME_OF_CROWS);
            });
            case 66 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(4.6f); // the ground 20 blocks ahead
            }
            case CAST -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case CAST2 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            default -> {
            }
        }
        // First journey: from behind, as the caster sees it
        switch (a) {
            case 8 -> shot(client, "1a_first_crows");
            case 22 -> shot(client, "1b_gathering");
            case 36 -> shot(client, "1c_spiralling_in");
            case 46 -> shot(client, "1d_closing");
            case 53 -> shot(client, "1e_column");
            case 59 -> shot(client, "1f_burst");
            case 63 -> shot(client, "1g_flying_over");
            case 70 -> shot(client, "1h_far_column");
            case 78 -> shot(client, "1i_far_column_peak");
            case 83 -> shot(client, "1j_scatter");
            case 90 -> shot(client, "1k_after");
            case 105 -> shot(client, "1l_feathers");
            case 120 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Crows.TOME_OF_CROWS));
                p.setYaw(180f);
                p.setPitch(4.6f);
            }
            default -> {
            }
        }
        // Second journey: from the front
        if (b == 2) client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        switch (b) {
            case 30 -> shot(client, "2a_front_circling");
            case 44 -> shot(client, "2b_front_closing");
            case 50 -> shot(client, "2c_front_engulfed");
            case 55 -> shot(client, "2d_front_gone");
            case 60 -> shot(client, "2e_front_burst");
            case 79 -> shot(client, "2f_front_arrive_column");
            case 84 -> shot(client, "2g_front_arrived");
            case 120 -> {
                RagnarsMagicMod.LOGGER.info("[CROWS SMOKE] done, cooling down {}", p.getItemCooldownManager().isCoolingDown(Crows.TOME_OF_CROWS));
                client.scheduleStop();
            }
            default -> {
            }
        }
    }
}
