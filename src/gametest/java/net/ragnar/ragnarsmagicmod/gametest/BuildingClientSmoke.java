package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.client.building.BuildingClient;
import net.ragnar.ragnarsmagicmod.client.building.BuildingScreen;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer;
import net.ragnar.ragnarsmagicmod.util.building.BuildPlanner;
import net.ragnar.ragnarsmagicmod.util.building.BuildSettings;
import net.ragnar.ragnarsmagicmod.util.building.BuildShape;

import java.io.File;

/**
 * End-to-end client check for the Tome of Building: joins a world, sets up a scene, uses the staff through the
 * real right-click path and saves screenshots along the way. Only runs with -Dragnarsmagicmod.smoketest=true
 * (./gradlew runClientSmoke), and only exists in the test source set.
 */
public class BuildingClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private int tick = -1;
    private int failures;
    private int xpBefore;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.smoketest")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void log(String msg) {
        RagnarsMagicMod.LOGGER.info("[SMOKE] " + msg);
    }

    private void check(boolean ok, String msg) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + msg);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "smoke_" + name + ".png", client.getFramebuffer(), t -> {});
        log("screenshot " + name);
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        IntegratedServer server = client.getServer();
        ClientPlayerEntity player = client.player;
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(player.getUuid());
            action.accept(sp.getServerWorld(), sp);
        });
    }

    private void look(ClientPlayerEntity p, float yaw, float pitch) {
        p.setYaw(yaw);
        p.setPitch(pitch);
        p.prevYaw = yaw;
        p.prevPitch = pitch;
        p.setHeadYaw(yaw);
    }

    /** XP points from the level and bar (totalExperience isn't updated when levels are set directly). */
    private int xp(ClientPlayerEntity p) {
        int total = 0;
        for (int i = 0; i < p.experienceLevel; i++) total += i >= 30 ? 112 + 9 * (i - 30) : i >= 15 ? 37 + 5 * (i - 15) : 7 + 2 * i;
        return total + Math.round(p.experienceProgress * p.getNextLevelExperience());
    }

    private int count(ClientPlayerEntity p, Item item) {
        return BuildPlanner.count(p, item);
    }

    private int blocksAround(MinecraftClient client, net.minecraft.block.Block block) {
        int n = 0;
        for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-8, 1, -8), ORIGIN.add(8, 10, 8))) {
            if (client.world.getBlockState(pos).isOf(block)) n++;
        }
        return n;
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 40 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-8, 0, -8), ORIGIN.add(8, 12, 8))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                // A cliff to test building against a side face, and a block in the way of the first wall
                for (int y = 1; y <= 3; y++) for (int x = -8; x <= 8; x++) world.setBlockState(ORIGIN.add(x, y, -8), Blocks.STONE.getDefaultState());
                world.setBlockState(ORIGIN.add(0, 2, -2), Blocks.GOLD_BLOCK.getDefaultState());
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 2.5, 180f, 30f);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, ModItems.TOME_OF_FIREBALLS);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, ModItems.TOME_OF_BUILDING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().setStack(1, new ItemStack(Items.STONE_BRICKS, 64));
                sp.getInventory().setStack(2, new ItemStack(Items.COBBLESTONE, 64));
                sp.getInventory().setStack(3, new ItemStack(Items.MOSSY_STONE_BRICKS, 20));
                sp.getInventory().setStack(4, new ItemStack(Items.GLASS, 10));
                sp.getInventory().setStack(5, new ItemStack(Items.OAK_PLANKS, 64));
                sp.getInventory().setStack(6, new ItemStack(Items.OAK_PLANKS, 64));
                sp.getInventory().setStack(9, new ItemStack(Items.CHEST, 3));
                sp.getInventory().selectedSlot = 0;
            });
            case 60 -> {
                p.getInventory().selectedSlot = 0;
                look(p, 180f, 22f);
                BuildSettings s = BuildingClient.settings();
                s.shape = BuildShape.WALL;
                s.width = 5;
                s.height = 5;
                s.hollow = false;
                s.pattern = BlockMixer.Pattern.LAYERED;
                s.palette.clear();
                s.palette.add(new BuildSettings.Entry(Items.COBBLESTONE, 1, BlockMixer.Layer.BOTTOM));
                s.palette.add(new BuildSettings.Entry(Items.STONE_BRICKS, 1, BlockMixer.Layer.MIDDLE));
                s.palette.add(new BuildSettings.Entry(Items.MOSSY_STONE_BRICKS, 1, BlockMixer.Layer.TOP));
                BuildingClient.onSettingsChanged();
            }
            case 80 -> {
                check(BuildingClient.isActive(p), "building tome is active in hand");
                BuildPlanner.Plan plan = BuildingClient.currentPlan();
                check(plan != null, "preview has a plan");
                if (plan != null) {
                    log("plan: free=" + plan.free().size() + " blocked=" + plan.blocked().size() + " needed=" + plan.needed()
                            + " available=" + plan.available() + " target=" + plan.target());
                    check(plan.needed() == 24 && plan.blocked().size() == 1, "5x5 wall with the gold block in the way needs 24");
                    check(plan.enough(), "enough blocks");
                }
                shot(client, "1_preview");
            }
            case 90 -> client.setScreen(new BuildingScreen());
            case 108 -> client.getToastManager().clear();
            case 110 -> shot(client, "2_menu");
            case 115 -> client.setScreen(null);
            case 120 -> {
                int before = count(p, Items.COBBLESTONE) + count(p, Items.STONE_BRICKS) + count(p, Items.MOSSY_STONE_BRICKS);
                log("blocks before: " + before);
                xpBefore = xp(p);
                // The real right-click path: staff use -> spell -> request packet -> server build
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 124 -> shot(client, "3_mid_build");
            case 150 -> {
                int after = count(p, Items.COBBLESTONE) + count(p, Items.STONE_BRICKS) + count(p, Items.MOSSY_STONE_BRICKS);
                int placed = blocksAround(client, Blocks.COBBLESTONE) + blocksAround(client, Blocks.STONE_BRICKS)
                        + blocksAround(client, Blocks.MOSSY_STONE_BRICKS);
                log("blocks after: " + after + ", placed in world: " + placed);
                check(placed == 24, "24 blocks placed");
                check(after == 148 - 24, "24 blocks taken from the inventory");
                check(xp(p) == xpBefore - 2, "a build costs 2 XP (" + xpBefore + " -> " + xp(p) + ")");
                xpBefore = xp(p);
                check(client.world.getBlockState(ORIGIN.add(0, 2, -2)).isOf(Blocks.GOLD_BLOCK), "gold block untouched");
                shot(client, "4_built");
            }
            // Hollow sphere with glass: needs more than the 10 glass carried -> refused
            case 160 -> {
                BuildSettings s = BuildingClient.settings();
                s.shape = BuildShape.SPHERE;
                s.width = 5;
                s.hollow = true;
                s.pattern = BlockMixer.Pattern.RANDOM;
                s.palette.clear();
                s.palette.add(new BuildSettings.Entry(Items.GLASS, 1, BlockMixer.Layer.BOTTOM));
                BuildingClient.onSettingsChanged();
                look(p, 90f, 30f);
            }
            case 175 -> {
                BuildPlanner.Plan plan = BuildingClient.currentPlan();
                check(plan != null && !plan.enough(), "preview shows not enough glass");
                shot(client, "5_not_enough");
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 200 -> {
                check(count(p, Items.GLASS) == 10, "no glass used when short");
                check(xp(p) == xpBefore, "a refused build costs no XP");
                shot(client, "5b_short_message");
                check(blocksAround(client, Blocks.GLASS) == 0, "nothing built when short");
            }
            // Resize down to 3 with the scroll helper: now 10 glass is enough for a hollow 3-sphere? (it needs 18) -> use planks
            case 210 -> {
                BuildSettings s = BuildingClient.settings();
                s.palette.clear();
                s.palette.add(new BuildSettings.Entry(Items.OAK_PLANKS, 1, BlockMixer.Layer.BOTTOM));
                s.palette.add(new BuildSettings.Entry(Items.GLASS, 1, BlockMixer.Layer.TOP));
                s.pattern = BlockMixer.Pattern.CHECKER;
                BuildingClient.onSettingsChanged();
            }
            case 225 -> {
                BuildPlanner.Plan plan = BuildingClient.currentPlan();
                check(plan != null && plan.enough(), "planks + glass is enough");
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 228 -> shot(client, "6_sphere_mid");
            case 255 -> {
                int glass = blocksAround(client, Blocks.GLASS), planks = blocksAround(client, Blocks.OAK_PLANKS);
                log("sphere: glass=" + glass + " planks=" + planks);
                check(glass > 0 && planks > 0 && glass <= 10, "checker sphere used both, within supply");
                shot(client, "7_sphere");
            }
            // Bridge off the cliff face: aim at the side of the stone wall
            case 265 -> {
                BuildSettings s = BuildingClient.settings();
                s.shape = BuildShape.FLOOR;
                s.width = 3;
                s.length = 5;
                s.hollow = false;
                s.pattern = BlockMixer.Pattern.RANDOM;
                s.palette.clear();
                s.palette.add(new BuildSettings.Entry(Items.OAK_PLANKS, 1, BlockMixer.Layer.BOTTOM));
                BuildingClient.onSettingsChanged();
                onServer(client, (world, sp) -> sp.teleport(world, 5.5, ORIGIN.getY() + 1, -1.5, 180f, 0f));
            }
            case 280 -> {
                look(p, 180f, 8f);
                BuildPlanner.Plan plan = BuildingClient.currentPlan();
                log("bridge plan: " + (plan == null ? "none" : plan.target() + " needed=" + plan.needed()));
                check(plan != null && plan.target().face() == net.minecraft.util.math.Direction.SOUTH, "aiming at the cliff's side");
                shot(client, "8_bridge_preview");
            }
            // Creative (no hearts) with nothing chosen: the message must not sit on top of the HUD line
            case 285 -> {
                onServer(client, (world, sp) -> sp.changeGameMode(GameMode.CREATIVE));
                BuildingClient.settings().palette.clear();
                BuildingClient.onSettingsChanged();
                look(p, 180f, 30f);
            }
            case 292 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 296 -> shot(client, "9_creative_message");
            // ---- Tomes of Shrinking, Growing and Invisibility, each on its own staff (cooldowns are shared per staff)
            case 300 -> onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.SURVIVAL);
                sp.setExperienceLevel(30);
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-8, 1, -8), ORIGIN.add(8, 12, 8))) world.setBlockState(pos, Blocks.AIR.getDefaultState());
                // A half-slab "secret entrance": slab in the top half of a floor-level block
                world.setBlockState(ORIGIN.add(3, 1, 0), Blocks.OAK_SLAB.getDefaultState()
                        .with(net.minecraft.block.SlabBlock.TYPE, net.minecraft.block.enums.SlabType.TOP));
                sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, -90f, 10f);
                sp.getInventory().clear();
                StaffItem staffItem = (StaffItem) ModItems.DIAMOND_STAFF;
                ItemStack[] staffs = {new ItemStack(ModItems.DIAMOND_STAFF), new ItemStack(ModItems.DIAMOND_STAFF), new ItemStack(ModItems.DIAMOND_STAFF)};
                staffItem.insertTome(staffs[0], ModItems.TOME_OF_SHRINKING);
                staffItem.insertTome(staffs[1], ModItems.TOME_OF_GROWING);
                staffItem.insertTome(staffs[2], (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) ModItems.TOME_INVISIBILITY);
                for (int i = 0; i < 3; i++) sp.getInventory().setStack(i, staffs[i]);
                sp.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                sp.getInventory().selectedSlot = 0;
            });
            case 310 -> {
                p.getInventory().selectedSlot = 0;
                client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
                xpBefore = xp(p);
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 335 -> {
                check(p.getHeight() < 0.5f && p.getHeight() > 0.4f, "tiny: " + p.getHeight() + " blocks tall");
                check(xp(p) < xpBefore, "shrinking cost XP");
                onServer(client, (world, sp) -> sp.teleport(world, 3.5, ORIGIN.getY() + 1, 0.5, -90f, 10f));
            }
            case 350 -> {
                check(Math.abs(p.getX() - 3.5) < 0.01 && p.getY() == ORIGIN.getY() + 1, "standing under the slab");
                check(!p.isInsideWall(), "not suffocating under the slab");
                shot(client, "10_tiny_under_slab");
                client.interactionManager.interactItem(p, Hand.MAIN_HAND); // try to grow back: no room
            }
            case 370 -> {
                check(p.getHeight() < 0.5f, "stays tiny under the slab");
                onServer(client, (world, sp) -> sp.teleport(world, 0.5, ORIGIN.getY() + 1, 0.5, -90f, 10f));
            }
            case 395 -> {
                check(Math.abs(p.getHeight() - 1.8f) < 0.01f, "grew back once out: " + p.getHeight());
                check(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_SHRINKING), "shrinking cooldown started");
                p.getInventory().selectedSlot = 1;
            }
            case 400 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 425 -> {
                check(Math.abs(p.getHeight() - 6.0f) < 0.01f, "giant: " + p.getHeight() + " blocks tall");
                check(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.STRENGTH), "giant has strength");
                shot(client, "11_giant");
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 445 -> {
                check(Math.abs(p.getHeight() - 1.8f) < 0.01f, "back from giant: " + p.getHeight());
                p.getInventory().selectedSlot = 2;
            }
            case 450 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            case 460 -> {
                check(p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY), "invisible");
                check(net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility.CLIENT.contains(p.getId()), "client told we're fully hidden");
                shot(client, "12_invisible_self_view");
                client.interactionManager.interactItem(p, Hand.MAIN_HAND);
            }
            case 470 -> {
                check(!p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.INVISIBILITY), "visible again");
                check(!net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility.CLIENT.contains(p.getId()), "client told we're visible");
                check(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_INVISIBILITY), "invisibility cooldown started");
            }
            case 480 -> {
                client.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON);
                log("stats: " + (failures == 0 ? "ALL PASSED" : failures + " FAILED"));
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
