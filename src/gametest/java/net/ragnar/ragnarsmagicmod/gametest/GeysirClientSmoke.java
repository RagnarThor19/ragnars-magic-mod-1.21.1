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
import net.ragnar.ragnarsmagicmod.geysir.GeysirEntity;
import net.ragnar.ragnarsmagicmod.geysir.GeysirSpell;

import java.util.ArrayList;
import java.util.List;

/**
 * Tome of Geysir, played for real: six husks (with their wits about them, so some edge off their spots in the warning)
 * a few blocks ahead, and the tome cast. Logs how high and how far sideways each was thrown, and screenshots the
 * warning, the eruption and the collapse. Only runs with -Dragnarsmagicmod.geysirsmoke=true (./gradlew runGeysirSmoke).
 */
public class GeysirClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 110, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int CAST = 100;
    private int tick = -1;
    private final List<HuskEntity> husks = new ArrayList<>();
    private final List<double[]> track = new ArrayList<>(); // start x, z; highest y climbed; x, z at the top

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.geysirsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[GEYSIR SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "geysir_" + name + ".png", client.getFramebuffer(), t -> {});
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
        for (int i = 0; i < husks.size(); i++) {
            HuskEntity h = husks.get(i);
            double[] tr = track.get(i);
            if (h.getY() > tr[2]) {
                tr[2] = h.getY();
                tr[3] = h.getX();
                tr[4] = h.getZ();
            }
        }
        int since = tick - CAST;
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-25, 0, -25), ORIGIN.add(25, 40, 25))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getInventory().clear();
                sp.teleport(world, 0.5, Y, -6.5, 0f, -18f);
                sp.setHealth(sp.getMaxHealth());
                sp.getAbilities().invulnerable = true; // the husks are only here to be thrown
            });
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
                p.setPitch(-18f);
            }
            case 80 -> onServer(client, (world, sp) -> {
                double[][] at = {{-3, 2}, {0, 1}, {3, 2}, {-2, 5}, {2, 5}, {0, 8}};
                for (double[] a : at) {
                    HuskEntity h = EntityType.HUSK.create(world);
                    h.refreshPositionAndAngles(0.5 + a[0], Y, 0.5 + a[1], 180f, 0f);
                    world.spawnEntity(h);
                    husks.add(h);
                    track.add(new double[]{h.getX(), h.getZ(), h.getY(), h.getX(), h.getZ()});
                }
            });
            case CAST -> onServer(client, (world, sp) -> {
                for (int i = 0; i < husks.size(); i++) {
                    double[] tr = track.get(i);
                    tr[0] = husks.get(i).getX();
                    tr[1] = husks.get(i).getZ();
                    tr[2] = husks.get(i).getY();
                }
                boolean cast = new GeysirSpell().cast(world, sp, ItemStack.EMPTY);
                log("cast: {}, {} geysirs", cast, world.getEntitiesByClass(GeysirEntity.class, sp.getBoundingBox().expand(20), g -> true).size());
            });
            case CAST + 200 -> {
                for (int i = 0; i < husks.size(); i++) {
                    double[] tr = track.get(i);
                    log("husk {}: thrown {} blocks up, {} blocks sideways from where it was when cast", i,
                            String.format("%.1f", tr[2] - Y), String.format("%.1f", Math.hypot(tr[3] - tr[0], tr[4] - tr[1])));
                }
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
        if (since == 6 || since == 16 || since == 21 || since == 23 || since == 26 || since == 32 || since == 40 || since == 46) {
            shot(client, String.format("t%02d", since));
        }
    }
}
