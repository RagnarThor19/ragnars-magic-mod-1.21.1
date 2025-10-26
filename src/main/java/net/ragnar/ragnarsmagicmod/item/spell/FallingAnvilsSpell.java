package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.Blocks;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
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

public final class FallingAnvilsSpell implements Spell {
    private static final double RANGE = 48.0;   // aim distance
    private static final int DROP_HEIGHT = 30;  // blocks above target

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Raycast where the player is looking
        HitResult hr = player.raycast(RANGE, 0f, false);
        Vec3d hitPos;
        if (hr.getType() == HitResult.Type.BLOCK) {
            hitPos = ((BlockHitResult) hr).getPos();
        } else {
            Vec3d eye = player.getEyePos();
            Vec3d dir = player.getRotationVec(1.0f).normalize().multiply(RANGE);
            hitPos = eye.add(dir);
        }

        // Grid center + height clamp
        int cx = MathHelper.floor(hitPos.x);
        int cz = MathHelper.floor(hitPos.z);
        int cy = MathHelper.clamp(MathHelper.floor(hitPos.y) + DROP_HEIGHT,
                world.getBottomY() + 5, world.getTopY() - 5);

        // Cast sound (deep cue)
        world.playSound(
                null,
                player.getBlockPos(),
                SoundEvents.BLOCK_ANVIL_PLACE,
                SoundCategory.PLAYERS,
                0.9f,
                0.8f
        );

        // Spawn a 3x3 of damaged anvils that always shatter
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                double spawnX = cx + dx + 0.5;
                double spawnY = cy + 0.01;
                double spawnZ = cz + dz + 0.5;

                BlockPos spawnPos = BlockPos.ofFloored(spawnX, spawnY, spawnZ);

                // Spawn via factory (constructor is private in 1.21.1)
                FallingBlockEntity ent = FallingBlockEntity.spawnFromBlock(
                        world,
                        spawnPos,
                        Blocks.DAMAGED_ANVIL.getDefaultState()
                );

                // Always break, no drops
                ent.setDestroyedOnLanding();
                ent.dropItem = false;

                // Start falling immediately, hit harder
                ent.setVelocity(0.0, -0.05, 0.0);
                ent.setHurtEntities(6.0F, 60);

                // Schedule ground impact FX (sound + particles) when it hits
                // Raycast straight down to find ground height under spawn point
                var downResult = world.raycast(new RaycastContext(
                        new Vec3d(spawnX, spawnY, spawnZ),
                        new Vec3d(spawnX, world.getBottomY() + 1, spawnZ),
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.ANY,
                        player
                ));

                double groundY = (downResult instanceof BlockHitResult bhr)
                        ? bhr.getPos().y
                        : (world.getBottomY() + 1);

                double dropDist = Math.max(0.0, spawnY - groundY);
                int ticksToImpact = net.ragnar.ragnarsmagicmod.util.SpellEffects.estimateFallTicks(dropDist);

                if (world instanceof ServerWorld sw) {
                    net.ragnar.ragnarsmagicmod.util.SpellEffects.scheduleImpact(
                            sw,
                            spawnX, groundY + 0.1, spawnZ,
                            ticksToImpact
                    );
                }
            }
        }

        return true;
    }
}
