package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
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
 * A four-block stalactite forms above the target, trembles, then is fired into the ground,
 * skewering what's below. It stays stuck in the ground for a moment before crumbling.
 */
public final class FallingStalactiteSpell implements Spell {
    private static final double RANGE = 48.0;
    private static final double HOVER = 21.0;
    private static final int SHAKE_TICKS = 16;
    private static final float DIRECT_DAMAGE = 80.0f;   // hard to land, so a direct hit kills almost anything
    private static final double SPLASH_RADIUS = 2.5;
    private static final float SPLASH_DAMAGE = 5.0f;
    private static final int STUCK_TICKS = 30;

    private static final BlockState DRIPSTONE = Blocks.DRIPSTONE_BLOCK.getDefaultState();
    private static final DustParticleEffect WARNING = new DustParticleEffect(new Vector3f(0.55f, 0.35f, 0.25f), 1.0f);

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;
        ServerWorld sw = (ServerWorld) world;

        Vec3d ground = SkyDrop.aimedGround(sw, player, RANGE);
        Vec3d anchor = SkyDrop.anchorAbove(sw, ground, HOVER, 4.0);
        new Stalactite(sw, player, ground, anchor).launch(List.of(
                new SkyDrop.Piece(segment(Thickness.TIP), 1.0f, 0f),
                new SkyDrop.Piece(segment(Thickness.FRUSTUM), 1.0f, 1f),
                new SkyDrop.Piece(segment(Thickness.MIDDLE), 1.0f, 2f),
                new SkyDrop.Piece(segment(Thickness.BASE), 1.0f, 3f)
        ));

        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.7f, 1.4f);
        return true;
    }

    private static BlockState segment(Thickness thickness) {
        return Blocks.POINTED_DRIPSTONE.getDefaultState()
                .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.DOWN)
                .with(PointedDripstoneBlock.THICKNESS, thickness);
    }

    private static final class Stalactite extends SkyDrop {
        private final Vec3d ground;

        Stalactite(ServerWorld world, PlayerEntity owner, Vec3d ground, Vec3d anchor) {
            super(world, owner, anchor, 0.4, 4.0, SHAKE_TICKS);
            this.ground = ground;
        }

        @Override protected double initialSpeed() { return 0.9; }
        @Override protected double acceleration() { return 0.4; }
        @Override protected double maxSpeed() { return 4.0; }

        @Override
        protected void onStart() {
            world.playSound(null, anchor.x, anchor.y + 2, anchor.z, SoundEvents.BLOCK_DRIPSTONE_BLOCK_BREAK, SoundCategory.PLAYERS, 2.5f, 0.6f);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DRIPSTONE),
                    anchor.x, anchor.y + 3.5, anchor.z, 20, 0.4, 0.3, 0.4, 0.05);
        }

        @Override
        protected void onShake(double progress) {
            // Grit trickling off the rock, and a marker on the ground where it will land
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, DRIPSTONE),
                    pos.x, pos.y + 2.0, pos.z, 2, 0.35, 1.5, 0.35, 0.0);
            if (phaseAge % 2 == 0) {
                for (int i = 0; i < 12; i++) {
                    double a = i * Math.PI * 2.0 / 12;
                    world.spawnParticles(WARNING, ground.x + Math.cos(a) * 0.8, ground.y + 0.1, ground.z + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
                }
            }
            if (phaseAge % 4 == 0) {
                world.playSound(null, pos.x, pos.y + 2, pos.z, SoundEvents.BLOCK_DRIPSTONE_BLOCK_HIT, SoundCategory.PLAYERS, 2.5f, 0.5f + (float) progress * 0.5f);
            }
        }

        @Override
        protected void onRelease() {
            world.playSound(null, pos.x, pos.y + 2, pos.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_FALL, SoundCategory.PLAYERS, 3.0f, 0.7f);
            world.playSound(null, pos.x, pos.y + 2, pos.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.8f, 0.6f);
            world.spawnParticles(ParticleTypes.GUST, pos.x, pos.y + 4.2, pos.z, 1, 0, 0, 0, 0);
        }

        @Override
        protected void onFall(Vec3d from, Vec3d to) {
            // Speed lines streaming off the rock
            for (double y = to.y; y < from.y + 4.0; y += 0.8) {
                world.spawnParticles(ParticleTypes.CLOUD, to.x, y, to.z, 1, 0.25, 0.1, 0.25, 0.0);
            }
        }

        @Override
        protected void onStrike(LivingEntity target) {
            PlayerEntity owner = owner();
            target.damage(world.getDamageSources().fallingStalactite(owner), DIRECT_DAMAGE);
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getBodyY(0.5), target.getZ(), 6, 0.3, 0.3, 0.3, 0.1);
        }

        @Override
        protected void onImpact(BlockHitResult hit) {
            Vec3d c = hit.getPos();
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 2.0f, 0.6f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 1.2f, 0.8f);
            BlockState groundState = world.getBlockState(hit.getBlockPos());
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, groundState.isAir() ? DRIPSTONE : groundState),
                    c.x, c.y + 0.2, c.z, 40, 0.8, 0.2, 0.8, 0.2);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DRIPSTONE), c.x, c.y + 1, c.z, 30, 0.3, 1.0, 0.3, 0.15);
            world.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 1, 0, 0, 0, 0);

            // Shockwave around the point of impact
            PlayerEntity owner = owner();
            Box area = new Box(c, c).expand(SPLASH_RADIUS, 1.5, SPLASH_RADIUS);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && e != owner)) {
                double dx = e.getX() - c.x, dz = e.getZ() - c.z;
                if (dx * dx + dz * dz > SPLASH_RADIUS * SPLASH_RADIUS) continue;
                e.damage(world.getDamageSources().fallingStalactite(owner), SPLASH_DAMAGE);
                Vec3d out = e.getPos().subtract(c).multiply(1, 0, 1);
                out = out.lengthSquared() > 1.0e-4 ? out.normalize().multiply(0.6) : Vec3d.ZERO;
                e.addVelocity(out.x, 0.35, out.z);
                e.velocityModified = true;
            }

            // Bury the tip in the ground
            moveTo(pos.add(0, -0.9, 0));
        }

        @Override
        protected boolean onLandedTick(int ticksSinceImpact) {
            if (ticksSinceImpact < STUCK_TICKS) return true;
            world.playSound(null, pos.x, pos.y + 1.5, pos.z, SoundEvents.BLOCK_DRIPSTONE_BLOCK_BREAK, SoundCategory.PLAYERS, 1.2f, 0.8f);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DRIPSTONE), pos.x, pos.y + 2, pos.z, 50, 0.3, 1.4, 0.3, 0.1);
            return false;
        }
    }
}
