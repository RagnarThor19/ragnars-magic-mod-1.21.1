package net.ragnar.ragnarsmagicmod.thunderstrike;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Thunderstrike (see Thunderstrike): calls the storm down on the ground you aim at. */
public class ThunderstrikeSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Vec3d ground = Thunderstrike.aim(sw, player);
        if (ground == null) {
            player.sendMessage(Text.literal("Aim at the ground to call the storm down."), true);
            return false;
        }
        Thunderstrike.start(sw, player, ground);
        return true;
    }
}
