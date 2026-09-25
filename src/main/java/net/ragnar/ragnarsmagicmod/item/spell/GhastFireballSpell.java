package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireballEntity; // <-- big ghast fireball
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.CastFx;

public final class GhastFireballSpell implements Spell {
    private final int power; // 1–3 is sane

    public GhastFireballSpell(int power) { this.power = power; }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        Vec3d look = player.getRotationVec(1.0f).normalize().multiply(0.5);

        FireballEntity ball = new FireballEntity(world, player, look, power);

        // Put it at eye height
        ball.setPosition(player.getX(), player.getEyeY() - 0.1, player.getZ());
        world.spawnEntity(ball);

        // The ghast's own shriek, lowered, over a deep whoomph
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_GHAST_SHOOT, SoundCategory.PLAYERS, 0.7f, 0.8f + world.random.nextFloat() * 0.15f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.8f, 0.6f);

        if (world instanceof ServerWorld sw) {
            // A bigger burst of fire and smoke at the staff
            Vec3d m = CastFx.muzzle(player);
            Vec3d d = player.getRotationVector();
            sw.spawnParticles(ParticleTypes.FLAME, m.x, m.y, m.z, 14, 0.2, 0.2, 0.2, 0.06);
            sw.spawnParticles(ParticleTypes.FLAME, m.x, m.y, m.z, 0, d.x, d.y, d.z, 0.25);
            sw.spawnParticles(ParticleTypes.LARGE_SMOKE, m.x, m.y, m.z, 4, 0.15, 0.15, 0.15, 0.02);
        }
        return true;
    }
}
