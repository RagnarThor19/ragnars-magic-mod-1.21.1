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
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.earthquake.Earthquake;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Earthquake: one quake out in a field ringed by zombies and golems, then one in a stone
 * cave. Saves screenshots of the cracks spreading, the shock, the slabs and the rubble. Only runs with
 * -Dragnarsmagicmod.quakesmoke=true (./gradlew runQuakeSmoke).
 */
public class EarthquakeClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    /** The cave: a hollow in solid stone, its floor at CAVE_Y. */
    private static final BlockPos CAVE = new BlockPos(200, 120, 0);
    private static final int CAVE_Y = CAVE.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.quakesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "quake_" + name + ".png", client.getFramebuffer(), t -> {});
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Earthquake.TOME_OF_EARTHQUAKE));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private static void mobs(ServerWorld world, double cx, int y, double cz) {
        for (int i = 0; i < 10; i++) {
            MobEntity m = (i % 5 == 0 ? EntityType.IRON_GOLEM : EntityType.ZOMBIE).create(world);
            double a = i * Math.PI * 2 / 10 + 0.3, d = 6 + (i % 3) * 6;
            m.refreshPositionAndAngles(cx + Math.cos(a) * d, y, cz + Math.sin(a) * d, 0f, 0f);
            m.setAiDisabled(true);
            m.setPersistent();
            world.spawnEntity(m);
        }
    }

    // The quake goes off 60 ticks after each cast (at 132 and 302); aftershocks 24 and 44 ticks after that
    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
                onServer(client, (world, sp) -> sp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 120, 4, false, false)));
            }
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(1000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-32, -2, -32), ORIGIN.add(32, 20, 32))) {
                    int y = pos.getY() - ORIGIN.getY();
                    world.setBlockState(pos, y == 0 ? Blocks.GRASS_BLOCK.getDefaultState()
                            : y < 0 ? Blocks.DIRT.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(200);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Earthquake.TOME_OF_EARTHQUAKE);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                mobs(world, 0.5, Y, 0.5);
            });
            case 70 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(35f);
            }
            case 72 -> cast(client, p);
            case 92 -> shot(client, "1a_cracks");
            case 112 -> shot(client, "1b_cracks");
            case 130 -> shot(client, "1c_cracks_full");
            case 134 -> shot(client, "2a_shock");
            case 137 -> shot(client, "2b_wave");
            case 141 -> shot(client, "2c_wave");
            case 146 -> shot(client, "2d_slabs");
            case 152 -> shot(client, "2e_slabs");
            case 159 -> shot(client, "2f_aftershock");
            case 166 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setPitch(12f);
            }
            case 170 -> shot(client, "2g_first_person");
            case 179 -> shot(client, "2h_aftershock2");
            case 200 -> shot(client, "2i_settled");
            // Underground
            case 220 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                for (BlockPos pos : BlockPos.iterate(CAVE.add(-30, -3, -30), CAVE.add(30, 10, 30))) {
                    int y = pos.getY() - CAVE.getY();
                    boolean hollow = y >= 1 && y <= 6 && Math.abs(pos.getX() - CAVE.getX()) < 27 && Math.abs(pos.getZ() - CAVE.getZ()) < 27;
                    world.setBlockState(pos, hollow ? Blocks.AIR.getDefaultState() : (y > 6 ? Blocks.DEEPSLATE : Blocks.STONE).getDefaultState());
                }
                for (int i = 0; i < 40; i++) {
                    BlockPos t = CAVE.add(world.random.nextInt(50) - 25, 7, world.random.nextInt(50) - 25);
                    world.setBlockState(t, Blocks.GLOWSTONE.getDefaultState());
                }
                sp.teleport(world, CAVE.getX() + 0.5, CAVE_Y, CAVE.getZ() + 0.5, 0f, 0f);
                mobs(world, CAVE.getX() + 0.5, CAVE_Y, CAVE.getZ() + 0.5);
            });
            case 240 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(20f);
            }
            case 242 -> cast(client, p);
            case 290 -> shot(client, "3a_cave_cracks");
            case 304 -> shot(client, "3b_cave_shock");
            case 309 -> shot(client, "3c_cave_rubble");
            case 316 -> shot(client, "3d_cave_rubble");
            case 330 -> shot(client, "3e_cave_aftershock");
            case 345 -> {
                RagnarsMagicMod.LOGGER.info("[QUAKE SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
