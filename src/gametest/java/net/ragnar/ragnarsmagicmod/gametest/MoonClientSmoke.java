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
import net.ragnar.ragnarsmagicmod.moon.Moon;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of the Moon: brings the moon down at dusk on a few husks and saves screenshots of it
 * forming, falling, lifting them, landing and the afterglow. Only runs with -Dragnarsmagicmod.moonsmoke=true
 * (./gradlew runMoonSmoke).
 */
public class MoonClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.moonsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "moon_" + name + ".png", client.getFramebuffer(), t -> {});
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
            sp.getItemCooldownManager().remove(Moon.TOME_OF_THE_MOON);
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
                world.setTimeOfDay(12700); // dusk
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-30, 0, -20), ORIGIN.add(30, 30, 50))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Moon.TOME_OF_THE_MOON);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                for (int i = 0; i < 6; i++) {
                    MobEntity h = EntityType.HUSK.create(world);
                    double a = i * Math.PI * 2 / 6;
                    h.refreshPositionAndAngles(0.5 + Math.cos(a) * 5, Y, 20.5 + Math.sin(a) * 5, 180f, 0f);
                    world.spawnEntity(h);
                }
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(4.6f); // the ground 20 blocks out
            }
            case 72 -> cast(client, p);
            case 76 -> p.setPitch(-32f);
            case 80 -> shot(client, "1a_forming");
            case 90 -> shot(client, "1b_formed");
            case 104 -> shot(client, "2a_falling");
            case 108 -> p.setPitch(-20f);
            case 118 -> shot(client, "2b_lifting");
            case 126 -> p.setPitch(-10f);
            case 132 -> shot(client, "2c_lifting");
            case 140 -> p.setPitch(-4f);
            case 144 -> shot(client, "2d_close");
            case 150 -> shot(client, "2e_closer");
            case 155 -> shot(client, "2f_almost");
            case 158 -> shot(client, "3a_impact");
            case 160 -> shot(client, "3b_impact");
            case 163 -> shot(client, "3c_pillar");
            case 168 -> shot(client, "3d_rings");
            case 180 -> shot(client, "4a_afterglow");
            case 205 -> shot(client, "4b_afterglow");
            case 230 -> shot(client, "4c_fading");
            case 236 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            case 238 -> {
                p.setPitch(5f);
                onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Moon.TOME_OF_THE_MOON));
            }
            case 240 -> client.interactionManager.interactItem(p, net.minecraft.util.Hand.MAIN_HAND);
            case 244 -> p.setPitch(-25f);
            case 275 -> shot(client, "5a_tp_falling");
            case 296 -> shot(client, "5b_tp_close");
            case 304 -> shot(client, "5c_tp_impact");
            case 310 -> {
                RagnarsMagicMod.LOGGER.info("[MOON SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
