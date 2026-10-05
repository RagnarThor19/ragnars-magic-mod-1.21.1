package net.ragnar.ragnarsmagicmod.shadowhands;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import org.jetbrains.annotations.Nullable;

/**
 * Tome of Unseen Hands: finds the ground where you're looking (up to {@link ShadowHands#REACH} blocks; aim at a
 * wall and it drops to the floor at its foot) and lets the shadow loose there. Looking at nothing, it doesn't cast.
 */
public class ShadowHandsSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Vec3d at = groundInSight(sw, player);
        if (at == null) {
            player.sendMessage(Text.literal("No ground in sight.").formatted(Formatting.DARK_PURPLE), true);
            return false;
        }
        Grasp grasp = new Grasp(sw, player, at);
        ShadowHands.ACTIVE.add(grasp);
        grasp.begin();
        return true;
    }

    /** The ground where {@code player} is looking, or null if they're looking at nothing in reach. */
    @Nullable
    public static Vec3d groundInSight(World world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1f).multiply(ShadowHands.REACH));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return null;
        if (hit.getSide() == Direction.UP) return hit.getPos();
        // A wall or a ceiling: drop down to the floor in front of it
        BlockPos p = hit.getBlockPos().offset(hit.getSide());
        for (int i = 0; i < 12; i++) {
            BlockPos below = p.down();
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                double top = below.getY() + world.getBlockState(below).getCollisionShape(world, below).getMax(Direction.Axis.Y);
                return new Vec3d(hit.getPos().x, top, hit.getPos().z);
            }
            p = below;
        }
        return null;
    }
}
