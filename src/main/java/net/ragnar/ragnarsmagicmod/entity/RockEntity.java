package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.boulders.Tumble;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import org.jetbrains.annotations.Nullable;

/**
 * The Tome of Rocks' rock: a fist-sized lump of stone, drawn in 3D (see RockRenderer), that tumbles through the air on
 * a heavy arc. A direct hit does {@link #HIT_DAMAGE} and knocks the target back; when it breaks it spatters anything
 * close by for a little. Thrown low and fast at the ground it skips once, like a stone across a pond, before it breaks.
 */
public class RockEntity extends ThrownItemEntity {
    public static final float HIT_DAMAGE = 7.0f;
    public static final float SPLASH_DAMAGE = 3.0f;
    public static final double SPLASH_RADIUS = 2.0;
    private static final double SKIP_SPEED = 0.6;   // needs to be going at least this fast sideways to skip
    private static final int MAX_SKIPS = 1;
    /** Drawn size, in blocks from the middle. */
    public static final float RADIUS = 0.28f;

    // Client
    public final Tumble tumble;

    // Both sides (the client runs the same flight to keep it smooth)
    private int skips;
    private boolean skippedThisHit;
    @Nullable private Entity hitHead;

    public RockEntity(EntityType<? extends RockEntity> type, World world) {
        super(type, world);
        tumble = new Tumble(world.random.nextLong());
    }

    public RockEntity(World world, LivingEntity owner) {
        super(ModEntities.ROCK, owner, world);
        tumble = new Tumble(world.random.nextLong());
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.ROCK_ITEM;
    }

    @Override
    protected double getGravity() {
        return 0.05; // heavier than a snowball
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient) return;
        Vec3d moved = getPos().subtract(prevX, prevY, prevZ);
        tumble.roll(moved, RADIUS, 0.35f, 0.7f);
        // A thin trail of grit
        if (random.nextInt(2) == 0) {
            getWorld().addParticle(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.STONE.getDefaultState()),
                    getX() + (random.nextDouble() - 0.5) * 0.3, getY() + getHeight() / 2, getZ() + (random.nextDouble() - 0.5) * 0.3, 0, 0, 0);
        }
    }

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);
        if (!(getWorld() instanceof ServerWorld sw)) return;
        Entity target = hit.getEntity();
        hitHead = target;
        target.damage(getDamageSources().thrown(this, getOwner()), HIT_DAMAGE);
        if (target instanceof LivingEntity le) {
            Vec3d dir = getVelocity().multiply(1, 0, 1);
            if (dir.lengthSquared() > 1e-4) le.takeKnockback(0.9, -dir.x, -dir.z);
        }
        Vec3d c = target.getBoundingBox().getCenter();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.0f, 0.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 1.2f, 0.6f);
        sw.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 10, 0.2, 0.2, 0.2, 0.3);
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        super.onBlockHit(hit);
        Vec3d v = getVelocity();
        double across = v.horizontalLength();
        // Low and fast onto the ground: skip off it instead of breaking
        if (hit.getSide() == Direction.UP && skips < MAX_SKIPS && across > SKIP_SPEED && -v.y < across * 0.9) {
            skips++;
            skippedThisHit = true;
            setVelocity(v.x * 0.65, Math.max(-v.y * 0.45, 0.18), v.z * 0.65);
            setPosition(hit.getPos().x, hit.getPos().y + 0.02, hit.getPos().z);
            if (getWorld() instanceof ServerWorld sw) {
                BlockState ground = sw.getBlockState(hit.getBlockPos());
                Vec3d p = hit.getPos();
                sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), p.x, p.y + 0.05, p.z, 12, 0.2, 0.05, 0.2, 0.1);
                sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 1.0f, 1.3f);
                sw.playSound(null, p.x, p.y, p.z, ground.getSoundGroup().getHitSound(), SoundCategory.PLAYERS, 1.0f, 0.8f);
            }
        }
    }

    @Override
    protected void onCollision(HitResult hit) {
        skippedThisHit = false;
        super.onCollision(hit);
        if (getWorld() instanceof ServerWorld sw && !skippedThisHit) {
            shatter(sw, hit);
            discard();
        }
    }

    /** Breaks apart where it landed, spattering whatever's close (but not what it just hit head on). */
    private void shatter(ServerWorld sw, HitResult hit) {
        Vec3d c = hit.getPos();
        Entity owner = getOwner();
        Box box = new Box(c, c).expand(SPLASH_RADIUS + 1);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, box, e -> e != owner && e != hitHead && e.isAlive() && !e.isSpectator())) {
            Vec3d at = e.getBoundingBox().getCenter();
            double d = at.distanceTo(c);
            if (d > SPLASH_RADIUS) continue;
            float k = (float) (1.0 - d / SPLASH_RADIUS);
            e.damage(getDamageSources().thrown(this, owner), MathHelper.lerp(k, 1.0f, SPLASH_DAMAGE));
            Vec3d push = at.subtract(c).multiply(1, 0, 1);
            if (push.lengthSquared() > 1e-4) e.takeKnockback(0.25 + 0.35 * k, -push.x, -push.z);
        }

        BlockState hitState = hit instanceof BlockHitResult bh ? sw.getBlockState(bh.getBlockPos()) : Blocks.STONE.getDefaultState();
        if (hitState.isAir()) hitState = Blocks.STONE.getDefaultState();
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), c.x, c.y + 0.2, c.z, 18, 0.2, 0.2, 0.2, 0.15);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()), c.x, c.y + 0.2, c.z, 10, 0.2, 0.2, 0.2, 0.15);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, hitState), c.x, c.y + 0.1, c.z, 10, 0.3, 0.1, 0.3, 0.1);
        sw.spawnParticles(ParticleTypes.POOF, c.x, c.y + 0.2, c.z, 4, 0.15, 0.1, 0.15, 0.03);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DECORATED_POT_SHATTER, SoundCategory.PLAYERS, 0.6f, 0.5f);
    }
}
