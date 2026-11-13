package net.ragnar.ragnarsmagicmod.astral;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Astral Projection (see AstralProjection): leave your body, or cast again to go back early. */
public class AstralProjectionSpell implements Spell {
    // Going back early is free, and allowed while the tome is cooling down
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return player instanceof ServerPlayerEntity sp && AstralProjection.isProjecting(sp);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return player instanceof ServerPlayerEntity sp && AstralProjection.isProjecting(sp) ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts once you're back in your body
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        return AstralProjection.cast(sw, sp);
    }
}
