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
import net.ragnar.ragnarsmagicmod.spinattack.SpinAttack;

/**
 * Photo shoot for the Tome of Spin Attack: sword in the main hand, staff in the off hand, a ring of husks; cast in
 * third person (front) to see the body whirl, then in first person to see the view hold still.
 * Only runs with -Dragnarsmagicmod.spinsmoke=true (./gradlew runSpinSmoke).
 */
public class SpinAttackClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.spinsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "spin_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private void reset(MinecraftClient client) {
        onServer(client, (world, sp) -> {
            world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(40), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
            sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
            sp.getItemCooldownManager().remove(SpinAttack.TOME_OF_SPIN_ATTACK);
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI * 2 / 6 + 0.3;
                MobEntity h = EntityType.HUSK.create(world);
                h.refreshPositionAndAngles(0.5 + Math.cos(a) * 2.6, Y, 0.5 + Math.sin(a) * 2.6, 0f, 0f);
                h.setAiDisabled(true);
                world.spawnEntity(h);
            }
        });
    }

    private void cast(MinecraftClient client, ClientPlayerEntity p) {
        // Main hand holds the sword (no use of its own), so right-click falls through to the staff in the off hand
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
        client.interactionManager.interactItem(p, Hand.OFF_HAND);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> onServer(client, (world, sp) -> {
                sp.getAbilities().invulnerable = true;
                sp.sendAbilitiesUpdate();
            });
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-12, 0, -12), ORIGIN.add(12, 45, 12))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                sp.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
                sp.getInventory().selectedSlot = 0;
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, SpinAttack.TOME_OF_SPIN_ATTACK);
                sp.setStackInHand(Hand.OFF_HAND, staff);
            });
            case 50 -> reset(client);
            // --- Third person, from the front ---
            case 55 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                p.setYaw(0f);
                p.setPitch(15f);
            }
            case 60 -> shot(client, "0_before");
            case 62 -> cast(client, p);
            case 64 -> shot(client, "1a_spin");
            case 66 -> shot(client, "1b_spin");
            case 68 -> shot(client, "1c_spin");
            case 71 -> shot(client, "2a_spin");
            case 73 -> shot(client, "2b_spin");
            case 77 -> shot(client, "3a_last");
            case 79 -> shot(client, "3b_last");
            case 84 -> shot(client, "4_after");
            // --- First person ---
            case 120 -> reset(client);
            case 125 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(0f);
                p.setPitch(10f);
            }
            case 130 -> cast(client, p);
            case 133 -> shot(client, "5_first_person");
            case 143 -> shot(client, "6_first_person_last");
            case 160 -> {
                RagnarsMagicMod.LOGGER.info("[SPIN SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
