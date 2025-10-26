package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public final class BlinkSpell implements Spell {
    private static final double MAX_RANGE = 24.0;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // 1) Raycast where the player is looking (up to MAX_RANGE)
        HitResult hr = player.raycast(MAX_RANGE, 0f, false);
        Vec3d eye = player.getEyePos();
        Vec3d dest;

        if (hr.getType() == HitResult.Type.BLOCK) {
            // If we hit a block, step back a little from the face so we don't clip in
            Vec3d hit = ((BlockHitResult) hr).getPos();
            Vec3d dir = player.getRotationVec(1.0f).normalize();
            dest = hit.subtract(dir.multiply(0.6));
        } else {
            // Miss → end of the ray
            Vec3d dir = player.getRotationVec(1.0f).normalize().multiply(MAX_RANGE);
            dest = eye.add(dir);
        }

        // 2) Find a safe landing spot (2-block headroom). Try slight vertical adjustments.
        Vec3d safe = findSafeSpot(world, dest, 4);
        if (safe == null) {
            // Try from just before the block along the ray as a fallback
            Vec3d dir = player.getRotationVec(1.0f).normalize();
            Vec3d fallback = eye.add(dir.multiply(MAX_RANGE - 1.0));
            safe = findSafeSpot(world, fallback, 4);
        }
        if (safe == null) {
            // Last resort: try current spot (small step forward). If still null, fail cast.
            Vec3d dir = player.getRotationVec(1.0f).normalize();
            safe = findSafeSpot(world, player.getPos().add(dir.multiply(0.8)), 2);
            if (safe == null) return false; // no valid blink
        }

        // 3) FX at origin
        if (world instanceof ServerWorld sw) {
            spawnBlinkParticles(sw, player.getX(), player.getY() + 0.1, player.getZ());
        }
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);

        // 4) Teleport
        player.requestTeleport(safe.x, safe.y, safe.z);

        // 5) FX at destination
        if (world instanceof ServerWorld sw2) {
            spawnBlinkParticles(sw2, safe.x, safe.y, safe.z);
        }
        world.playSound(null, BlockPos.ofFloored(safe),
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);

        // 6) QoL: no fall damage from blink; tiny resistance so you don't get frame-1 deleted
        player.fallDistance = 0.0f;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 10, 0, false, false));

        return true;
    }

    private static void spawnBlinkParticles(ServerWorld sw, double x, double y, double z) {
        // Purple end vibes
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 40, 0.6, 1.0, 0.6, 0.02);
        sw.spawnParticles(ParticleTypes.PORTAL,         x, y, z, 25, 0.5, 0.8, 0.5, 0.02);
        sw.spawnParticles(ParticleTypes.DRAGON_BREATH,  x, y, z, 10, 0.4, 0.3, 0.4, 0.0);
    }

    /**
     * Try to find a safe spot near 'pos' with up to 'verticalTries' adjustments.
     * We require: two non-solid blocks for feet+head. We allow blinking midair.
     */
    private static Vec3d findSafeSpot(World world, Vec3d pos, int verticalTries) {
        int baseX = MathHelper.floor(pos.x);
        int baseZ = MathHelper.floor(pos.z);
        double y = pos.y;

        // Try small vertical adjustments: first current Y, then up to 3 blocks up, then 1 down.
        int[] offsets = new int[Math.max(1, verticalTries)];
        // e.g., [0, 1, 2, 3] then [-1]
        int idx = 0;
        offsets[idx++] = 0;
        if (verticalTries > 1) offsets[idx++] = 1;
        if (verticalTries > 2) offsets[idx++] = 2;
        if (verticalTries > 3) offsets[idx++] = 3;

        for (int off : offsets) {
            int yi = MathHelper.floor(y) + off;
            BlockPos feet = new BlockPos(baseX, yi, baseZ);
            BlockPos head = feet.up();

            if (isAiry(world, feet) && isAiry(world, head)) {
                // good enough; return centered on the block
                return new Vec3d(baseX + 0.5, yi, baseZ + 0.5);
            }
        }
        // small try below
        {
            int yi = MathHelper.floor(y) - 1;
            BlockPos feet = new BlockPos(baseX, yi, baseZ);
            BlockPos head = feet.up();
            if (isAiry(world, feet) && isAiry(world, head)) {
                return new Vec3d(baseX + 0.5, yi, baseZ + 0.5);
            }
        }
        return null;
    }

    private static boolean isAiry(World world, BlockPos pos) {
        var state = world.getBlockState(pos);
        // Safe if no collision box (player won’t suffocate)
        return state.isAir() || state.getCollisionShape(world, pos).isEmpty();
    }
}
