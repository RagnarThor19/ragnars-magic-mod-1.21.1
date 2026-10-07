package net.ragnar.ragnarsmagicmod.moon;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of the Moon: calls the moon down on the ground under your crosshair (see MoonEntity). */
public class MoonSpell implements Spell {
    /** How far away you can bring it down. */
    public static final double RANGE = 48;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Vec3d ground = landingSpot(sw, player);
        if (ground == null) return false; // nothing to bring it down on (aiming at open sky over the void)

        sw.spawnEntity(new MoonEntity(sw, player, ground));
        Vec3d eye = player.getEyePos();
        sw.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 1.0f, 0.6f);
        // A glint of moonlight at the caster - for everyone else, as it'd hang right in front of your own eyes
        for (net.minecraft.server.network.ServerPlayerEntity viewer : sw.getPlayers()) {
            if (viewer == player || viewer.squaredDistanceTo(eye) > 64 * 64) continue;
            sw.spawnParticles(viewer, ParticleTypes.END_ROD, false, eye.x, eye.y - 0.3, eye.z, 15, 0.3, 0.3, 0.3, 0.05);
        }
        return true;
    }

    /** The ground under the crosshair, or under the point it reaches out to if that's in mid-air. */
    static Vec3d landingSpot(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1f);
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(look.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        if (hit.getType() != HitResult.Type.MISS && hit.getSide() == Direction.UP) return hit.getPos();
        // A wall or open air: drop straight down from just in front of it
        Vec3d from = hit.getType() == HitResult.Type.MISS ? eye.add(look.multiply(RANGE)) : hit.getPos().subtract(look.multiply(0.5));
        BlockHitResult down = world.raycast(new RaycastContext(from, from.add(0, -64, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        return down.getType() == HitResult.Type.MISS ? null : down.getPos();
    }
}
