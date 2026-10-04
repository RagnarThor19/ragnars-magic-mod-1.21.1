package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.ZapPayload;

/**
 * Hitscan lightning: the instant you cast, whatever is under your crosshair (checked against real hitboxes, point
 * blank included) is struck. The bolt itself - a thin, jagged yellow-white zap racing out from the staff - is
 * drawn by ZapClient.
 */
public final class ZapSpell implements Spell {
    private static final double RANGE = 32.0;
    private static final float DAMAGE = 7.5f;       // three zaps kill a 20 HP mob, even through light armor
    private static final double KNOCK = 0.45;
    private static final double KNOCK_UP = 0.15;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;
        ServerWorld sw = (ServerWorld) world;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d reach = eye.add(look.multiply(RANGE));

        // Hitscan: first block, then the nearest hitbox in front of it
        BlockHitResult blockHit = world.raycast(new RaycastContext(eye, reach,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        boolean hitBlock = blockHit.getType() != HitResult.Type.MISS;
        Vec3d end = hitBlock ? blockHit.getPos() : reach;
        EntityHitResult entityHit = ProjectileUtil.raycast(player, eye, end,
                new Box(eye, end).expand(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e.canHit(),
                eye.squaredDistanceTo(end));

        Entity target = entityHit != null ? entityHit.getEntity() : null;
        Vec3d hitPos = target != null ? entityHit.getPos() : end;
        if (target != null) {
            // Aim the visible bolt at the middle of the body rather than the edge of the hitbox
            hitPos = target.getBoundingBox().getCenter().lerp(entityHit.getPos(), 0.5);
        }

        // Where the staff is, slightly below and to the right of the eyes (the client works out its own when it can)
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() > 1.0e-4 ? right.normalize() : new Vec3d(1, 0, 0);
        Vec3d from = eye.add(look.multiply(0.8)).add(right.multiply(0.35)).add(0, -0.35, 0);

        int hit = target != null ? ZapPayload.ENTITY : hitBlock ? ZapPayload.BLOCK : ZapPayload.MISS;
        Vec3d normal = hit == ZapPayload.BLOCK ? Vec3d.of(blockHit.getSide().getVector()) : look.multiply(-1);
        ZapPayload.broadcast(sw, player.getId(), from, hitPos, hit, normal);

        world.playSound(null, player.getBlockPos(), net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.ZAP_CAST, SoundCategory.PLAYERS, 1.0f, 1.0f);
        // A soft, crisp click as it fires
        world.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.BLOCK_COPPER_BULB_TURN_ON, SoundCategory.PLAYERS,
                0.45f, 1.3f + world.random.nextFloat() * 0.2f);
        world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.9f, 1.7f);
        world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.25f, 2.0f);

        if (target instanceof LivingEntity le) {
            le.damage(world.getDamageSources().playerAttack(player), DAMAGE);
            le.addVelocity(look.x * KNOCK, KNOCK_UP, look.z * KNOCK);
            le.velocityModified = true;
            // Left crackling
            Vec3d c = le.getBoundingBox().getCenter();
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 25, le.getWidth() * 0.5, le.getHeight() * 0.4, le.getWidth() * 0.5, 0.3);
        }
        return true;
    }
}
