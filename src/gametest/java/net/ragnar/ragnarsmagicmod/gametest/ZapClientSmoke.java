package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
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
 * Photo shoot for the Tome of Zap: zaps a wall in first person and a cow in third person through the real
 * right-click path, and saves screenshots of the bolt racing out, holding and fading. Only runs with
 * -Dragnarsmagicmod.zapsmoke=true (./gradlew runZapSmoke).
 */
public class ZapClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.zapsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "zap_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    /** Zaps, then (if {@code turn} isn't 0) swings the camera that far round so the bolt is seen side-on. */
    private void zap(MinecraftClient client, float turn) {
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(ModItems.TOME_ZAP));
        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        if (turn != 0f) client.player.setYaw(client.player.getYaw() + turn);
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
                sp.getAbilities().allowFlying = true;
                sp.getAbilities().flying = true;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-22, 0, -22), ORIGIN.add(22, 10, 22))) {
                    boolean wall = pos.getZ() == ORIGIN.getZ() + 14 && pos.getY() <= ORIGIN.getY() + 5;
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState()
                            : wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(CowEntity.class, sp.getBoundingBox().expand(40), c -> true).forEach(c -> c.discard());
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, (TomeItem) ModItems.TOME_ZAP);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // --- First person, into the wall ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(10f);
                p.setPitch(4f);
            }
            case 72 -> zap(client, 0f);
            case 73 -> shot(client, "1a_fp_travel");
            case 74 -> shot(client, "1b_fp_hit");
            case 76 -> shot(client, "1c_fp_hold");
            case 79 -> shot(client, "1d_fp_fade");
            // --- Third person, into a cow ---
            case 90 -> onServer(client, (world, sp) -> {
                CowEntity cow = EntityType.COW.create(world);
                cow.refreshPositionAndAngles(-6.5, Y, 9.5, 90f, 0f);
                cow.setAiDisabled(true);
                world.spawnEntity(cow);
            });
            case 95 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(35f);
                p.setPitch(12f);
            }
            case 98 -> zap(client, 0f);
            case 99 -> shot(client, "2a_tp_travel");
            case 100 -> shot(client, "2b_tp_hit");
            case 102 -> shot(client, "2c_tp_hold");
            // --- Third person, long shot down the field ---
            case 110 -> {
                p.setYaw(-60f);
                p.setPitch(-4f);
            }
            case 112 -> zap(client, 0f);
            case 113 -> shot(client, "3a_long_travel");
            case 115 -> shot(client, "3b_long_hold");
            // --- Side-on: zap down the field, then look away from it ---
            case 118 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(-90f);
                p.setPitch(2f);
            }
            case 120 -> zap(client, 55f);
            case 121 -> shot(client, "4a_side_travel");
            case 122 -> shot(client, "4b_side_hit");
            case 124 -> shot(client, "4c_side_hold");
            case 127 -> shot(client, "4d_side_fade");
            case 132 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(35f);
                p.setPitch(12f);
            }
            case 134 -> zap(client, -50f);
            case 135 -> shot(client, "5a_cow_side_travel");
            case 136 -> shot(client, "5b_cow_side_hit");
            case 138 -> shot(client, "5c_cow_side_hold");
            case 145 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[ZAP SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
