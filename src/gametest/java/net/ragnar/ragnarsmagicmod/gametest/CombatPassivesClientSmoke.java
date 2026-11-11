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
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.desperation.Desperation;
import net.ragnar.ragnarsmagicmod.groundpound.GroundPound;
import net.ragnar.ragnarsmagicmod.groundpound.client.GroundPoundClient;
import net.ragnar.ragnarsmagicmod.reflection.Reflection;
import net.ragnar.ragnarsmagicmod.sidestep.Sidestep;

import java.util.ArrayList;
import java.util.List;

/**
 * Tomes of Ground Pound, Sidestep, Desperation and Reflection, played for real in survival: pound down 15 blocks into a
 * ring of husks (and check a crouch during an ordinary jump never pounds), sidestep on the ground, in the air and while
 * crouching (it mustn't), drop to 2.5 hearts and look at the screen, and have 20 arrows shot at you. Logs the numbers
 * and saves screenshots. Only runs with -Dragnarsmagicmod.combatsmoke=true (./gradlew runCombatSmoke).
 */
public class CombatPassivesClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private Vec3d start;
    private float startHealth;
    private boolean sawPound, poundingNow;
    private int landedAt = -1;
    private boolean husksDone;
    private final java.util.Set<ArrowEntity> bounced = new java.util.HashSet<>();
    private final List<HuskEntity> husks = new ArrayList<>();
    private SkeletonEntity skeleton;
    private final List<ArrowEntity> arrows = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.combatsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[COMBAT SMOKE] " + s, a);
    }

    private static String f(double d) {
        return String.format("%.2f", d);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "combat_" + name + ".png", client.getFramebuffer(), t -> {});
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
            sp.teleport(world, x, y, z, yaw, 15f);
            sp.setVelocity(0, 0, 0);
            sp.fallDistance = 0f;
            sp.setHealth(sp.getMaxHealth());
        });
    }

    private void give(MinecraftClient client, ItemStack tome) {
        onServer(client, (world, sp) -> {
            sp.getInventory().clear();
            sp.getInventory().setStack(20, tome);
        });
    }

    private void tap(net.minecraft.client.option.KeyBinding key, int at, int now) {
        if (now == at) key.setPressed(true);
        if (now == at + 2) key.setPressed(false);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (GroundPoundClient.active()) { sawPound = true; poundingNow = true; }
        else if (poundingNow) { poundingNow = false; landedAt = tick; }
        if (landedAt > 0 && tick == landedAt + 8 && !husksDone) {
            StringBuilder s = new StringBuilder();
            for (HuskEntity h : husks) s.append(String.format("[%.1f hp, %.1f away] ", h.getHealth(), Math.sqrt(h.squaredDistanceTo(0.5, h.getY(), 0.5))));
            log("8 ticks after the pound landed: husks {}", s);
            shot(client, "1_pound_burst");
            // Gone before they can wear you down
            onServer(client, (world, sp) -> husks.forEach(Entity::discard));
            husksDone = true;
        }
        if (landedAt > 0 && tick == landedAt + 1) shot(client, "1_pound_land");
        for (ArrowEntity a : arrows) if (a.getVelocity().z < -0.1) bounced.add(a);
        var sneak = client.options.sneakKey;
        var jump = client.options.jumpKey;
        var right = client.options.rightKey;

        // Sidestep double taps
        tap(right, 420, tick);
        tap(right, 424, tick);
        tap(right, 482, tick);
        tap(right, 486, tick);
        tap(right, 540, tick);
        tap(right, 544, tick);

        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
            }
            case 40 -> onServer(client, (world, sp) -> {
                world.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-25, 0, -25), ORIGIN.add(25, 40, 25))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
            });
            case 55 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);

            // 1. A crouch at the top of an ordinary jump: never a pound
            case 60 -> {
                give(client, new ItemStack(GroundPound.TOME_OF_GROUND_POUND));
                place(client, 0.5, Y, 0.5, 0f);
            }
            case 75 -> {
                sawPound = false;
                jump.setPressed(true);
            }
            case 77 -> jump.setPressed(false);
            case 80 -> sneak.setPressed(true);
            case 82 -> sneak.setPressed(false);
            case 100 -> log("crouch during an ordinary jump pounded: {}", sawPound);

            // 2. Pound down 15 blocks into a ring of husks
            case 110 -> onServer(client, (world, sp) -> {
                for (int i = 0; i < 4; i++) {
                    double a = i * Math.PI / 2;
                    HuskEntity h = EntityType.HUSK.create(world);
                    h.refreshPositionAndAngles(0.5 + Math.cos(a) * 2.5, Y, 0.5 + Math.sin(a) * 2.5, 0f, 0f);
                    world.spawnEntity(h);
                    husks.add(h);
                }
                // One further out, beyond the reach of a 15 block pound
                HuskEntity far = EntityType.HUSK.create(world);
                far.refreshPositionAndAngles(0.5 + 9, Y, 0.5, 0f, 0f);
                far.setAiDisabled(true);
                world.spawnEntity(far);
                husks.add(far);
            });
            case 120 -> place(client, 0.5, Y + 15, 0.5, 0f);
            case 126 -> {
                startHealth = p.getHealth();
                sneak.setPressed(true);
            }
            case 128 -> sneak.setPressed(false);
            case 131 -> shot(client, "1_pound_hang");
            case 170 -> {
                StringBuilder s = new StringBuilder();
                for (HuskEntity h : husks) {
                    s.append(String.format("[%.1f hp, %.1f away] ", h.getHealth(), Math.sqrt(h.squaredDistanceTo(0.5, h.getY(), 0.5))));
                }
                log("pound from 15 blocks: health {} -> {} (a plain 15 block fall does 12); husks {}", startHealth, p.getHealth(), s);
            }

            // 3. Sidestep: on the ground, in the air, and (not) while crouching
            case 400 -> {
                give(client, new ItemStack(Sidestep.TOME_OF_SIDESTEP));
                onServer(client, (world, sp) -> husks.forEach(Entity::discard));
                place(client, 0.5, Y, 0.5, 0f);
            }
            case 415 -> start = p.getPos();
            case 423 -> shot(client, "2_sidestep");
            case 445 -> log("sidestep on the ground: moved {} sideways (x), {} forward (z)", f(p.getX() - start.x), f(p.getZ() - start.z));
            case 478 -> {
                start = p.getPos();
                jump.setPressed(true);
            }
            case 480 -> jump.setPressed(false);
            case 505 -> log("sidestep in the air: moved {} sideways (x)", f(p.getX() - start.x));
            case 535 -> {
                start = p.getPos();
                sneak.setPressed(true);
            }
            case 560 -> {
                sneak.setPressed(false);
                log("double tap while crouching: moved {} sideways (x) (only crouch-walking, no sidestep)", f(p.getX() - start.x));
            }

            // 4. Desperation at 2.5 hearts
            case 570 -> {
                give(client, new ItemStack(Desperation.TOME_OF_DESPERATION));
                place(client, 0.5, Y, 0.5, 0f);
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
            case 580 -> onServer(client, (world, sp) -> sp.setHealth(5f));
            case 600 -> log("at {} health: speed {} (attack damage is server-side only)", p.getHealth(),
                    f(p.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED)));
            case 601, 605, 610, 615 -> shot(client, "3_desperation_t" + tick);
            case 640 -> onServer(client, (world, sp) -> sp.setHealth(20f));
            case 660 -> log("healed to {}: speed {}", p.getHealth(),
                    f(p.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED)));

            // 5. Reflection: 20 arrows from a skeleton
            case 670 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                give(client, new ItemStack(Reflection.TOME_OF_REFLECTION));
                place(client, 0.5, Y, 0.5, 180f);
                onServer(client, (world, sp) -> {
                    skeleton = EntityType.SKELETON.create(world);
                    skeleton.refreshPositionAndAngles(0.5, Y, -7.5, 0f, 0f);
                    skeleton.setAiDisabled(true);
                    world.spawnEntity(skeleton);
                    sp.getAbilities().invulnerable = false;
                });
            }
            case 1000 -> {
                long back = bounced.size();
                log("arrows: {} of {} bounced back; skeleton health {} / {}", back, arrows.size(), f(skeleton.getHealth()), f(skeleton.getMaxHealth()));
            }
            case 1010 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
        // One arrow every 15 ticks, from the skeleton, straight at the player
        if (tick >= 690 && tick < 990 && (tick - 690) % 15 == 0) {
            onServer(client, (world, sp) -> {
                sp.setHealth(sp.getMaxHealth());
                ArrowEntity a = new ArrowEntity(EntityType.ARROW, world);
                a.setOwner(skeleton);
                a.refreshPositionAndAngles(0.5, Y + 1.4, -3.5, 0f, 0f);
                a.setVelocity(0, 0.02, 1.6);
                world.spawnEntity(a);
                arrows.add(a);
            });
        }
        if (tick == 702) shot(client, "4_reflection");
    }
}
