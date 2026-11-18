package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.blocktome.BlockTome;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Photo shoot for the Tome of Blocks: a floor patched with trait blocks, a summon (blocks lifting out of the ground),
 * the ring from behind, in front and first person, one tapped shot, then a held barrage into a row of husks.
 * Only runs with -Dragnarsmagicmod.blockssmoke=true (./gradlew runBlocksSmoke).
 */
public class BlockTomeClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private static final Block[] PATCH = {Blocks.PACKED_ICE, Blocks.NETHERRACK, Blocks.SLIME_BLOCK, Blocks.OXIDIZED_COPPER,
            Blocks.TNT, Blocks.AMETHYST_BLOCK, Blocks.GOLD_BLOCK, Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.SAND, Blocks.OBSIDIAN,
            Blocks.MOSS_BLOCK, Blocks.GLOWSTONE};
    private int tick = -1;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.blockssmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "blocks_" + name + ".png", client.getFramebuffer(), t -> {});
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
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-20, 0, -20), ORIGIN.add(20, 10, 30))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A patch of trait blocks set into the grass around the caster
                for (int i = 0; i < PATCH.length; i++) {
                    double a = i * Math.PI * 2 / PATCH.length;
                    world.setBlockState(ORIGIN.add((int) Math.round(Math.cos(a) * 3), 0, (int) Math.round(Math.sin(a) * 3)), PATCH[i].getDefaultState());
                }
                world.getEntitiesByClass(LivingEntity.class, sp.getBoundingBox().expand(60), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                for (int i = 0; i < 4; i++) {
                    MobEntity husk = EntityType.HUSK.create(world);
                    husk.refreshPositionAndAngles(-3 + i * 2 + 0.5, Y, 12.5, 180f, 0f);
                    husk.setAiDisabled(true);
                    husk.setPersistent();
                    world.spawnEntity(husk);
                }
                sp.teleport(world, 0.5, Y, 0.5, 0f, 0f);
                sp.setExperienceLevel(100);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
                ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, BlockTome.TOME_OF_BLOCKS);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
            });
            case 70 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.getInventory().selectedSlot = 0;
                p.setYaw(0f);
                p.setPitch(25f);
            }
            // --- Summon: the blocks lift out of the ground ---
            case 75 -> client.options.useKey.setPressed(true);
            case 77 -> client.options.useKey.setPressed(false);
            case 82 -> shot(client, "1a_rising");
            case 90 -> shot(client, "1b_rising");
            case 120 -> {
                p.setPitch(10f);
                shot(client, "2a_ring_back");
            }
            case 122 -> client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
            case 126 -> shot(client, "2b_ring_front");
            case 160 -> shot(client, "2c_ring_front_letters");
            case 162 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                p.setPitch(-5f);
            }
            case 166 -> shot(client, "2d_ring_fp");
            // --- One tap at a husk ---
            case 170 -> {
                p.setPitch(3f);
                client.options.useKey.setPressed(true);
            }
            case 172 -> client.options.useKey.setPressed(false);
            case 175 -> shot(client, "3a_tap");
            case 176 -> {
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                p.setYaw(-25f);
            }
            case 179 -> shot(client, "3b_tap_flying");
            case 186 -> shot(client, "3c_tap_hit");
            // --- Hold: the barrage ---
            case 195 -> {
                p.setYaw(10f);
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                client.options.useKey.setPressed(true);
            }
            case 204 -> shot(client, "4a_barrage");
            case 208 -> shot(client, "4b_barrage");
            case 213 -> shot(client, "4c_barrage_hits");
            case 225 -> {
                client.options.useKey.setPressed(false);
                client.options.setPerspective(Perspective.FIRST_PERSON);
            }
            case 230 -> shot(client, "5_cooldown");
            case 240 -> {
                RagnarsMagicMod.LOGGER.info("[BLOCKS SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
