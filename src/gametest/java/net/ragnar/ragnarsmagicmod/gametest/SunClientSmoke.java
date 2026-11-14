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
 * Photo shoot for the Tome of the Sun, cast for real through the staff: kindling in your hand, drifting off and
 * growing (from the side), close up, collapsing and going nova - at night, then by day.
 * Only runs with -Dragnarsmagicmod.sunsmoke=true (./gradlew runSunSmoke).
 */
public class SunClientSmoke implements ClientModInitializer {
    private static final int Y = 201;
    private static final int NIGHT = 80, DAY = 360, LEN = 260;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.sunsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "sun_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private void look(ClientPlayerEntity p, float yaw, float pitch) {
        p.setYaw(yaw);
        p.setPitch(pitch);
    }

    private void scene(MinecraftClient client, ClientPlayerEntity p, int t, String time) {
        // Cast at t = 8; ignition runs to 38; it drifts ~170 ticks, collapses for 10, then bursts
        switch (t) {
            case 0 -> onServer(client, (world, sp) -> {
                world.setTimeOfDay(time.equals("night") ? 18000 : 6000);
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                for (int i = 0; i < 4; i++) {
                    MobEntity h = EntityType.HUSK.create(world);
                    h.refreshPositionAndAngles(0.5 + (i % 2 == 0 ? -1.5 : 1.5), Y, 14.5 + i * 5, 180f, 0f);
                    h.setAiDisabled(true);
                    world.spawnEntity(h);
                }
                sp.teleport(world, 0.5, Y, 0.5, 0f, -4f);
                sp.getItemCooldownManager().remove((TomeItem) ModItems.TOME_SUN);
            });
            case 4 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                look(p, 0f, -4f);
            }
            case 8 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 22 -> shot(client, time + "_1_kindling");
            case 36 -> shot(client, time + "_2_kindled");
            // From the side of its path
            case 42 -> onServer(client, (world, sp) -> sp.teleport(world, 26.5, Y + 4, 20.5, 90f, -8f));
            case 44 -> look(p, 90f, -8f);
            case 60 -> shot(client, time + "_3_drifting");
            case 110 -> shot(client, time + "_4_growing");
            // Close up
            case 130 -> onServer(client, (world, sp) -> sp.teleport(world, 9.5, Y + 2.5, 22.5, 90f, 0f));
            case 132 -> look(p, 90f, 0f);
            case 140 -> shot(client, time + "_5_close");
            case 150 -> onServer(client, (world, sp) -> sp.teleport(world, 30.5, Y + 4, 28.5, 90f, -8f));
            case 152 -> look(p, 90f, -8f);
            case 190 -> shot(client, time + "_6_full_grown");
            case 214 -> shot(client, time + "_7_collapsing");
            case 220 -> shot(client, time + "_8_nova");
            case 222 -> shot(client, time + "_9_nova");
            case 225 -> shot(client, time + "_10_nova");
            case 229 -> shot(client, time + "_11_nova");
            case 245 -> shot(client, time + "_12_after");
            default -> {}
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
                for (BlockPos pos : BlockPos.iterate(new BlockPos(-30, Y - 1, -20), new BlockPos(45, Y + 30, 60))) {
                    world.setBlockState(pos, pos.getY() == Y - 1 ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                sp.setExperienceLevel(300);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, (TomeItem) ModItems.TOME_SUN);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
        } else if (tick >= NIGHT && tick < NIGHT + LEN) {
            scene(client, p, tick - NIGHT, "night");
        } else if (tick >= DAY && tick < DAY + LEN) {
            scene(client, p, tick - DAY, "day");
        } else if (tick == DAY + LEN) {
            RagnarsMagicMod.LOGGER.info("[SUN SMOKE] done");
            client.scheduleStop();
        }
    }
}
