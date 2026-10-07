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
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Bubbles: blows a bubble at an iron golem, watches it get sealed in and lifted, then
 * blows one at a skeleton that shoots its way out. Saves screenshots along the way. Only runs with
 * -Dragnarsmagicmod.bubblesmoke=true (./gradlew runBubbleSmoke).
 */
public class BubbleClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private int lastBubbles, popShots;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.bubblesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "bubble_" + name + ".png", client.getFramebuffer(), t -> {});
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
        onServer(client, (world, sp) -> sp.getItemCooldownManager().remove(Bubbles.TOME_OF_BUBBLES));
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        // Catch the moment one pops
        int bubbles = client.world.getEntitiesByClass(net.ragnar.ragnarsmagicmod.bubbles.BubbleEntity.class,
                p.getBoundingBox().expand(64), b -> true).size();
        if (bubbles < lastBubbles) popShots = 1;
        lastBubbles = bubbles;
        if (popShots > 0 && popShots <= 3) shot(client, "pop_" + tick);
        if (popShots > 0) popShots += popShots < 3 ? 1 : 0;
        if (popShots == 3) popShots = 99;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
                // Unhurt but still a target, so the skeleton shoots
                onServer(client, (world, sp) -> sp.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        net.minecraft.entity.effect.StatusEffects.RESISTANCE, 20 * 60, 4, false, false)));
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-25, 0, -15), ORIGIN.add(25, 20, 40))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Bubbles.TOME_OF_BUBBLES);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                MobEntity golem = EntityType.IRON_GOLEM.create(world);
                golem.refreshPositionAndAngles(0.5, Y, 7.5, 180f, 0f);
                golem.setAiDisabled(true);
                world.spawnEntity(golem);
            });
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 72 -> cast(client, p);
            case 76 -> shot(client, "1a_blown");
            case 86 -> shot(client, "1b_drifting");
            case 100 -> shot(client, "1c_close");
            case 115 -> shot(client, "2a_sealed");
            case 140 -> {
                p.setPitch(-12f);
                shot(client, "2b_lifting");
            }
            case 150 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 155 -> shot(client, "2c_front_view");
            case 160 -> client.options.setPerspective(Perspective.FIRST_PERSON);
            case 200 -> shot(client, "2d_floating");
            // A skeleton: sealed in, it shoots its way out
            case 210 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                MobEntity skel = EntityType.SKELETON.create(world);
                skel.refreshPositionAndAngles(0.5, Y, 6.5, 180f, 0f);
                skel.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.item.Items.BOW));
                world.spawnEntity(skel);
            });
            case 214 -> {
                p.setYaw(0f);
                p.setPitch(5f);
            }
            case 216 -> cast(client, p);
            case 236 -> shot(client, "3a_skeleton_sealed");
            case 250 -> shot(client, "3b_skeleton");
            case 265 -> shot(client, "3c_skeleton");
            case 280 -> shot(client, "3d_skeleton");
            case 300 -> shot(client, "3e_skeleton");
            case 320 -> {
                RagnarsMagicMod.LOGGER.info("[BUBBLE SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
