package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.Blocks;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class FallingAnvilsSpell implements Spell {
    private static final double RANGE = 48.0; // how far we aim
    private static final int DROP_HEIGHT = 30; // how high above target we spawn

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Raycast to where the player is looking
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

        // 3x3 grid of falling anvils (damaged so players can't really use/repair farm)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                BlockPos spawnPos = BlockPos.ofFloored(x + 0.5, cy + 0.01, z + 0.5);

                // Use DAMAGED_ANVIL (you can swap to CHIPPED_ANVIL if you want it weaker)
                FallingBlockEntity ent = FallingBlockEntity.spawnFromBlock(
                        world,
                        spawnPos,
                        Blocks.DAMAGED_ANVIL.getDefaultState()
                );

                // Kick it downward a bit so it starts falling immediately
                ent.setVelocity(0.0, -0.05, 0.0);

                // Make it hurt on impact more (vanilla anvils already hurt; this boosts it)
                ent.setHurtEntities(6.0F, 60); // damage scale, maxFallDistance cap

                // Do NOT drop as item when it breaks/lands (prevents players taking them)
                ent.dropItem = false;
            }
        }

        // Chunky SFX so it feels heavy
        world.playSound(null,
                BlockPos.ofFloored(cx, cy, cz),
                SoundEvents.BLOCK_ANVIL_PLACE, // cast cue
                SoundCategory.PLAYERS,
                0.9f,
                0.8f
        );

        return true;
    }
}
