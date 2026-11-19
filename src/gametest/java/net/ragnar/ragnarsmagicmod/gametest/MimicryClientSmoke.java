package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.mimicry.Mimicry;

/**
 * Photo shoot for the Tome of Mimicry: a row of chests, a barrel, a crafting table and a bookshelf with a gap the
 * caster stands in. They become a chest, the camera turns round to look at the row, they step out with a pop, then
 * hide again as the crafting table. Only runs with -Dragnarsmagicmod.mimicrysmoke=true (./gradlew runMimicrySmoke).
 */
public class MimicryClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.mimicrysmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "mimicry_" + name + ".png", client.getFramebuffer(), t -> {});
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
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-20, 0, -20), ORIGIN.add(20, 10, 20))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.OAK_PLANKS.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // The row, with a gap at x=1 to stand in
                BlockState chest = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
                world.setBlockState(new BlockPos(-2, Y, 2), Blocks.BARREL.getDefaultState());
                world.setBlockState(new BlockPos(-1, Y, 2), chest);
                world.setBlockState(new BlockPos(0, Y, 2), Blocks.CRAFTING_TABLE.getDefaultState());
                world.setBlockState(new BlockPos(2, Y, 2), chest);
                world.setBlockState(new BlockPos(3, Y, 2), Blocks.BOOKSHELF.getDefaultState());
                world.setBlockState(new BlockPos(4, Y, 2), Blocks.FURNACE.getDefaultState());
                world.getEntitiesByClass(LivingEntity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 1.5, Y, 2.5, -90f, 35f); // in the gap, looking at the chest on the right
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Mimicry.TOME_OF_MIMICRY);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 66 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                p.setYaw(180f);
                p.setPitch(15f);
            }
            case 70 -> shot(client, "1_before");
            case 72 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(-90f);
                p.setPitch(35f);
            }
            // --- Become the chest ---
            case 75 -> client.options.useKey.setPressed(true);
            case 76 -> client.options.useKey.setPressed(false);
            case 80 -> shot(client, "2a_fp_disguised");
            case 82 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                p.setYaw(180f);
                p.setPitch(15f);
            }
            case 88 -> shot(client, "2b_which_chest");
            // --- Step out ---
            case 95 -> client.options.backKey.setPressed(true);
            case 99 -> client.options.backKey.setPressed(false);
            case 100 -> shot(client, "3_pop");
            // --- Back in as the crafting table, once the cooldown's up ---
            case 180 -> onServer(client, (world, sp) -> sp.teleport(world, 1.5, Y, 2.5, 90f, 40f));
            case 186 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setYaw(90f);
                p.setPitch(40f);
            }
            case 188 -> client.options.useKey.setPressed(true);
            case 189 -> client.options.useKey.setPressed(false);
            case 193 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                p.setYaw(180f);
                p.setPitch(15f);
            }
            case 199 -> shot(client, "4_crafting_table");
            case 205 -> {
                RagnarsMagicMod.LOGGER.info("[MIMICRY SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
