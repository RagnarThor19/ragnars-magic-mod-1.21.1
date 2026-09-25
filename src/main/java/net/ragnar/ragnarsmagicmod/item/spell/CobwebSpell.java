package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;
import net.ragnar.ragnarsmagicmod.entity.CobwebProjectileEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tome of Cobwebs: throws a cobweb. A creature it hits is wrapped in real cobwebs for 4 seconds; anywhere
 * else it just sticks as a cobweb for 4 seconds. The webs then break away on their own.
 */
public class CobwebSpell implements Spell {
    private static final int WEB_TICKS = 20 * 4;
    private static final float SPEED = 1.8f;

    /** Webs this spell placed, and when they go. */
    private static final Map<ServerWorld, Map<BlockPos, Long>> WEBS = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Map<BlockPos, Long> webs = WEBS.get(world);
            if (webs == null || webs.isEmpty()) return;
            long now = world.getTime();
            Iterator<Map.Entry<BlockPos, Long>> it = webs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Long> e = it.next();
                if (now < e.getValue()) continue;
                it.remove();
                BlockPos pos = e.getKey();
                if (world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                    world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, pos, Block.getRawIdFromState(Blocks.COBWEB.getDefaultState()));
                    world.setBlockState(pos, Blocks.AIR.getDefaultState());
                }
            }
        });
        // Don't leave the webs behind if the server stops mid-spell
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WEBS.forEach((world, webs) -> webs.keySet().forEach(pos -> {
                if (world.getBlockState(pos).isOf(Blocks.COBWEB)) world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }));
            WEBS.clear();
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();

        CobwebProjectileEntity web = new CobwebProjectileEntity(world, player);
        web.setVelocity(player, player.getPitch(), player.getYaw(), 0.0f, SPEED, 0.5f);
        world.spawnEntity(web);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW,
                SoundCategory.PLAYERS, 0.6f, 0.6f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_SPIDER_AMBIENT,
                SoundCategory.PLAYERS, 0.3f, 1.6f);
        return true;
    }

    /** Hit a creature: webs where it stands, and it's stuck. */
    public static void trap(ServerWorld world, LivingEntity target) {
        ensureRegistered();
        BlockPos feet = target.getBlockPos();
        int height = Math.max(1, MathHelper.ceil(target.getHeight()));
        for (int i = 0; i < height; i++) place(world, feet.up(i));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, WEB_TICKS, 4, false, true));
        target.setVelocity(target.getVelocity().multiply(0.1, 0.1, 0.1));
        target.velocityModified = true;
        world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    /** Hit a block: a web sticks on that side of it. */
    public static void stick(ServerWorld world, BlockHitResult hit) {
        ensureRegistered();
        BlockPos pos = world.getBlockState(hit.getBlockPos()).isReplaceable() ? hit.getBlockPos() : hit.getBlockPos().offset(hit.getSide());
        if (place(world, pos)) {
            world.playSound(null, pos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 1.0f, 0.8f);
        }
    }

    private static boolean place(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (state.isOf(Blocks.COBWEB)) {
            // Already webbed: just keep it up for the full time
            Map<BlockPos, Long> webs = WEBS.get(world);
            if (webs != null && webs.containsKey(pos)) webs.put(pos.toImmutable(), world.getTime() + WEB_TICKS);
            return true;
        }
        if (!state.isReplaceable() || !state.getFluidState().isEmpty()) return false;
        world.setBlockState(pos, Blocks.COBWEB.getDefaultState());
        WEBS.computeIfAbsent(world, w -> new HashMap<>()).put(pos.toImmutable(), world.getTime() + WEB_TICKS);
        return true;
    }
}
