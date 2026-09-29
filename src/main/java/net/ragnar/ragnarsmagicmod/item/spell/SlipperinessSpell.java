package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.SlipperyPayload;
import net.ragnar.ragnarsmagicmod.util.Aim;
import net.ragnar.ragnarsmagicmod.util.Slippery;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;

/**
 * Tome of Slipperiness. The creature you're looking at slides around like the ground is greased for 15 seconds:
 * it keeps sliding long after it stops walking, and can barely turn or stop. Mobs chasing you overshoot and
 * skate off ledges; players find out the hard way. The physics are in LivingEntityMixin.
 */
public class SlipperinessSpell implements Spell {
    private static final double RANGE = 32.0;
    private static final double AIM_CONE = 6.0;
    private static final int DURATION = 20 * 15;

    private static final DustParticleEffect SHEEN = new DustParticleEffect(new Vector3f(0.55f, 0.85f, 1.0f), 0.8f);

    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Slippery.SERVER.clear());
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Entity aimed = Aim.target(sw, player, RANGE, AIM_CONE, e -> e instanceof LivingEntity);
        if (aimed == null) {
            player.sendMessage(Text.literal("Look at a creature to grease its feet."), true);
            return false;
        }
        LivingEntity target = (LivingEntity) aimed;
        beam(sw, player, target);

        Slippery.SERVER.put(target, DURATION);
        if (target instanceof ServerPlayerEntity p) {
            SlipperyPayload.send(p, DURATION);
            p.sendMessage(Text.literal("Your feet feel very slippery..."), true);
        }

        Vec3d c = target.getPos();
        sw.spawnParticles(ParticleTypes.SPLASH, c.x, c.y + 0.1, c.z, 30, target.getWidth() * 0.6, 0.05, target.getWidth() * 0.6, 0.1);
        sw.spawnParticles(SHEEN, c.x, c.y + 0.1, c.z, 20, target.getWidth() * 0.6, 0.05, target.getWidth() * 0.6, 0);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 1.0f, 1.4f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_HONEY_BLOCK_SLIDE, SoundCategory.PLAYERS, 1.0f, 1.6f);
        return true;
    }

    private static void tick() {
        Iterator<Map.Entry<LivingEntity, Integer>> it = Slippery.SERVER.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, Integer> e = it.next();
            LivingEntity entity = e.getKey();
            int left = e.getValue() - 1;
            if (left <= 0 || !entity.isAlive() || entity.isRemoved()) {
                it.remove();
                if (entity instanceof ServerPlayerEntity p) {
                    SlipperyPayload.send(p, 0);
                    if (entity.isAlive()) p.sendMessage(Text.literal("You've found your footing."), true);
                }
                continue;
            }
            e.setValue(left);

            // A wet sheen and the odd squeak while it slides
            if (!(entity.getWorld() instanceof ServerWorld world) || !entity.isOnGround()) continue;
            Vec3d v = entity.getVelocity();
            double speed = v.horizontalLength();
            if (world.getTime() % 3 == 0) {
                world.spawnParticles(SHEEN, entity.getX(), entity.getY() + 0.05, entity.getZ(), 1, entity.getWidth() * 0.4, 0, entity.getWidth() * 0.4, 0);
            }
            if (speed > 0.08 && world.getTime() % 4 == 0) {
                world.spawnParticles(ParticleTypes.SPLASH, entity.getX(), entity.getY() + 0.05, entity.getZ(), 3, 0.2, 0, 0.2, 0.05);
            }
            if (speed > 0.15 && world.random.nextInt(25) == 0) {
                world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.BLOCK_HONEY_BLOCK_SLIDE,
                        SoundCategory.PLAYERS, 0.6f, 1.5f + world.random.nextFloat() * 0.3f);
            }
        }
    }

    /** A wet streak from the staff to the target. */
    private static void beam(ServerWorld world, PlayerEntity player, LivingEntity target) {
        Vec3d from = player.getEyePos().add(player.getRotationVector().multiply(0.8)).add(0, -0.3, 0);
        Vec3d to = target.getBoundingBox().getCenter();
        Vec3d path = to.subtract(from);
        int steps = (int) (path.length() * 3);
        for (int i = 0; i <= steps; i++) {
            Vec3d p = from.add(path.multiply(i / (double) Math.max(1, steps)));
            world.spawnParticles(SHEEN, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            if (i % 3 == 0) world.spawnParticles(ParticleTypes.FALLING_WATER, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
        }
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_SLIME_JUMP_SMALL, SoundCategory.PLAYERS, 0.8f, 1.5f);
    }
}
