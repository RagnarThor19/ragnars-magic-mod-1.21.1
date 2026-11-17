package net.ragnar.ragnarsmagicmod.venom;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.EntityEffectParticleEffect;
import net.minecraft.particle.ParticleTypes;
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
import net.ragnar.ragnarsmagicmod.frostbite.Frostbite;
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
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Venom: a passive tome, Frostbite's twin. While it's in your inventory (or on a staff you carry), each melee hit
 * or shot you land on an ordinary vanilla creature or another player (see {@link Frostbite#ordinary}) has a
 * {@link #CHANCE} chance to poison it for {@link #DURATION} ticks; and whenever one of them hits or shoots you, there's a
 * {@link #RETALIATE} chance it's poisoned instead. Unlike frost it works anywhere, the Nether included.
 * <p>
 * It's vanilla Poison I (green hearts and all, cured by milk) on anything that can take it. Undead and spiders shrug off
 * poison in vanilla, so on them the venom does the same damage itself. Like poison it never kills: it stops at half a
 * heart. Green clouds puff off whatever is poisoned for as long as it lasts.
 * <p>
 * To remove it: delete this package, {@code Venom.register()} in RagnarsMagicMod, {@code VENOM} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_venom.json} and its en_us.json line.
 */
public final class Venom {
    private Venom() {}

    /** The chance each hit you land poisons what it hit. */
    public static final float CHANCE = 0.5f;
    /** The chance each hit you take poisons whoever dealt it. */
    public static final float RETALIATE = 0.2f;
    public static final int DURATION = 100;      // 5s, like a splash of Poison
    private static final int DOSE_EVERY = 25;    // Poison I's pace

    private static final DustParticleEffect GREEN = new DustParticleEffect(new Vector3f(0.30f, 0.62f, 0.12f), 1.6f);
    private static final DustParticleEffect LIME = new DustParticleEffect(new Vector3f(0.55f, 0.85f, 0.20f), 1.1f);
    private static final EntityEffectParticleEffect SWIRL =
            EntityEffectParticleEffect.create(ParticleTypes.ENTITY_EFFECT, 0.53f, 0.64f, 0.18f);

    public static final TomeItem TOME_OF_VENOM = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_venom"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.VENOM, 0
            ).setCooldown(0)
    );

    private static final class Poisoned {
        final LivingEntity entity;
        /** True if the venom deals the damage itself (the creature can't take vanilla poison). */
        final boolean selfDosed;
        int left;

        Poisoned(LivingEntity entity, boolean selfDosed) {
            this.entity = entity;
            this.selfDosed = selfDosed;
        }
    }

    private static final Map<UUID, Poisoned> POISONED = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.VENOM, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_VENOM);
        Spells.register(SpellId.VENOM, new PassiveSpell("your hits sometimes poison what they strike, and whatever hits you is sometimes poisoned."));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseTaken, taken, blocked) -> {
            if (taken <= 0f || !entity.isAlive() || !Frostbite.isHit(source)) return;
            LivingEntity attacker = (LivingEntity) source.getAttacker();
            if (attacker == entity) return;
            if (attacker instanceof ServerPlayerEntity p && PassiveTomes.has(p, TOME_OF_VENOM)
                    && p.getRandom().nextFloat() < CHANCE) poison(entity);
            if (entity instanceof ServerPlayerEntity p && attacker.isAlive() && PassiveTomes.has(p, TOME_OF_VENOM)
                    && p.getRandom().nextFloat() < RETALIATE) poison(attacker);
        });
        ServerTickEvents.END_WORLD_TICK.register(Venom::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> POISONED.clear());
    }

    /** True while {@code entity} has the venom in it. */
    public static boolean isPoisoned(LivingEntity entity) {
        return POISONED.containsKey(entity.getUuid());
    }

    /** Poisons {@code target} if it can be: returns whether it did. The dice are rolled by the caller, so tests can call this. */
    public static boolean poison(LivingEntity target) {
        if (!(target.getWorld() instanceof ServerWorld world) || !target.isAlive() || !Frostbite.ordinary(target)) return false;
        StatusEffectInstance effect = new StatusEffectInstance(StatusEffects.POISON, DURATION, 0, false, false, true);
        boolean vanilla = target.canHaveStatusEffect(effect);
        if (vanilla) target.addStatusEffect(effect);

        Poisoned p = POISONED.get(target.getUuid());
        if (p == null || p.selfDosed != !vanilla) {
            p = new Poisoned(target, !vanilla);
            POISONED.put(target.getUuid(), p);
        }
        p.left = DURATION;

        // A sickly green puff as it takes hold
        Vec3d c = target.getBoundingBox().getCenter();
        double w = target.getWidth() * 0.5, h = target.getHeight() * 0.4;
        world.spawnParticles(ParticleTypes.SNEEZE, c.x, c.y, c.z, 10, w, h, w, 0.03);
        world.spawnParticles(GREEN, c.x, c.y, c.z, 12, w + 0.1, h, w + 0.1, 0);
        world.spawnParticles(ParticleTypes.ITEM_SLIME, c.x, c.y, c.z, 6, w, h, w, 0.05);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 0.6f, 0.7f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.15f, 1.9f);
        return true;
    }

    private static void tick(ServerWorld world) {
        if (POISONED.isEmpty()) return;
        Iterator<Poisoned> it = POISONED.values().iterator();
        while (it.hasNext()) {
            Poisoned p = it.next();
            LivingEntity e = p.entity;
            if (e.getWorld() != world && !e.isRemoved()) continue;
            // Gone, run its course, or (vanilla poison) cured with milk
            if (!e.isAlive() || e.isRemoved() || --p.left <= 0 || (!p.selfDosed && !e.hasStatusEffect(StatusEffects.POISON))) {
                it.remove();
                continue;
            }

            if (p.selfDosed && p.left % DOSE_EVERY == 0 && e.getHealth() > 1.0f) {
                int before = e.timeUntilRegen;
                e.timeUntilRegen = 0;
                e.damage(world.getDamageSources().magic(), Math.min(1.0f, e.getHealth() - 1.0f));
                e.timeUntilRegen = before;
            }

            // Green clouds rolling off them, and the swirl of poison
            double w = e.getWidth() * 0.5;
            if (world.getTime() % 2 == 0) {
                world.spawnParticles(ParticleTypes.SNEEZE, e.getX(), e.getBodyY(0.2 + world.random.nextDouble() * 0.7), e.getZ(),
                        2, w, 0.15, w, 0.015);
                world.spawnParticles(SWIRL, e.getX(), e.getBodyY(world.random.nextDouble()), e.getZ(), 1, w, 0.1, w, 1.0);
            }
            world.spawnParticles(world.random.nextBoolean() ? GREEN : LIME, e.getX(), e.getBodyY(0.3 + world.random.nextDouble() * 0.8), e.getZ(),
                    1, w * 0.9, 0.15, w * 0.9, 0);
        }
    }
}
