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
import net.ragnar.ragnarsmagicmod.mightypush.MightyPush;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of Mighty Pushing: casts it from the air over a field of husks with a wall to crush them
 * against, then again from the ground (with a look at the pose from the front), saving screenshots of the chant,
 * the release and the push. Only runs with -Dragnarsmagicmod.pushsmoke=true
 * (./gradlew runPushSmoke).
 */
public class MightyPushClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.pushsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "push_" + name + ".png", client.getFramebuffer(), t -> {});
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
            sp.getItemCooldownManager().remove(MightyPush.TOME_OF_MIGHTY_PUSHING);
        });
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private void husks(ServerWorld world) {
        for (int i = 0; i < 12; i++) {
            MobEntity h = EntityType.HUSK.create(world);
            double a = -0.9 + i * 0.16, d = 6 + (i * 7) % 17;
            h.refreshPositionAndAngles(0.5 + Math.sin(a) * d, Y, 0.5 + Math.cos(a) * d, 180f, 0f);
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
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-40, -2, -30), ORIGIN.add(40, 32, 50))) {
                    world.setBlockState(pos, pos.getY() < ORIGIN.getY() ? Blocks.DIRT.getDefaultState()
                            : pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A wall to be crushed against, and patches of stone and gravel to see the ground heave
                for (int x = -8; x <= 8; x++) for (int y = 1; y <= 3; y++) world.setBlockState(new BlockPos(x, Y - 1 + y, 22), Blocks.STONE_BRICKS.getDefaultState());
                for (int x = -14; x <= 14; x++) for (int z = 4; z <= 18; z++) {
                    if (Math.floorMod(x * 7 + z * 13, 11) == 0) world.setBlockState(new BlockPos(x, Y - 1, z), Blocks.COBBLESTONE.getDefaultState());
                    if (Math.floorMod(x * 5 + z * 3, 13) == 0) world.setBlockState(new BlockPos(x, Y - 1, z), Blocks.GRAVEL.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(90), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, MightyPush.TOME_OF_MIGHTY_PUSHING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                husks(world);
            });
            // --- From the air ---
            case 70 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(20f);
            }
            case 72 -> onServer(client, (world, sp) -> sp.teleport(world, 0.5, Y + 2.5, 0.5, 0f, 20f));
            case 74 -> cast(client, p);
            case 86 -> shot(client, "1a_rising");
            case 98 -> p.setPitch(32f);
            case 100 -> shot(client, "1b_feel_pain");
            case 114 -> shot(client, "1c_think_about_pain");
            case 128 -> shot(client, "1d_know_pain");
            case 142 -> shot(client, "1e_charged");
            case 147 -> shot(client, "2a_mighty_push");
            case 151 -> shot(client, "2b_push");
            case 157 -> shot(client, "2c_push");
            case 165 -> shot(client, "2d_push");
            case 175 -> shot(client, "2e_push");
            case 190 -> shot(client, "2f_push_end");
            case 215 -> shot(client, "2g_after");
            // --- From the ground ---
            case 240 -> onServer(client, (world, sp) -> {
                sp.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 10f);
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(90), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                husks(world);
            });
            case 250 -> {
                p.setYaw(0f);
                p.setPitch(10f);
            }
            case 252 -> cast(client, p);
            case 280 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 284 -> shot(client, "3a_pose_front");
            case 300 -> shot(client, "3b_pose_front_late");
            case 306 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            case 325 -> shot(client, "3c_ground_release");
            case 330 -> shot(client, "3d_ground_push");
            case 340 -> shot(client, "3e_ground_push");
            case 352 -> shot(client, "3f_ground_push");
            case 380 -> {
                RagnarsMagicMod.LOGGER.info("[PUSH SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
