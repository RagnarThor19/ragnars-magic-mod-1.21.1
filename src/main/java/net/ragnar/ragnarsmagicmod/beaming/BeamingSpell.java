package net.ragnar.ragnarsmagicmod.beaming;

import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.ChanneledSpell;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Beaming, on the server: keeps each caster's charge, burns whatever the beam touches every
 * {@link Beaming#HIT_EVERY} ticks, and overheats the tome when the charge runs out.
 */
public class BeamingSpell implements ChanneledSpell {
    /** The local player's charge, as the server last told it (client side only; see BeamingClient). */
    public static volatile int clientFuel = Beaming.FUEL_TICKS;

    private static final class Charge {
        final PlayerEntity player;
        int fuel = Beaming.FUEL_TICKS;
        int fired; // ticks fired out of this charge, across every burst
        boolean firing;
        int lastTick;

        Charge(PlayerEntity player) {
            this.player = player;
        }

        float heat() {
            return 1f - (float) fuel / Beaming.FUEL_TICKS;
        }
    }

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    private static int now;

    private static Charge charge(PlayerEntity player) {
        return CHARGES.computeIfAbsent(player.getUuid(), k -> new Charge(player));
    }

    /** Ticks of beam {@code player} has left in their charge (the full charge if they haven't started one). */
    public static int fuel(PlayerEntity player) {
        Charge c = CHARGES.get(player.getUuid());
        return c != null ? c.fuel : Beaming.FUEL_TICKS;
    }

    // ---------------------------------------------------------------------
    // Starting, holding, letting go
    // ---------------------------------------------------------------------

    /** A fresh charge costs the tome's XP; picking up a part-spent one is free. */
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        int fuel = player.getWorld().isClient ? clientFuel : fuel(player);
        return fuel >= Beaming.FUEL_TICKS ? tomeCost : 0;
    }

    /** The cooldown only starts when the charge is spent (see {@link #overheat}), never when a burst starts. */
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return clientFuel > 0;
        Charge c = charge(player);
        if (c.fuel <= 0) c.fuel = Beaming.FUEL_TICKS;
        c.firing = true;
        c.lastTick = now;
        Beaming.broadcast(sp, true, c.heat());
        Vec3d o = Beaming.origin(player, 1f);
        sw.playSound(null, o.x, o.y, o.z, SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 0.7f, 1.0f + 0.25f * c.heat());
        sw.playSound(null, o.x, o.y, o.z, SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, SoundCategory.PLAYERS, 1.0f, 0.8f);
        return true;
    }

    @Override
    public boolean channelTick(ServerWorld world, PlayerEntity player, ItemStack staff, TomeItem tome) {
        if (!(player instanceof ServerPlayerEntity sp)) return false;
        Charge c = charge(player);
        if (!c.firing || c.fuel <= 0) return false;
        c.lastTick = now;
        c.fuel--;
        c.fired++;
        Beaming.sendFuel(sp, c.fuel);

        HitResult hit = Beaming.trace(world, player, 1f);
        Vec3d at = hit.getPos();
        if (hit instanceof EntityHitResult eh && eh.getEntity() instanceof LivingEntity victim) {
            burn(world, player, victim, at, c);
        } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            scorch(world, bh, c);
        }
        if (c.fired % 4 == 0) ShakePayload.around(world, player.getPos(), 0.5, 0.07f + 0.08f * c.heat(), 5);

        if (c.fuel <= 0) {
            overheat(world, sp, staff, tome, c);
            return false;
        }
        return true;
    }

    @Override
    public void channelStop(ServerWorld world, PlayerEntity player) {
        stop(world, player);
    }

    private static void stop(ServerWorld world, PlayerEntity player) {
        Charge c = CHARGES.get(player.getUuid());
        if (c == null || !c.firing) return;
        c.firing = false;
        if (player instanceof ServerPlayerEntity sp) Beaming.broadcast(sp, false, c.heat());
        Vec3d o = Beaming.origin(player, 1f);
        world.playSound(null, o.x, o.y, o.z, SoundEvents.BLOCK_PISTON_CONTRACT, SoundCategory.PLAYERS, 0.6f, 1.1f);
    }

    /** Safety net: a beam whose use ended without a stop (died, swapped items...) is stopped here. */
    static void sweep(MinecraftServer server) {
        now++;
        for (Charge c : CHARGES.values()) {
            if (c.firing && now - c.lastTick > 2 && c.player.getWorld() instanceof ServerWorld sw) stop(sw, c.player);
        }
    }

    static void forget(PlayerEntity player) {
        CHARGES.remove(player.getUuid());
    }

    // ---------------------------------------------------------------------
    // What the beam does
    // ---------------------------------------------------------------------

    private static void burn(ServerWorld world, PlayerEntity player, LivingEntity victim, Vec3d at, Charge c) {
        // Leans on them a little, like a hose
        if (!(victim instanceof PlayerEntity)) {
            Vec3d push = player.getRotationVec(1f).multiply(0.03);
            victim.addVelocity(push.x, 0, push.z);
            victim.velocityModified = true;
        }
        world.spawnParticles(dust(c), at.x, at.y, at.z, 2, 0.08, 0.08, 0.08, 0);
        if (c.fired % Beaming.HIT_EVERY != 0) return;
        DamageSource source = world.getDamageSources().indirectMagic(player, player);
        victim.timeUntilRegen = 0;
        victim.damage(source, Beaming.DAMAGE_PER_SECOND * Beaming.HIT_EVERY / 20f);
        world.spawnParticles(ParticleTypes.WHITE_SMOKE, at.x, at.y, at.z, 3, 0.1, 0.1, 0.1, 0.02);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_CONDUIT_ATTACK_TARGET, SoundCategory.PLAYERS, 0.9f, 0.9f + 0.3f * c.heat());
    }

    /** Chips and sparks off the block it's hitting, and a sizzle now and then. Breaks nothing. */
    private static void scorch(ServerWorld world, BlockHitResult hit, Charge c) {
        Vec3d at = hit.getPos();
        BlockState state = world.getBlockState(hit.getBlockPos());
        Vec3d n = Vec3d.of(hit.getSide().getVector());
        if (!state.isAir()) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), at.x + n.x * 0.05, at.y + n.y * 0.05, at.z + n.z * 0.05,
                    2, 0.05, 0.05, 0.05, 0.15);
        }
        world.spawnParticles(dust(c), at.x + n.x * 0.1, at.y + n.y * 0.1, at.z + n.z * 0.1, 1, 0.05, 0.05, 0.05, 0);
        if (c.fired % 6 == 0) {
            world.spawnParticles(ParticleTypes.WHITE_SMOKE, at.x + n.x * 0.1, at.y + n.y * 0.1, at.z + n.z * 0.1, 1, 0.05, 0.05, 0.05, 0.01);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BLASTFURNACE_FIRE_CRACKLE, SoundCategory.PLAYERS, 0.8f, 0.9f);
        }
    }

    /** The charge is spent: the tome vents steam and goes on cooldown, and the next charge will be a fresh one. */
    private static void overheat(ServerWorld world, ServerPlayerEntity sp, ItemStack staff, TomeItem tome, Charge c) {
        c.firing = false;
        c.fuel = Beaming.FUEL_TICKS;
        c.fired = 0;
        StaffItem.applyCooldown(world, sp, staff, tome, tome.getCooldown());
        Beaming.broadcast(sp, false, 1f);
        Beaming.sendFuel(sp, c.fuel);
        Vec3d o = Beaming.origin(sp, 1f);
        world.playSound(null, o.x, o.y, o.z, SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE, SoundCategory.PLAYERS, 1.0f, 0.6f);
        world.playSound(null, o.x, o.y, o.z, SoundEvents.BLOCK_SMOKER_SMOKE, SoundCategory.PLAYERS, 1.5f, 0.5f);
        world.playSound(null, o.x, o.y, o.z, SoundEvents.BLOCK_PISTON_CONTRACT, SoundCategory.PLAYERS, 0.8f, 0.5f);
        world.spawnParticles(ParticleTypes.WHITE_SMOKE, o.x, o.y, o.z, 14, 0.12, 0.12, 0.12, 0.04);
    }

    private static DustParticleEffect dust(Charge c) {
        float h = c.heat();
        return new DustParticleEffect(new Vector3f(MathHelper.lerp(h, 0.25f, 1f), MathHelper.lerp(h, 1f, 0.45f), MathHelper.lerp(h, 0.85f, 0.1f)), 0.8f);
    }
}
