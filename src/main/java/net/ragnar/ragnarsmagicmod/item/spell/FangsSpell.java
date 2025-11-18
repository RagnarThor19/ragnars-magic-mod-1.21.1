package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class FangsSpell implements Spell {

    private static final int FANG_COUNT = 10;
    private static final double START_DISTANCE = 1.5D;
    private static final double STEP = 1.0D;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) {
            return false;
        }

        ServerWorld sw = (ServerWorld) world;

        Vec3d look = player.getRotationVec(1.0F);
        Vec3d origin = player.getPos();

        boolean spawnedAny = false;

        for (int i = 0; i < FANG_COUNT; i++) {
            double distance = START_DISTANCE + STEP * i;

            double x = origin.x + look.x * distance;
            double z = origin.z + look.z * distance;
            double y = origin.y;

            // Snap to ground like the evoker does (top solid block at that x/z)
            BlockPos guess = BlockPos.ofFloored(x, y, z);
            BlockPos ground = sw.getTopPosition(
                    net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    guess
            );

            double fangY = ground.getY();

            EvokerFangsEntity fangs = new EvokerFangsEntity(
                    sw,
                    x,
                    fangY,
                    z,
                    player.getYaw(),
                    5 + i * 2, // slight delay wave
                    player
            );

            sw.spawnEntity(fangs);
            spawnedAny = true;
        }

        if (spawnedAny) {
            world.playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.ENTITY_EVOKER_CAST_SPELL,
                    SoundCategory.PLAYERS,
                    0.5F,
                    1.0F
            );
        }

        return spawnedAny;
    }
}
