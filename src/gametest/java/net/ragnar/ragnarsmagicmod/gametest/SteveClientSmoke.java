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
import net.minecraft.entity.LivingEntity;
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
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * Photo shoot for the Tome of Steve: calls the squad down for real at dusk in front of a few zombies and saves
 * screenshots of the ritual, the lightning, the squad and the fight. Only runs with -Dragnarsmagicmod.stevesmoke=true
 * (./gradlew runSteveSmoke).
 */
public class SteveClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.stevesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "steve_" + name + ".png", client.getFramebuffer(), t -> {});
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
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            });
            case 50 -> onServer(client, (world, sp) -> {
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(12800); // dusk
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-24, 0, -24), ORIGIN.add(24, 10, 34))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(70), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, (TomeItem) ModItems.TOME_OF_STEVE);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(22f); // the ground about 6 blocks out
            }
            case 72 -> {
                onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(ModItems.TOME_OF_STEVE));
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 84 -> shot(client, "1a_ritual");
            case 94 -> shot(client, "1b_ritual_hot");
            case 97 -> shot(client, "2a_first_bolt");
            case 105 -> shot(client, "2b_bolts");
            case 112 -> shot(client, "2c_squad");
            case 116 -> p.setPitch(8f);
            case 120 -> onServer(client, (world, sp) -> {
                for (int i = 0; i < 4; i++) {
                    MobEntity z = EntityType.ZOMBIE.create(world);
                    z.refreshPositionAndAngles(-4.5 + i * 3, Y, 16.5, 180f, 0f);
                    world.spawnEntity(z);
                }
            });
            case 140 -> shot(client, "3a_charge");
            case 160 -> shot(client, "3b_fight");
            case 165 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            case 175 -> shot(client, "3c_fight_tp");
            case 200 -> shot(client, "3d_fight_tp");
            case 205 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                RagnarsMagicMod.LOGGER.info("[STEVE SMOKE] done, player health {}", p.getHealth());
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
