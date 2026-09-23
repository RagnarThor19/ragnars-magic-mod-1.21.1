package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.SkyDrop;
import org.joml.Vector3f;

import java.util.List;

/**
 * A 3x3 of anvils materialises above the target, rattling against each other, then they slam
 * down one after another, crushing whatever is underneath. The centre anvil sends out a shockwave.
 */
public final class FallingAnvilsSpell implements Spell {
    private static final double RANGE = 48.0;
    private static final double HOVER = 9.0;
    private static final int SHAKE_TICKS = 22;
    private static final int MAX_STAGGER = 8;          // outer anvils follow the centre within this many ticks
    private static final float DIRECT_DAMAGE = 22.0f;
    private static final double SHOCK_RADIUS = 3.5;
    private static final float SHOCK_DAMAGE = 6.0f;

    private static final DustParticleEffect WARNING = new DustParticleEffect(new Vector3f(0.45f, 0.45f, 0.5f), 1.0f);

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;
        ServerWorld sw = (ServerWorld) world;

        Vec3d ground = SkyDrop.aimedGround(sw, player, RANGE);
        Vec3d center = new Vec3d(Math.floor(ground.x) + 0.5, ground.y, Math.floor(ground.z) + 0.5);
        Vec3d centerAnchor = SkyDrop.anchorAbove(sw, center, HOVER, 1.0);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean isCenter = dx == 0 && dz == 0;
                Vec3d anchor = centerAnchor.add(dx, 0, dz);
                int stagger = isCenter ? 0 : 1 + sw.random.nextInt(MAX_STAGGER);
                Direction facing = Direction.Type.HORIZONTAL.random(sw.random);
                BlockState anvil = (sw.random.nextInt(3) == 0 ? Blocks.CHIPPED_ANVIL : Blocks.DAMAGED_ANVIL)
                        .getDefaultState().with(AnvilBlock.FACING, facing);
                new Anvil(sw, player, anchor, SHAKE_TICKS + stagger, isCenter, center)
                        .launch(List.of(new SkyDrop.Piece(anvil, 1.0f, 0f)));
            }
        }

        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.8f, 0.8f);
        world.playSound(null, centerAnchor.x, centerAnchor.y, centerAnchor.z, SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        return true;
    }

    private static final class Anvil extends SkyDrop {
        private final boolean isCenter;
        private final Vec3d groundCenter;

        Anvil(ServerWorld world, PlayerEntity owner, Vec3d anchor, int shakeTicks, boolean isCenter, Vec3d groundCenter) {
            super(world, owner, anchor, 0.5, 1.0, shakeTicks);
            this.isCenter = isCenter;
            this.groundCenter = groundCenter;
        }

        @Override protected double initialSpeed() { return 1.0; }
        @Override protected double acceleration() { return 0.45; }
        @Override protected double maxSpeed() { return 3.6; }

        @Override
        protected void onShake(double progress) {
            if (!isCenter) return; // the centre anvil drives the shared sounds and ground marker
            if (phaseAge % 3 == 0) {
                world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_CHAIN_HIT, SoundCategory.PLAYERS, 1.2f, 0.6f + (float) progress * 0.4f);
            }
            if (phaseAge % 6 == 0) {
                world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_ANVIL_HIT, SoundCategory.PLAYERS, 0.9f, 0.5f + (float) progress * 0.3f);
            }
            if (phaseAge % 2 == 0) {
                // Square outline of the 3x3 landing zone
                for (int i = 0; i < 12; i++) {
                    double t = i / 12.0 * 3.0 - 1.5;
                    world.spawnParticles(WARNING, groundCenter.x + t, groundCenter.y + 0.1, groundCenter.z - 1.5, 1, 0, 0, 0, 0);
                    world.spawnParticles(WARNING, groundCenter.x + t, groundCenter.y + 0.1, groundCenter.z + 1.5, 1, 0, 0, 0, 0);
                    world.spawnParticles(WARNING, groundCenter.x - 1.5, groundCenter.y + 0.1, groundCenter.z + t, 1, 0, 0, 0, 0);
                    world.spawnParticles(WARNING, groundCenter.x + 1.5, groundCenter.y + 0.1, groundCenter.z + t, 1, 0, 0, 0, 0);
                }
            }
            world.spawnParticles(ParticleTypes.SMOKE, pos.x, pos.y + 1.1, pos.z, 2, 1.2, 0.05, 1.2, 0.0);
        }

        @Override
        protected void onRelease() {
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 0.8f, 0.5f);
            if (isCenter) {
                world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 1.0f, 0.5f);
            }
            world.spawnParticles(ParticleTypes.GUST, pos.x, pos.y + 1.2, pos.z, 1, 0, 0, 0, 0);
        }

        @Override
        protected void onFall(Vec3d from, Vec3d to) {
            for (double y = to.y + 1.0; y < from.y + 1.0; y += 0.9) {
                world.spawnParticles(ParticleTypes.CLOUD, to.x, y, to.z, 1, 0.3, 0.1, 0.3, 0.0);
            }
        }

        @Override
        protected void onStrike(LivingEntity target) {
            target.damage(world.getDamageSources().fallingAnvil(owner()), DIRECT_DAMAGE);
        }

        @Override
        protected void onImpact(BlockHitResult hit) {
            Vec3d c = hit.getPos();
            BlockState anvilDust = Blocks.ANVIL.getDefaultState();
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1.4f, 0.7f + world.random.nextFloat() * 0.2f);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, anvilDust), c.x, c.y + 0.3, c.z, 20, 0.4, 0.2, 0.4, 0.15);
            BlockState groundState = world.getBlockState(hit.getBlockPos());
            if (!groundState.isAir()) {
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, groundState), c.x, c.y + 0.1, c.z, 16, 0.5, 0.1, 0.5, 0.2);
            }

            if (!isCenter) return;
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 2.0f, 0.7f);
            world.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 3, 1.0, 0.2, 1.0, 0);
            for (int i = 0; i < 32; i++) {
                double a = i * Math.PI * 2.0 / 32;
                world.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 0.2, c.z, 0, Math.cos(a) * 0.5, 0.02, Math.sin(a) * 0.5, 1.0);
            }

            PlayerEntity owner = owner();
            Box area = new Box(c, c).expand(SHOCK_RADIUS, 1.5, SHOCK_RADIUS);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && e != owner)) {
                double dx = e.getX() - c.x, dz = e.getZ() - c.z;
                if (dx * dx + dz * dz > SHOCK_RADIUS * SHOCK_RADIUS) continue;
                e.damage(world.getDamageSources().fallingAnvil(owner), SHOCK_DAMAGE);
                Vec3d out = new Vec3d(dx, 0, dz);
                out = out.lengthSquared() > 1.0e-4 ? out.normalize().multiply(0.8) : Vec3d.ZERO;
                e.addVelocity(out.x, 0.45, out.z);
                e.velocityModified = true;
            }
        }

        @Override
        protected boolean onLandedTick(int ticksSinceImpact) {
            if (ticksSinceImpact < 8) return true;
            // Anvils shatter after they land
            world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_ANVIL_DESTROY, SoundCategory.PLAYERS, 0.7f, 0.9f);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ANVIL.getDefaultState()), pos.x, pos.y + 0.5, pos.z, 25, 0.3, 0.3, 0.3, 0.1);
            return false;
        }
    }
}
