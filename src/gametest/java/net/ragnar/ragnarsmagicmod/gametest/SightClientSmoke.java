package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.sight.Sight;

/**
 * Photo shoot for the Tome of Sight: casts it for real inside a block of stone salted with ore veins and saves
 * screenshots of the wave going out, the ores popping in and the outlines holding. Only runs with
 * -Dragnarsmagicmod.sightsmoke=true (./gradlew runSightSmoke).
 */
public class SightClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 150, 0);
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.sightsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "sight_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private static void vein(ServerWorld world, int x, int y, int z, Block ore, int size) {
        for (int i = 0; i < size; i++) {
            world.setBlockState(ORIGIN.add(x + (i % 2), y + (i / 4), z + ((i / 2) % 2)), ore.getDefaultState());
        }
    }

    private void cast(MinecraftClient client) {
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Sight.TOME_OF_SIGHT));
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
                sp.teleport(world, 0.5, ORIGIN.getY(), 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                // A solid block of stone with a little room in the middle
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-14, -14, -14), ORIGIN.add(14, 14, 14))) {
                    int dx = pos.getX() - ORIGIN.getX(), dy = pos.getY() - ORIGIN.getY(), dz = pos.getZ() - ORIGIN.getZ();
                    boolean room = Math.abs(dx) <= 3 && dy >= 0 && dy <= 3 && Math.abs(dz) <= 3;
                    world.setBlockState(pos, room ? Blocks.AIR.getDefaultState()
                            : dy < -4 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState());
                }
                vein(world, 2, 1, 6, Blocks.IRON_ORE, 6);
                vein(world, -6, 0, 4, Blocks.COAL_ORE, 8);
                vein(world, 5, -3, 2, Blocks.COPPER_ORE, 7);
                vein(world, -1, -7, 3, Blocks.DEEPSLATE_DIAMOND_ORE, 4);
                vein(world, 4, -6, -4, Blocks.DEEPSLATE_REDSTONE_ORE, 6);
                vein(world, -5, 2, -5, Blocks.GOLD_ORE, 3);
                vein(world, 1, 4, 7, Blocks.LAPIS_ORE, 5);
                vein(world, -8, -2, -1, Blocks.EMERALD_ORE, 1);
                vein(world, 0, 6, -6, Blocks.COAL_ORE, 6);
                vein(world, 12, 0, 0, Blocks.DIAMOND_ORE, 4); // out of reach
                sp.teleport(world, 0.5, ORIGIN.getY(), 0.5, 0f, 0f);
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                sp.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 20 * 60, 0, false, false));
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Sight.TOME_OF_SIGHT);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(20f);
                p.setPitch(25f);
            }
            case 72 -> cast(client);
            case 76 -> shot(client, "1a_wave_early");
            case 82 -> shot(client, "1b_wave_mid");
            case 92 -> shot(client, "1c_wave_late");
            case 105 -> shot(client, "1d_hold");
            case 110 -> {
                p.setYaw(200f);
                p.setPitch(10f);
            }
            case 115 -> shot(client, "2a_hold_behind");
            case 120 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(-30f);
                p.setPitch(35f);
            }
            case 125 -> shot(client, "3a_hold_third_person");
            case 140 -> cast(client);
            case 146 -> shot(client, "3b_tp_wave");
            case 150 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[SIGHT SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
