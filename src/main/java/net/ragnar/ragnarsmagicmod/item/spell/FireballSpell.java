package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.util.math.Vec3d;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;


public final class FireballSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        Vec3d look = player.getRotationVec(1.0f).normalize().multiply(0.5);

        SmallFireballEntity fireball = new SmallFireballEntity(world, player, look);

        // put it at eye height so it doesn't spawn inside the player
        fireball.setPosition(player.getX(), player.getEyeY() - 0.1, player.getZ());

        world.spawnEntity(fireball);
        world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS,
                0.5f, 0.9f + world.random.nextFloat() * 0.2f);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.35f, 1.3f);

        if (world instanceof net.minecraft.server.world.ServerWorld sw) {
            // A little puff of flame at the staff
            Vec3d m = net.ragnar.ragnarsmagicmod.util.CastFx.muzzle(player);
            Vec3d d = player.getRotationVector();
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.FLAME, m.x, m.y, m.z, 0, d.x, d.y, d.z, 0.15);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.FLAME, m.x, m.y, m.z, 6, 0.1, 0.1, 0.1, 0.03);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, m.x, m.y, m.z, 3, 0.1, 0.1, 0.1, 0.02);
        }

        return true;
    }
}
