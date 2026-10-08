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
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Deflection: the pane up close while turning the view (it should hold still on screen),
 * from behind in third person, and a skeleton's arrow striking it and going back. Only runs with
 * -Dragnarsmagicmod.deflectsmoke=true (./gradlew runDeflectSmoke).
 */
public class DeflectionClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private SkeletonEntity skeleton;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.deflectsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "deflect_" + name + ".png", client.getFramebuffer(), t -> {});
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(ModItems.TOME_OF_DEFLECTION));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
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
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-24, 0, -16), ORIGIN.add(24, 14, 40))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // Something behind the glass to see through it
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-3, 1, 8), ORIGIN.add(3, 4, 8))) {
                    world.setBlockState(pos, (pos.getX() + pos.getY()) % 2 == 0 ? Blocks.OAK_PLANKS.getDefaultState() : Blocks.BRICKS.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, ModItems.TOME_OF_DEFLECTION);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                skeleton = EntityType.SKELETON.create(world);
                skeleton.setAiDisabled(true);
                skeleton.refreshPositionAndAngles(6.5, Y, 14.5, 180f, 0f);
                world.spawnEntity(skeleton);
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(0f);
            }
            case 72 -> cast(client, p);
            case 74 -> shot(client, "1a_up");
            case 76 -> shot(client, "1b_up");
            case 77 -> p.setYaw(p.getYaw() + 25f); // turn: the pane should stay put on screen
            case 78 -> shot(client, "1c_turned");
            case 79 -> p.setPitch(-20f);
            case 80 -> shot(client, "1d_looking_up");
            case 81 -> p.setPitch(0f);
            case 85 -> shot(client, "1e_fading");
            // Third person, from behind
            case 95 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(0f);
            }
            case 97 -> cast(client, p);
            case 100 -> shot(client, "2_third_person");
            // A skeleton's arrow, back at it
            case 110 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                Vec3d at = new Vec3d(6.5, Y + 1, 14.5).subtract(p.getEyePos());
                p.setYaw((float) Math.toDegrees(Math.atan2(at.z, at.x)) - 90f);
                p.setPitch((float) -Math.toDegrees(Math.atan2(at.y, at.horizontalLength())));
            }
            case 112 -> cast(client, p);
            case 113 -> onServer(client, (world, sp) -> {
                ArrowEntity arrow = new ArrowEntity(world, skeleton, new ItemStack(Items.ARROW), null);
                Vec3d look = sp.getRotationVector();
                Vec3d start = sp.getEyePos().add(look.multiply(5));
                arrow.setPosition(start.x, start.y - 0.2, start.z);
                arrow.setVelocity(look.multiply(-1.6));
                world.spawnEntity(arrow);
            });
            case 116 -> shot(client, "3a_ripple");
            case 118 -> shot(client, "3b_ripple");
            case 135 -> {
                RagnarsMagicMod.LOGGER.info("[DEFLECT SMOKE] skeleton health {}", skeleton.getHealth());
                client.scheduleStop();
            }
            default -> {
            }
        }
    }
}
