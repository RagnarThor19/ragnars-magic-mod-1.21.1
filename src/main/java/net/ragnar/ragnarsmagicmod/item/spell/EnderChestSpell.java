package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/** Tome of the Ender Chest: opens your ender chest wherever you are. */
public class EnderChestSpell implements Spell {
    private static final Text TITLE = Text.translatable("container.enderchest");

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        EnderChestInventory inventory = player.getEnderChestInventory();
        // Not tied to any real ender chest block, so it never closes for being too far from one
        inventory.setActiveBlockEntity(null);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, playerInventory, inventory),
                TITLE));
        player.incrementStat(Stats.OPEN_ENDERCHEST);

        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_ENDER_CHEST_OPEN, SoundCategory.PLAYERS, 0.6f, 1.1f);
        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.4, 0.6, 0.4, 0.4);
        }
        return true;
    }
}
