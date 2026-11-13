package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.gambling.Gambling;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Gambling: a doubled stack of diamonds (the whole spin, start to verdict), a lost stack
 * of gold, and a jackpot watched in third person so the sign over the head shows. Logs what's in hand after each.
 * Only runs with -Dragnarsmagicmod.gamblingsmoke=true (./gradlew runGamblingSmoke).
 */
public class GamblingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.gamblingsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "gambling_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void bet(MinecraftClient client, ItemStack stake, int slice) {
        onServer(client, (world, sp) -> {
            sp.getItemCooldownManager().remove(Gambling.TOME_OF_GAMBLING);
            sp.setStackInHand(Hand.OFF_HAND, stake);
            Gambling.forcedSlice = slice;
        });
    }

    private void report(MinecraftClient client, String what) {
        onServer(client, (world, sp) -> RagnarsMagicMod.LOGGER.info("[GAMBLING SMOKE] {}: off hand = {}, total diamonds = {}, gold = {}",
                what, sp.getOffHandStack(), sp.getInventory().count(Items.DIAMOND), sp.getInventory().count(Items.GOLD_INGOT)));
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
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
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(90), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-20, 0, -20), ORIGIN.add(20, 10, 20))) {
                    BlockState s = pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState();
                    if (pos.getY() > ORIGIN.getY() && pos.getY() < ORIGIN.getY() + 5 && pos.getZ() == 8 && Math.abs(pos.getX()) < 6) {
                        s = Blocks.OAK_PLANKS.getDefaultState();
                    }
                    world.setBlockState(pos, s);
                }
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Gambling.TOME_OF_GAMBLING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // --- A win: 32 diamonds, doubled ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(5f);
                bet(client, new ItemStack(Items.DIAMOND, 32), 4);
            }
            case 74 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 78 -> shot(client, "1a_pop");
            case 86 -> shot(client, "1b_fast");
            case 120 -> shot(client, "1c_slowing");
            case 150 -> shot(client, "1d_crawl");
            case 162 -> shot(client, "1e_crawl");
            case 168 -> shot(client, "1f_stop");
            case 176 -> shot(client, "1g_doubled");
            case 200 -> report(client, "after double");
            // --- A loss: 20 gold ---
            case 230 -> bet(client, new ItemStack(Items.GOLD_INGOT, 20), 9);
            case 234 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 330 -> shot(client, "2a_lost");
            case 340 -> shot(client, "2b_lost");
            case 370 -> report(client, "after loss");
            // --- The jackpot, in third person ---
            case 390 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                bet(client, new ItemStack(Items.DIAMOND, 16), 0);
            }
            case 394 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 420 -> shot(client, "3a_sign_spinning");
            case 490 -> shot(client, "3b_jackpot");
            case 500 -> shot(client, "3c_jackpot");
            case 530 -> report(client, "after jackpot");
            case 550 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[GAMBLING SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
