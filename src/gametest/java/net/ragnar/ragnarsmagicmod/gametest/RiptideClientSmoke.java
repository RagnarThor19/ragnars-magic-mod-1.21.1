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
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.riptide.Riptide;

/**
 * Tome of Riptide, played for real through the staff: a launch up and away (logs how high and far it went and whether
 * the fall hurt), then one straight into a husk. Screenshots both. Only runs with -Dragnarsmagicmod.riptidesmoke=true
 * (./gradlew runRiptideSmoke).
 */
public class RiptideClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 110, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int CAST1 = 80, CAST2 = 200;
    private int tick = -1;
    private Vec3d start;
    private double top;
    private boolean landed;
    private HuskEntity husk;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.riptidesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[RIPTIDE SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "riptide_" + name + ".png", client.getFramebuffer(), t -> {});
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
        if (start != null && tick > CAST1 && tick < CAST2) {
            top = Math.max(top, p.getY() - start.y);
            if (!landed && tick > CAST1 + 5 && p.isOnGround()) {
                landed = true;
                log("first launch: {} blocks high at most, landed {} blocks away after {} ticks, health {} (fall hurt: {})",
                        String.format("%.1f", top), String.format("%.1f", Math.hypot(p.getX() - start.x, p.getZ() - start.z)),
                        tick - CAST1, p.getHealth(), p.getHealth() < p.getMaxHealth());
                shot(client, "1_landed");
            }
        }
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
            }
            case 40 -> onServer(client, (world, sp) -> {
                world.getServer().setDifficulty(Difficulty.NORMAL, true);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-40, 0, -40), ORIGIN.add(40, 40, 60))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, -20.5, 0f, -30f);
                sp.setHealth(sp.getMaxHealth());
                sp.getHungerManager().setFoodLevel(20);
                sp.setExperienceLevel(200);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Riptide.TOME_OF_RIPTIDE);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(-30f);
            }
            case CAST1 -> {
                start = p.getPos();
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                log("cast 1: riptiding {}, velocity {}", p.isUsingRiptide(), p.getVelocity());
            }
            case CAST1 + 2 -> {
                shot(client, "1a_launch");
                log("2 ticks on: riptiding {}, {} blocks up", p.isUsingRiptide(), String.format("%.1f", p.getY() - start.y));
            }
            case CAST1 + 5 -> shot(client, "1b_spin");
            case CAST1 + 10 -> shot(client, "1c_spin");
            case CAST1 + 18 -> shot(client, "1d_arc");
            case CAST2 - 30 -> onServer(client, (world, sp) -> {
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getItemCooldownManager().remove(Riptide.TOME_OF_RIPTIDE);
                husk = EntityType.HUSK.create(world);
                husk.refreshPositionAndAngles(0.5, Y, 7.5, 180f, 0f);
                husk.setAiDisabled(true);
                world.spawnEntity(husk);
            });
            case CAST2 - 10 -> {
                p.setYaw(0f);
                p.setPitch(0f);
            }
            case CAST2 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case CAST2 + 2 -> shot(client, "2a_charge");
            case CAST2 + 4 -> shot(client, "2b_hit");
            case CAST2 + 8 -> shot(client, "2c_after");
            case CAST2 + 60 -> {
                onServer(client, (world, sp) -> log("husk health {} / {}", husk.getHealth(), husk.getMaxHealth()));
                log("done");
            }
            case CAST2 + 65 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
