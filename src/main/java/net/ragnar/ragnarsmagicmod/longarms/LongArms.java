package net.ragnar.ragnarsmagicmod.longarms;

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
 * Tome of Long Arms: a passive tome. While it's in your inventory (or on a staff you carry) you reach {@link #EXTRA}
 * blocks further for everything you do to blocks - mining, placing, opening, using - so 4.5 blocks becomes 7, and
 * {@link #EXTRA_CREATURES} further for hitting and using creatures, so 3 becomes 4.
 * <p>
 * It's the game's own two reach attributes, raised by a modifier while the tome is carried and taken off when it
 * isn't; they're synced to the player's client, so what it lets you target matches what the server allows.
 * <p>
 * To remove it: delete this package, {@code LongArms.register()} in RagnarsMagicMod, {@code LONG_ARMS} in SpellId,
 * it in PassiveTomes.register, {@code models/item/tome_of_long_arms.json} and its en_us.json line.
 */
public final class LongArms {
    private LongArms() {}

    public static final double EXTRA = 2.5, EXTRA_CREATURES = 1.0;
    private static final int CHECK_EVERY = 10;
    private static final Identifier MODIFIER = Identifier.of(RagnarsMagicMod.MOD_ID, "long_arms");

    public static final TomeItem TOME_OF_LONG_ARMS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_long_arms"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.LONG_ARMS, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.LONG_ARMS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_LONG_ARMS);
        Spells.register(SpellId.LONG_ARMS, new PassiveSpell("You reach further - blocks out to 7, creatures out to 4 - while this tome is in your inventory or on a staff you carry."));
        ServerTickEvents.END_SERVER_TICK.register(LongArms::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY != 0) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) update(player);
    }

    /** Gives {@code player} the longer reach if they carry the tome, or takes it away if they don't. */
    public static void update(PlayerEntity player) {
        boolean on = PassiveTomes.has(player, TOME_OF_LONG_ARMS);
        set(player, EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, on, EXTRA);
        set(player, EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE, on, EXTRA_CREATURES);
    }

    private static void set(PlayerEntity player, RegistryEntry<EntityAttribute> attribute, boolean on, double amount) {
        EntityAttributeInstance a = player.getAttributeInstance(attribute);
        if (a == null) return;
        EntityAttributeModifier current = a.getModifier(MODIFIER);
        if (on && current != null && current.value() == amount) return;
        if (current != null) a.removeModifier(MODIFIER); // gone, or left over from an older size of the bonus
        if (on) a.addTemporaryModifier(new EntityAttributeModifier(MODIFIER, amount, EntityAttributeModifier.Operation.ADD_VALUE));
    }
}
