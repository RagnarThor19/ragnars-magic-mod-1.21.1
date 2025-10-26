package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class RisingSpikesSpell implements Spell {
    // wave tuning
    private static final int LENGTH_BLOCKS = 16;      // how far forward the wave travels
    private static final int STEP_DELAY_T  = 2;       // ticks between segments
    private static final int LIFETIME_T    = 15;      // spike lifetime before despawn (1s)
    private static final int[] LANES       = new int[] { -1, 0, 1 }; // 3 lanes (left, center, right)

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Cast cue (low rumble + rock)
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 0.9f, 0.9f);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 0.5f, 0.7f);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.7f);

        Vec3d dir = player.getRotationVec(1.0f);
        Vec3d flatDir = new Vec3d(dir.x, 0, dir.z).normalize();

        // start 2 blocks in front of the player
        Vec3d origin = player.getPos().add(flatDir.multiply(2.0));

        if (world instanceof ServerWorld sw) {
            net.ragnar.ragnarsmagicmod.util.SpikeWave.queueWave(
                    sw,
                    origin,
                    dir,
                    LENGTH_BLOCKS,
                    LANES,
                    STEP_DELAY_T,
                    LIFETIME_T,
                    player.getUuid()   // <— NEW: pass caster id
            );
        }


        return true;
    }
}
