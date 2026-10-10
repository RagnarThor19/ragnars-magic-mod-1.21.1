package net.ragnar.ragnarsmagicmod.sleep;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;

import java.util.List;
import java.util.Optional;

/**
 * The Tome of Sleep Potions throw: a lavender splash potion lobbed hard enough to carry about 30 blocks when aimed
 * high. Wherever it breaks it bursts into a sleepy cloud, and every living thing within {@link #RADIUS} blocks
 * (except whoever threw it) starts nodding off.
 */
public class SleepPotionEntity extends ThrownItemEntity {
    public static final float SPEED = 1.35f;
    public static final double RADIUS = 4.5;

    public SleepPotionEntity(EntityType<? extends SleepPotionEntity> type, World world) {
        super(type, world);
    }

    public SleepPotionEntity(World world, LivingEntity owner) {
        super(Sleep.POTION, owner, world);
        setItem(stack());
    }

    /** A splash potion bottle in the spell's colour, for the renderer. */
    static ItemStack stack() {
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(Optional.empty(), Optional.of(Sleep.COLOR), List.of()));
        return stack;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.SPLASH_POTION;
    }

    @Override
    protected double getGravity() {
        return 0.05;
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient && age > 1) getWorld().addParticle(Sleep.DUST, getX(), getY() + 0.1, getZ(), 0, 0, 0);
    }

    @Override
    protected void onCollision(HitResult hit) {
        super.onCollision(hit);
        if (!(getWorld() instanceof ServerWorld sw)) return;
        Vec3d at = hit.getPos();
        burst(sw, at);
        discard();
    }

    private void burst(ServerWorld world, Vec3d at) {
        // The glass breaking and the usual splash ring, in lavender
        world.syncWorldEvent(WorldEvents.INSTANT_SPLASH_POTION_SPLASHED, getBlockPos(), Sleep.COLOR);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_SPLASH_POTION_BREAK, SoundCategory.PLAYERS, 1.0f, 0.9f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PUFFER_FISH_BLOW_OUT, SoundCategory.PLAYERS, 1.0f, 0.6f);
        world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.5, at.z, 28, RADIUS * 0.35, 0.4, RADIUS * 0.35, 0.06);
        world.spawnParticles(Sleep.DUST, at.x, at.y + 0.6, at.z, 35, RADIUS * 0.4, 0.5, RADIUS * 0.4, 0.0);
        Sleep.cloud(world, at);

        Box area = new Box(at, at).expand(RADIUS, RADIUS * 0.75, RADIUS);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e != getOwner())) {
            Vec3d c = e.getBoundingBox().getCenter();
            if (c.squaredDistanceTo(at) > (RADIUS + e.getWidth() * 0.5) * (RADIUS + e.getWidth() * 0.5)) continue;
            Sleep.putToSleep(world, e);
        }
    }
}
