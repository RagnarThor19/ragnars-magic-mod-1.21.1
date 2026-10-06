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
import net.ragnar.ragnarsmagicmod.boulders.Boulders;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tomes of Rocks and Boulders: throws a rock, then a boulder (first person, then third person into
 * a few husks), and saves screenshots of each stage. Only runs with -Dragnarsmagicmod.bouldersmoke=true
 * (./gradlew runBoulderSmoke).
 */
public class BoulderClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.bouldersmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "boulder_" + name + ".png", client.getFramebuffer(), t -> {});
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
            sp.getItemCooldownManager().remove(ModItems.TOME_OF_ROCKS);
            sp.getItemCooldownManager().remove(Boulders.TOME_OF_BOULDERS);
        });
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
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
                world.setTimeOfDay(5000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-24, 0, -24), ORIGIN.add(24, 10, 34))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(70), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack rocks = new ItemStack(ModItems.GOLDEN_STAFF);
                ((StaffItem) ModItems.GOLDEN_STAFF).insertTome(rocks, (TomeItem) ModItems.TOME_OF_ROCKS);
                ItemStack boulders = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(boulders, Boulders.TOME_OF_BOULDERS);
                sp.getInventory().setStack(0, rocks);
                sp.getInventory().setStack(1, boulders);
                sp.getInventory().selectedSlot = 0;
            });
            // --- Rock, from behind ---
            case 70 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(-4f);
            }
            case 72 -> cast(client, p);
            case 74 -> shot(client, "1a_rock");
            case 76 -> shot(client, "1b_rock");
            case 79 -> shot(client, "1c_rock");
            // --- Boulder, first person ---
            case 95 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 1;
                p.setPitch(8f);
            }
            case 97 -> cast(client, p);
            case 99 -> shot(client, "2a_forming_fp");
            case 103 -> shot(client, "2b_formed_fp");
            case 107 -> shot(client, "2c_thrown_fp");
            case 112 -> shot(client, "2d_flying_fp");
            case 118 -> shot(client, "2e_landed_fp");
            // --- Boulder, third person into husks ---
            case 150 -> onServer(client, (world, sp) -> {
                for (int i = 0; i < 4; i++) {
                    MobEntity h = EntityType.HUSK.create(world);
                    h.refreshPositionAndAngles(-3.5 + i * 2.3, Y, 13.5 + (i % 2), 180f, 0f);
                    world.spawnEntity(h);
                }
            });
            case 155 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(7f);
            }
            case 157 -> cast(client, p);
            case 160 -> shot(client, "3a_forming_tp");
            case 165 -> shot(client, "3b_launch_tp");
            case 166 -> p.setYaw(35f); // look off to the side so the player isn't in front of it
            case 168 -> shot(client, "3c_flying_tp");
            case 171 -> shot(client, "3d_slam_tp");
            case 174 -> shot(client, "3e_slam_tp");
            case 180 -> shot(client, "3f_rolling_tp");
            case 190 -> shot(client, "3g_rolling_tp");
            case 205 -> shot(client, "3h_after_tp");
            case 210 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[BOULDER SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
