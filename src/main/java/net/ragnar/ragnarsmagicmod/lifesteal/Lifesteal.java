package net.ragnar.ragnarsmagicmod.lifesteal;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Lifesteal: a passive tome. While it's in your inventory (or on a staff you carry), damage you deal to mobs
 * heals you by {@link #SHARE} of it: swords, arrows, spells, anything that counts as yours. Overkill doesn't count
 * (you can't drain more than the mob has), other players don't count, and nothing happens at full health.
 * <p>
 * A thin thread of red drifts from the mob toward you, with a soft heartbeat you alone hear (at most once a second).
 * To remove it: delete this package, {@code Lifesteal.register()} in RagnarsMagicMod, {@code LIFESTEAL} in SpellId, it
 * in PassiveTomes.register, {@code models/item/tome_of_lifesteal.json} and its en_us.json line.
 */
public final class Lifesteal {
    private Lifesteal() {}

    /** The share of damage dealt that comes back as health. */
    public static final float SHARE = 0.2f;
    private static final int SOUND_EVERY = 20;
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.75f, 0.05f, 0.1f), 0.7f);

    public static final TomeItem TOME_OF_LIFESTEAL = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_lifesteal"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.LIFESTEAL, 0
            ).setCooldown(0)
    );

    /** When each player last heard the heartbeat. */
    private static final Map<UUID, Integer> LAST_SOUND = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.LIFESTEAL, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_LIFESTEAL);
        Spells.register(SpellId.LIFESTEAL, new PassiveSpell("damage you deal to mobs heals you while this tome is in your inventory or on a staff you carry."));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseTaken, taken, blocked) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity player) drain(player, entity, taken);
        });
    }

    /** {@code player} has just dealt {@code damage} to {@code target}: heal them their share. Public so tests can call it. */
    public static void drain(ServerPlayerEntity player, LivingEntity target, float damage) {
        if (!(target instanceof MobEntity) || damage <= 0f || !player.isAlive() || player.getHealth() >= player.getMaxHealth()
                || !PassiveTomes.has(player, TOME_OF_LIFESTEAL)) return;
        player.heal(Math.min(damage, target.getMaxHealth()) * SHARE);

        ServerWorld sw = player.getServerWorld();
        // A thread of red from the mob toward you
        Vec3d from = target.getPos().add(0, target.getHeight() * 0.6, 0), to = player.getPos().add(0, player.getHeight() * 0.5, 0);
        for (int i = 1; i <= 5; i++) {
            Vec3d at = from.lerp(to, i / 7.0);
            sw.spawnParticles(BLOOD, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0);
        }
        int now = sw.getServer().getTicks();
        Integer last = LAST_SOUND.get(player.getUuid());
        if (last == null || now - last >= SOUND_EVERY) {
            LAST_SOUND.put(player.getUuid(), now);
            player.playSoundToPlayer(SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 0.35f, 1.5f);
        }
    }

    /** True if {@code player} heals from the damage they deal. */
    public static boolean has(PlayerEntity player) {
        return PassiveTomes.has(player, TOME_OF_LIFESTEAL);
    }
}
