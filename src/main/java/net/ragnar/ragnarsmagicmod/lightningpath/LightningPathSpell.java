package net.ragnar.ragnarsmagicmod.lightningpath;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/**
 * Tome of the Lightning Path: the first cast starts painting; casting again while painting sets off straight away
 * (free, and allowed during the cooldown the first cast started).
 */
public class LightningPathSpell implements Spell {
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return painting(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return painting(player) ? 0 : tomeCost;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return false;
        LightningPathRun run = LightningPathRun.of(sp);
        if (run != null) {
            if (run.painting()) {
                run.goNow(sp);
                return true;
            }
            return false; // already running, or just landed
        }
        LightningPathRun.begin(sp);
        return true;
    }

    private static boolean painting(PlayerEntity player) {
        LightningPathRun run = LightningPathRun.of(player);
        return run != null && run.painting();
    }
}
