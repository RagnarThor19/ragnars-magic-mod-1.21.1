package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
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
import net.ragnar.ragnarsmagicmod.phantom.Phantom;

/**
 * Photo shoot for the Tome of the Phantom: becomes a spectre, looks at it from behind and in front, flies through a
 * stone wall, surges, then gets caught inside the wall when the time runs out. Positions are logged and screenshots
 * saved. Only runs with -Dragnarsmagicmod.phantomsmoke=true (./gradlew runPhantomSmoke).
 */
public class PhantomClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final int WALL_Z = 14;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.phantomsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "phantom_" + name + ".png", client.getFramebuffer(), t -> {});
        ClientPlayerEntity p = client.player;
        RagnarsMagicMod.LOGGER.info("[PHANTOM SMOKE] {}: pos {} {} {}, spectre {}, noClip {}, health {}", name,
                String.format("%.2f", p.getX()), String.format("%.2f", p.getY()), String.format("%.2f", p.getZ()),
                Phantom.isPhantom(p), p.noClip, p.getHealth());
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void keys(MinecraftClient client, boolean forward, boolean sprint) {
        client.options.forwardKey.setPressed(forward);
        client.options.sprintKey.setPressed(sprint);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
                onServer(client, (world, sp) -> {
                    sp.getAbilities().invulnerable = false;
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
                world.setTimeOfDay(14000); // dusk: spectres look best in the dark
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-24, 0, -16), ORIGIN.add(24, 14, 60))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A thick stone wall to fly through
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-8, 1, WALL_Z), ORIGIN.add(8, 7, WALL_Z + 2))) {
                    world.setBlockState(pos, (pos.getX() + pos.getY()) % 5 == 0 ? Blocks.MOSSY_STONE_BRICKS.getDefaultState() : Blocks.STONE_BRICKS.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setHealth(sp.getMaxHealth());
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Phantom.TOME_OF_THE_PHANTOM);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                sp.getItemCooldownManager().remove(Phantom.TOME_OF_THE_PHANTOM);
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 72 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 76 -> shot(client, "1a_rising");
            case 84 -> shot(client, "1b_behind");
            case 86 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 89 -> shot(client, "2a_front");
            case 90 -> p.setPitch(-25f);
            case 92 -> shot(client, "2b_front_looking_up");
            case 93 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setPitch(3f);
            }
            // Fly at the wall and through it
            case 95 -> keys(client, true, false);
            case 101 -> shot(client, "3a_flying");
            case 106 -> shot(client, "3b_at_wall");
            case 109 -> shot(client, "3c_in_wall");
            case 112 -> shot(client, "3d_in_wall");
            case 118 -> shot(client, "3e_through");
            case 119 -> keys(client, false, false);
            case 126 -> shot(client, "3f_stopped");
            // A surge
            case 128 -> keys(client, true, true);
            case 136 -> shot(client, "4a_surge");
            case 140 -> shot(client, "4b_surge");
            case 141 -> keys(client, false, false);
            case 150 -> {
                p.setYaw(180f);
                p.setPitch(10f);
            }
            case 156 -> shot(client, "5_looking_back");
            // Park inside the wall and let the time run out there
            case 160 -> onServer(client, (world, sp) -> sp.teleport(world, 0.5, Y + 1, WALL_Z + 1.5, 0f, 5f));
            case 180 -> shot(client, "6a_warning_in_wall");
            case 200 -> shot(client, "6b_warning_in_wall");
            case 240 -> shot(client, "7a_trapped");
            case 260 -> {
                shot(client, "7b_trapped");
                onServer(client, (world, sp) -> RagnarsMagicMod.LOGGER.info(
                        "[PHANTOM SMOKE] server: inside wall {}, noClip {}, health {}, difficulty {}, invulnerable {}, hurtTime {}",
                        sp.isInsideWall(), sp.noClip, sp.getHealth(), world.getDifficulty(), sp.getAbilities().invulnerable, sp.hurtTime));
            }
            case 265 -> {
                RagnarsMagicMod.LOGGER.info("[PHANTOM SMOKE] done, cooling down {}", p.getItemCooldownManager().isCoolingDown(Phantom.TOME_OF_THE_PHANTOM));
                client.scheduleStop();
            }
            default -> {
            }
        }
    }
}
