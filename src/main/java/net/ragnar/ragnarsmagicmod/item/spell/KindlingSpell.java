package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.TntBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.ragnar.ragnarsmagicmod.util.Aim;

/**
 * Tome of Kindling: a flint and steel that works at range. Whatever you're looking at catches light on the
 * spot - a mob bursts into flames, a campfire or candle lights, TNT is primed, or fire appears on the face
 * you're pointing at.
 */
public class KindlingSpell implements Spell {
    private static final double RANGE = 32.0;
    private static final double AIM_ASSIST_DEGREES = 3.0;
    private static final int BURN_SECONDS = 8;
    private static final float SCORCH_DAMAGE = 1.0f;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;

        Entity target = Aim.target(sw, player, RANGE, AIM_ASSIST_DEGREES, e -> e instanceof LivingEntity);
        if (target != null) {
            target.setOnFireFor(BURN_SECONDS);
            target.damage(sw.getDamageSources().inFire(), SCORCH_DAMAGE);
            Vec3d body = target.getBoundingBox().getCenter();
            burst(sw, body, target.getWidth() * 0.4, target.getHeight() * 0.4, 25);
            sw.playSound(null, body.x, body.y, body.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1.0f, 1.2f);
            click(sw, player);
            return true;
        }

        HitResult hit = player.raycast(RANGE, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) return false;
        BlockPos pos = bhr.getBlockPos();
        if (!player.canModifyBlocks() || !world.canPlayerModifyAt(player, pos)) return false;
        BlockState state = world.getBlockState(pos);

        Vec3d spot;
        if (state.isOf(Blocks.TNT)) {
            TntBlock.primeTnt(world, pos);
            world.removeBlock(pos, false);
            spot = Vec3d.ofCenter(pos);
        } else if (CampfireBlock.canBeLit(state) || CandleBlock.canBeLit(state) || CandleCakeBlock.canBeLit(state)) {
            world.setBlockState(pos, state.with(Properties.LIT, true), 11);
            world.emitGameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            spot = bhr.getPos();
        } else {
            // Vanilla flint and steel: fire on the face you're looking at (and portals light themselves)
            BlockPos firePos = pos.offset(bhr.getSide());
            if (!AbstractFireBlock.canPlaceAt(world, firePos, player.getHorizontalFacing())) return false;
            world.setBlockState(firePos, AbstractFireBlock.getState(world, firePos), 11);
            world.emitGameEvent(player, GameEvent.BLOCK_PLACE, firePos);
            spot = Vec3d.ofBottomCenter(firePos).add(0, 0.3, 0);
        }

        burst(sw, spot, 0.2, 0.2, 14);
        sw.playSound(null, spot.x, spot.y, spot.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.5f, 1.4f);
        click(sw, player);
        return true;
    }

    /** A quick pop of flame and embers right where the fire takes hold. */
    private static void burst(ServerWorld sw, Vec3d at, double spreadXZ, double spreadY, int flames) {
        sw.spawnParticles(ParticleTypes.FLAME, at.x, at.y, at.z, flames, spreadXZ, spreadY, spreadXZ, 0.04);
        sw.spawnParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 3, spreadXZ, spreadY, spreadXZ, 0);
        sw.spawnParticles(ParticleTypes.SMOKE, at.x, at.y + 0.2, at.z, 5, spreadXZ, spreadY, spreadXZ, 0.01);
    }

    /** The flint strike, heard at the caster. */
    private static void click(ServerWorld sw, PlayerEntity player) {
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_FLINTANDSTEEL_USE,
                SoundCategory.PLAYERS, 1.0f, 0.9f + sw.random.nextFloat() * 0.3f);
    }
}
