package net.ragnar.ragnarsmagicmod.terraform;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

import java.util.function.Consumer;

/**
 * Tome of Terraforming (see Terraform). Like the Tome of Building, the client sends what it aimed at and its settings
 * just before the cast, and the server works it all out again.
 */
public class TerraformSpell implements Spell {
    /** Set on the client: sends the aim and settings to the server when the staff is used. */
    public static Consumer<PlayerEntity> clientCast;

    /** XP is only charged on the server, where we know whether the land really changed. */
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return player.getWorld().isClient ? 0 : tomeCost;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) {
            if (clientCast != null) clientCast.accept(player);
            return true; // claim the click, so nothing in the other hand is used as well
        }
        return world instanceof ServerWorld sw && player instanceof ServerPlayerEntity sp && Terraform.cast(sw, sp);
    }
}
