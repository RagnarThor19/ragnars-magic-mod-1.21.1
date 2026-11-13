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
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.terraform.Terraform;
import net.ragnar.ragnarsmagicmod.terraform.TerraformMode;
import net.ragnar.ragnarsmagicmod.terraform.TerraformSettings;
import net.ragnar.ragnarsmagicmod.terraform.client.TerraformClient;
import net.ragnar.ragnarsmagicmod.terraform.client.TerraformScreen;

/**
 * Photo shoot for the Tome of Terraforming, cast for real through the staff (so the client's aim and settings go to
 * the server just as in play): rolling hills are laid out, then the menu, and for several modes the preview, the land
 * rippling into shape, and the result. Only runs with -Dragnarsmagicmod.terraformsmoke=true (./gradlew runTerraformSmoke).
 */
public class TerraformClientSmoke implements ClientModInitializer {
    private static final int BASE = 200;
    private int tick = -1;

    /** One mode to show: what to set, where to stand and look. */
    private record Scene(String name, TerraformMode mode, int radius, int strength, boolean soft, double x, double z, float yaw, float pitch) {}

    private static final Scene[] SCENES = {
            new Scene("flatten", TerraformMode.FLATTEN, 7, 0, true, 0.5, -6.5, 0f, 32f),
            new Scene("terrace", TerraformMode.TERRACE, 9, 3, true, 22.5, -6.5, 0f, 30f),
            new Scene("cliff", TerraformMode.CLIFF, 7, 6, true, -20.5, -6.5, 0f, 30f),
            new Scene("raise", TerraformMode.RAISE, 7, 7, true, 0.5, 18.5, 0f, 34f),
            new Scene("ramp", TerraformMode.RAMP, 2, 0, true, 22.5, 18.5, 0f, 22f),
    };
    private static final int MENU = 60, FIRST = 90, SCENE_TICKS = 70;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.terraformsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "terraform_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    /** Rolling grassland: swells and dips of a few blocks, stone under a couple of dirt, the odd flower. */
    private static int hill(int x, int z) {
        return BASE + (int) Math.round(4 + 3 * MathHelper.sin(x / 6f) + 2.2 * MathHelper.cos(z / 5f) + 1.5 * MathHelper.sin((x + z) / 3.3f));
    }

    private void lay(ServerWorld world) {
        for (int x = -40; x <= 42; x++) {
            for (int z = -20; z <= 40; z++) {
                int h = hill(x, z);
                for (int y = BASE - 6; y <= BASE + 22; y++) {
                    Block b = y > h ? Blocks.AIR : y == h ? Blocks.GRASS_BLOCK : y >= h - 2 ? Blocks.DIRT : Blocks.STONE;
                    world.setBlockState(new BlockPos(x, y, z), b.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
                if (Math.floorMod(x * 7 + z * 13, 23) == 0) world.setBlockState(new BlockPos(x, h + 1, z), Blocks.POPPY.getDefaultState(), Block.NOTIFY_LISTENERS);
                if (Math.floorMod(x * 5 + z * 3, 17) == 0) world.setBlockState(new BlockPos(x, h + 1, z), Blocks.SHORT_GRASS.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.getServer() == null) return;
        tick++;
        if (tick == 1) {
            onServer(client, (world, sp) -> {
                sp.getAbilities().invulnerable = true;
                sp.sendAbilitiesUpdate();
            });
        } else if (tick == 40) {
            onServer(client, (world, sp) -> {
                sp.changeGameMode(GameMode.CREATIVE);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(5000);
                world.resetWeather();
                lay(world);
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.getInventory().clear();
                ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
                ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Terraform.TOME_OF_TERRAFORMING);
                sp.getInventory().setStack(0, staff);
                sp.getInventory().selectedSlot = 0;
                sp.getAbilities().flying = true;
                sp.sendAbilitiesUpdate();
                sp.teleport(world, 0.5, hill(0, -6) + 7, -6.5, 0f, 32f);
            });
        } else if (tick == MENU) {
            client.options.setPerspective(Perspective.FIRST_PERSON);
            client.setScreen(new TerraformScreen());
        } else if (tick == MENU + 6) {
            shot(client, "0_menu");
        } else if (tick == MENU + 10) {
            client.setScreen(null);
        } else if (tick >= FIRST) {
            int i = (tick - FIRST) / SCENE_TICKS, t = (tick - FIRST) % SCENE_TICKS;
            if (i >= SCENES.length) {
                if (t == 0) {
                    RagnarsMagicMod.LOGGER.info("[TERRAFORM SMOKE] done");
                    client.scheduleStop();
                }
                return;
            }
            Scene s = SCENES[i];
            switch (t) {
                case 0 -> {
                    TerraformSettings set = TerraformClient.settings();
                    set.mode = s.mode();
                    set.radius = s.radius();
                    set.softEdge = s.soft();
                    if (s.mode().hasStrength()) set.setStrength(s.strength());
                    TerraformClient.onSettingsChanged();
                    onServer(client, (world, sp) -> {
                        sp.teleport(world, s.x(), hill((int) s.x(), (int) s.z()) + 7, s.z(), s.yaw(), s.pitch());
                        sp.getItemCooldownManager().remove(Terraform.TOME_OF_TERRAFORMING);
                    });
                }
                case 4 -> {
                    p.setYaw(s.yaw());
                    p.setPitch(s.pitch());
                }
                case 14 -> shot(client, (i + 1) + "a_" + s.name() + "_preview");
                case 16 -> client.interactionManager.interactItem(p, Hand.MAIN_HAND);
                case 22 -> shot(client, (i + 1) + "b_" + s.name() + "_shaping");
                case 50 -> shot(client, (i + 1) + "c_" + s.name() + "_done");
                default -> {}
            }
        }
    }
}
