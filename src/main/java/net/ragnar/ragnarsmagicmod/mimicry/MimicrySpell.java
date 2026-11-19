package net.ragnar.ragnarsmagicmod.mimicry;

import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Tome of Mimicry, on the server (see Mimicry): who is disguised as what, and what gives them away. */
public class MimicrySpell implements Spell {
    /** How far you can drift from your spot (being nudged, standing on a slab edge...) before you're found out. */
    private static final double SLACK = 0.25;

    private static final class Disguise {
        final PlayerEntity player;
        final ServerWorld world;
        final BlockState state;
        final DisplayEntity display;
        final Vec3d anchor;
        int left = Mimicry.DURATION;

        Disguise(PlayerEntity player, ServerWorld world, BlockState state, DisplayEntity display, Vec3d anchor) {
            this.player = player;
            this.world = world;
            this.state = state;
            this.display = display;
            this.anchor = anchor;
        }
    }

    private static final Map<UUID, Disguise> DISGUISES = new HashMap<>();

    public static boolean isDisguised(PlayerEntity player) {
        return DISGUISES.containsKey(player.getUuid());
    }

    /** What {@code player} is disguised as, or null (server side; for the game tests). */
    @Nullable
    public static BlockState disguiseOf(PlayerEntity player) {
        Disguise d = DISGUISES.get(player.getUuid());
        return d == null ? null : d.state;
    }

    // ---------------------------------------------------------------------
    // The staff
    // ---------------------------------------------------------------------

    /** Casting again drops the disguise: free, and allowed while the tome cools down. */
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return !player.getWorld().isClient && isDisguised(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return !player.getWorld().isClient && isDisguised(player) ? 0 : tomeCost;
    }

    /** The cooldown starts when you reappear (see {@link #reveal}). */
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        if (isDisguised(player)) {
            reveal(player, true);
            return true;
        }

