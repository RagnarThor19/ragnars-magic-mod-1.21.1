package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class LightningSpell implements Spell {

    private static final double RANGE = 64.0;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;

        HitResult hit = player.raycast(RANGE, 0.0f, false);

        BlockPos strikePos;
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hit;
            strikePos = bhr.getBlockPos().up();
        } else {
            // 8 blocks forward from the camera/eyes
            Vec3d ahead = player.getCameraPosVec(0.0f)
                    .add(player.getRotationVector().normalize().multiply(8.0));
            strikePos = BlockPos.ofFloored(ahead);
        }

        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
        if (bolt == null) return false;

        bolt.refreshPositionAfterTeleport(
                strikePos.getX() + 0.5,
                strikePos.getY(),
                strikePos.getZ() + 0.5
        );
        world.spawnEntity(bolt);
        return true;
    }
}
