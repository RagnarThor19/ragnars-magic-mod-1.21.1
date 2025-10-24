package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireballEntity; // <-- big ghast fireball
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

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

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 0.5f, 0.4f);
        return true;
    }
}
