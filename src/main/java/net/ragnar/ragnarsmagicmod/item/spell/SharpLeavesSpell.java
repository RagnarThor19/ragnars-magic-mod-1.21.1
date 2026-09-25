package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.Slicer;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Flicks a razor-sharp leaf, spinning like a throwing star, straight at the crosshair. Small damage, but a
 * headshot - or a leaf thrown while falling, like a vanilla critical hit - cuts deeper.
 */
public class SharpLeavesSpell implements Spell {
    private static final float DAMAGE = 3.0f;
    private static final float CRIT_DAMAGE = 5.0f; // headshots and crits
    private static final float SPIN = 1.1f;   // radians per tick

    /** Each leaf remembers whether it was thrown as a crit. */
    private static Slicer.Style leaf(boolean crit) {
        return new Slicer.Style() {
            @Override public double speed() { return 2.2; }
            @Override public double range() { return 40.0; }
            @Override public double hitMargin() { return 0.2; }

            @Override
            public List<DisplayEntity> spawnVisual(ServerWorld world, Vec3d pos, Vec3d dir) {
                DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(world);
                if (d == null) return List.of();
                d.setItemStack(new ItemStack(Items.BIG_DRIPLEAF));
                d.setTransformationMode(ModelTransformationMode.NONE);
                d.setTeleportDuration(1);
                d.setViewRange(1.5f);
                d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
                d.setTransformation(leafTransform(dir, 0));
                TempEntities.track(d);
                world.spawnEntity(d);
                return List.of(d);
            }

            @Override
            public void flight(ServerWorld world, List<DisplayEntity> visual, Vec3d from, Vec3d to, Vec3d dir, int age) {
                for (DisplayEntity d : visual) {
                    d.setPosition(to.x, to.y, to.z);
                    d.setTransformation(leafTransform(dir, age));
                    d.setStartInterpolation(0);
                    d.setInterpolationDuration(1);
                }
            }

            @Override
            public void hitEntity(ServerWorld world, PlayerEntity owner, LivingEntity target, Vec3d at, boolean head) {
                boolean strong = head || crit;
                target.damage(owner != null ? world.getDamageSources().thrown(owner, owner) : world.getDamageSources().generic(),
                        strong ? CRIT_DAMAGE : DAMAGE);
                if (strong) {
                    world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 14, 0.2, 0.2, 0.2, 0.4);
                    if (crit) world.spawnParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.3);
                    world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.9f, 1.2f);
                }
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.AZALEA_LEAVES.getDefaultState()),
                        at.x, at.y, at.z, 12, 0.15, 0.15, 0.15, 0.1);
                world.spawnParticles(ParticleTypes.COMPOSTER, at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.05);
                world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 5, 0.1, 0.1, 0.1, 0.2);
                world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 1.8f);
                world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.PLAYERS, 1.0f, 1.3f);
                if (owner != null) {
                    // The little "hit" ding, just for the caster
                    world.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS,
                            strong ? 0.5f : 0.35f, strong ? 2.0f : 1.6f);
                }
            }

            @Override
            public void hitWall(ServerWorld world, Vec3d at, Vec3d dir) {
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.AZALEA_LEAVES.getDefaultState()),
                        at.x, at.y, at.z, 8, 0.1, 0.1, 0.1, 0.05);
                world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AZALEA_LEAVES_HIT, SoundCategory.PLAYERS, 1.0f, 1.2f);
            }
        };
    }

    /** Same conditions as a vanilla critical hit: falling, and not on a ladder, in water, blind or riding. */
    private static boolean isCrit(PlayerEntity player) {
        return player.fallDistance > 0.0f && !player.isOnGround() && !player.isClimbing() && !player.isTouchingWater()
                && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.BLINDNESS) && !player.hasVehicle();
    }

    /** The leaf lying flat along its flight, spinning about the vertical like a throwing star. */
    private static AffineTransformation leafTransform(Vec3d dir, int age) {
        float yaw = (float) Math.atan2(-dir.x, dir.z);
        return new AffineTransformation(new Matrix4f()
                .rotate(new Quaternionf().rotationY(-yaw))
                .rotateX((float) Math.toRadians(90))
                .rotateZ(age * SPIN)
                .scale(0.6f));
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ServerWorld sw = (ServerWorld) world;
        Slicer.fire(sw, player, leaf(isCrit(player)));
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 2.0f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AZALEA_LEAVES_STEP, SoundCategory.PLAYERS, 1.0f, 1.5f);
        return true;
    }
}
