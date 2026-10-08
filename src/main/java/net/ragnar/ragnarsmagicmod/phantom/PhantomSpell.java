package net.ragnar.ragnarsmagicmod.phantom;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of the Phantom (see Phantom): become a spectre, or cast again to turn back early. */
public class PhantomSpell implements Spell {
    // Casting again turns you back: free, and allowed while the tome is cooling down
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return Phantom.isPhantom(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return Phantom.isPhantom(player) ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts when you're solid again
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        return Phantom.cast(sw, sp);
    }
}
