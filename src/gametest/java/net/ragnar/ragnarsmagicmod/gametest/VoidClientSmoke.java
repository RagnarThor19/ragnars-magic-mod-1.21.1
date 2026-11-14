package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
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
 * Photo shoot for the Tome of the Void, cast for real through the staff onto bumpy grass, dirt and stone with pigs
 * standing in it: opening, open, draining, closing and the blast. Only runs with -Dragnarsmagicmod.voidsmoke=true.
 */
public class VoidClientSmoke implements ClientModInitializer {
    private static final int Y = 201;
    private int tick = -1, cast = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.voidsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "void_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (tick == 1) onServer(client, (world, sp) -> {
            sp.getAbilities().invulnerable = true;
            sp.sendAbilitiesUpdate();
        });
        if (tick == 40) onServer(client, (world, sp) -> {
            sp.changeGameMode(GameMode.SURVIVAL);
            world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
            world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
            world.setTimeOfDay(6000);
            world.resetWeather();
            for (BlockPos pos : BlockPos.iterate(new BlockPos(-24, Y - 4, -24), new BlockPos(24, Y + 12, 24))) {
                // Gently rolling ground of grass, a patch of stone and a patch of dirt
                int h = Y - 1 + (Math.abs(pos.getX() + 14) <= 1 && Math.abs(pos.getZ()) <= 1 ? 0 : (int) Math.round(Math.sin(pos.getX() * 0.35) + Math.cos(pos.getZ() * 0.3) * 0.8));
                var state = pos.getY() > h ? Blocks.AIR.getDefaultState()
                        : pos.getY() < h ? Blocks.DIRT.getDefaultState()
                        : (pos.getX() + 14) * (pos.getX() + 14) + pos.getZ() * pos.getZ() < 30 ? Blocks.STONE.getDefaultState()
                        : pos.getX() > 2 && pos.getZ() > 1 ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
                world.setBlockState(pos, state);
            }
            world.getEntitiesByType(EntityType.PIG, e -> true).forEach(e -> e.discard());
            for (int i = 0; i < 3; i++) {
                EntityType.PIG.spawn(world, new BlockPos(-15 + i * 2, Y + 1, -2 + i * 2), SpawnReason.COMMAND);
            }
            sp.setExperienceLevel(100);
            sp.getInventory().clear();
            ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
            ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, (TomeItem) ModItems.TOME_OF_THE_VOID);
            sp.getInventory().setStack(0, staff);
            sp.getInventory().selectedSlot = 0;
            // Standing well back, looking down at the spot 14 blocks west
            sp.teleport(world, 2.5, Y + 4, 0.5, 90f, 20f);
        });
        if (tick == 50) client.options.setPerspective(Perspective.FIRST_PERSON);
        if (tick == 60) {
            p.setYaw(90f);
            p.setPitch((float) Math.toDegrees(Math.atan2(4 + 1.62, 17)));
        }
        if (tick == 62) {
            client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            cast = tick;
        }
        if (cast < 0) return;
        int t = tick - cast;
        switch (t) {
            case 3 -> shot(client, "1_thread");
            case 12 -> shot(client, "2_opening");
            case 35 -> shot(client, "3_open");
            case 42 -> onServer(client, (world, sp) -> sp.teleport(world, -14.5 + 9, Y + 6, 0.5 + 6, 125f, 30f));
            case 44 -> { p.setYaw(125f); p.setPitch(30f); }
            case 61 -> shot(client, "4_drain_pulse");
            case 80 -> onServer(client, (world, sp) -> sp.teleport(world, -14.5 + 4, Y + 3, 0.5 - 6, 34f, 28f));
            case 82 -> { p.setYaw(34f); p.setPitch(28f); }
            case 95 -> shot(client, "5_close_up");
            case 100 -> onServer(client, (world, sp) -> sp.teleport(world, 2.5, Y + 5, 0.5, 90f, 22f));
            case 102 -> { p.setYaw(90f); p.setPitch(22f); }
            case 124 -> shot(client, "6_closing");
            case 129 -> shot(client, "7_blast");
            case 134 -> shot(client, "8_after");
            case 150 -> {
                RagnarsMagicMod.LOGGER.info("[VOID SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
