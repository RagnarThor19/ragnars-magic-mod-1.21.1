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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.beaming.Beaming;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Beaming: holds right-click for real (a burst, a pause, then the rest of the charge),
 * and saves screenshots of the beam spooling up, burning a golem and a wall, heating up, and the bar cooling down.
 * Only runs with -Dragnarsmagicmod.beamsmoke=true (./gradlew runBeamSmoke).
 */
public class BeamingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.beamsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "beam_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-20, 0, -20), ORIGIN.add(20, 10, 30))) {
                    boolean wall = pos.getZ() == ORIGIN.getZ() + 16 && pos.getY() <= ORIGIN.getY() + 6;
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState()
                            : wall ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(LivingEntity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                MobEntity golem = EntityType.IRON_GOLEM.create(world);
                golem.refreshPositionAndAngles(0.5, Y, 8.5, 180f, 0f);
                golem.setAiDisabled(true);
                world.spawnEntity(golem);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Beaming.TOME_OF_BEAMING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(2f);
            }
            // --- First burst, into the golem ---
            case 75 -> client.options.useKey.setPressed(true);
            case 77 -> shot(client, "1a_spool");
            case 82 -> shot(client, "1b_golem");
            case 90 -> shot(client, "1c_golem");
            case 94 -> client.options.useKey.setPressed(false);
            case 97 -> shot(client, "2a_paused_bar");
            // --- Second burst: sweep onto the wall, side-on, until it overheats ---
            case 100 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(25f);
                p.setPitch(4f);
                client.options.useKey.setPressed(true);
            }
            case 110 -> shot(client, "3a_wall_tp");
            case 125 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(18f);
            }
            case 130 -> shot(client, "3b_wall_hot");
            case 148 -> shot(client, "3c_wall_hottest");
            case 160 -> {
                client.options.useKey.setPressed(false);
                shot(client, "4a_overheated");
            }
            case 200 -> shot(client, "4b_cooling");
            case 210 -> {
                RagnarsMagicMod.LOGGER.info("[BEAM SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
