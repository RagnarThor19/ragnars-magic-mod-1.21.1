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
import net.minecraft.entity.LivingEntity;
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
import net.ragnar.ragnarsmagicmod.shadowhands.ShadowHands;

/**
 * Photo shoot for the Tome of Unseen Hands: casts it for real into a field with a few mobs in it and saves
 * screenshots of the creep, the grab, the squeezing and the release. Only runs with
 * -Dragnarsmagicmod.shadowsmoke=true (./gradlew runShadowSmoke).
 */
public class ShadowHandsClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.shadowsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "shadow_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private static void mob(ServerWorld world, EntityType<? extends MobEntity> type, double x, double z, float yaw) {
        MobEntity m = type.create(world);
        m.refreshPositionAndAngles(x, Y, z, yaw, 0f);
        m.setAiDisabled(true);
        m.setPersistent();
        world.spawnEntity(m);
    }

    private void cast(MinecraftClient client) {
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(ShadowHands.TOME_OF_SHADOW_HANDS));
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
                sp.getAbilities().allowFlying = true;
                sp.getAbilities().flying = true;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(13000); // dusk
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-24, 0, -24), ORIGIN.add(24, 10, 34))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A few steps, so the shadow has something to climb
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(4, 1, 13), ORIGIN.add(7, 1, 16))) world.setBlockState(pos, Blocks.STONE_BRICKS.getDefaultState());
                world.getEntitiesByClass(LivingEntity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                mob(world, EntityType.ZOMBIE, -2.5, 9.5, 180f);
                mob(world, EntityType.IRON_GOLEM, 2.5, 13.5, 150f);
                mob(world, EntityType.COW, -5.5, 14.5, 120f);
                mob(world, EntityType.SKELETON, 5.5, 8.5, 200f);
                mob(world, EntityType.SPIDER, 0.5, 17.5, 180f);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                sp.setExperienceLevel(200);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, ShadowHands.TOME_OF_SHADOW_HANDS);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(13f); // the ground about 12 blocks out
            }
            case 72 -> cast(client);
            case 92 -> shot(client, "1a_creep_early");
            case 115 -> shot(client, "1b_creep_late");
            case 128 -> shot(client, "1c_creep_end");
            case 134 -> shot(client, "2a_burst");
            case 138 -> shot(client, "2b_grab");
            case 150 -> shot(client, "2c_squeeze");
            case 152 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setPitch(20f);
            }
            case 166 -> shot(client, "3a_squeeze_tp");
            case 182 -> shot(client, "3b_squeeze_tp");
            case 188 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setPitch(25f);
                p.setYaw(-15f);
            }
            case 192 -> shot(client, "3c_closeup");
            case 210 -> shot(client, "4a_release");
            case 225 -> shot(client, "4b_drain");
            case 235 -> {
                RagnarsMagicMod.LOGGER.info("[SHADOW SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
