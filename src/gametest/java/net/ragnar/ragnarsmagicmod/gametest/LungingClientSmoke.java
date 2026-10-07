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
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;

/**
 * Check for the Tome of Lunging, played for real: right-clicks with a sword at a golem three blocks ahead (lunge,
 * stab, spring back), then into empty air (lunge, stop), logging where you are each tick and saving a few
 * screenshots. Only runs with -Dragnarsmagicmod.lungesmoke=true (./gradlew runLungeSmoke).
 */
public class LungingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private int logUntil = -1;
    private double startZ;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.lungesmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "lunge_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void log(MinecraftClient client, String what) {
        ClientPlayerEntity p = client.player;
        LivingEntity golem = client.world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(20), e -> e.getType() == EntityType.IRON_GOLEM)
                .stream().findFirst().orElse(null);
        RagnarsMagicMod.LOGGER.info("[LUNGE SMOKE] {} t={} dz={} y={} onGround={} golemHp={}", what, tick,
                String.format("%.2f", p.getZ() - startZ), String.format("%.2f", p.getY() - Y), p.isOnGround(),
                golem == null ? "-" : golem.getHealth());
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (tick <= logUntil) log(client, "tick");
        switch (tick) {
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-12, 0, -12), ORIGIN.add(12, 8, 24))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getInventory().clear();
                sp.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Lunging.TOME_OF_LUNGING);
                sp.getInventory().setStack(4, staff);
                sp.getInventory().selectedSlot = 0;
                sp.setExperienceLevel(10);
                MobEntity golem = EntityType.IRON_GOLEM.create(world);
                golem.refreshPositionAndAngles(0.5, Y, 3.5, 180f, 0f);
                golem.setAiDisabled(true);
                world.spawnEntity(golem);
            });
            case 60 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(10f);
            }
            case 64 -> {
                startZ = p.getZ();
                log(client, "before");
                logUntil = 80;
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 65 -> shot(client, "1a_lunge");
            case 66 -> shot(client, "1b_stab");
            case 68 -> shot(client, "1c_back");
            case 72 -> shot(client, "1d_landed");
            // A miss: the golem moved away, cooldown cleared
            case 90 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getItemCooldownManager().remove(Lunging.TOME_OF_LUNGING);
                sp.getItemCooldownManager().remove(Items.IRON_SWORD);
            });
            case 100 -> {
                p.setYaw(0f);
                startZ = p.getZ();
                log(client, "miss-before");
                logUntil = 110;
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 104 -> shot(client, "2a_missed");
            // Holding right-click with nothing in the off hand: one lunge, not one every few ticks
            case 115 -> onServer(client, (world, sp) -> {
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getItemCooldownManager().remove(Lunging.TOME_OF_LUNGING);
                sp.getItemCooldownManager().remove(Items.IRON_SWORD);
            });
            case 125 -> {
                p.setYaw(0f);
                startZ = p.getZ();
                client.options.useKey.setPressed(true);
            }
            case 145 -> {
                RagnarsMagicMod.LOGGER.info("[LUNGE SMOKE] held 20 ticks, no shield: dz={} (one lunge is ~4)", String.format("%.2f", p.getZ() - startZ));
                client.options.useKey.setPressed(false);
            }
            // A shield in the off hand, sprinting, holding right-click: blocks the whole time, never lunges
            case 150 -> onServer(client, (world, sp) -> {
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.getItemCooldownManager().remove(Lunging.TOME_OF_LUNGING);
                sp.getItemCooldownManager().remove(Items.IRON_SWORD);
                sp.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
            });
            case 160 -> {
                p.setYaw(0f);
                startZ = p.getZ();
                p.setSprinting(true);
                client.options.useKey.setPressed(true);
            }
            case 170 -> RagnarsMagicMod.LOGGER.info("[LUNGE SMOKE] shield held: blocking={} dz={}", p.isBlocking() || p.isUsingItem(), String.format("%.2f", p.getZ() - startZ));
            case 180 -> {
                RagnarsMagicMod.LOGGER.info("[LUNGE SMOKE] shield held 20 ticks: blocking={} dz={} cooling={}", p.isUsingItem(),
                        String.format("%.2f", p.getZ() - startZ), p.getItemCooldownManager().isCoolingDown(Lunging.TOME_OF_LUNGING));
                client.options.useKey.setPressed(false);
            }
            // ...and a quick tap with the shield: lunges when you let go
            case 190 -> {
                startZ = p.getZ();
                logUntil = 202;
                client.options.useKey.setPressed(true);
            }
            case 192 -> client.options.useKey.setPressed(false);
            case 198 -> RagnarsMagicMod.LOGGER.info("[LUNGE SMOKE] shield tapped: dz={} blocking={}", String.format("%.2f", p.getZ() - startZ), p.isUsingItem());
            case 205 -> {
                RagnarsMagicMod.LOGGER.info("[LUNGE SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
