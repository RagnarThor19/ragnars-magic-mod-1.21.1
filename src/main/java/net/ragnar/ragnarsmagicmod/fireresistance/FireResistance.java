package net.ragnar.ragnarsmagicmod.fireresistance;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.util.PassiveEffect;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Fire Resistance: a passive tome. While it's in your inventory (or on a staff you carry) fire never hurts you
 * (fire, lava, magma, campfires, fireballs: anything Minecraft counts as fire), and you swim through lava at a decent
 * pace: about as fast as a normal swim through water (without the Tome of Swimming), against the crawl lava usually
 * allows. You also get Fire
 * Resistance's icon and its clearer view through lava. Dropping into lava hisses softly with a puff of steam.
 * <p>
 * The lava speed is in PassiveFluidMovementMixin. To remove it: delete this package, {@code FireResistance.register()} in
 * RagnarsMagicMod, its lines in PassiveFluidMovementMixin, {@code FIRE_RESISTANCE} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_fire_resistance.json} and its en_us.json line.
 */
public final class FireResistance {
    private FireResistance() {}

    /** How hard you push through lava (vanilla's is 0.02, and lava's drag halves your speed every tick). */
    public static final float LAVA_PUSH = 0.085f;
    /** Extra upward push when swimming up in lava (vanilla's is 0.04). */
    public static final double LAVA_UP = 0.04;
    private static final int CHECK_EVERY = 10;

    public static final TomeItem TOME_OF_FIRE_RESISTANCE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_fire_resistance"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE).fireproof(), // Passive; it doesn't burn either
                    TomeTier.PASSIVE, SpellId.FIRE_RESISTANCE, 0
            ).setCooldown(0)
    );

    /** Players who were in lava last tick (to hear them drop in). */
    private static final Set<UUID> IN_LAVA = new HashSet<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.FIRE_RESISTANCE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_FIRE_RESISTANCE);
        Spells.register(SpellId.FIRE_RESISTANCE, new PassiveSpell("fire never hurts you and you can swim through lava while this tome is in your inventory or on a staff you carry."));
        ServerTickEvents.END_SERVER_TICK.register(FireResistance::tick);
        // The effect covers this too; this covers the moment after milk clears it, or before the first top-up
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof PlayerEntity p && source.isIn(DamageTypeTags.IS_FIRE) && PassiveTomes.has(p, TOME_OF_FIRE_RESISTANCE)));
    }

    private static void tick(MinecraftServer server) {
        boolean check = server.getTicks() % CHECK_EVERY == 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (check) update(player);
            boolean in = player.isInLava();
            if (in && IN_LAVA.add(player.getUuid()) && PassiveTomes.has(player, TOME_OF_FIRE_RESISTANCE)) hiss(player);
            else if (!in) IN_LAVA.remove(player.getUuid());
        }
    }

    /** Gives {@code player} Fire Resistance if they carry the tome, or takes the tome's away if they don't. */
    public static void update(PlayerEntity player) {
        PassiveEffect.keep(player, StatusEffects.FIRE_RESISTANCE, PassiveTomes.has(player, TOME_OF_FIRE_RESISTANCE));
    }

    /** A soft hiss and a puff of steam as they drop into lava, for everyone near. */
    private static void hiss(ServerPlayerEntity player) {
        ServerWorld sw = player.getServerWorld();
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.35f, 1.4f);
        sw.spawnParticles(ParticleTypes.WHITE_SMOKE, player.getX(), player.getY() + 0.6, player.getZ(), 8, 0.3, 0.2, 0.3, 0.02);
    }

    /** How hard {@code player} pushes through lava, given vanilla's {@code speed}. Both sides call it each tick in lava. */
    public static float lavaSpeed(PlayerEntity player, float speed) {
        return PassiveTomes.has(player, TOME_OF_FIRE_RESISTANCE) ? Math.max(speed, LAVA_PUSH) : speed;
    }

    /** Extra upward push when {@code player} swims up (holding jump) in lava. */
    public static double extraUp(PlayerEntity player) {
        return PassiveTomes.has(player, TOME_OF_FIRE_RESISTANCE) ? LAVA_UP : 0.0;
    }
}
