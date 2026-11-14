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
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizard;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizardEntity;
import net.ragnar.ragnarsmagicmod.wizard.WizardSpell;

/**
 * Photo shoot for the Wandering Wizard: him and his horse standing about with his runes drifting round his head, a
 * husk provoking him so he casts (wind-up, circle, release), and at night the invisibility spell. Only runs with
 * -Dragnarsmagicmod.wizardsmoke=true (./gradlew runWizardSmoke).
 */
public class WizardClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 200, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private int provokedAt = -1000;
    private WanderingWizardEntity wizard;
    private MobEntity husk;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.wizardsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "wizard_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(uuid);
            action.accept(sp.getServerWorld(), sp);
        });
    }

    /** A husk off to his side hits him, so he turns side-on to the camera to fight it. */
    private void provoke(MinecraftClient client, WizardSpell... spells) {
        onServer(client, (world, sp) -> {
            if (husk != null) husk.discard();
            husk = EntityType.HUSK.create(world);
            husk.refreshPositionAndAngles(7.5, Y, 7.5, 90f, 0f);
            husk.setAiDisabled(true);
            husk.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(1000);
            husk.setHealth(1000);
            world.spawnEntity(husk);
            wizard.setSpells(java.util.List.of(spells));
            wizard.setHealth(wizard.getMaxHealth());
            wizard.damage(world.getDamageSources().mobAttack(husk), 0.01f);
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
                sp.changeGameMode(GameMode.CREATIVE);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-25, 0, -15), ORIGIN.add(25, 12, 35))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.teleport(world, 0.5, Y, 0.5, 0f, 12f);
                sp.getAbilities().flying = false;
                sp.sendAbilitiesUpdate();
                wizard = WanderingWizard.WANDERING_WIZARD.spawn(world, new BlockPos(0, Y, 7), SpawnReason.SPAWN_EGG);
                wizard.setAiDisabled(false);
                wizard.setPositionTarget(new BlockPos(0, Y, 7), 1);
                wizard.refreshPositionAndAngles(0.5, Y, 7.5, 180f, 0f);
            });
            case 60 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                client.options.hudHidden = true;
                p.setYaw(0f);
                p.setPitch(12f);
            }
            case 90 -> shot(client, "1a_idle");
            case 100 -> shot(client, "1b_idle");
            case 105 -> onServer(client, (world, sp) -> sp.teleport(world, 0.5, Y, 4.6, 0f, 18f));
            case 112 -> {
                p.setYaw(0f);
                p.setPitch(18f);
            }
            case 118 -> shot(client, "1c_close");
            case 122 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(net.minecraft.entity.passive.HorseEntity.class, sp.getBoundingBox().expand(40), h -> true).forEach(Entity::discard);
                wizard.refreshPositionAndAngles(0.5, Y, 7.5, 180f, 0f);
                sp.teleport(world, 3.5, Y + 2.5, -5.5, 0f, 12f);
            });
            case 126 -> {
                p.setYaw(0f);
                p.setPitch(12f);
                provoke(client, WizardSpell.STONE_CANNON, WizardSpell.BOULDERS, WizardSpell.ROCKS);
                provokedAt = tick;
            }
            case 560 -> provoke(client, WizardSpell.IMPALING, WizardSpell.THUNDERSTRIKE);
            case 1000 -> {
                onServer(client, (world, sp) -> {
                    if (husk != null) husk.discard();
                    wizard.setTarget(null);
                    world.setTimeOfDay(18000);
                });
            }
            case 1080 -> {
                RagnarsMagicMod.LOGGER.info("[WIZARD SMOKE] done");
                client.scheduleStop();
            }
            default -> {}
        }
        // Through each cast, a picture every few ticks
        if (tick > 126 && tick < 1000 && tick % 5 == 0) shot(client, String.format("2_cast_%04d", tick));
    }
}
