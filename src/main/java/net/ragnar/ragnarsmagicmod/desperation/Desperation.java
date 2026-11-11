package net.ragnar.ragnarsmagicmod.desperation;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
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

import java.util.EnumMap;

/**
 * Tome of Desperation: a passive tome. While it's in your inventory (or on a staff you carry) and you're down to
 * {@link #THRESHOLD} health (3 hearts) or less, you hit {@link #DAMAGE_BONUS} harder and move {@link #SPEED_BONUS}
 * faster. Heal above it and it's gone again.
 * <p>
 * You feel it rather than see an icon: the edges of the screen pulse red with your heartbeat, faster and stronger the
 * closer you are to death, each pulse with a soft thump (see DesperationClient). The bonuses are attribute modifiers, so
 * the client already knows how fast you move. To remove it: delete this package, {@code Desperation.register()} in
 * RagnarsMagicMod, {@code DesperationClient.init()} in RagnarsMagicModClient, {@code DESPERATION} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_desperation.json} and its en_us.json line.
 */
public final class Desperation {
    private Desperation() {}

    /** At or below this much health (in half hearts) it kicks in. */
    public static final float THRESHOLD = 6f;
    /** Extra melee damage and movement speed, as a share of what you'd have otherwise. */
    public static final double DAMAGE_BONUS = 0.5, SPEED_BONUS = 0.3;
    private static final int CHECK_EVERY = 2;

    private static final Identifier DAMAGE = Identifier.of(RagnarsMagicMod.MOD_ID, "desperation_damage");
    private static final Identifier SPEED = Identifier.of(RagnarsMagicMod.MOD_ID, "desperation_speed");

    public static final TomeItem TOME_OF_DESPERATION = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_desperation"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.DESPERATION, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.DESPERATION, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_DESPERATION);
        Spells.register(SpellId.DESPERATION, new PassiveSpell("at 3 hearts or less you hit harder and move faster."));
        ServerTickEvents.END_SERVER_TICK.register(Desperation::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY != 0) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) update(player);
    }

    /** True if {@code player} is desperate right now: carrying the tome, alive, and at {@link #THRESHOLD} or less. Both sides. */
    public static boolean desperate(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && !player.getAbilities().invulnerable && player.getHealth() <= THRESHOLD
                && PassiveTomes.has(player, TOME_OF_DESPERATION);
    }

    /** Server: gives {@code player} the bonuses while they're desperate, and takes them away otherwise. Public for tests. */
    public static void update(PlayerEntity player) {
        boolean on = desperate(player);
        set(player, EntityAttributes.GENERIC_ATTACK_DAMAGE, DAMAGE, DAMAGE_BONUS, on);
        set(player, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED, SPEED_BONUS, on);
    }

    private static void set(PlayerEntity player, RegistryEntry<EntityAttribute> attribute, Identifier id, double amount, boolean on) {
        EntityAttributeInstance a = player.getAttributeInstance(attribute);
        if (a == null || a.hasModifier(id) == on) return;
        if (on) a.addTemporaryModifier(new EntityAttributeModifier(id, amount, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        else a.removeModifier(id);
    }
}
