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
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPath;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of the Lightning Path: paints a zigzag over a hill and runs it, then whirls the camera in
 * circles while painting, then goes early with a second right-click - saving screenshots and logging where the
 * client and server each think the runner ended up. Only runs with -Dragnarsmagicmod.pathsmoke=true
 * (./gradlew runPathSmoke).
 */
public class LightningPathClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.pathsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "path_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void cast(MinecraftClient client, ClientPlayerEntity p) {
        onServer(client, (world, sp) -> {
            sp.getItemCooldownManager().remove(LightningPath.TOME_OF_THE_LIGHTNING_PATH);
        });
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private int castAt = -1000;
    private int mode;

    private void report(MinecraftClient client, String what) {
        ClientPlayerEntity p = client.player;
        Vec3d clientPos = p.getPos();
        onServer(client, (world, sp) -> RagnarsMagicMod.LOGGER.info("[PATH SMOKE] {}: client {} server {} gap {}",
                what, clientPos, sp.getPos(), clientPos.distanceTo(sp.getPos())));
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        int t = tick - castAt;
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
                world.setTimeOfDay(13000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-40, -2, -30), ORIGIN.add(40, 20, 90))) {
                    world.setBlockState(pos, pos.getY() < ORIGIN.getY() ? Blocks.DIRT.getDefaultState()
                            : pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A hill across the way, stepped up three blocks and down again
                for (int x = -12; x <= 12; x++) {
                    for (int z = 22; z <= 32; z++) {
                        int h = Math.min(3, Math.min(z - 21, 33 - z));
                        for (int y = 1; y <= h; y++) world.setBlockState(new BlockPos(x, Y - 1 + y, z), Blocks.GRASS_BLOCK.getDefaultState());
                    }
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(120), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, LightningPath.TOME_OF_THE_LIGHTNING_PATH);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                double[][] husks = {{-2, 10}, {4, 16}, {-5, 38}, {2, 44}, {6, 52}};
                for (double[] h : husks) {
                    MobEntity m = EntityType.HUSK.create(world);
                    m.setAiDisabled(true);
                    m.refreshPositionAndAngles(h[0] + 0.5, Y, h[1] + 0.5, 180f, 0f);
                    world.spawnEntity(m);
                }
            });
            // --- 1: a zigzag over the hill ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(30f);
            }
            case 72 -> {
                mode = 1;
                castAt = tick;
                cast(client, p);
            }
            case 82 -> shot(client, "1a_painting");
            case 92 -> shot(client, "1b_painting");
            case 99 -> {
                shot(client, "1c_painted");
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
            case 101 -> shot(client, "1d_painted_tp");
            case 104 -> shot(client, "1e_running");
            case 107 -> shot(client, "1f_running");
            case 105 -> shot(client, "1g2_arriving");
            case 106 -> shot(client, "1g3_arriving");
            case 108 -> shot(client, "1g4_arriving");
            case 111 -> shot(client, "1g_running");
            case 116 -> shot(client, "1h_arrived");
            case 120 -> shot(client, "1i_bolt");
            case 140 -> report(client, "zigzag run");
            // --- 2: whirling the camera round in circles ---
            case 160 -> onServer(client, (world, sp) -> {
                sp.teleport(world, 0.5, Y, 0.5, 0f, 30f);
                sp.getItemCooldownManager().remove(LightningPath.TOME_OF_THE_LIGHTNING_PATH);
            });
            case 165 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(30f);
            }
            case 167 -> {
                mode = 2;
                castAt = tick;
                cast(client, p);
            }
            case 190 -> shot(client, "2a_circles");
            case 230 -> report(client, "circles run");
            // --- 3: right-click early ---
            case 250 -> onServer(client, (world, sp) -> {
                sp.teleport(world, 0.5, Y, 0.5, 0f, 30f);
                sp.getItemCooldownManager().remove(LightningPath.TOME_OF_THE_LIGHTNING_PATH);
            });
            case 255 -> {
                p.setYaw(0f);
                p.setPitch(30f);
            }
            case 257 -> {
                mode = 3;
                castAt = tick;
                cast(client, p);
            }
            case 266 -> client.interactionManager.interactItem(p, net.minecraft.util.Hand.MAIN_HAND);
            case 269 -> shot(client, "3a_early");
            case 290 -> report(client, "early run");
            case 300 -> {
                RagnarsMagicMod.LOGGER.info("[PATH SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
        // Painting: sweep the crosshair out along the ground as each run wants
        if (t >= 1 && t <= 27) {
            if (mode == 1) {
                p.setPitch(Math.max(-8f, 60f - t * 2.8f));
                p.setYaw((float) Math.sin(t * 0.45) * 26f);
            } else if (mode == 2) {
                p.setPitch(40f);
                p.setYaw(t * 40f); // round and round
            } else if (mode == 3) {
                p.setPitch(Math.max(10f, 50f - t * 4f));
                p.setYaw(-20f);
            }
        }
    }
}
