package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class ArrowVolleySpell implements Spell {

    private static final int CHARGE_TICKS = 20; // 1 second charge
    private static final float VELOCITY = 7.0f; // much faster than bow (usually ~3.0 max)
    private static final float DIVERGENCE = 0.5f; // very accurate
    private static final double DAMAGE = 2.0;

    // Tracking active charges
    private static final Map<RegistryKey<World>, List<ChargingArrow>> CHARGING = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(ArrowVolleySpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // FIX 1: Added .value() because SoundEvents returns a RegistryEntry in 1.21
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE.value(), SoundCategory.PLAYERS, 1.0f, 1.5f);

        // Add to charging list
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
                // === CHARGING PHASE ===
                // Spawn particles around the player's hand/body
                if (elapsed % 2 == 0) {
                    Vec3d pos = p.getPos().add(0, 1.2, 0)
                            .add(p.getRotationVector().normalize().multiply(0.5));

                    world.spawnParticles(ParticleTypes.CRIT,
                            pos.x, pos.y, pos.z,
                            2, 0.1, 0.1, 0.1, 0.05);

                    world.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                            pos.x, pos.y, pos.z,
                            1, 0.1, 0.1, 0.1, 0.05);
                }
            } else {
                // === FIRE PHASE ===
                // Launch the arrow
                ItemStack arrowStack = new ItemStack(Items.ARROW);

                // FIX 2: Constructor expects (World, LivingEntity, ItemStack, ItemStack)
                // Removed .getDefaultStack() which does not exist on ItemStack
                ArrowEntity arrow = new ArrowEntity(world, p, arrowStack, null);

                arrow.setVelocity(p, p.getPitch(), p.getYaw(), 0.0F, VELOCITY, DIVERGENCE);

                // Buff the arrow
                arrow.setDamage(DAMAGE);

                arrow.pickupType = PersistentProjectileEntity.PickupPermission.ALLOWED;

                world.spawnEntity(arrow);

                // Boom sound
                world.playSound(null, p.getBlockPos(),
                        SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 2.0f); // High pitch = fast

                // Recoil
                p.addVelocity(p.getRotationVector().multiply(-0.4));
                p.velocityModified = true;

                it.remove();
            }
        }
    }

    private record ChargingArrow(UUID owner, long startTime) {}
}