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
import net.ragnar.ragnarsmagicmod.climbing.Climbing;
import net.ragnar.ragnarsmagicmod.climbing.client.ClimbingClient;
import net.ragnar.ragnarsmagicmod.gliding.Gliding;
import net.ragnar.ragnarsmagicmod.gliding.client.GlidingClient;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;
import net.ragnar.ragnarsmagicmod.leaping.Leaping;

/**
 * Tomes of Climbing and Gliding, played for real in survival with Jumping and Leaping carried too, to check they all
 * get along: climb a 12 block wall and over the top, hang and slide, glide off the top, hop along holding jump (must
 * never glide), triple jump into a glide, leap into a glide, and catch a long fall on a wall. Logs heights, distances,
 * health and states, and saves screenshots. Only runs with -Dragnarsmagicmod.climbglidesmoke=true
 * (./gradlew runClimbGlideSmoke).
 */
public class ClimbGlideClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    /** The wall: x -3..3, three blocks thick from z = 3, 12 high. */
    private static final int WALL_Z = 3, WALL_TOP = Y + 11;
    private int tick = -1;
    private double startY, maxY, minY, startZ;
    private boolean tracking, sawGlide, sawWall, stoodOnTop, wasGliding, wasOnWall;
    private float startHealth;
    private String run = "";

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.climbglidesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // Anything that hurts the player is logged, with what the server thought they were doing
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp) {
                log("DAMAGE {} from {} at y {} vy {} fallDistance {} onGround {} gliding {} climbing {} (client tick {})", amount,
                        source.getName(), f(sp.getY()), f(sp.getVelocity().y), sp.fallDistance, sp.isOnGround(),
                        Gliding.isGliding(sp), Climbing.isClimbing(sp), tick);
            }
            return true;
        });
        // When the server starts and stops counting a glide, and what it thinks the player is doing then
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                boolean g = Gliding.isGliding(sp);
                if (g != serverGliding) {
                    serverGliding = g;
                    log("  server glide {} at y {} onGround {} fallDistance {} (client tick {})", g ? "on" : "off",
                            f(sp.getY() - Y), sp.isOnGround(), sp.fallDistance, tick);
                }
            }
        });
    }

    private static boolean serverGliding;

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[CLIMB GLIDE SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "climbglide_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void place(MinecraftClient client, double x, double y, double z, float yaw) {
        onServer(client, (world, sp) -> {
            sp.teleport(world, x, y, z, yaw, 10f);
            sp.setVelocity(0, 0, 0);
            sp.fallDistance = 0f;
            sp.setHealth(sp.getMaxHealth());
        });
    }

    private void begin(ClientPlayerEntity p, String name) {
        run = name;
        startY = maxY = minY = p.getY();
        startZ = p.getZ();
        startHealth = p.getHealth();
        sawGlide = sawWall = false;
        tracking = true;
    }

    private void end(ClientPlayerEntity p) {
        log("{}: rose {}, lowest {}, ended {} blocks from the start height, moved {} along z, health {} -> {}, on ground {}, "
                        + "saw glide {}, saw wall {}", run, f(maxY - startY), f(minY - startY), f(p.getY() - startY),
                f(p.getZ() - startZ), startHealth, p.getHealth(), p.isOnGround(), sawGlide, sawWall);
        tracking = false;
    }

    private static String f(double d) {
        return String.format("%.2f", d);
    }

    private void release(MinecraftClient c) {
        c.options.jumpKey.setPressed(false);
        c.options.forwardKey.setPressed(false);
        c.options.sneakKey.setPressed(false);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (tracking) {
            maxY = Math.max(maxY, p.getY());
            minY = Math.min(minY, p.getY());
            sawGlide |= GlidingClient.gliding();
            sawWall |= ClimbingClient.onWall();
            if (p.isOnGround() && p.getY() >= WALL_TOP + 1 - 0.01 && Math.abs(p.getX()) < 3.5 && p.getZ() > WALL_Z && p.getZ() < WALL_Z + 3) {
                stoodOnTop = true;
            }
            if (GlidingClient.gliding() != wasGliding) {
                wasGliding = GlidingClient.gliding();
                log("  glide {} at y {} vy {}", wasGliding ? "on" : "off", f(p.getY() - startY), f(p.getVelocity().y));
            }
            if (ClimbingClient.onWall() != wasOnWall) {
                wasOnWall = ClimbingClient.onWall();
                log("  wall {} at y {} vy {}", wasOnWall ? "grabbed" : "let go", f(p.getY() - startY), f(p.getVelocity().y));
            }
        }
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-12, 0, -12), ORIGIN.add(12, 50, 80))) {
                    boolean floor = pos.getY() == ORIGIN.getY();
                    boolean wall = pos.getZ() >= WALL_Z && pos.getZ() <= WALL_Z + 2 && Math.abs(pos.getX()) <= 3 && pos.getY() > ORIGIN.getY() && pos.getY() <= WALL_TOP;
                    // A 35 high pillar off to the side for catching a long fall
                    boolean pillar = pos.getZ() == WALL_Z && pos.getX() >= 7 && pos.getX() <= 9 && pos.getY() > ORIGIN.getY() && pos.getY() <= Y + 34;
                    world.setBlockState(pos, floor ? Blocks.GRASS_BLOCK.getDefaultState() : wall ? Blocks.STONE_BRICKS.getDefaultState()
                            : pillar ? Blocks.OAK_PLANKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getInventory().clear();
                // All four movement tomes at once, tucked away in the backpack
                sp.getInventory().setStack(20, new ItemStack(Climbing.TOME_OF_CLIMBING));
                sp.getInventory().setStack(21, new ItemStack(Gliding.TOME_OF_GLIDING));
                sp.getInventory().setStack(22, new ItemStack(Jumping.TOME_OF_JUMPING));
                sp.getInventory().setStack(23, new ItemStack(Leaping.TOME_OF_LEAPING));
                sp.setExperienceLevel(10);
            });
            case 50 -> place(client, 0.5, Y, 0.5, 0f);
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(10f);
            }

            // 1. Walk into the wall holding jump: climb all 12 blocks and over the top
            case 70 -> {
                begin(p, "climb the wall");
                fwd.setPressed(true);
                jump.setPressed(true);
            }
            case 100 -> shot(client, "1_climbing");
            case 200 -> {
                release(client);
                end(p);
                log("stood on top of the wall at some point: {}", stoodOnTop);
            }

            // 2. Climb a bit, hang on with crouch, then let go of crouch and slide down
            case 210 -> place(client, 0.5, Y, 0.5, 0f);
            case 220 -> {
                begin(p, "climb, hang, slide");
                fwd.setPressed(true);
                jump.setPressed(true);
            }
            case 255 -> {
                jump.setPressed(false);
                sneak.setPressed(true);
            }
            case 260 -> log("hanging at {} up", f(p.getY() - startY));
            case 280 -> {
                log("still hanging at {} up after a second", f(p.getY() - startY));
                shot(client, "2_hanging");
                sneak.setPressed(false);
            }
            case 340 -> {
                release(client);
                end(p);
            }

            // 3. Glide off the top of the wall
            case 350 -> place(client, 0.5, WALL_TOP + 1, WALL_Z + 0.5, 0f);
            case 360 -> {
                begin(p, "glide off the wall");
                fwd.setPressed(true);
                jump.setPressed(true);
            }
            case 380 -> shot(client, "3_gliding");
            case 520 -> {
                release(client);
                end(p);
            }

            // 4. Hop along flat ground holding jump: must never glide
            case 530 -> place(client, 0.5, Y, -8.5, 180f);
            case 540 -> {
                begin(p, "hop along holding jump");
                fwd.setPressed(true);
                jump.setPressed(true);
            }
            case 600 -> {
                release(client);
                end(p);
            }

            // 5. Triple jump, then keep holding jump to glide
            case 610 -> place(client, 0.5, Y, 6.5, 0f);
            case 620 -> {
                begin(p, "triple jump into a glide");
                fwd.setPressed(true);
                jump.setPressed(true);
            }
            case 622 -> jump.setPressed(false);
            case 628 -> jump.setPressed(true);
            case 630 -> jump.setPressed(false);
            case 636 -> jump.setPressed(true); // held from here on
            case 720 -> {
                release(client);
                end(p);
            }

            // 6. Full leap, holding jump on the way down
            case 730 -> place(client, 0.5, Y, 6.5, 0f);
            case 740 -> sneak.setPressed(true);
            case 785 -> {
                begin(p, "leap into a glide");
                jump.setPressed(true);
            }
            case 787 -> sneak.setPressed(false);
            case 790 -> fwd.setPressed(true);
            case 900 -> {
                release(client);
                end(p);
            }

            // 7. A 33 block fall beside the pillar, grabbing on with jump a few blocks down (falling fast by then)
            case 910 -> place(client, 8.5, Y + 33, WALL_Z - 0.7, 0f);
            case 912 -> {
                begin(p, "catch a long fall on the wall");
                fwd.setPressed(true);
            }
            case 924 -> jump.setPressed(true);
            case 926 -> shot(client, "4_catch");
            case 960 -> jump.setPressed(false); // slide the rest of the way
            case 1180 -> {
                release(client);
                end(p);
            }
            case 1190 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
