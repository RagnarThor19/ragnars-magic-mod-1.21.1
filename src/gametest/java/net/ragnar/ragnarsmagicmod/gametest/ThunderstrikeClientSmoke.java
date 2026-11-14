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
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of Thunderstrike, cast for real through the staff: at night, then by day, watched from
 * off to the side, with husks in and around the strike for the chains. Only runs with
 * -Dragnarsmagicmod.thundersmoke=true (./gradlew runThunderSmoke).
 */
public class ThunderstrikeClientSmoke implements ClientModInitializer {
    private static final int Y = 201;
    private static final double CX = 0.5, CZ = 14.5;
    private static final int NIGHT = 80, DAY = 300;
    private static final int[] SHOTS = {6, 20, 34, 42, 46, 48, 50, 51, 52, 54, 56, 59, 64, 75, 110};
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.thundersmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "thunder_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private void husks(ServerWorld world) {
        double[][] at = {{2, 1}, {-2.5, -1}, {0.5, 3}, {8, 2}, {-9, 0}, {12, 6}, {-8, 9}, {3, -10}, {-14, -4}};
        for (double[] a : at) {
            MobEntity h = EntityType.HUSK.create(world);
            h.refreshPositionAndAngles(CX + a[0], Y, CZ + a[1], world.getRandom().nextFloat() * 360f, 0f);
            h.setAiDisabled(true);
            world.spawnEntity(h);
        }
    }

    private void scene(MinecraftClient client, ClientPlayerEntity p, int t, String time) {
        switch (t) {
            case 0 -> onServer(client, (world, sp) -> {
                world.setTimeOfDay(time.equals("night") ? 18000 : 6000);
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                husks(world);
                sp.teleport(world, CX, Y, 0.5, 0f, 6.6f);
                sp.getItemCooldownManager().remove((TomeItem) ModItems.TOME_LIGHTNING_CASCADE);
            });
            case 4 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(6.6f);
            }
            case 8 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            // Off to the side, far enough back to take in the cloud and the ground together
            case 10 -> onServer(client, (world, sp) -> sp.teleport(world, CX + 34, Y + 3, CZ, 90f, -12f));
            case 12 -> {
                p.setYaw(90f);
                p.setPitch(-12f);
            }
            default -> {
                for (int s : SHOTS) if (t == 8 + s) shot(client, time + "_" + String.format("%03d", s));
            }
        }
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (tick == 1) {
            onServer(client, (world, sp) -> {
                sp.getAbilities().invulnerable = true;
                sp.sendAbilitiesUpdate();
            });
        } else if (tick == 40) {
            onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(new BlockPos(-50, Y - 1, -30), new BlockPos(50, Y + 40, 50))) {
                    world.setBlockState(pos, pos.getY() == Y - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                sp.setExperienceLevel(300);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, (TomeItem) ModItems.TOME_LIGHTNING_CASCADE);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
        } else if (tick >= NIGHT && tick < DAY) {
            scene(client, p, tick - NIGHT, "night");
        } else if (tick >= DAY && tick < DAY + 130) {
            scene(client, p, tick - DAY, "day");
        } else if (tick == DAY + 130) {
            RagnarsMagicMod.LOGGER.info("[THUNDER SMOKE] done");
            client.scheduleStop();
        }
    }
}
