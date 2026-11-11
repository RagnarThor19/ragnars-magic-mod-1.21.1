package net.ragnar.ragnarsmagicmod.poisondarts;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Poison Darts (see PoisonDarts): a sharp puff, and the dart's away. */
public class PoisonDartSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Vec3d tip = tip(player);
        PoisonDartEntity dart = new PoisonDartEntity(sw, player);
        dart.setPosition(tip.x, tip.y, tip.z);
        dart.setVelocity(player, player.getPitch(), player.getYaw(), 0f, PoisonDartEntity.SPEED, 0.05f);
        sw.spawnEntity(dart);

        // The puff of a blowpipe: a soft spit and a hiss of air, no twang
        sw.playSound(null, tip.x, tip.y, tip.z, SoundEvents.ENTITY_LLAMA_SPIT, SoundCategory.PLAYERS, 0.9f, 1.7f);
        sw.playSound(null, tip.x, tip.y, tip.z, SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.35f, 2.0f);
        Vec3d look = player.getRotationVec(1f);
        sw.spawnParticles(ParticleTypes.POOF, tip.x, tip.y, tip.z, 0, look.x, look.y, look.z, 0.08);
        sw.spawnParticles(PoisonDarts.MOTE, tip.x, tip.y, tip.z, 5, 0.05, 0.05, 0.05, 0.0);
        return true;
    }

    /** About where the staff's tip is, a little out in front of the caster's eyes. */
    static Vec3d tip(PlayerEntity p) {
        Vec3d look = p.getRotationVec(1f);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1.0e-4) right = new Vec3d(1, 0, 0);
        return p.getEyePos().add(look.multiply(1.0)).add(right.normalize().multiply(0.25)).add(0, -0.2, 0);
    }
}
