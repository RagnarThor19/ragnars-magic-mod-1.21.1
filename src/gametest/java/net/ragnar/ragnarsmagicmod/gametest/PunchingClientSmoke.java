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
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.punching.Punching;

/**
 * Tome of Punching, played for real: a dash across open ground (how far did it really go?), then dashes into a line of
 * husks seen from the side and from in front, with screenshots of the wind-up, the punch and the shockwave, and how far
 * the husk that took the hit flew. Only runs with -Dragnarsmagicmod.punchsmoke=true (./gradlew runPunchSmoke).
 */
public class PunchingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private double startZ;
    private MobEntity target;
    private double targetZ;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.punchsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[PUNCH SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "punch_" + name + ".png", client.getFramebuffer(), t -> {});
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Punching.TOME_OF_PUNCHING));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private void husks(MinecraftClient client, double z) {
        onServer(client, (world, sp) -> {
            world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
            for (int i = 0; i < 3; i++) {
                MobEntity h = EntityType.HUSK.create(world);
                h.refreshPositionAndAngles(0.5 + (i - 1) * 1.6, Y, z + (i == 1 ? 0 : 1.2), 180f, 0f);
                h.setPersistent(); // AI left on: mobs with no AI can't be knocked back at all
                world.spawnEntity(h);
                if (i == 1) {
                    target = h;
                    targetZ = h.getZ();
                }
            }
            sp.teleport(world, 0.5, Y, 0.5, 0f, 5f);
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
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-20, 0, -20), ORIGIN.add(20, 14, 70))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 5f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Punching.TOME_OF_PUNCHING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            // 1. Open ground: how far does the dash go?
            case 60 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 64 -> {
                startZ = p.getZ();
                cast(client, p);
            }
            case 66 -> shot(client, "0_dash_fp");
            case 80 -> log("open ground dash: went {} blocks", String.format("%.2f", p.getZ() - startZ));
            // 2. Into a line of husks, seen from the side
            case 90 -> husks(client, 7.5);
            case 100 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 104 -> {
                startZ = p.getZ();
                cast(client, p);
            }
            case 106 -> shot(client, "1a_windup_tp");
            case 108 -> shot(client, "1b_dash_tp");
            case 109 -> p.setYaw(-60f); // look round at it from the side as it lands
            case 110 -> shot(client, "1c_impact_side");
            case 112 -> shot(client, "1d_impact_side");
            case 115 -> shot(client, "1e_after_side");
            case 140 -> {
                log("dash into husks: went {} blocks", String.format("%.2f", p.getZ() - startZ));
                if (target != null) log("the husk that was hit flew {} blocks, alive {}, health {}",
                        String.format("%.1f", target.getZ() - targetZ), target.isAlive(), target.getHealth());
            }
            // 3. Again, from in front, to see the punch thrown
            case 150 -> husks(client, 6.5);
            case 160 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 164 -> cast(client, p);
            case 166 -> shot(client, "2a_windup_front");
            case 169 -> shot(client, "2b_impact_front");
            case 171 -> shot(client, "2c_impact_front");
            case 175 -> shot(client, "2d_followthrough_front");
            case 200 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
