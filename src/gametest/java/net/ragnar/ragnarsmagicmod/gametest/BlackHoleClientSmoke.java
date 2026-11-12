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
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoles;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Black Holes: casts it first person, then third person into a row of husks, then watches
 * one drift, collapse and burst from the side, saving screenshots all the way. Only runs with
 * -Dragnarsmagicmod.blackholesmoke=true (./gradlew runBlackHoleSmoke).
 */
public class BlackHoleClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.blackholesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "blackhole_" + name + ".png", client.getFramebuffer(), t -> {});
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(BlackHoles.TOME_OF_BLACK_HOLES));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private static void reset(ServerWorld world, ServerPlayerEntity sp) {
        world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(90), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
        sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
    }

    private static void husks(ServerWorld world) {
        for (int i = 0; i < 6; i++) {
            MobEntity h = EntityType.HUSK.create(world);
            h.refreshPositionAndAngles(-5.5 + i * 2.2, Y, 14.5 + (i % 3) * 2, 180f, 0f);
            h.setPersistent(); // with AI: a mob without it ignores velocity, and wouldn't be pulled
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
                // Grass, with patches of stone, dirt and gravel for the hole to tear up
                BlockState[] patches = {Blocks.STONE.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.COBBLESTONE.getDefaultState(),
                        Blocks.GRAVEL.getDefaultState(), Blocks.MOSSY_COBBLESTONE.getDefaultState()};
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-40, 0, -24), ORIGIN.add(40, 14, 70))) {
                    BlockState s = Blocks.AIR.getDefaultState();
                    if (pos.getY() == ORIGIN.getY()) {
                        int h = Math.floorMod(pos.getX() * 73856093 ^ pos.getZ() * 19349663, 100);
                        s = h < 22 ? patches[h % patches.length] : Blocks.GRASS_BLOCK.getDefaultState();
                    }
                    world.setBlockState(pos, s);
                }
                reset(world, sp);
                sp.setExperienceLevel(500);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, BlackHoles.TOME_OF_BLACK_HOLES);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // --- First person: forming right in front of you, then away it goes ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(2f);
            }
            case 72 -> cast(client, p);
            case 80 -> shot(client, "1a_form_fp");
            case 92 -> shot(client, "1b_form_fp");
            case 104 -> shot(client, "1c_form_fp");
            case 118 -> shot(client, "1d_formed_fp");
            case 140 -> shot(client, "1e_drift_fp");
            case 190 -> shot(client, "1f_drift_fp");
            // --- Third person, into a row of husks ---
            case 330 -> onServer(client, (world, sp) -> {
                reset(world, sp);
                husks(world);
            });
            case 335 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(6f);
            }
            case 340 -> cast(client, p);
            case 362 -> shot(client, "2a_form_tp");
            case 388 -> shot(client, "2b_formed_tp");
            case 400 -> shot(client, "2c_drift_tp");
            case 420 -> shot(client, "2d_drift_tp");
            case 450 -> shot(client, "2e_drift_tp");
            case 500 -> shot(client, "2f_drift_tp");
            case 536 -> shot(client, "2g_collapse_tp");
            case 551 -> shot(client, "2h_burst_tp");
            case 555 -> shot(client, "2i_burst_tp");
            case 562 -> shot(client, "2j_burst_tp");
            // --- From the side ---
            case 600 -> onServer(client, (world, sp) -> {
                reset(world, sp);
                husks(world);
            });
            case 604 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(2f);
            }
            case 606 -> cast(client, p);
            // Once it's loosed (it no longer follows the caster), step off to the side to watch
            case 660 -> onServer(client, (world, sp) -> sp.teleport(world, 15.5, Y + 2, 13.5, 90f, 8f));
            case 662 -> {
                p.setYaw(90f);
                p.setPitch(8f);
            }
            case 668 -> shot(client, "3a_side");
            case 690 -> shot(client, "3b_side");
            case 730 -> shot(client, "3c_side");
            case 760 -> {
                p.setYaw(60f);
            }
            case 770 -> shot(client, "3d_side");
            case 785 -> p.setYaw(40f);
            case 800 -> shot(client, "3e_collapse_side");
            case 815 -> shot(client, "3f_burst_side");
            case 819 -> shot(client, "3g_burst_side");
            case 870 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[BLACK HOLE SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
