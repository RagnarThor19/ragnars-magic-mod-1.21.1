package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.EntityPose;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.BlinkPayload;

/**
 * Tome of Blinking: vanish and reappear exactly where you're aiming, up to {@link #MAX_RANGE} blocks away.
 * Aim at the top of something and you land on it; aim at a wall near its top edge and you pull yourself up onto it;
 * aim at a wall and you end up right in front of it; aim at open air and you appear out there, mid-air. You keep
 * your momentum (plus a little shove forward), so blinks can be chained through the air. The camera zips across and
 * a streak of End particles marks the path.
 */
public final class BlinkSpell implements Spell {
    private static final double MAX_RANGE = 32.0;
    private static final double MANTLE_REACH = 1.2; // how close to a wall's top edge counts as "grab the ledge"
    private static final double PUSH = 0.25;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;

        Vec3d eye = player.getEyePos();
        Vec3d dir = player.getRotationVector();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(dir.multiply(MAX_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));

        Vec3d dest = landing(sw, player, eye, dir, hit);
        if (dest == null) {
            sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), SoundCategory.PLAYERS, 0.6f, 1.6f);
            return false;
        }

        Vec3d from = player.getPos();
        Vec3d momentum = player.getVelocity();

        // Leaving: the air snaps shut where you stood
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0, from.z, 50, 0.3, 0.6, 0.3, 0.05);
        sw.spawnParticles(ParticleTypes.POOF, from.x, from.y + 1.0, from.z, 6, 0.2, 0.4, 0.2, 0.01);
        sw.playSound(null, from.x, from.y, from.z, SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.8f);

        // The path: a streak of End particles from where you were to where you are
        Vec3d span = dest.subtract(from);
        int steps = (int) Math.min(64, span.length() * 2);
        for (int i = 1; i < steps; i++) {
            Vec3d p = from.add(span.multiply(i / (double) steps)).add(0, 1.0, 0);
            sw.spawnParticles(ParticleTypes.PORTAL, p.x, p.y, p.z, 2, 0.1, 0.2, 0.1, 0.1);
            if (i % 3 == 0) sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.01);
        }

        player.stopRiding();
        sp.networkHandler.requestTeleport(dest.x, dest.y, dest.z, player.getYaw(), player.getPitch());
        // Carry your momentum through, with a little shove the way you're facing
        player.setVelocity(momentum.add(dir.multiply(PUSH)));
        player.velocityModified = true;
        player.fallDistance = 0.0f;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 10, 0, false, false));
        BlinkPayload.send(sp);

        // Arriving: a burst of End light and a clear ding
        sw.spawnParticles(ParticleTypes.PORTAL, dest.x, dest.y + 1.0, dest.z, 60, 0.4, 0.7, 0.4, 0.6);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, dest.x, dest.y + 1.0, dest.z, 25, 0.2, 0.5, 0.2, 0.08);
        sw.spawnParticles(ParticleTypes.END_ROD, dest.x, dest.y + 1.0, dest.z, 8, 0.2, 0.4, 0.2, 0.06);
        sw.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.3f);
        sw.playSound(null, dest.x, dest.y, dest.z, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 1.0f, 1.5f);
        return true;
    }

    /** Where your feet should end up, or null if there's nowhere to go. */
    private static Vec3d landing(ServerWorld world, PlayerEntity player, Vec3d eye, Vec3d dir, BlockHitResult hit) {
        double eyeHeight = player.getStandingEyeHeight();
        Vec3d target;
        if (hit.getType() != HitResult.Type.BLOCK) {
            // Open air: appear where you were looking, mid-air
            target = eye.add(dir.multiply(MAX_RANGE)).subtract(0, eyeHeight, 0);
        } else {
            Vec3d p = hit.getPos();
            BlockPos block = hit.getBlockPos();
            Direction side = hit.getSide();
            Vec3d normal = Vec3d.of(side.getVector());
            if (side == Direction.UP) {
                target = p;                                                // stand on it
            } else if (side == Direction.DOWN) {
                target = p.subtract(0, player.getHeight() + 0.01, 0);     // hang right under it
            } else {
                double top = block.getY() + 1.0;
                if (top - p.y <= MANTLE_REACH && fits(world, player, new Vec3d(p.x, top, p.z).subtract(normal.multiply(0.4)))) {
                    // Near a wall's top edge: pull yourself up onto it
                    target = new Vec3d(p.x, top, p.z).subtract(normal.multiply(0.4));
                } else {
                    // Otherwise, right in front of the wall at the height you aimed
                    target = p.add(normal.multiply(player.getWidth() / 2 + 0.05)).subtract(0, eyeHeight * 0.5, 0);
                }
            }
        }

        // Nudge into a spot you fit: a little up or down first, then back along the way you came
        double[] lifts = {0.0, 0.5, 1.0, -0.5, 1.5, -1.0};
        for (double back = 0; back <= MAX_RANGE; back += 0.5) {
            Vec3d base = target.subtract(dir.multiply(back));
            for (double lift : lifts) {
                Vec3d at = base.add(0, lift, 0);
                if (fits(world, player, at)) return at;
            }
            if (base.squaredDistanceTo(player.getPos()) < 1.0) break;
        }
        return null;
    }

    /** Room to stand, and never into lava. */
    private static boolean fits(ServerWorld world, PlayerEntity player, Vec3d feet) {
        Box box = player.getDimensions(EntityPose.STANDING).getBoxAt(feet);
        if (!world.isSpaceEmpty(player, box)) return false;
        return BlockPos.stream(box.contract(0.05)).noneMatch(p -> world.getFluidState(p).isIn(FluidTags.LAVA));
    }
}
