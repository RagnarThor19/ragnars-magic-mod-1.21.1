package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.knight.Knight;
import net.ragnar.ragnarsmagicmod.knight.KnightEntity;

import java.util.List;

/**
 * Photo shoot for the Tome of Knight: summons it through the real right-click path, then freezes it and plays each
 * animation, saving screenshots at the key frames. Only runs with -Dragnarsmagicmod.knightsmoke=true
 * (./gradlew runKnightSmoke).
 */
public class KnightClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private int tick = -1;
    private int failures;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.knightsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String msg) {
        RagnarsMagicMod.LOGGER.info("[KNIGHT SMOKE] " + msg);
    }

    private void check(boolean ok, String msg) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + msg);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "knight_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private KnightEntity knight(MinecraftClient client) {
        List<KnightEntity> all = client.world.getEntitiesByClass(KnightEntity.class, client.player.getBoundingBox().expand(40), k -> true);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Stand the camera {@code dist} blocks from the knight at {@code angle} degrees off its front, looking at its chest. */
    private void frame(MinecraftClient client, float angle, double dist, double height) {
        onServer(client, (world, sp) -> {
            KnightEntity k = world.getEntitiesByClass(KnightEntity.class, sp.getBoundingBox().expand(40), e -> true).get(0);
            double a = Math.toRadians(k.getYaw() + angle);
            Vec3d eye = k.getPos().add(-Math.sin(a) * dist, height, Math.cos(a) * dist);
            Vec3d look = k.getPos().add(0, 1.6, 0).subtract(eye);
            float yaw = (float) (MathHelper.atan2(look.z, look.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
            float pitch = (float) -(MathHelper.atan2(look.y, look.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN);
            sp.teleport(world, eye.x, eye.y - sp.getStandingEyeHeight(), eye.z, yaw, pitch);
        });
    }

    /** Plays one of the knight's animations on the client (statuses 100-106, see KnightEntity). */
    private void play(MinecraftClient client, int status) {
        KnightEntity k = knight(client);
        if (k != null) k.handleStatus((byte) status);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            // From the first tick: the camera can't fall or be hurt (still survival, so the XP cost is real)
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
                onServer(client, (world, sp) -> {
                    sp.getAbilities().allowFlying = true;
                    sp.getAbilities().flying = true;
                    sp.getAbilities().invulnerable = true;
                    sp.sendAbilitiesUpdate();
                });
            }
            // Get there first, then build the platform, so the client has the chunk before the blocks change
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getAbilities().allowFlying = true; // the camera hops around in mid-air
                sp.getAbilities().flying = true;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, 0f, 10f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-16, 0, -16), ORIGIN.add(16, 12, 16))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, 0f, 10f);
                sp.setExperienceLevel(30);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                check(((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Knight.TOME_OF_KNIGHT) == StaffItem.InsertResult.OK, "tome fits a netherite staff");
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 70 -> {
                p.getInventory().selectedSlot = 0;
                client.interactionManager.interactItem(p, Hand.MAIN_HAND); // the real cast
            }
            case 75 -> check(p.experienceLevel < 30, "summoning cost XP (level now " + p.experienceLevel + ")");
            case 74 -> {
                KnightEntity k = knight(client);
                check(k != null, "knight summoned");
                // Freeze it where it stands for the photos
                onServer(client, (world, sp) -> world.getEntitiesByClass(KnightEntity.class, sp.getBoundingBox().expand(40), e -> true)
                        .forEach(e -> e.setAiDisabled(true)));
                frame(client, 35f, 7.0, 1.4);
            }
            case 80 -> shot(client, "01_rise_early");
            case 89 -> shot(client, "02_rise_mid");
            case 99 -> shot(client, "03_rise_salute_up");
            case 107 -> shot(client, "04_rise_salute");
            case 115 -> shot(client, "05_idle_front");
            case 117 -> frame(client, 100f, 7.0, 1.2);
            case 122 -> shot(client, "06_idle_side");
            case 124 -> frame(client, 200f, 7.0, 1.6);
            case 129 -> shot(client, "07_idle_back");
            case 131 -> frame(client, 50f, 7.5, 1.4);
            // Cleave: wind-up at 0.3s, impact at 0.4s
            case 140 -> play(client, 101);
            case 146 -> shot(client, "10_cleave_windup");
            case 148 -> shot(client, "11_cleave_impact");
            // Sweep: wound up at 0.25s, through at 0.35s
            case 165 -> play(client, 102);
            case 170 -> shot(client, "12_sweep_windup");
            case 172 -> shot(client, "13_sweep_through");
            // Uppercut: low at 0.2s, overhead at 0.3s
            case 190 -> play(client, 103);
            case 194 -> shot(client, "14_uppercut_low");
            case 196 -> shot(client, "15_uppercut_high");
            // Dash: charge pose held, then the strike
            case 215 -> play(client, 104);
            case 230 -> frame(client, 70f, 7.5, 1.2);
            case 235 -> shot(client, "16_dash_charge");
            case 240 -> play(client, 105);
            case 242 -> shot(client, "17_dash_strike");
            case 244 -> shot(client, "18_dash_strike_through");
            // Night, close on the face: glowing eyes and blade runes
            case 260 -> {
                onServer(client, (world, sp) -> world.setTimeOfDay(18000));
                frame(client, 20f, 4.0, 2.2);
            }
            case 270 -> shot(client, "19_night_eyes");
            // Dismissal: kneel, then sink
            case 275 -> {
                onServer(client, (world, sp) -> world.setTimeOfDay(6000));
                frame(client, 40f, 7.0, 1.4);
            }
            case 280 -> play(client, 106);
            case 292 -> shot(client, "20_dismiss_kneel");
            case 302 -> shot(client, "21_dismiss_sinking");
            // In action: let it loose on a pack of husks
            case 310 -> {
                play(client, 100); // climbs back out (it's still mid-rise on the server)
                onServer(client, (world, sp) -> {
                KnightEntity k = world.getEntitiesByClass(KnightEntity.class, sp.getBoundingBox().expand(40), e -> true).get(0);
                k.setAiDisabled(false);
                sp.teleport(world, k.getX() - 1, k.getY(), k.getZ() - 4, 0f, 10f);
                for (int i = 0; i < 4; i++) {
                    HuskEntity husk = EntityType.HUSK.create(world);
                    husk.refreshPositionAndAngles(k.getX() - 4 + i * 2.5, k.getY(), k.getZ() + 7, 180f, 0f);
                    world.spawnEntity(husk);
                }
                });
            }
            case 312 -> frame(client, 60f, 9.0, 2.5);
            case 350 -> shot(client, "22_fight_a");
            case 365 -> shot(client, "23_fight_b");
            case 390 -> shot(client, "24_fight_c");
            case 560 -> {
                int husks = client.world.getEntitiesByClass(HuskEntity.class, p.getBoundingBox().expand(30), h -> h.isAlive()).size();
                check(husks == 0, "the knight cut down all four husks (" + husks + " left)");
                shot(client, "25_after_fight");
                client.options.sneakKey.setPressed(true); // sneak-cast to send it home
            }
            case 565 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 570 -> client.options.sneakKey.setPressed(false);
            case 610 -> {
                check(knight(client) == null, "sneak-cast sent it back into the ground");
                check(p.getItemCooldownManager().isCoolingDown(Knight.TOME_OF_KNIGHT), "cooldown started once it was gone");
                log("stats: " + (failures == 0 ? "ALL PASSED" : failures + " FAILED"));
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
