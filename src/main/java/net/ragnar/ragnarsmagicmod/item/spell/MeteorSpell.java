package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class MeteorSpell implements Spell {
    private static final double RANGE = 80.0;
    private static final int DROP_HEIGHT = 150;
    private static final int POWER = 9;
    //speed of descent
    private static final double DESCENT_SPEED = -0.10;
    // bigger visual (reads like ~10 blocks wide)
    private static final double VISUAL_RADIUS = 10;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Goat horn variant (use one from the list). 7 is pretty ominous.
        var horn = SoundEvents.GOAT_HORN_SOUNDS.get(7).value();
        world.playSound(
                null,
                player.getBlockPos(),
                horn,
                SoundCategory.PLAYERS,
                1.3f,
                0.55f
        );

        // Find target
        HitResult hr = player.raycast(RANGE, 0f, false);
        Vec3d hitPos;
        if (hr.getType() == HitResult.Type.BLOCK) {
            hitPos = ((BlockHitResult) hr).getPos();
        } else {
            Vec3d eye = player.getEyePos();
            Vec3d dir = player.getRotationVec(1.0f).normalize().multiply(RANGE);
            hitPos = eye.add(dir);
        }

        int cx = MathHelper.floor(hitPos.x);
        int cz = MathHelper.floor(hitPos.z);
        int targetY = MathHelper.floor(hitPos.y);

        int spawnY = MathHelper.clamp(targetY + DROP_HEIGHT, world.getBottomY() + 8, world.getTopY() - 8);
        double sx = cx + 0.5;
        double sy = spawnY + 0.5;
        double sz = cz + 0.5;

        // Spawn powered fireball descending from above
        FireballEntity meteor = new FireballEntity(world, player, new Vec3d(0.0, DESCENT_SPEED, 0.0), POWER);
        meteor.setPosition(sx, sy, sz);
        meteor.setVelocity(0.0, DESCENT_SPEED, 0.0);
        world.spawnEntity(meteor);

        // Ominous cue at target (short, punchy, low pitch)
        world.playSound(
                null,
                cx + 0.5, targetY + 0.1, cz + 0.5,                    // coords
                net.minecraft.sound.SoundEvents.ENTITY_GOAT_SCREAMING_AMBIENT,
                net.minecraft.sound.SoundCategory.PLAYERS,
                2.2f,                                                 // volume
                0.9f                                                 // pitch (lower = eerier)
        );


        // Attach VERY dense trail so it looks massive
        if (world instanceof ServerWorld sw) {
            int ticks = Math.max(40, (int)Math.ceil((spawnY - targetY) / Math.abs(DESCENT_SPEED)));
            net.ragnar.ragnarsmagicmod.util.MeteorEffects.attach(
                    sw,
                    meteor.getUuid(),
                    ticks,
                    VISUAL_RADIUS
            );
        }

        return true;
    }
}
