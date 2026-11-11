package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.leaping.Leaping;
import net.ragnar.ragnarsmagicmod.leaping.client.LeapingClient;

/**
 * Tome of Leaping, played for real: a quick crouch-and-jump (should be a normal jump), crouch-walking (no bar), a full
 * wind-up straight up (15 blocks, no damage on landing) and a full wind-up forward. Logs heights, health and whether the
 * bar showed, and saves screenshots of the bar. Only runs with -Dragnarsmagicmod.leapsmoke=true (./gradlew runLeapSmoke).
 */
public class LeapingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private double startY, maxY, startZ;
    private boolean tracking, sawBar;
    private String run = "";

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.leapsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[LEAP SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "leap_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void begin(ClientPlayerEntity p, String name) {
        run = name;
        startY = p.getY();
        startZ = p.getZ();
        maxY = p.getY();
        tracking = true;
    }

    private void end(ClientPlayerEntity p) {
        log("{}: rose {} blocks, moved {} forward, health {}, on ground {}", run, String.format("%.2f", maxY - startY),
                String.format("%.2f", p.getZ() - startZ), p.getHealth(), p.isOnGround());
        tracking = false;
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (tracking) maxY = Math.max(maxY, p.getY());
        if (LeapingClient.showing()) sawBar = true;
        var sneak = client.options.sneakKey;
        var jump = client.options.jumpKey;
        var fwd = client.options.forwardKey;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
            }
            case 40 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-10, 0, -10), ORIGIN.add(10, 30, 40))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 10f);
                sp.setHealth(sp.getMaxHealth());
                sp.getInventory().clear();
                sp.getInventory().setStack(20, new ItemStack(Leaping.TOME_OF_LEAPING)); // tucked away in the backpack
                sp.setExperienceLevel(7);
            });
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(10f);
            }
            // 1. A quick crouch then jump: just a normal jump, no bar
            case 70 -> {
                sawBar = false;
                sneak.setPressed(true);
            }
            case 74 -> {
                begin(p, "quick crouch + jump");
                jump.setPressed(true);
            }
            case 76 -> jump.setPressed(false);
            case 78 -> sneak.setPressed(false);
            case 100 -> {
                end(p);
                log("bar showed during the quick crouch: {}", sawBar);
            }
            // 2. Crouch-walking charges too: the bar should show
            case 110 -> {
                sawBar = false;
                sneak.setPressed(true);
                fwd.setPressed(true);
            }
            case 140 -> shot(client, "0_crouch_walking");
            case 141 -> {
                fwd.setPressed(false);
                sneak.setPressed(false);
                log("bar showed while crouch-walking: {}, charged {}", sawBar, LeapingClient.showing());
            }
            case 150 -> onServer(client, (world, sp) -> sp.teleport(world, 0.5, Y, 0.5, 0f, 10f));
            // 3. Full wind-up, straight up
            case 160 -> sneak.setPressed(true);
            case 172 -> shot(client, "1_bar_start");
            case 185 -> shot(client, "2_bar_half");
            case 215 -> shot(client, "3_bar_full");
            case 216 -> {
                begin(p, "full leap up");
                jump.setPressed(true);
            }
            case 218 -> {
                jump.setPressed(false);
                sneak.setPressed(false);
            }
            case 219 -> p.setPitch(55f);
            case 221 -> shot(client, "4_cloud_ring");
            case 226 -> {
                shot(client, "4_rising");
                p.setPitch(10f);
            }
            case 300 -> end(p);
            // 4. Full wind-up, forward
            case 310 -> onServer(client, (world, sp) -> {
                sp.teleport(world, 0.5, Y, 0.5, 0f, 10f);
                sp.setHealth(sp.getMaxHealth());
            });
            case 320 -> {
                // Crouch-walking forward the whole time it charges
                sneak.setPressed(true);
                fwd.setPressed(true);
            }
            case 376 -> {
                begin(p, "full leap forward, charged while crouch-walking");
                jump.setPressed(true);
            }
            case 378 -> {
                jump.setPressed(false);
                sneak.setPressed(false);
            }
            case 384 -> shot(client, "5_forward");
            case 395 -> fwd.setPressed(false);
            case 460 -> end(p);
            // 5. Full leap forward off a 6 block pillar: lands 6 below where it started, so only those 6 blocks hurt
            case 465 -> onServer(client, (world, sp) -> {
                for (int i = 1; i <= 6; i++) world.setBlockState(ORIGIN.add(0, i, -6), Blocks.STONE.getDefaultState());
                sp.teleport(world, 0.5, Y + 6, -5.5, 0f, 10f);
                sp.setHealth(sp.getMaxHealth());
            });
            case 475 -> sneak.setPressed(true);
            case 531 -> {
                begin(p, "full leap off a 6 block pillar");
                fwd.setPressed(true);
                jump.setPressed(true);
            }
            case 533 -> {
                jump.setPressed(false);
                sneak.setPressed(false);
            }
            case 550 -> fwd.setPressed(false);
            case 620 -> {
                end(p);
                log("landed {} blocks below the start", String.format("%.2f", startY - p.getY()));
            }
            case 630 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
