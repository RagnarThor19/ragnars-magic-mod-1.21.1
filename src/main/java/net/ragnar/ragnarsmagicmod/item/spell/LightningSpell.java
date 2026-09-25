package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.Aim;

/**
 * Calls a lightning bolt down on the creature or block under the crosshair. The bolt is the spell's own: it hurts
 * and ignites creatures around the strike and the kill counts as yours, but it never sets the ground on fire.
 */
public class LightningSpell implements Spell {

    private static final double RANGE = 64.0;
    private static final double AIM_CONE = 3.0;       // a little help landing it on a moving target
    private static final double STRIKE_RADIUS = 1.5;
    private static final float DAMAGE = 8.0f;
    private static final int BURN_SECONDS = 4;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ServerWorld sw = (ServerWorld) world;

        Vec3d strike = strikePoint(sw, player);
        if (strike == null) {
            player.sendMessage(Text.literal("Nothing to strike."), true);
            return false;
        }

        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(sw);
        if (bolt == null) return false;
        // Just the flash and thunder; the damage below replaces vanilla's (which starts fires and isn't credited)
        bolt.setCosmetic(true);
        bolt.refreshPositionAfterTeleport(strike.x, strike.y, strike.z);
        sw.spawnEntity(bolt);

        Box area = new Box(strike, strike).expand(STRIKE_RADIUS, 3.0, STRIKE_RADIUS);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, area,
                e -> e.isAlive() && !e.isSpectator() && e != player)) {
            e.damage(sw.getDamageSources().indirectMagic(player, player), DAMAGE);
            e.setOnFireFor(BURN_SECONDS);
        }
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, strike.x, strike.y + 0.2, strike.z, 25, 0.6, 0.3, 0.6, 0.4);
        return true;
    }

    /** The feet of the creature under the crosshair, else the top of the block hit, else null (aimed at the sky). */
    private static Vec3d strikePoint(ServerWorld world, PlayerEntity player) {
        Entity target = Aim.target(world, player, RANGE, AIM_CONE, e -> e instanceof LivingEntity);
        if (target != null) return target.getPos();

        Vec3d eye = player.getEyePos();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(player.getRotationVector().multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        // On a floor, strike on top of it; on a wall, strike the open space in front of it
        return Vec3d.ofBottomCenter(hit.getBlockPos().offset(hit.getSide()));
    }
}
