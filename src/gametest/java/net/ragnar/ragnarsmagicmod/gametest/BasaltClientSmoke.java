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
import net.ragnar.ragnarsmagicmod.basalt.Basalt;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Basalt: casts it first person, then third person into a few husks, then from the side,
 * and saves screenshots of the gathering, the throw, the slam and the roll. Only runs with
 * -Dragnarsmagicmod.basaltsmoke=true (./gradlew runBasaltSmoke).
 */
public class BasaltClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.basaltsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "basalt_" + name + ".png", client.getFramebuffer(), t -> {});
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Basalt.TOME_OF_BASALT));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private static void husks(ServerWorld world) {
        for (int i = 0; i < 4; i++) {
            MobEntity h = EntityType.HUSK.create(world);
            h.refreshPositionAndAngles(-3.5 + i * 2.3, Y, 13.5 + (i % 2), 180f, 0f);
            h.setAiDisabled(true);
            world.spawnEntity(h);
        }
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
                world.setTimeOfDay(13000); // dusk, so the glow shows
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-30, 0, -24), ORIGIN.add(30, 12, 50))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(200);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Basalt.TOME_OF_BASALT);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // --- First person ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(-25f);
            }
            case 72 -> cast(client, p);
            case 75 -> shot(client, "1a_gather_fp");
            case 80 -> shot(client, "1b_gather_fp");
            case 85 -> shot(client, "1c_gather_fp");
            case 90 -> p.setPitch(12f);
            case 91 -> shot(client, "1d_gathered_fp");
            case 95 -> shot(client, "1e_thrown_fp");
            case 100 -> shot(client, "1f_flying_fp");
            case 106 -> shot(client, "1g_landed_fp");
            case 120 -> shot(client, "1h_rolling_fp");
            // --- Third person into husks ---
            case 170 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                husks(world);
            });
            case 175 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(8f);
            }
            case 177 -> cast(client, p);
            case 181 -> shot(client, "2a_gather_tp");
            case 187 -> shot(client, "2b_gather_tp");
            case 194 -> shot(client, "2c_gathered_tp");
            case 199 -> shot(client, "2d_flying_tp");
            case 203 -> shot(client, "2e_slam_tp");
            case 207 -> shot(client, "2f_slam_tp");
            case 215 -> shot(client, "2g_rolling_tp");
            case 230 -> shot(client, "2h_rolling_tp");
            // --- From the side ---
            case 280 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                husks(world);
            });
            case 284 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(8f);
            }
            case 286 -> cast(client, p);
            case 288 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 292 -> shot(client, "3a_gather_front");
            case 298 -> shot(client, "3b_gather_front");
            case 304 -> shot(client, "3c_gathered_front");
            case 330 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[BASALT SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
