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
import net.ragnar.ragnarsmagicmod.astral.AstralProjection;
import net.ragnar.ragnarsmagicmod.astral.client.AstralClient;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Astral Projection: leaves the body, looks back down at it, drifts through a wall to the
 * mobs behind it, turns round to see the cord, rides the heartbeat and the reel back in; then a second cast where the
 * body gets hurt and yanks the spirit home. Logs where the body and the spirit are at each step. Only runs with
 * -Dragnarsmagicmod.astralsmoke=true (./gradlew runAstralSmoke).
 */
public class AstralClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.astralsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        log(client, name);
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "astral_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void log(MinecraftClient client, String what) {
        RagnarsMagicMod.LOGGER.info("[ASTRAL SMOKE] {} active={} spirit={} body={} hp={} cooldown={}", what, AstralClient.isActive(),
                AstralClient.cameraPos(0f), client.player.getPos(), client.player.getHealth(),
                client.player.getItemCooldownManager().isCoolingDown(AstralProjection.TOME_OF_ASTRAL_PROJECTION));
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(AstralProjection.TOME_OF_ASTRAL_PROJECTION));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private static void mob(ServerWorld world, EntityType<? extends MobEntity> type, double x, double z, float yaw) {
        MobEntity m = type.create(world);
        m.refreshPositionAndAngles(x, Y, z, yaw, 0f);
        m.setAiDisabled(true);
        m.setPersistent();
        world.spawnEntity(m);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
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
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(90), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-30, 0, -20), ORIGIN.add(30, 14, 40))) {
                    BlockState s = Blocks.AIR.getDefaultState();
                    if (pos.getY() == ORIGIN.getY()) s = Blocks.GRASS_BLOCK.getDefaultState();
                    // A thick stone wall across the way, with mobs hidden behind it
                    else if (pos.getZ() >= 7 && pos.getZ() <= 8 && Math.abs(pos.getX()) <= 9 && pos.getY() <= ORIGIN.getY() + 6) {
                        s = Blocks.STONE_BRICKS.getDefaultState();
                    }
                    world.setBlockState(pos, s);
                }
                mob(world, EntityType.ZOMBIE, -2.5, 13.5, 180f);
                mob(world, EntityType.SKELETON, 1.5, 15.5, 180f);
                mob(world, EntityType.COW, 4.5, 12.5, 90f);
                mob(world, EntityType.VILLAGER, -5.5, 16.5, 200f);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setHealth(sp.getMaxHealth());
                sp.setExperienceLevel(500);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, AstralProjection.TOME_OF_ASTRAL_PROJECTION);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // --- Out of the body ---
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(10f);
            }
            case 72 -> cast(client, p);
            case 75 -> shot(client, "1a_leaving");
            case 84 -> shot(client, "1b_floating_up");
            case 88 -> p.setPitch(55f);
            case 92 -> shot(client, "1c_own_body");
            // --- Through the wall ---
            case 94 -> {
                p.setPitch(8f);
                client.options.forwardKey.setPressed(true);
            }
            case 112 -> shot(client, "1d_in_the_wall");
            case 122 -> {
                client.options.forwardKey.setPressed(false);
                shot(client, "1e_beyond");
            }
            case 130 -> shot(client, "1f_sensed_mobs");
            case 134 -> {
                p.setYaw(180f);
                p.setPitch(15f);
            }
            case 138 -> shot(client, "1g_cord_home");
            // Push out to the end of the cord
            case 140 -> {
                p.setYaw(0f);
                p.setPitch(-5f);
                client.options.forwardKey.setPressed(true);
                client.options.sprintKey.setPressed(true);
            }
            case 175 -> shot(client, "1h_cord_taut");
            case 182 -> {
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
                p.setYaw(180f);
                p.setPitch(10f);
            }
            case 198 -> shot(client, "1i_heartbeat");
            case 214 -> shot(client, "1j_reeling");
            case 218 -> shot(client, "1k_reeling");
            case 222 -> shot(client, "1l_reeling");
            case 236 -> shot(client, "1m_landed");
            // --- Hurt while out: yanked home ---
            case 250 -> {
                p.setYaw(0f);
                p.setPitch(10f);
            }
            case 252 -> cast(client, p);
            case 256 -> client.options.forwardKey.setPressed(true);
            case 276 -> {
                client.options.forwardKey.setPressed(false);
                log(client, "before hit");
            }
            case 280 -> onServer(client, (world, sp) -> sp.damage(world.getDamageSources().magic(), 2f));
            case 282 -> shot(client, "2a_jolt");
            case 284 -> shot(client, "2b_jolt");
            case 292 -> shot(client, "2c_jolt_landed");
            case 320 -> {
                log(client, "done");
                RagnarsMagicMod.LOGGER.info("[ASTRAL SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
