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
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.fireresistance.FireResistance;
import net.ragnar.ragnarsmagicmod.gourmet.Gourmet;
import net.ragnar.ragnarsmagicmod.grace.Grace;
import net.ragnar.ragnarsmagicmod.lifesteal.Lifesteal;
import net.ragnar.ragnarsmagicmod.swimming.Swimming;

/**
 * Tomes of Swimming, Fire Resistance, Grace, Lifesteal and the Gourmet, played for real in survival: sprint-swim a
 * channel with and without Swimming (and stay under long enough to need air), swim a lava channel, eat a steak, stand
 * near a creeper, and hit a zombie at half health. Logs distances, health, air, hunger and the creeper's distance, and
 * saves screenshots. Only runs with -Dragnarsmagicmod.survivalsmoke=true (./gradlew runSurvivalSmoke).
 */
public class SurvivalPassivesClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 150, 0);
    /** Channels 3 wide and 3 deep along +z: water at x 0, lava at x 6. */
    private static final int LEN = 60;
    private int tick = -1;
    private double startZ;
    private float startHealth;
    private CreeperEntity creeper;
    private ZombieEntity zombie;
    private int foodBefore;


    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.survivalsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[SURVIVAL SMOKE] " + s, a);
    }

    private static String f(double d) {
        return String.format("%.2f", d);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "survival_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void place(MinecraftClient client, double x, double y, double z, float yaw, float pitch) {
        onServer(client, (world, sp) -> {
            sp.teleport(world, x, y, z, yaw, pitch);
            sp.setVelocity(0, 0, 0);
            sp.fallDistance = 0f;
            sp.setHealth(sp.getMaxHealth());
            sp.setAir(sp.getMaxAir());
            sp.extinguish();
        });
    }

    private void give(MinecraftClient client, ItemStack... tomes) {
        onServer(client, (world, sp) -> {
            sp.getInventory().clear();
            for (int i = 0; i < tomes.length; i++) sp.getInventory().setStack(20 + i, tomes[i]);
        });
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        var fwd = client.options.forwardKey;
        var sprint = client.options.sprintKey;
        var jump = client.options.jumpKey;

        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
            }
            case 40 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-6, -4, -6), ORIGIN.add(14, 12, LEN + 6))) {
                    int x = pos.getX(), y = pos.getY() - ORIGIN.getY(), z = pos.getZ();
                    boolean inWater = x >= -1 && x <= 1 && y >= -3 && y <= -1 && z >= 0 && z <= LEN;
                    boolean inLava = x >= 5 && x <= 7 && y >= -3 && y <= -1 && z >= 0 && z <= LEN;
                    world.setBlockState(pos, inWater ? Blocks.WATER.getDefaultState() : inLava ? Blocks.LAVA.getDefaultState()
                            : y <= -1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(100), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getInventory().clear();
            });
            case 55 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);

            // 1. Sprint-swim along the water channel without the tome, then with it
            case 60 -> place(client, 0.5, ORIGIN.getY() - 2.5, 1.5, 0f, 0f);
            case 70 -> {
                startZ = p.getZ();
                fwd.setPressed(true);
                sprint.setPressed(true);
            }
            case 130 -> {
                fwd.setPressed(false);
                sprint.setPressed(false);
                log("water, no tome: {} blocks in 3 seconds, swimming {}", f(p.getZ() - startZ), p.isSwimming());
            }
            case 140 -> {
                give(client, new ItemStack(Swimming.TOME_OF_SWIMMING));
                place(client, 0.5, ORIGIN.getY() - 2.5, 1.5, 0f, 0f);
            }
            case 160 -> {
                startZ = p.getZ();
                fwd.setPressed(true);
                sprint.setPressed(true);
            }
            case 175 -> shot(client, "1_swimming");
            case 220 -> {
                fwd.setPressed(false);
                sprint.setPressed(false);
                log("water, with Swimming: {} blocks in 3 seconds, swimming {}", f(p.getZ() - startZ), p.isSwimming());
            }
            // Stay under for 20 seconds: plenty to drown without water breathing
            case 230 -> place(client, 0.5, ORIGIN.getY() - 2.5, 30.5, 0f, 0f);
            case 640 -> log("after 20 seconds underwater: air {}/{}, health {}, water breathing {}", p.getAir(), p.getMaxAir(),
                    p.getHealth(), p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.WATER_BREATHING));
            // Swim up faster: hold jump from the bottom
            case 650 -> place(client, 0.5, ORIGIN.getY() - 3, 40.5, 0f, 0f);
            case 660 -> {
                startZ = p.getY();
                jump.setPressed(true);
            }
            case 672 -> {
                jump.setPressed(false);
                log("swam up {} blocks in 12 ticks with Swimming", f(p.getY() - startZ));
            }

            // 2. Lava: without the tome would kill, so only with it
            case 680 -> {
                give(client, new ItemStack(FireResistance.TOME_OF_FIRE_RESISTANCE));
            }
            case 700 -> place(client, 6.5, ORIGIN.getY() - 2.5, 1.5, 0f, 0f);
            case 710 -> {
                startZ = p.getZ();
                startHealth = p.getHealth();
                fwd.setPressed(true);
                sprint.setPressed(true);
            }
            case 725 -> shot(client, "2_lava");
            case 770 -> {
                fwd.setPressed(false);
                sprint.setPressed(false);
                log("lava, with Fire Resistance: {} blocks in 3 seconds, health {} -> {}, fire resistance {}", f(p.getZ() - startZ),
                        startHealth, p.getHealth(), p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE));
            }
            case 775 -> {
                startZ = p.getY();
                jump.setPressed(true);
            }
            case 795 -> {
                jump.setPressed(false);
                log("swam up {} blocks in 20 ticks of lava", f(p.getY() - startZ));
            }
            case 800 -> place(client, 3.5, ORIGIN.getY(), 1.5, 0f, 0f);
            case 860 -> log("out of lava 3 seconds later: health {}, on fire {}", p.getHealth(), p.isOnFire());

            // 3. Gourmet: one tap of right-click on a steak
            case 870 -> {
                give(client, new ItemStack(Gourmet.TOME_OF_THE_GOURMET));
                onServer(client, (world, sp) -> {
                    sp.getInventory().setStack(0, new ItemStack(Items.COOKED_BEEF, 8));
                    sp.getInventory().selectedSlot = 0;
                    sp.getHungerManager().setFoodLevel(6);
                });
            }
            case 880 -> {
                p.getInventory().selectedSlot = 0;
                foodBefore = p.getHungerManager().getFoodLevel();
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 884 -> log("ate a steak: food {} -> {} within 4 ticks, steaks left {}, still using {}", foodBefore,
                    p.getHungerManager().getFoodLevel(), p.getMainHandStack().getCount(), p.isUsingItem());

            // 4. Grace: a creeper spawned 5 blocks away
            case 890 -> {
                give(client, new ItemStack(Grace.TOME_OF_GRACE));
                place(client, 3.5, ORIGIN.getY(), 20.5, 0f, 10f);
            }
            case 900 -> onServer(client, (world, sp) -> {
                creeper = EntityType.CREEPER.create(world);
                creeper.refreshPositionAndAngles(3.5, ORIGIN.getY(), 25.5, 180f, 0f);
                world.spawnEntity(creeper);
            });
            case 905 -> log("creeper starts {} blocks away", f(creeper.distanceTo(p)));
            case 930 -> shot(client, "3_grace");
            case 980 -> log("4 seconds later: creeper {} blocks away, target {}, fuse {}", f(creeper.distanceTo(p)),
                    creeper.getTarget(), creeper.getFuseSpeed());
            // Walk right up to it
            case 985 -> fwd.setPressed(true);
            case 1025 -> {
                fwd.setPressed(false);
                log("after walking at it: creeper {} blocks away, alive {}, health {}", f(creeper.distanceTo(p)), creeper.isAlive(), p.getHealth());
            }
            case 1030 -> onServer(client, (world, sp) -> creeper.discard());

            // 5. Lifesteal: half health, hit a zombie with a diamond sword
            case 1040 -> {
                give(client, new ItemStack(Lifesteal.TOME_OF_LIFESTEAL));
                place(client, 3.5, ORIGIN.getY(), 40.5, 0f, 10f);
                onServer(client, (world, sp) -> {
                    sp.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
                    sp.getInventory().selectedSlot = 0;
                    zombie = EntityType.ZOMBIE.create(world);
                    zombie.refreshPositionAndAngles(3.5, ORIGIN.getY(), 42.0, 180f, 0f);
                    zombie.setAiDisabled(true);
                    world.spawnEntity(zombie);
                });
            }
            case 1050 -> onServer(client, (world, sp) -> sp.setHealth(10f));
            case 1060 -> {
                startHealth = p.getHealth();
                p.getInventory().selectedSlot = 0;
                var target = client.world.getEntityById(zombie.getId());
                client.interactionManager.attackEntity(p, target);
                p.swingHand(Hand.MAIN_HAND);
            }
            case 1066 -> log("hit a zombie: health {} -> {}", startHealth, p.getHealth());
            case 1080 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);

                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
