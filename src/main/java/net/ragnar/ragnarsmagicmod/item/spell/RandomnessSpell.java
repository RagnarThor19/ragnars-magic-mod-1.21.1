// File: src/main/java/net/ragnar/ragnarsmagicmod/item/spell/RandomnessSpell.java
package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class RandomnessSpell implements Spell {

    private static final int CHARGE_TICKS = 20; // 1 second charge
    private static final float VELOCITY = 7.0f; // Fast
    private static final float DIVERGENCE = 0.5f; // Accurate

    // Damage scaled for high velocity
    private static final double DAMAGE = 2.5;

    // Using RegistryEntry for 1.21 StatusEffects
    private static final List<RegistryEntry<StatusEffect>> RANDOM_EFFECTS = Arrays.asList(
            StatusEffects.POISON,
            StatusEffects.INSTANT_DAMAGE,
            StatusEffects.SLOWNESS,
            StatusEffects.WEAKNESS,
            StatusEffects.WITHER,
            StatusEffects.HUNGER,
            StatusEffects.NAUSEA
    );

    private static final Map<RegistryKey<World>, List<ChargingArrow>> CHARGING = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(RandomnessSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE.value(), SoundCategory.PLAYERS, 1.0f, 1.2f);

        ServerWorld sw = (ServerWorld) world;
        CHARGING.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new ChargingArrow(player.getUuid(), sw.getTime()));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<ChargingArrow> list = CHARGING.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<ChargingArrow> it = list.iterator();

        while (it.hasNext()) {
            ChargingArrow c = it.next();
            PlayerEntity p = world.getPlayerByUuid(c.owner);

            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }

            long elapsed = now - c.startTime;

            if (elapsed < CHARGE_TICKS) {
                // === CHARGING PARTICLES ===
                if (elapsed % 2 == 0) {
                    Vec3d pos = p.getPos().add(0, 1.2, 0)
                            .add(p.getRotationVector().normalize().multiply(0.5));

                    world.spawnParticles(ParticleTypes.WITCH,
                            pos.x, pos.y, pos.z,
                            2, 0.1, 0.1, 0.1, 0.05);

                    world.spawnParticles(ParticleTypes.ENCHANTED_HIT,
                            pos.x, pos.y, pos.z,
                            1, 0.1, 0.1, 0.1, 0.05);
                }
            } else {
                // === FIRE PHASE ===
                PersistentProjectileEntity projectile;

                // 20% Chance for Spectral Arrow
                if (world.random.nextFloat() < 0.2f) {
                    ItemStack spectralStack = new ItemStack(Items.SPECTRAL_ARROW);

                    // FIX: Using the (world, owner, stack, shotFrom) constructor you provided
                    projectile = new SpectralArrowEntity(world, p, spectralStack, null);
                } else {
                    // Tipped Arrow with Random Effect
                    ItemStack arrowStack = new ItemStack(Items.ARROW);

                    ArrowEntity arrow = new ArrowEntity(world, p, arrowStack, null);

                    // Add Random Effect
                    RegistryEntry<StatusEffect> effect = RANDOM_EFFECTS.get(world.random.nextInt(RANDOM_EFFECTS.size()));
                    arrow.addEffect(new StatusEffectInstance(effect, 100, 1));

                    projectile = arrow;
                }

                // Physics & Damage
                projectile.setVelocity(p, p.getPitch(), p.getYaw(), 0.0F, VELOCITY, DIVERGENCE);
                projectile.setDamage(DAMAGE);
                projectile.pickupType = PersistentProjectileEntity.PickupPermission.ALLOWED;

                world.spawnEntity(projectile);

                world.playSound(null, p.getBlockPos(),
                        SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 1.8f);

                // Recoil
                p.addVelocity(p.getRotationVector().multiply(-0.4));
                p.velocityModified = true;

                it.remove();
            }
        }
    }

    private record ChargingArrow(UUID owner, long startTime) {}
}