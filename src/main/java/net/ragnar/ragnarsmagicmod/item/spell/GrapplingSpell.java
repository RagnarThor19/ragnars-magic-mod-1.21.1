package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.GrapplePayloads;
import net.ragnar.ragnarsmagicmod.util.Aim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Grappling. A magic chain shoots out to wherever you're looking and hooks in, then reels you in hard: you
 * swing around the anchor as you go (steer with WASD), pop up over the ledge when you arrive, or jump to let go and
 * fling yourself onwards with all that speed. Sneak to let go gently. No fall damage while you're on the chain.
 *
 * Hook a small creature and it's yanked through the air to your feet instead. Hook a big one (an iron golem, a
 * ravager...) and it's you that gets pulled to it.
 *
 * The pull itself is worked out on the caster's client (see GrappleClient), so it's smooth; the server fires the hook,
 * plays the sounds everyone hears and handles creatures.
 */
public class GrapplingSpell implements Spell {
    public static final double RANGE = 40.0;
    private static final double AIM_CONE = 2.5;
    private static final double HOOK_SPEED = 4.0;       // blocks per tick
    public static final int MAX_PULL_TICKS = 60;
    private static final int YANK_TICKS = 14;
    private static final float BIG = 2.5f;              // width * height above this and it pulls you instead

    private static final class Grapple {
        final ServerWorld world;
        final Vec3d anchor;
        final BlockPos anchorBlock;  // null when hooked on a creature
        final Entity entity;         // null when hooked on a block
        final boolean pullSelf;
        final int travel;
        int age = 0;

        Grapple(ServerWorld world, Vec3d anchor, BlockPos anchorBlock, Entity entity, boolean pullSelf, int travel) {
            this.world = world;
            this.anchor = anchor;
            this.anchorBlock = anchorBlock;
            this.entity = entity;
            this.pullSelf = pullSelf;
            this.travel = travel;
        }
    }

    private static final Map<UUID, Grapple> ACTIVE = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(GrapplingSpell::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ACTIVE.clear());
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity caster)) return false;
        ensureRegistered();

        Vec3d eye = caster.getEyePos();
        Vec3d look = caster.getRotationVector();
        HitResult hit = sw.raycast(new RaycastContext(eye, eye.add(look.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, caster));
        BlockHitResult blockHit = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK ? bhr : null;
        Entity target = Aim.target(sw, caster, RANGE, AIM_CONE, e -> e instanceof LivingEntity);
        if (target != null && blockHit != null
                && target.getBoundingBox().getCenter().squaredDistanceTo(eye) > blockHit.getPos().squaredDistanceTo(eye)) {
            target = null; // the wall is closer
        }
        if (target == null && blockHit == null) {
            caster.sendMessage(Text.literal("Nothing to hook onto."), true);
            return false;
        }

        end(caster); // a new hook replaces any old one
        Grapple g;
        if (target != null) {
            boolean big = target.getWidth() * target.getHeight() > BIG;
            Vec3d at = target.getBoundingBox().getCenter();
            g = new Grapple(sw, at, null, target, big, travelTicks(eye, at));
        } else {
            Vec3d at = blockHit.getPos();
            g = new Grapple(sw, at, blockHit.getBlockPos(), null, true, travelTicks(eye, at));
        }
        ACTIVE.put(caster.getUuid(), g);
        GrapplePayloads.broadcast(caster, new GrapplePayloads.Attach(caster.getId(), g.anchor.x, g.anchor.y, g.anchor.z,
                g.entity != null ? g.entity.getId() : -1, g.pullSelf, g.travel));

        sw.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 1.0f, 0.5f);
        sw.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.4f);
        sw.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BLOCK_CHAIN_FALL, SoundCategory.PLAYERS, 0.6f, 1.6f);
        return true;
    }

    private static int travelTicks(Vec3d from, Vec3d to) {
        return MathHelper.clamp(MathHelper.ceil(from.distanceTo(to) / HOOK_SPEED), 2, 10);
    }

    private static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, Grapple> e : new ArrayList<>(ACTIVE.entrySet())) {
            Grapple g = e.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || p.getServerWorld() != g.world) {
                ACTIVE.remove(e.getKey());
                if (p != null) GrapplePayloads.broadcast(p, new GrapplePayloads.End(p.getId()));
                continue;
            }
            g.age++;
            if (g.age == g.travel) latch(g, p);
            if (g.entity != null && (!g.entity.isAlive() || g.entity.getWorld() != g.world)) { end(p); continue; }
            if (g.pullSelf) {
                if (g.age >= g.travel) p.fallDistance = 0;
                if (g.age > g.travel + MAX_PULL_TICKS + 10) end(p); // the client lets go well before this
            } else if (g.age >= g.travel + YANK_TICKS) {
                end(p);
            }
        }
    }

    /** The hook bites: a clank, sparks of whatever it hit, and a hooked creature gets hauled in. */
    private static void latch(Grapple g, ServerPlayerEntity p) {
        ServerWorld world = g.world;
        Vec3d at = g.entity != null ? g.entity.getBoundingBox().getCenter() : g.anchor;
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.2f, 0.8f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_TRIDENT_HIT_GROUND, SoundCategory.PLAYERS, 0.9f, 1.3f);
        world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_CROSSBOW_LOADING_END, SoundCategory.PLAYERS, 0.8f, 1.6f);
        if (g.anchorBlock != null) {
            BlockState state = world.getBlockState(g.anchorBlock);
            if (!state.isAir()) world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 16, 0.15, 0.15, 0.15, 0.15);
        }
        world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 10, 0.1, 0.1, 0.1, 0.4);

        if (!g.pullSelf && g.entity != null) yank(g.entity, p);
    }

    /** Throws the hooked creature in an arc that lands it about a block and a half in front of you. */
    private static void yank(Entity e, ServerPlayerEntity p) {
        e.stopRiding();
        Vec3d delta = p.getPos().subtract(e.getPos());
        Vec3d flat = new Vec3d(delta.x, 0, delta.z);
        double dist = Math.max(0, flat.length() - 1.5);
        Vec3d h = flat.lengthSquared() < 1.0e-6 ? Vec3d.ZERO : flat.normalize().multiply(Math.min(dist / 7.0, 2.4));
        double up = MathHelper.clamp(0.45 + delta.y * 0.08, 0.3, 1.2);
        e.setVelocity(h.x, up, h.z);
        e.velocityModified = true;
        e.velocityDirty = true;
        e.getWorld().playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 1.0f, 0.6f);
    }

    /** The caster let go, from their own client. */
    public static void onRelease(ServerPlayerEntity player, boolean jumped) {
        Grapple g = ACTIVE.get(player.getUuid());
        if (g == null || !g.pullSelf) return;
        player.fallDistance = 0;
        if (jumped) {
            g.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1.0f, 1.1f);
            g.world.spawnParticles(ParticleTypes.GUST, player.getX(), player.getY() + 0.5, player.getZ(), 1, 0, 0, 0, 0);
        }
        end(player);
    }

    private static void end(ServerPlayerEntity p) {
        Grapple g = ACTIVE.remove(p.getUuid());
        if (g == null) return;
        GrapplePayloads.broadcast(p, new GrapplePayloads.End(p.getId()));
        g.world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 0.8f, 1.2f);
    }
}
