package net.ragnar.ragnarsmagicmod.knockback;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.TagKey;
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

import java.util.EnumMap;

/**
 * Tome of Knockback: a passive tome. While it's in your inventory (or on a staff you carry), anything that hurts you up
 * close - a mob's bite or swing, or another player's hit - is thrown back from you as if struck by a Knockback II sword.
 * Arrows, fangs, explosions and other things from a distance don't count, nor does anything that doesn't actually hurt
 * you (a hit your shield blocks). Bosses and the heaviest mobs (the {@code ragnarsmagicmod:knockback_immune} entity
 * tag: the Ender Dragon, the Wither, Wardens, Iron Golems, Ravagers and Elder Guardians) stand their ground.
 * <p>
 * The shove: a ring of air pulses out from you toward them, they're caught in a little gust, and you hear vanilla's own
 * knockback thump under a soft burst of wind. Everyone nearby sees and hears it. To remove it: delete this package,
 * {@code Knockback.register()} in RagnarsMagicMod, {@code KNOCKBACK} in SpellId, it in PassiveTomes.register,
 * {@code data/ragnarsmagicmod/tags/entity_type/knockback_immune.json}, {@code models/item/tome_of_knockback.json} and its
 * en_us.json line.
 */
public final class Knockback {
    private Knockback() {}

    /** How hard they're thrown: what a Knockback II sword hit gives ({@code 2 * 0.5}). */
    public static final double STRENGTH = 1.0;
    /** Anything further than this isn't "up close" (a long-armed mob still is). */
    private static final double REACH = 6.0;

    /** Mobs too heavy to be shoved. */
    public static final TagKey<EntityType<?>> IMMUNE = TagKey.of(RegistryKeys.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "knockback_immune"));

    public static final TomeItem TOME_OF_KNOCKBACK = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_knockback"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.KNOCKBACK, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.KNOCKBACK, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_KNOCKBACK);
        Spells.register(SpellId.KNOCKBACK, new PassiveSpell("anything that hurts you up close is knocked back, while this tome is in your inventory or on a staff you carry."));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseTaken, taken, blocked) -> {
            if (entity instanceof ServerPlayerEntity victim && taken > 0f) retaliate(victim, source);
        });
    }

    /**
     * {@code victim} has just been hurt by {@code source}: if it was something up close and they carry the tome, throw it
     * back. Returns whether it did. Public so tests can call it.
     */
    public static boolean retaliate(ServerPlayerEntity victim, DamageSource source) {
        if (!(source.getAttacker() instanceof LivingEntity attacker) || source.getSource() != attacker) return false;
        if (attacker == victim || !attacker.isAlive() || source.isIn(DamageTypeTags.IS_EXPLOSION)) return false;
        if (attacker.getType().isIn(IMMUNE) || attacker.squaredDistanceTo(victim) > REACH * REACH) return false;
        if (!victim.isAlive() || !PassiveTomes.has(victim, TOME_OF_KNOCKBACK)) return false;

        // Straight away from you (or the way you're facing, if they're right on top of you)
        Vec3d away = new Vec3d(attacker.getX() - victim.getX(), 0, attacker.getZ() - victim.getZ());
        if (away.lengthSquared() < 1.0e-4) away = Vec3d.fromPolar(0f, victim.getYaw());
        away = away.normalize();
        attacker.takeKnockback(STRENGTH, -away.x, -away.z);
        attacker.velocityModified = true; // a player is moved by their own client, so tell it
        effects(victim, attacker, away);
        return true;
    }

    /**
     * A ring of air that pulses out from you and spreads as it travels toward them, a little gust where it catches them,
     * and vanilla's knockback thump under a soft burst of wind.
     */
    private static void effects(ServerPlayerEntity victim, LivingEntity attacker, Vec3d away) {
        ServerWorld sw = victim.getServerWorld();
        Vec3d center = victim.getPos().add(0, victim.getHeight() * 0.55, 0).add(away.multiply(0.6));
        // Two directions across the ring: sideways and up
        Vec3d side = new Vec3d(-away.z, 0, away.x), up = new Vec3d(0, 1, 0);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            Vec3d out = side.multiply(Math.cos(a)).add(up.multiply(Math.sin(a)));
            Vec3d at = center.add(out.multiply(0.45));
            Vec3d v = out.multiply(0.2).add(away.multiply(0.2));
            // Count 0: the "spread" is the particle's own velocity
            sw.spawnParticles(ParticleTypes.POOF, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0);
        }
        sw.spawnParticles(ParticleTypes.SMALL_GUST, attacker.getX(), attacker.getY() + attacker.getHeight() * 0.5, attacker.getZ(),
                1, 0, 0, 0, 0);

        double x = victim.getX(), y = victim.getY() + 1, z = victim.getZ();
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 0.9f, 0.85f);
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.3f, 1.6f);
    }
}
