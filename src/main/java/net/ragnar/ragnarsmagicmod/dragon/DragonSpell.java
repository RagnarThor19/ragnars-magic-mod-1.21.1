package net.ragnar.ragnarsmagicmod.dragon;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of the Dragon: looses the little dragon from the staff and hands you its eyes (see Dragon). */
public class DragonSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (Dragon.isPiloting(sp)) return false; // one at a time (clicks set the one you're flying off)

        DragonMissileEntity dragon = new DragonMissileEntity(sw, sp);
        sw.spawnEntity(dragon);
        Dragon.start(sp, dragon);
        // Your body stops where it stands
        sp.setVelocity(Vec3d.ZERO);
        sp.velocityModified = true;

        Vec3d p = dragon.getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.9f, 1.8f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 1.2f, 1.5f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.8f, 1.3f);
        // A puff of the End where it leaps out - for everyone else: it'd hang right in front of you when you come back
        for (ServerPlayerEntity viewer : sw.getPlayers()) {
            if (viewer == sp || viewer.squaredDistanceTo(p) > 64 * 64) continue;
            sw.spawnParticles(viewer, ParticleTypes.DRAGON_BREATH, false, p.x, p.y + 0.45, p.z, 25, 0.25, 0.25, 0.25, 0.05);
            sw.spawnParticles(viewer, ParticleTypes.PORTAL, false, p.x, p.y + 0.45, p.z, 40, 0.3, 0.3, 0.3, 0.6);
        }
        return true;
    }
}
