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
import net.ragnar.ragnarsmagicmod.sleep.Sleep;

/**
 * Photo shoot for the Tomes of Sleep Darts and Sleep Potions: darts a husk, lobs a potion into a little herd, then
 * puts the player to sleep and slaps them awake. Saves screenshots along the way. Only runs with
 * -Dragnarsmagicmod.sleepsmoke=true (./gradlew runSleepSmoke).
 */
public class SleepClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.sleepsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "sleep_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private MobEntity spawn(ServerWorld world, EntityType<? extends MobEntity> type, double x, double z) {
        MobEntity mob = type.create(world);
        mob.refreshPositionAndAngles(x, Y, z, 180f, 0f);
        mob.setPersistent();
        world.spawnEntity(mob);
        return mob;
    }

    private void select(MinecraftClient client, ClientPlayerEntity p, int index) {
        onServer(client, (world, sp) -> StaffItem.setSelectedIndex(sp.getMainHandStack(), index));
        StaffItem.setSelectedIndex(p.getMainHandStack(), index);
    }

    private void cast(MinecraftClient client, ClientPlayerEntity p) {
        onServer(client, (world, sp) -> {
            sp.getItemCooldownManager().remove(Sleep.TOME_OF_SLEEP_DARTS);
            sp.getItemCooldownManager().remove(Sleep.TOME_OF_SLEEP_POTIONS);
        });
        client.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 1 -> {
                if (p.isDead()) p.requestRespawn();
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
                sp.setHealth(sp.getMaxHealth());
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Sleep.TOME_OF_SLEEP_DARTS);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Sleep.TOME_OF_SLEEP_POTIONS);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                spawn(world, EntityType.HUSK, 0.5, 8.5).setAiDisabled(true); // stays put, out of the potion's way
                spawn(world, EntityType.PIG, -1.5, 15.5);
                spawn(world, EntityType.COW, 2.5, 16.5);
                spawn(world, EntityType.SHEEP, 0.5, 18.0);
            });
            case 66 -> select(client, p, 0);
            case 70 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(4f);
            }
            // The dart: a second's charge, then it flies
            case 72 -> cast(client, p);
            case 82 -> shot(client, "1a_charging");
            case 93 -> shot(client, "1b_fired");
            case 105 -> shot(client, "1c_drowsy");
            case 150 -> shot(client, "1d_asleep");
            case 152 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            case 158 -> shot(client, "1e_asleep_from_behind");
            // The potion, lobbed into the herd
            case 162 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                select(client, p, 1);
            }
            case 166 -> {
                p.setYaw(-12f);
                p.setPitch(-6f);
            }
            case 168 -> cast(client, p);
            case 176 -> shot(client, "2a_flying");
            case 186 -> {
                p.setPitch(12f);
                shot(client, "2b_burst");
            }
            case 196 -> shot(client, "2c_cloud");
            case 245 -> shot(client, "2d_herd_asleep");
            // Side on, close up: the husk slumped and hanging its head
            case 246 -> onServer(client, (world, sp) -> sp.teleport(world, -2.0, Y, 8.5, -90f, 15f));
            case 249 -> shot(client, "2e_husk_side");
            // Now the caster's turn: nodding off, then out cold
            case 250 -> onServer(client, (world, sp) -> Sleep.putToSleep(world, sp));
            case 262 -> shot(client, "3a_drowsy_first_person");
            case 300 -> shot(client, "3b_asleep_first_person");
            case 302 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 320 -> shot(client, "3c_asleep_front");
            case 324 -> onServer(client, (world, sp) -> sp.damage(world.getDamageSources().generic(), 1f));
            case 326 -> shot(client, "3d_woken");
            case 330 -> {
                RagnarsMagicMod.LOGGER.info("[SLEEP SMOKE] done, player asleep={}", Sleep.isAsleep(p));
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
