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
import net.ragnar.ragnarsmagicmod.tornado.Tornado;
import net.ragnar.ragnarsmagicmod.tornado.TornadoEntity;

/**
 * Photo shoot for the Tome of Tornados: casts at a field with a few animals in it and photographs the tornado through
 * its life, first as the caster sees it and then from the side. Only runs with -Dragnarsmagicmod.tornadosmoke=true
 * (./gradlew runTornadoSmoke).
 */
public class TornadoClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int CAST = 72, CAST2 = 300;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.tornadosmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "tornado_" + name + ".png", client.getFramebuffer(), t -> {});
        var tornados = client.world.getEntitiesByClass(TornadoEntity.class, client.player.getBoundingBox().expand(60), e -> true);
        RagnarsMagicMod.LOGGER.info("[TORNADO SMOKE] {}: {} tornado(s) {}", name, tornados.size(),
                tornados.isEmpty() ? "" : tornados.get(0).getPos() + " debris " + tornados.get(0).debris.size());
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private static void animals(ServerWorld world) {
        EntityType<?>[] kinds = {EntityType.PIG, EntityType.SHEEP, EntityType.ZOMBIE, EntityType.PIG, EntityType.CHICKEN};
        for (int i = 0; i < kinds.length; i++) {
            Entity e = kinds[i].create(world);
            e.refreshPositionAndAngles(-3 + i * 1.6, Y, 7 + (i % 2) * 1.5, 0f, 0f);
            if (e instanceof MobEntity m) m.setPersistent();
            world.spawnEntity(e);
        }
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
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-30, 0, -20), ORIGIN.add(30, 14, 50))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-6, 0, 12), ORIGIN.add(6, 0, 20))) {
                    world.setBlockState(pos, Blocks.SAND.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                world.getGameRules().get(GameRules.DO_MOB_GRIEFING).set(false, world.getServer());
                animals(world);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setHealth(sp.getMaxHealth());
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Tornado.TOME_OF_TORNADOS);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                sp.getItemCooldownManager().remove(Tornado.TOME_OF_TORNADOS);
            });
            case 66 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(-12f);
            }
            case CAST -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            default -> {
            }
        }
        // First tornado: as the caster sees it
        switch (a) {
            case 4 -> shot(client, "1a_spinning_up");
            case 14 -> shot(client, "1b_grown");
            case 30 -> shot(client, "1c_pulling");
            case 50 -> shot(client, "1d_lifting");
            case 70 -> shot(client, "1e_flinging");
            case 95 -> shot(client, "1f_wandering");
            case 125 -> shot(client, "1g_later");
            case 148 -> shot(client, "1h_fading");
            case 162 -> shot(client, "1i_gone");
            case 175 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                animals(world);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getItemCooldownManager().remove(Tornado.TOME_OF_TORNADOS);
            });
            case 190 -> {
                p.setYaw(0f);
                p.setPitch(-12f);
            }
            default -> {
            }
        }
        // Second tornado: cast, then step aside and look at it from the side
        if (tick == CAST2) client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        switch (b) {
            case 3 -> onServer(client, (world, sp) -> sp.teleport(world, -13.5, Y + 0.5, 8.5, -90f, -6f));
            case 8 -> {
                p.setYaw(-90f);
                p.setPitch(-6f);
            }
            case 20 -> shot(client, "2a_side");
            case 40 -> shot(client, "2b_side_pulling");
            case 60 -> shot(client, "2c_side_lifting");
            case 80 -> shot(client, "2d_side");
            case 100 -> shot(client, "2e_side");
            case 120 -> {
                RagnarsMagicMod.LOGGER.info("[TORNADO SMOKE] done");
                client.scheduleStop();
            }
            default -> {
            }
        }
    }
}