        BlockHitResult hit = sw.raycast(new RaycastContext(player.getEyePos(),
                player.getEyePos().add(player.getRotationVector().multiply(Mimicry.REACH)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        BlockState state = hit.getType() == HitResult.Type.BLOCK ? sw.getBlockState(hit.getBlockPos()) : null;
        if (state == null || !copyable(state)) {
            player.sendMessage(Text.literal("Look at a block to copy it."), true);
            return false;
        }
        if (!player.isOnGround() || player.hasVehicle()) {
            player.sendMessage(Text.literal("Stand on solid ground to blend in."), true);
            return false;
        }
        if (state.contains(Properties.WATERLOGGED)) state = state.with(Properties.WATERLOGGED, false);

        // Onto the block grid, so the copy lines up with the real blocks around it
        BlockPos feet = player.getBlockPos();
        Vec3d anchor = new Vec3d(feet.getX() + 0.5, player.getY(), feet.getZ() + 0.5);
        if (player instanceof ServerPlayerEntity sp) sp.networkHandler.requestTeleport(anchor.x, anchor.y, anchor.z, sp.getYaw(), sp.getPitch());
        else player.setPosition(anchor);
        player.setVelocity(Vec3d.ZERO);

        DisplayEntity display = spawnDisplay(sw, state, anchor);
        DISGUISES.put(player.getUuid(), new Disguise(player, sw, state, display, anchor));
        if (player instanceof ServerPlayerEntity sp) Mimicry.broadcast(sp, true);
        loseInterest(sw, player);

        // A puff, and there's a block where you were
        Vec3d c = anchor.add(0, 0.5, 0);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), c.x, c.y, c.z, 30, 0.35, 0.5, 0.35, 0.1);
        sw.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 12, 0.3, 0.5, 0.3, 0.03);
        sw.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y + 0.6, c.z, 20, 0.4, 0.4, 0.4, 0.8);
        sw.playSound(null, c.x, c.y, c.z, state.getSoundGroup().getPlaceSound(), SoundCategory.PLAYERS, 1.0f, 0.9f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.6f, 1.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_CHICKEN_EGG, SoundCategory.PLAYERS, 0.6f, 0.7f);
        player.sendMessage(Text.literal("You're a ").append(state.getBlock().getName()).append("!").formatted(Formatting.GREEN), true);
        return true;
    }

    /**
     * Anything drawn as an ordinary block model (stone, crafting tables, barrels, flowers, lanterns, fences, half a
     * door...), and the few drawn by their own renderer that make good hiding places: chests, shulker boxes and pots.
     */
    static boolean copyable(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() && !state.contains(Properties.WATERLOGGED)) return false;
        if (state.getRenderType() == BlockRenderType.MODEL) return true;
        return state.getRenderType() == BlockRenderType.ENTITYBLOCK_ANIMATED
                && (state.getBlock() instanceof AbstractChestBlock<?> || state.getBlock() instanceof ShulkerBoxBlock
                || state.getBlock() instanceof DecoratedPotBlock)
                && state.getBlock().asItem() != Items.AIR;
    }

    private static DisplayEntity spawnDisplay(ServerWorld world, BlockState state, Vec3d anchor) {
        DisplayEntity display;
        if (state.getRenderType() == BlockRenderType.MODEL) {
            // A block display draws from its corner
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
            d.setBlockState(state);
            d.refreshPositionAndAngles(anchor.x - 0.5, anchor.y, anchor.z - 0.5, 0f, 0f);
            display = d;
        } else {
            // Chests and the like only draw right as items: centred, turned to face the way the real one does
            DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(world);
            d.setItemStack(new ItemStack(state.getBlock().asItem()));
            d.setTransformationMode(ModelTransformationMode.NONE);
            Direction facing = state.contains(Properties.HORIZONTAL_FACING) ? state.get(Properties.HORIZONTAL_FACING) : Direction.SOUTH;
            d.setTransformation(new AffineTransformation(new Vector3f(),
                    new Quaternionf().rotationY((float) Math.toRadians(180 - facing.asRotation())), new Vector3f(1, 1, 1), new Quaternionf()));
            d.refreshPositionAndAngles(anchor.x, anchor.y + 0.5, anchor.z, 0f, 0f);
            display = d;
        }
        display.setViewRange(2.0f);
        TempEntities.track(display);
        world.spawnEntity(display);
        return display;
    }

    /** Whatever was after you loses track of you. */
    private static void loseInterest(ServerWorld world, PlayerEntity player) {
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(48), m -> m.getTarget() == player)) {
            mob.setTarget(null);
            mob.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
            if (mob instanceof Angerable a) a.stopAnger();
        }
    }

    // ---------------------------------------------------------------------
    // Every tick
    // ---------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        if (DISGUISES.isEmpty()) return;
        Iterator<Disguise> it = DISGUISES.values().iterator();
        while (it.hasNext()) {
            Disguise d = it.next();
            PlayerEntity p = d.player;
            Vec3d moved = p.getPos().subtract(d.anchor);
            boolean gone = p.isRemoved() || !p.isAlive() || p.getWorld() != d.world;
            boolean found = Math.hypot(moved.x, moved.z) > SLACK || Math.abs(moved.y) > 0.6 || p.hasVehicle();
            if (gone || found || --d.left <= 0) {
                it.remove();
                unmask(d, !gone);
                continue;
            }
            if (d.left % 20 == 0) {
                int s = d.left / 20;
                p.sendMessage(Text.literal("You're a ").append(d.state.getBlock().getName())
                        .append(Text.literal("  ·  move to come out  ·  " + s + "s").formatted(Formatting.DARK_GRAY)).formatted(Formatting.GRAY), true);
            }
            // Very rarely, a single stray letter drifts off the block: a tell for a sharp-eyed seeker
            if (d.world.random.nextInt(160) == 0) {
                Vec3d c = d.anchor.add(0, 0.9, 0);
                d.world.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y, c.z, 1, 0.2, 0.1, 0.2, 0.3);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Coming out
    // ---------------------------------------------------------------------

    /** Back to yourself, with a pop, and the tome starts cooling down. */
    public static void reveal(PlayerEntity player, boolean noisy) {
        Disguise d = DISGUISES.remove(player.getUuid());
        if (d != null) unmask(d, noisy);
    }

    private static void unmask(Disguise d, boolean noisy) {
        TempEntities.discard(d.display);
        PlayerEntity p = d.player;
        if (p instanceof ServerPlayerEntity sp && !sp.isDisconnected()) Mimicry.broadcast(sp, false);
        if (!noisy) return;

        Vec3d c = d.anchor.add(0, 0.5, 0);
        d.world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, d.state), c.x, c.y, c.z, 35, 0.35, 0.5, 0.35, 0.15);
        d.world.spawnParticles(ParticleTypes.POOF, c.x, c.y + 0.4, c.z, 15, 0.3, 0.6, 0.3, 0.05);
        d.world.playSound(null, c.x, c.y, c.z, d.state.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, 1.0f, 1.1f);
        d.world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.9f, 0.5f);
        p.sendMessage(Text.empty(), true);

        ItemStack staff = StaffItem.findStaffWith(p, Mimicry.TOME_OF_MIMICRY);
        if (!staff.isEmpty()) StaffItem.applyCooldown(d.world, p, staff, Mimicry.TOME_OF_MIMICRY, Mimicry.COOLDOWN);
        else p.getItemCooldownManager().set(Mimicry.TOME_OF_MIMICRY, Mimicry.COOLDOWN);
    }

    /** Logged out: the block just goes. */
    static void drop(PlayerEntity player) {
        Disguise d = DISGUISES.remove(player.getUuid());
        if (d != null) TempEntities.discard(d.display);
    }

    static void clear() {
        DISGUISES.clear();
    }

}
