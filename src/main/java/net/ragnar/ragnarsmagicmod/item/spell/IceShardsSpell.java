package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IceShardEntity;

public final class IceShardsSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // base look dir
        Vec3d look = player.getRotationVec(1.0f).normalize();

        // small horizontal spread (-6°, 0°, +6°)
        spawnShard(world, player, yaw(look, -6));
        spawnShard(world, player, look);
        spawnShard(world, player, yaw(look,  6));

        return true;
    }

    private static void spawnShard(World world, PlayerEntity player, Vec3d dir) {
        IceShardEntity shard = new IceShardEntity(world, player);
        shard.setPosition(player.getX(), player.getEyeY() - 0.1, player.getZ());
        shard.setVelocity(dir.multiply(1.0)); // a bit faster than snowball
        world.spawnEntity(shard);
    }

    private static Vec3d yaw(Vec3d v, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        // rotate around Y
        double x = v.x * cos - v.z * sin;
        double z = v.x * sin + v.z * cos;
        return new Vec3d(x, v.y, z);
    }
}
