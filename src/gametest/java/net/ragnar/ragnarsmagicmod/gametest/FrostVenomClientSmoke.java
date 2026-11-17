package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
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
import net.ragnar.ragnarsmagicmod.frostbite.Frostbite;
import net.ragnar.ragnarsmagicmod.venom.Venom;

import java.util.ArrayList;
import java.util.List;

/**
 * Tomes of Frostbite and Venom in a real client: a row of mobs frozen and then poisoned (screenshots), and the real
 * damage events counted - 40 player hits (about half should land) and 40 mob hits on the player (about a fifth).
 * Only runs with -Dragnarsmagicmod.frostvenomsmoke=true (./gradlew runFrostVenomSmoke).
 */
public class FrostVenomClientSmoke implements ClientModInitializer {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private static final int Y = ORIGIN.getY() + 1;
    private int tick = -1;
    private final List<MobEntity> row = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("ragnarsmagicmod.frostvenomsmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static void log(String s, Object... a) {
        RagnarsMagicMod.LOGGER.info("[FROSTVENOM SMOKE] " + s, a);
    }

    private void shot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, "frostvenom_" + name + ".png", client.getFramebuffer(), t -> {});
    }

    private void onServer(MinecraftClient client, java.util.function.BiConsumer<ServerWorld, ServerPlayerEntity> action) {
        var server = client.getServer();
        var uuid = client.player.getUuid();
        server.execute(() -> action.accept(server.getPlayerManager().getPlayer(uuid).getServerWorld(), server.getPlayerManager().getPlayer(uuid)));
    }

    private void setUp(ServerWorld world, ServerPlayerEntity sp, ItemStack tome, List<EntityType<? extends MobEntity>> types) {
        row.forEach(Entity::discard);
        row.clear();
        sp.getInventory().clear();
        sp.getInventory().setStack(20, tome);
        sp.teleport(world, 0.5, Y, 0.5, 0f, 20f);
        sp.setHealth(sp.getMaxHealth());
        sp.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.NIGHT_VISION, 2000, 0, false, false, false));
        for (int i = 0; i < types.size(); i++) {
            MobEntity m = types.get(i).create(world);
            m.refreshPositionAndAngles(0.5 + (i - (types.size() - 1) / 2.0) * 1.6, Y, 3.0, 180f, 0f);
            m.setAiDisabled(true);
            world.spawnEntity(m);
            row.add(m);
        }
    }

    /** Hits 40 fresh husks once each as the player, and has 40 more hit the player, through the real damage events. */
    private void count(ServerWorld world, ServerPlayerEntity sp, String name, java.util.function.Predicate<LivingEntity> afflicted) {
        int dealt = 0, taken = 0;
        sp.getAbilities().invulnerable = false;
        for (int i = 0; i < 40; i++) {
            var h = EntityType.HUSK.create(world);
            h.refreshPositionAndAngles(-12.5 + i % 8, Y, -10.5 - i / 8, 0f, 0f);
            h.setAiDisabled(true);
            world.spawnEntity(h);
            h.damage(sp.getDamageSources().playerAttack(sp), 1f);
            if (afflicted.test(h)) dealt++;
            sp.timeUntilRegen = 0;
            sp.setHealth(sp.getMaxHealth());
            var z = EntityType.HUSK.create(world);
            z.refreshPositionAndAngles(12.5 + i % 8, Y, -10.5 - i / 8, 0f, 0f);
            z.setAiDisabled(true);
            world.spawnEntity(z);
            sp.damage(sp.getDamageSources().mobAttack(z), 1f);
            if (afflicted.test(z)) taken++;
        }
        sp.setHealth(sp.getMaxHealth());
        log("{}: {} of 40 hits landed it (expect ~20), {} of 40 attackers got it back (expect ~8)", name, dealt, taken);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getServer() == null) return;
        tick++;
        switch (tick) {
            case 40 -> onServer(client, (world, sp) -> {
                world.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
                world.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, world.getServer());
                world.setTimeOfDay(6000);
                world.resetWeather();
                for (BlockPos pos : BlockPos.iterate(ORIGIN.add(-25, 0, -25), ORIGIN.add(25, 20, 25))) {
                    world.setBlockState(pos, pos.getY() == ORIGIN.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
                }
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                sp.changeGameMode(GameMode.SURVIVAL);
            });
            case 55 -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK);

            // 1. Frostbite on a row of mobs
            case 60 -> onServer(client, (world, sp) -> setUp(world, sp, new ItemStack(Frostbite.TOME_OF_FROSTBITE),
                    List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.STRAY)));
            case 80 -> onServer(client, (world, sp) -> {
                StringBuilder s = new StringBuilder();
                for (MobEntity m : row) s.append(m.getType().getUntranslatedName()).append('=').append(Frostbite.freeze(m, sp)).append(' ');
                log("frozen: {}", s);
            });
            case 82 -> shot(client, "1_freeze");
            case 95 -> shot(client, "2_frozen");
            case 112 -> {
                StringBuilder s = new StringBuilder();
                for (MobEntity m : row) s.append(m.getType().getUntranslatedName()).append(' ').append(String.format("%.1f/%.1f ", m.getHealth(), m.getMaxHealth()));
                log("after the thaw: {}", s);
                shot(client, "3_shatter");
            }
            case 130 -> onServer(client, (world, sp) -> count(world, sp, "frostbite", Frostbite::isFrozen));

            // 2. Venom on a row of mobs
            case 200 -> onServer(client, (world, sp) -> {
                world.getEntitiesByClass(Entity.class, sp.getBoundingBox().expand(80), e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
                setUp(world, sp, new ItemStack(Venom.TOME_OF_VENOM),
                        List.of(EntityType.ZOMBIE, EntityType.PIG, EntityType.WITHER_SKELETON, EntityType.SPIDER, EntityType.PIGLIN));
            });
            case 220 -> onServer(client, (world, sp) -> {
                StringBuilder s = new StringBuilder();
                for (MobEntity m : row) s.append(m.getType().getUntranslatedName()).append('=').append(Venom.poison(m)).append(' ');
                log("poisoned: {}", s);
            });
            case 222 -> shot(client, "4_poison");
            case 250 -> shot(client, "5_poisoned");
            case 320 -> {
                StringBuilder s = new StringBuilder();
                for (MobEntity m : row) s.append(m.getType().getUntranslatedName()).append(' ').append(String.format("%.1f/%.1f ", m.getHealth(), m.getMaxHealth()));
                log("after the venom: {}", s);
            }
            case 330 -> onServer(client, (world, sp) -> count(world, sp, "venom", Venom::isPoisoned));
            case 360 -> {
                client.options.setPerspective(Perspective.FIRST_PERSON);
                log("done");
                client.scheduleStop();
            }
            default -> {}
        }
    }
}
