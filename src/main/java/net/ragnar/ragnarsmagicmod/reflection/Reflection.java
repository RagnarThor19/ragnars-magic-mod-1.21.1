package net.ragnar.ragnarsmagicmod.reflection;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ProjectileDeflection;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.MathHelper;
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
 * Tome of Reflection: a passive tome. While it's in your inventory (or on a staff you carry), each arrow, trident,
 * fireball, skull, snowball, potion or other projectile that hits you has a {@link #CHANCE} chance to glance off
 * untouched and fly straight back at whoever threw it - aimed to hit, a little faster than it came. It's yours once it
 * bounces, so it counts as your shot.
 * <p>
 * A bright ting and a spray of sparks where it glanced off. Ender pearls, fishing hooks, fireworks and wind charges
 * pass as normal, and so does anything you threw yourself. It's in ReflectionProjectileMixin. To remove it: delete this
 * package and that mixin (and its line in ragnarsmagicmod.mixins.json), {@code Reflection.register()} in
 * RagnarsMagicMod, {@code REFLECTION} in SpellId, it in PassiveTomes.register,
 * {@code models/item/tome_of_reflection.json} and its en_us.json line.
 */
public final class Reflection {
    private Reflection() {}

    /** The chance each projectile has of bouncing back. */
    public static final float CHANCE = 0.25f;
    /** Bounced projectiles fly back this much faster, and at least this fast. */
    private static final double SPEED_BOOST = 1.15, MIN_SPEED = 0.8;

    public static final TomeItem TOME_OF_REFLECTION = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_reflection"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.REFLECTION, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.REFLECTION, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_REFLECTION);
        Spells.register(SpellId.REFLECTION, new PassiveSpell("projectiles that hit you sometimes bounce back at whoever threw them."));
    }

    /** True if {@code projectile} is the kind that can be bounced back at {@code player} (ignoring the dice). */
    public static boolean reflectable(ProjectileEntity projectile, ServerPlayerEntity player) {
        if (projectile instanceof EnderPearlEntity || projectile instanceof FishingBobberEntity
                || projectile instanceof FireworkRocketEntity || projectile instanceof WindChargeEntity) return false;
        return projectile.getOwner() != player && player.isAlive() && !player.isSpectator()
                && PassiveTomes.has(player, TOME_OF_REFLECTION);
    }

    /**
     * Server: {@code projectile} has just hit {@code player}. Rolls the dice and, if it comes up, sends it back at its
     * thrower and returns true (then it doesn't hit). {@code force} skips the dice, for tests.
     */
    public static boolean tryReflect(ProjectileEntity projectile, ServerPlayerEntity player, boolean force) {
        if (!reflectable(projectile, player)) return false;
        if (!force && player.getRandom().nextFloat() >= CHANCE) return false;
        Entity shooter = projectile.getOwner();
        Vec3d v = projectile.getVelocity();
        double speed = Math.max(MIN_SPEED, v.length() * SPEED_BOOST);

        // Back at the shooter, leading a little high for anything that drops; or straight back if there's no one
        Vec3d from = projectile.getPos();
        Vec3d dir;
        if (shooter instanceof LivingEntity s && s.isAlive() && s.getWorld() == projectile.getWorld()) {
            Vec3d at = s.getBoundingBox().getCenter();
            double ticks = at.distanceTo(from) / speed;
            dir = at.add(0, 0.5 * projectile.getFinalGravity() * ticks * ticks, 0).subtract(from).normalize();
        } else {
            dir = v.lengthSquared() > 1.0e-6 ? v.normalize().negate() : player.getRotationVector();
        }

        // It's yours now: it can't hit you again, and counts as your shot
        projectile.deflect(ProjectileDeflection.SIMPLE, player, player, false);
        Vec3d out = dir.multiply(speed);
        Vec3d start = from.add(dir.multiply(0.6));
        projectile.setPosition(start.x, start.y, start.z);
        projectile.setVelocity(out);
        projectile.setYaw((float) (MathHelper.atan2(out.x, out.z) * MathHelper.DEGREES_PER_RADIAN));
        projectile.setPitch((float) (MathHelper.atan2(out.y, out.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN));
        projectile.prevYaw = projectile.getYaw();
        projectile.prevPitch = projectile.getPitch();
        projectile.velocityModified = true;
        effects(player, from);
        return true;
    }

    /** A bright ting and a spray of sparks where it glanced off you. */
    private static void effects(ServerPlayerEntity player, Vec3d at) {
        ServerWorld sw = player.getServerWorld();
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 12, 0.15, 0.15, 0.15, 0.3);
        sw.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.1, 0.1, 0.1, 0.3);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.4f, 1.8f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_BREEZE_DEFLECT, SoundCategory.PLAYERS, 0.7f, 1.3f);
    }
}
