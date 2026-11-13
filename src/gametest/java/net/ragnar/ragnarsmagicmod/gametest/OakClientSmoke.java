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
import net.minecraft.entity.mob.MobEntity;
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
import net.ragnar.ragnarsmagicmod.oak.Oak;

/**
 * Photo shoot for the Tome of Oak: casts it, watches the whole thing from off to the side (growing, leaning, falling,
 * landing, lying, crumbling), then casts again and watches from the caster's own eyes as it comes down over them.
 * Only runs with -Dragnarsmagicmod.oaksmoke=true (./gradlew runOakSmoke).
 */
public class OakClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int SIDE = 100, FP = 360; // when each part starts
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.oaksmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "oak_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void reset(MinecraftClient client) {
        onServer(client, (world, sp) -> {
            world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(110), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
            sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            sp.getItemCooldownManager().remove(Oak.TOME_OF_OAK);
            for (int i = 0; i < 5; i++) {
                MobEntity h = EntityType.HUSK.create(world);
                h.refreshPositionAndAngles(-2.5 - (i % 2) * 2.5, Y, 20.5 + i * 3.5, 180f, 0f);
                h.setAiDisabled(true);
                world.spawnEntity(h);
            }
        });
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        int s = tick - SIDE, f = tick - FP;
        if (tick == 1) {
            if (p.isDead()) p.requestRespawn();
            onServer(client, (world, sp) -> {
                sp.getAbilities().invulnerable = true;
                sp.sendAbilitiesUpdate();
            });
        } else if (tick == 40) {
            onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
        } else if (tick == 50) {
            onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(5000); // late morning
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-40, 0, -35), ORIGIN.add(72, 45, 62))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                sp.setExperienceLevel(300);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Oak.TOME_OF_OAK);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
        } else if (s >= 0 && s < FP - SIDE) {
            // --- The whole thing from off to the side ---
            switch (s) {
                case 0 -> reset(client);
                case 5 -> {
                    client.options.setPerspective(Perspective.FIRST_PERSON);
                    p.setYaw(0f);
                    p.setPitch(0f);
                }
                case 8 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                // Off to the east, looking west across the line it falls along
                case 10 -> onServer(client, (world, sp) -> sp.teleport(world, 66, Y + 12, 18, 90f, -10f));
                case 12 -> {
                    p.setYaw(90f);
                    p.setPitch(-10f);
                }
                case 20 -> shot(client, "1a_sprouting");
                case 30 -> shot(client, "1b_surging");
                case 42 -> shot(client, "1c_leaves");
                case 58 -> shot(client, "1d_grown");
                case 67 -> shot(client, "1e_leaning_back");
                case 82 -> shot(client, "1f_tipping");
                case 94 -> shot(client, "1g_falling");
                case 102 -> shot(client, "1h_falling");
                case 107 -> shot(client, "1i_falling");
                case 110 -> shot(client, "1j_falling");
                case 113 -> shot(client, "1k_impact");
                case 116 -> shot(client, "1l_impact");
                case 122 -> shot(client, "1m_bounce");
                case 132 -> shot(client, "1n_dust");
                case 165 -> shot(client, "1o_lying");
                // From behind and above, down the length of it
                case 170 -> onServer(client, (world, sp) -> sp.teleport(world, -2.5, Y + 22, -26, 0f, 30f));
                case 172 -> {
                    p.setYaw(0f);
                    p.setPitch(30f);
                }
                case 180 -> shot(client, "1r_lying_from_behind");
                case 200 -> shot(client, "1p_crumbling");
                case 210 -> shot(client, "1q_crumbling");
                default -> {}
            }
        } else if (f >= 0) {
            // --- Through the caster's eyes ---
            switch (f) {
                case 0 -> reset(client);
                case 5 -> {
                    client.options.setPerspective(Perspective.FIRST_PERSON);
                    p.setYaw(0f);
                    p.setPitch(0f);
                }
                case 8 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                // Turn round to watch it grow
                case 12 -> {
                    p.setYaw(160f);
                    p.setPitch(-35f);
                }
                case 30 -> shot(client, "2a_growing_behind");
                case 50 -> {
                    p.setYaw(165f);
                    p.setPitch(-60f);
                }
                case 58 -> shot(client, "2b_towering");
                // Face forward and look up as it comes over
                case 72 -> {
                    p.setYaw(0f);
                    p.setPitch(-45f);
                }
                case 92 -> shot(client, "2c_coming_over");
                case 102 -> shot(client, "2d_coming_over");
                case 106 -> p.setPitch(-5f);
                case 109 -> shot(client, "2e_coming_down");
                case 114 -> shot(client, "2f_impact");
                case 120 -> shot(client, "2g_after");
                case 135 -> shot(client, "2h_lying");
                case 138 -> {
                    client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                    p.setPitch(20f);
                }
                case 142 -> shot(client, "2i_lying_third_person");
                case 150 -> {
                    client.options.setPerspective(Perspective.FIRST_PERSON);
                    RagnarsMagicMod.LOGGER.info("[OAK SMOKE] done");
                    client.scheduleStop();
                }
                default -> {}
            }
        }
    }
}
