package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A moment of total invulnerability (Resistance XI for {@link #DURATION_TICKS}). Four enchanted shields fly in and
 * spin around the caster for exactly that long, ringing and sparking whenever something hits them, then burst away.
 */
public class AegisSpell implements Spell {
    private static final int DURATION_TICKS = 30;          // how long the Resistance lasts
    private static final int RESISTANCE_LEVEL = 10;        // anything 4+ blocks all damage
    private static final int SHIELD_COUNT = 4;
    private static final float ORBIT_RADIUS = 1.1f;
    private static final float ARRIVE_FROM = 3.5f;         // shields fly in from this far out
    private static final int ARRIVE_TICKS = 5;
    private static final int LEAVE_TICKS = 6;
    private static final float SPIN = 0.35f;               // radians per tick
    private static final float SCALE = 1.1f;

    private static final class Ward {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.ItemDisplayEntity[] shields = new DisplayEntity.ItemDisplayEntity[SHIELD_COUNT];
        int age = 0;
        int flash = 0;   // ticks left on the "hit" flare

        Ward(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
        }
    }

    private static final Map<UUID, Ward> ACTIVE = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Ward> it = ACTIVE.values().iterator();
            while (it.hasNext()) {
                Ward w = it.next();
                if (w.world == world && !tick(w)) {
                    for (var s : w.shields) if (s != null) TempEntities.discard(s);
                    it.remove();
                }
            }
        });
        // The shields ring and spark when they turn a blow aside; the Resistance does the actual blocking
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof PlayerEntity p && isShielded(p) && p.getWorld() instanceof ServerWorld sw) {
                Ward w = ACTIVE.get(p.getUuid());
                if (w.flash == 0) {
                    Vec3d c = p.getPos().add(0, 1.0, 0);
                    sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 25, 0.7, 0.6, 0.7, 0.3);
                    sw.spawnParticles(ParticleTypes.ENCHANTED_HIT, c.x, c.y, c.z, 15, 0.6, 0.6, 0.6, 0.3);
                    sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.2f, 0.9f + sw.random.nextFloat() * 0.3f);
                    w.flash = 4;
                }
            }
            return true;
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, DURATION_TICKS, RESISTANCE_LEVEL, false, false, true));

        Ward old = ACTIVE.remove(player.getUuid());
        if (old != null) for (var s : old.shields) if (s != null) TempEntities.discard(s);

        Ward ward = new Ward(sw, player.getUuid());
        ItemStack shield = new ItemStack(Items.SHIELD);
        shield.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        Vec3d c = center(player);
        for (int i = 0; i < SHIELD_COUNT; i++) {
            DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(sw);
            if (d == null) continue;
            d.setItemStack(shield.copy());
            d.setTransformationMode(ModelTransformationMode.FIXED);
            d.setTeleportDuration(1);
            d.setViewRange(2.0f);
            d.refreshPositionAndAngles(c.x, c.y, c.z, 0f, 0f);
            d.setTransformation(shieldTransform(i, 0, ARRIVE_FROM, SCALE));
            TempEntities.track(d);
            sw.spawnEntity(d);
            ward.shields[i] = d;
        }
        ACTIVE.put(player.getUuid(), ward);

        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 30, 0.2, 0.5, 0.2, 0.25);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.2f, 0.8f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.8f);
        return true;
    }

    /** Returns false once the shields have flown off. */
    private static boolean tick(Ward w) {
        PlayerEntity p = w.world.getPlayerByUuid(w.owner);
        if (p == null || !p.isAlive()) return false;
        w.age++;
        if (w.flash > 0) w.flash--;

        float radius;
        float scale = SCALE;
        if (w.age <= ARRIVE_TICKS) {
            // Fly in and snap into orbit
            radius = MathHelper.lerp(w.age / (float) ARRIVE_TICKS, ARRIVE_FROM, ORBIT_RADIUS);
            if (w.age == ARRIVE_TICKS) {
                w.world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0f, 1.3f);
            }
        } else if (w.age <= DURATION_TICKS) {
            radius = ORBIT_RADIUS + (w.flash > 0 ? 0.15f : 0f);
        } else if (w.age <= DURATION_TICKS + LEAVE_TICKS) {
            // Burst outward and shrink away as the protection ends
            float t = (w.age - DURATION_TICKS) / (float) LEAVE_TICKS;
            radius = MathHelper.lerp(t, ORBIT_RADIUS, ARRIVE_FROM);
            scale = SCALE * (1f - t);
            if (w.age == DURATION_TICKS + 1) {
                Vec3d c = center(p);
                w.world.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y, c.z, 40, 0.8, 0.8, 0.8, 0.8);
                w.world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.6f, 1.5f);
            }
        } else {
            return false;
        }

        Vec3d c = center(p);
        for (int i = 0; i < SHIELD_COUNT; i++) {
            DisplayEntity.ItemDisplayEntity d = w.shields[i];
            if (d == null) continue;
            d.setPosition(c.x, c.y, c.z);
            d.setTransformation(shieldTransform(i, w.age, radius, scale));
            d.setStartInterpolation(0);
            d.setInterpolationDuration(1);
        }

        // A faint shimmering dome while the shields hold
        if (w.age <= DURATION_TICKS) {
            for (int i = 0; i < 6; i++) {
                double a = w.world.random.nextDouble() * MathHelper.TAU;
                double y = (w.world.random.nextDouble() - 0.3) * 1.6;
                double r = ORBIT_RADIUS + 0.2;
                w.world.spawnParticles(ParticleTypes.WAX_OFF, c.x + Math.cos(a) * r, c.y + y, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }
        return true;
    }

    private static Vec3d center(PlayerEntity p) {
        return p.getPos().add(0, p.getHeight() * 0.55, 0);
    }

    /** Shield {@code i} standing upright on the ring, face turned outward, bobbing slightly out of step. */
    private static AffineTransformation shieldTransform(int i, int age, float radius, float scale) {
        float angle = age * SPIN + i * (MathHelper.TAU / SHIELD_COUNT);
        float bob = MathHelper.sin(age * 0.3f + i * 1.7f) * 0.08f;
        return new AffineTransformation(new Matrix4f()
                .rotateY(angle)
                .translate(0, bob, radius)
                .rotateX(-0.12f)
                .scale(scale));
    }

    public static boolean isShielded(PlayerEntity player) {
        Ward w = ACTIVE.get(player.getUuid());
        return w != null && w.age <= DURATION_TICKS;
    }
}
