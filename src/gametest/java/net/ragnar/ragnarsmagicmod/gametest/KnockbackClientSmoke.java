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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.knockback.Knockback;

/**
 * Tome of Knockback, played for real: a husk walks up and hits you three times. Logs how far it's thrown each time,
 * and screenshots the shove frame by frame after the first hit. Only runs with -Dragnarsmagicmod.knockbacksmoke=true
 * (./gradlew runKnockbackSmoke).
 */
public class KnockbackClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 120, 0);
    private int tick = -1, hitAt = -1, hits;
    private HuskEntity husk;
    private double huskBefore;
    private int lastHurt;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.knockbacksmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[KNOCKBACK SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "knockback_" + name + ".png", client.getFramebuffer(), t -> {});
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

        // Each time the husk's hit lands (we go red), note where it was and see how far it ends up
        if (husk != null && p.hurtTime > 0 && lastHurt == 0) {
            hits++;
            huskBefore = Math.sqrt(husk.squaredDistanceTo(p.getX(), husk.getY(), p.getZ()));
            hitAt = tick;
        }
        lastHurt = p.hurtTime;
        if (hitAt >= 0) {
            int since = tick - hitAt;
            if (hits == 1 && since <= 6) shot(client, "hit1_t" + since);
            if (since == 12) {
                log("hit {}: husk {} blocks away when it hit, {} blocks away 12 ticks later; health {}", hits,
                        String.format("%.2f", huskBefore), String.format("%.2f", Math.sqrt(husk.squaredDistanceTo(p.getX(), husk.getY(), p.getZ()))),
                        p.getHealth());
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-20, 0, -20), ORIGIN.add(20, 80, 20))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getInventory().clear();
                sp.getInventory().setStack(20, new ItemStack(Knockback.TOME_OF_KNOCKBACK));
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, 0f, 12f);
                sp.setHealth(sp.getMaxHealth());
            });
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(12f);
            }
            // A husk a few blocks in front, coming for you (husks don't burn in daylight)
            case 70 -> onServer(client, (world, sp) -> {
                husk = EntityType.HUSK.create(world);
                husk.refreshPositionAndAngles(0.5, ORIGIN.getY() + 1, 4.5, 180f, 0f);
                world.spawnEntity(husk);
                husk.setTarget(sp);
            });
            case 400 -> {
                log("{} hits in total, husk alive {}", hits, husk.isAlive());
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {
                // Keep facing it
                if (husk != null && tick > 70) {
                    double dx = husk.getX() - p.getX(), dz = husk.getZ() - p.getZ();
                    // Turned 70 degrees off it, so the camera behind sees both of you side-on
                    p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0 + 70.0));
                }
            }
        }
    }
}
