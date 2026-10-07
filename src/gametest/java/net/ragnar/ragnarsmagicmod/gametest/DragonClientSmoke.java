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
import net.ragnar.ragnarsmagicmod.dragon.Dragon;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of the Dragon: flies the dragon (through its eyes, then from behind and in front to see the
 * model), turns it, crashes it into some husks and saves screenshots, including the view snapping back home. Only runs with -Dragnarsmagicmod.dragonsmoke=true
 * (./gradlew runDragonSmoke).
 */
public class DragonClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.dragonsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "dragon_" + name + ".png", client.getFramebuffer(), t -> {});
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
            sp.getItemCooldownManager().remove(Dragon.TOME_OF_THE_DRAGON);
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-30, 0, -24), ORIGIN.add(30, 14, 50))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Dragon.TOME_OF_THE_DRAGON);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                for (int i = 0; i < 4; i++) {
                    MobEntity h = EntityType.HUSK.create(world);
                    h.setAiDisabled(true);
                    h.refreshPositionAndAngles(-2.5 + i * 1.6, Y, 26.5, 180f, 0f);
                    world.spawnEntity(h);
                }
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(-20f);
                p.setPitch(-12f);
            }
            case 72 -> cast(client, p);
            case 78 -> shot(client, "1a_pov");
            case 80 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            case 82 -> shot(client, "1b_behind");
            case 83 -> p.setYaw(p.getYaw() + 90f); // a quick look from the side
            case 84 -> shot(client, "1c_side");
            case 85 -> p.setYaw(p.getYaw() - 90f);

            case 104 -> shot(client, "2a_turning_pov");
            case 108 -> shot(client, "2b_turning_pov");
            case 117 -> shot(client, "2c_diving_pov");
            case 124 -> shot(client, "2d_pov");
            case 130 -> shot(client, "2e_pov");
            case 140 -> shot(client, "2f_pov");
            case 176 -> shot(client, "3_home");
            // A second flight, straight at the husks
            case 180 -> {
                p.setYaw(0f);
                p.setPitch(2f);
            }
            case 182 -> cast(client, p);
            case 196 -> shot(client, "4a_closing_pov");
            case 200 -> shot(client, "4b_pov");
            case 203 -> shot(client, "4c_pov");
            case 206 -> shot(client, "4d_pov");
            case 212 -> shot(client, "4e_home");
            case 220 -> shot(client, "4f_home");
            case 235 -> shot(client, "4g_lingering");
            case 255 -> shot(client, "4h_lingering");
            case 262 -> {
                RagnarsMagicMod.LOGGER.info("[DRAGON SMOKE] done, camera is player: {}", client.getCameraEntity() == p);
                client.scheduleStop();
            }
            default -> {
                // Swing round to the right, then bring it back round and down onto the husks
                if (tick > 88 && tick <= 100) p.setYaw(p.getYaw() + 4f);
                if (tick > 100 && tick <= 116) {
                    p.setYaw(p.getYaw() - 4f);
                    p.setPitch(Math.min(p.getPitch() + 1.6f, 14f));
                }
            }
        }
    }
}
