package net.ragnar.ragnarsmagicmod.mightypush;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Mighty Pushing: starts the chant - from the sky if you're off the ground, from where you stand if not. */
public class MightyPushSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return false;
        if (MightyPushCast.isCasting(sp)) return false;
        boolean airborne = !sp.isOnGround() && !sp.isTouchingWater() && !sp.isClimbing();
        MightyPushCast.begin(sp, airborne);
        return true;
    }
}
