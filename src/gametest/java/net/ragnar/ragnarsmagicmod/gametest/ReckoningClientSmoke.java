package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HuskEntity;
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

/**
 * Photo shoot for the Tome of Reckoning: casts it through the real right-click path at a ring of husks and saves
 * screenshots of the wave, the lift, the shake and the slam. Only runs with -Dragnarsmagicmod.reckoningsmoke=true
 * (./gradlew runReckoningSmoke).
 */
public class ReckoningClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private int tick = -1;
    private int failures;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.reckoningsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String msg) {
        RagnarsMagicMod.LOGGER.info("[RECKONING SMOKE] " + msg);
    }

    private void check(boolean ok, String msg) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + msg);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "reckoning_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private int husksAlive(MinecraftClient client) {
        return client.world.getEntitiesByClass(HuskEntity.class, client.player.getBoundingBox().expand(30), h -> h.isAlive()).size();
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
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.getAbilities().allowFlying = true;
                sp.getAbilities().flying = true;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, 0f, 30f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(13000); // dusk: the wave reads best against a darker ground
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-22, 0, -22), ORIGIN.add(22, 10, 22))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // Leftovers from earlier runs
                world.getEntitiesByClass(HuskEntity.class, sp.getBoundingBox().expand(40), h -> true).forEach(h -> h.discard());
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, 0f, 30f);
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                sp.setExperienceLevel(30);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, ModItems.TOME_OF_RECKONING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                // Husks spread out in front, rooted so the shots are readable
                double[][] spots = {{-3, 5}, {3, 7}, {-6, 10}, {5, 12}, {0, 15}};
                for (double[] s : spots) {
                    HuskEntity husk = EntityType.HUSK.create(world);
                    husk.refreshPositionAndAngles(0.5 + s[0], ORIGIN.getY() + 1, 0.5 + s[1], 180f, 0f);
                    husk.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 2000, 255, false, false));
                    husk.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 2000, 255, false, false));
                    world.spawnEntity(husk);
                }
            });
            case 70 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.getInventory().selectedSlot = 0;
                p.setPitch(32f);
                check(husksAlive(client) == 5, "five husks waiting (" + husksAlive(client) + ")");
            }
            case 75 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND); // the real cast
            case 81 -> shot(client, "1_wave_start");
            case 90 -> shot(client, "2_wave_near");
            case 102 -> shot(client, "3_wave_mid");
            case 112 -> shot(client, "4_wave_far");
            case 125 -> shot(client, "5_lift");
            case 138 -> shot(client, "6_shake");
            case 146 -> shot(client, "7_slam");
            case 150 -> shot(client, "8_after");
            case 175 -> {
                // Husks have 20 health and 2 armor; damage runs 26 at the caster down to 16 at the edge, so the two
                // within ~10 blocks die and the three further out survive on a sliver
                var husks = client.world.getEntitiesByClass(HuskEntity.class, p.getBoundingBox().expand(30), h -> h.isAlive());
                StringBuilder hp = new StringBuilder();
                husks.forEach(h -> hp.append(String.format(" %.1f", h.getHealth())));
                log("husks left after the slam: " + husks.size() + ", health:" + hp);
                check(husks.size() == 3, "the two nearest died, three further out survived (" + husks.size() + " left)");
                check(husks.stream().allMatch(h -> h.getHealth() < 3f), "every survivor was slammed nearly dead");
                check(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_RECKONING), "tome on cooldown");
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("stats: " + (failures == 0 ? "ALL PASSED" : failures + " FAILED"));
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
