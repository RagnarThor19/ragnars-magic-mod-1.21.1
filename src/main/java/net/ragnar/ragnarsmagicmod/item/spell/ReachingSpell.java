package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Tome of Reaching: shoots the block in your other hand out to wherever you're looking and places it there, exactly
 * as if you'd right-clicked that face up close (stairs, logs and slabs face the right way). A small spinning copy of
 * the block flies out and pops into place. Nothing is spent unless the block can go there; if the spot gets taken
 * while it's in the air, you get the block back.
 */
public class ReachingSpell implements Spell {
    private static final double RANGE = 48.0;
    private static final double SPEED = 2.5;      // blocks per tick in flight
    private static final int MAX_FLIGHT_TICKS = 16;
    private static final float FLIGHT_SCALE = 0.45f;

    private static final class Shot {
        final ServerWorld world;
        final UUID caster;
        final Hand hand;
        final ItemStack block;       // the single item being sent
        final BlockHitResult hit;
        final Vec3d from, to;
        final DisplayEntity.BlockDisplayEntity display;
        final int flightTicks;
        int age;

        Shot(ServerWorld world, UUID caster, Hand hand, ItemStack block, BlockHitResult hit, Vec3d from, Vec3d to,
             DisplayEntity.BlockDisplayEntity display, int flightTicks) {
            this.world = world;
            this.caster = caster;
            this.hand = hand;
            this.block = block;
            this.hit = hit;
            this.from = from;
            this.to = to;
            this.display = display;
            this.flightTicks = flightTicks;
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(ReachingSpell::tick);
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !player.canModifyBlocks()) return false;
        Hand hand = player.getMainHandStack() == staff ? Hand.OFF_HAND : Hand.MAIN_HAND;
        ItemStack held = player.getStackInHand(hand);
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            player.sendMessage(net.minecraft.text.Text.literal("Hold a block in your other hand.")
                    .formatted(net.minecraft.util.Formatting.GRAY), true);
            return false;
        }

        HitResult ray = player.raycast(RANGE, 0f, false);
        if (ray.getType() != HitResult.Type.BLOCK || !(ray instanceof BlockHitResult hit)) return false;

        // Would it actually go there? Check now so nothing is wasted on a throw that can't land
        ItemPlacementContext ctx = new ItemPlacementContext(player, hand, held.copyWithCount(1), hit);
        BlockPos pos = ctx.getBlockPos();
        if (!ctx.canPlace() || !world.canPlayerModifyAt(player, pos)) return false;
        BlockState preview = blockItem.getBlock().getPlacementState(ctx);
        if (preview == null || !preview.canPlaceAt(world, pos)) return false;

        ensureRegistered();
        ItemStack sent = held.copyWithCount(1);
        if (!player.isCreative()) held.decrement(1);

        Vec3d look = player.getRotationVector();
        Vec3d right = new Vec3d(-look.z, 0, look.x).normalize();
        double side = hand == Hand.OFF_HAND ^ player.getMainArm() == net.minecraft.util.Arm.LEFT ? -0.35 : 0.35;
        Vec3d from = player.getEyePos().add(look.multiply(0.6)).add(right.multiply(side)).add(0, -0.35, 0);
        Vec3d to = Vec3d.ofCenter(pos);
        int flight = MathHelper.clamp(MathHelper.ceil(from.distanceTo(to) / SPEED), 2, MAX_FLIGHT_TICKS);

        DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(sw);
        if (display != null) {
            display.setBlockState(preview);
            display.setTeleportDuration(1);
            display.setInterpolationDuration(1);
            display.setBrightness(new Brightness(15, 15));
            display.refreshPositionAndAngles(from.x, from.y, from.z, 0f, 0f);
            display.setTransformation(spin(0));
            TempEntities.track(display);
            sw.spawnEntity(display);
        }
        SHOTS.add(new Shot(sw, player.getUuid(), hand, sent, hit, from, to, display, flight));

        sw.playSound(null, from.x, from.y, from.z, SoundEvents.ENTITY_EGG_THROW, SoundCategory.PLAYERS, 0.6f, 1.3f);
        sw.playSound(null, from.x, from.y, from.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 0.4f, 1.9f);
        return true;
    }

    /** The flying block, shrunk and tumbling end over end around its centre. */
    private static AffineTransformation spin(int age) {
        return new AffineTransformation(new Matrix4f()
                .rotateY(age * 0.6f)
                .rotateX(age * 0.45f)
                .scale(FLIGHT_SCALE)
                .translate(-0.5f, -0.5f, -0.5f));
    }

    private static void tick(ServerWorld world) {
        if (SHOTS.isEmpty()) return;
        Iterator<Shot> it = SHOTS.iterator();
        while (it.hasNext()) {
            Shot s = it.next();
            if (s.world != world) continue;
            s.age++;
            if (s.age < s.flightTicks) {
                if (s.display != null) {
                    Vec3d p = s.from.lerp(s.to, s.age / (double) s.flightTicks);
                    s.display.setPosition(p.x, p.y, p.z);
                    s.display.setStartInterpolation(0);
                    s.display.setTransformation(spin(s.age));
                }
                continue;
            }
            it.remove();
            if (s.display != null) TempEntities.discard(s.display);
            land(world, s);
        }
    }

    private static void land(ServerWorld world, Shot s) {
        PlayerEntity player = world.getPlayerByUuid(s.caster);
        boolean placed = false;
        if (player != null && s.block.getItem() instanceof BlockItem blockItem) {
            ItemPlacementContext ctx = new ItemPlacementContext(player, s.hand, s.block.copy(), s.hit);
            placed = world.canPlayerModifyAt(player, ctx.getBlockPos()) && blockItem.place(ctx).isAccepted();
            if (placed) {
                BlockPos pos = ctx.getBlockPos();
                BlockState state = world.getBlockState(pos);
                BlockSoundGroup group = state.getSoundGroup();
                // Vanilla leaves the placer out of the place sound, so play it for everyone
                world.playSound(null, pos, group.getPlaceSound(), SoundCategory.BLOCKS, (group.getVolume() + 1f) / 2f, group.getPitch() * 0.8f);
                world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.4f, 0.7f);
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 18, 0.35, 0.35, 0.35, 0.1);
            }
        }
        if (!placed) {
            // Spot taken (or the caster left): the block comes back
            if (player != null && !player.isCreative()) player.getInventory().offerOrDrop(s.block);
            world.spawnParticles(ParticleTypes.POOF, s.to.x, s.to.y, s.to.z, 5, 0.15, 0.15, 0.15, 0.02);
        }
    }
}
