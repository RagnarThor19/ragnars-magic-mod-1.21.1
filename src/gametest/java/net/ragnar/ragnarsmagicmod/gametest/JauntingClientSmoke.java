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
import net.ragnar.ragnarsmagicmod.jaunting.Jaunting;
import net.ragnar.ragnarsmagicmod.jaunting.JauntingKunaiEntity;

import java.util.List;

/**
 * Photo shoot for the Tome of Jaunting: throws the kunai through the real right-click path into a wall and into a
 * cow, jaunts to it, and saves screenshots of each. Only runs with -Dragnarsmagicmod.jauntingsmoke=true
 * (./gradlew runJauntingSmoke).
 */
public class JauntingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private int failures;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.jauntingsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String msg) {
        RagnarsMagicMod.LOGGER.info("[JAUNTING SMOKE] " + msg);
    }

    private void check(boolean ok, String msg) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + msg);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "jaunting_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private List<JauntingKunaiEntity> kunais(MinecraftClient client) {
        return client.world.getEntitiesByClass(JauntingKunaiEntity.class, client.player.getBoundingBox().expand(60), k -> true);
    }

    private void look(MinecraftClient client, double x, double y, double z, float yaw, float pitch) {
        onServer(client, (world, sp) -> sp.teleport(world, x, y, z, yaw, pitch));
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
                world.setTimeOfDay(12800); // sunset: the glow reads best against a darker sky
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-22, 0, -22), ORIGIN.add(22, 10, 22))) {
                    boolean wall = pos.getZ() == ORIGIN.getZ() + 14 && pos.getY() <= ORIGIN.getY() + 5;
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState()
                            : wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(CowEntity.class, sp.getBoundingBox().expand(40), c -> true).forEach(c -> c.discard());
                world.getEntitiesByClass(JauntingKunaiEntity.class, sp.getBoundingBox().expand(60), k -> true).forEach(k -> k.discard());
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                sp.setExperienceLevel(30);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Jaunting.TOME_OF_JAUNTING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // --- Into the wall ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(-2f);
                check(kunais(client).isEmpty(), "no kunai out yet");
            }
            case 72 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND); // throw
            case 75 -> shot(client, "1_flight");
            case 82 -> {
                List<JauntingKunaiEntity> ks = kunais(client);
                check(ks.size() == 1 && !ks.get(0).isFlying(), "stuck in the wall (" + ks.size() + ")");
                if (!ks.isEmpty()) log("kunai at " + ks.get(0).getPos());
                look(client, 1.7, Y + 0.6, 12.6, 40f, -2f); // close, off to the side, looking at it
            }
            case 90 -> shot(client, "2_stuck_wall");
            case 91 -> look(client, -0.8, Y, 13.3, -90f, -12f); // dead side-on: blade in the wall, handle out
            case 99 -> shot(client, "2b_stuck_wall_side");
            case 100 -> onServer(client, (world, sp) -> {
                // Straight down on it: the flat copy of the sprite faces the camera, wall at the top of the screen
                sp.getAbilities().flying = true;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, 202.99 + 1.0 - 1.62, 13.2, 0f, 90f);
            });
            case 103 -> shot(client, "2c_stuck_wall_top");
            case 104 -> onServer(client, (world, sp) -> {
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 105 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 107 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND); // jaunt
            case 109 -> shot(client, "3_jaunt");
            case 113 -> shot(client, "4_jaunt_fade");
            case 116 -> {
                check(p.getZ() > 11 && p.getZ() < 14, "landed at the wall, z=" + p.getZ());
                check(kunais(client).isEmpty(), "kunai used up");
            }
            // --- Into a cow ---
            case 120 -> onServer(client, (world, sp) -> {
                CowEntity cow = EntityType.COW.create(world);
                cow.refreshPositionAndAngles(7.5, Y, 7.5, 90f, 0f);
                cow.setAiDisabled(true);
                world.spawnEntity(cow);
                sp.getItemCooldownManager().remove(Jaunting.TOME_OF_JAUNTING); // skip the throw's cooldown
                sp.teleport(world, 0.5, Y, 0.5, -45f, 6f);
            });
            case 126 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(-45f);
                p.setPitch(6f);
            }
            case 128 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND); // throw
            case 136 -> {
                List<JauntingKunaiEntity> ks = kunais(client);
                check(ks.size() == 1 && ks.get(0).host() != null, "stuck in the cow");
                look(client, 5.6, Y, 5.6, -45f, 18f); // from where it came in
            }
            case 144 -> shot(client, "5_in_cow");
            case 146 -> look(client, 9.1, Y, 5.4, 45f, 15f); // side on to the kunai
            case 154 -> shot(client, "6_in_cow_side");
            case 160 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("stats: " + (failures == 0 ? "ALL PASSED" : failures + " FAILED"));
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
