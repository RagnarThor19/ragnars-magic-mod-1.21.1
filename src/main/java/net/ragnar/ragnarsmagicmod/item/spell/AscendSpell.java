package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.AscendPayload;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Ascend, after the Legend of Zelda: Tears of the Kingdom. Underground, you float up off your feet
 * wrapped in rings of green light while a glowing circle opens on the ceiling above - then you dive up into the
 * rock, the view sweeping up through the stone, and burst out onto the surface directly above. Overworld only.
 */
public class AscendSpell implements Spell {
    private static final int CHARGE_TICKS = 14;

    private static final DustParticleEffect GREEN = new DustParticleEffect(new Vector3f(0.35f, 1.0f, 0.45f), 1.2f);
    private static final DustParticleEffect PALE = new DustParticleEffect(new Vector3f(0.75f, 1.0f, 0.7f), 0.7f);
    private static final DustColorTransitionParticleEffect SHIFT =
            new DustColorTransitionParticleEffect(new Vector3f(0.95f, 1.0f, 0.5f), new Vector3f(0.2f, 0.9f, 0.4f), 1.0f);

    private static final class Ascent {
        final ServerWorld world;
        final Vec3d start;
        final double ceiling; // underside of the ceiling straight above
        int age;

        Ascent(ServerWorld world, Vec3d start, double ceiling) {
            this.world = world;
            this.start = start;
            this.ceiling = ceiling;
        }
    }

    private static final Map<UUID, Ascent> ASCENTS = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(AscendSpell::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (ASCENTS.remove(handler.player.getUuid()) != null) handler.player.setNoGravity(false);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID id : ASCENTS.keySet()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) p.setNoGravity(false);
            }
            ASCENTS.clear();
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (ASCENTS.containsKey(player.getUuid())) return false;
        if (world.getRegistryKey() != World.OVERWORLD) {
            player.sendMessage(Text.literal("Ascend only works in the Overworld."), true);
            return false;
        }
        Double ceiling = ceilingAbove(sw, player);
        if (ceiling == null) {
            player.sendMessage(Text.literal("There's nothing above you to rise through."), true);
            return false;
        }
        Vec3d dest = surface(sw, sp);
        if (dest == null) {
            player.sendMessage(Text.literal("There's no way up from here."), true);
            return false;
        }

        ASCENTS.put(player.getUuid(), new Ascent(sw, player.getPos(), ceiling));
        player.stopRiding();
        player.setNoGravity(true);
        player.fallDistance = 0;
        player.setVelocity(0, 0.25, 0);
        player.velocityModified = true;

        Vec3d c = player.getPos();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.9f, 1.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.6f, 1.8f);
        ring(sw, c.add(0, 0.05, 0), 1.2, 32, GREEN);
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y + 0.2, c.z, 10, 0.4, 0.05, 0.4, 0.04);
        return true;
    }

    /** The underside of the first solid block straight above your head, or null if there isn't one below the surface. */
    private static Double ceilingAbove(ServerWorld world, PlayerEntity player) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(player.getX()), MathHelper.floor(player.getZ()));
        BlockPos.Mutable pos = BlockPos.ofFloored(player.getX(), player.getY() + player.getHeight(), player.getZ()).mutableCopy();
        for (; pos.getY() < top; pos.move(0, 1, 0)) {
            BlockState s = world.getBlockState(pos);
            if (!s.getCollisionShape(world, pos).isEmpty() || !s.getFluidState().isEmpty()) {
                return pos.getY() + (s.getCollisionShape(world, pos).isEmpty() ? 0.0
                        : s.getCollisionShape(world, pos).getMin(net.minecraft.util.math.Direction.Axis.Y));
            }
        }
        return null;
    }

    /** Where you come out: on top of whatever is highest straight above, or the nearest column beside it you fit in. */
    private static Vec3d surface(ServerWorld world, ServerPlayerEntity player) {
        int bx = MathHelper.floor(player.getX());
        int bz = MathHelper.floor(player.getZ());
        Vec3d exact = landing(world, player, player.getX(), player.getZ());
        if (exact != null) return exact;
        for (int r = 0; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Vec3d at = landing(world, player, bx + dx + 0.5, bz + dz + 0.5);
                    if (at != null) return at;
                }
            }
        }
        return null;
    }

    private static Vec3d landing(ServerWorld world, ServerPlayerEntity player, double x, double z) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
        if (top <= player.getY() + player.getHeight()) return null;
        BlockPos below = BlockPos.ofFloored(x, top - 1, z);
        if (world.getFluidState(below).isIn(FluidTags.LAVA)) return null;
        Vec3d at = new Vec3d(x, top, z);
        if (!world.isSpaceEmpty(player, player.getBoundingBox().offset(at.subtract(player.getPos())))) return null;
        return at;
    }

    private static void tick(ServerWorld world) {
        if (ASCENTS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Ascent>> it = ASCENTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Ascent> entry = it.next();
            Ascent a = entry.getValue();
            if (a.world != world) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!player.isAlive() || player.getWorld() != world) {
                it.remove();
                player.setNoGravity(false);
                continue;
            }
            if (++a.age >= CHARGE_TICKS) {
                it.remove();
                rise(world, player, a);
            } else {
                charge(world, player, a);
            }
        }
    }

    /** Floating up off your feet, wrapped in green light, while the way through opens above. */
    private static void charge(ServerWorld world, ServerPlayerEntity player, Ascent a) {
        int age = a.age;
        float f = age / (float) CHARGE_TICKS;

        // Drift up, slowing, never into the ceiling
        double room = a.ceiling - (player.getY() + player.getHeight()) - 0.3;
        double up = room > 0 ? Math.min(room, 0.16 * (1 - f)) : 0;
        player.setVelocity(0, up, 0);
        player.velocityModified = true;
        player.fallDistance = 0;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 3, 6, false, false, false));

        Vec3d p = player.getPos();
        // Rings sweeping up around you
        double ringY = (age % 7) / 7.0 * player.getHeight();
        ring(world, p.add(0, ringY, 0), 0.8, 16, age % 2 == 0 ? GREEN : SHIFT);
        if (age % 2 == 0) {
            double spin = age * 0.6;
            for (int i = 0; i < 3; i++) {
                double ang = spin + i * Math.PI * 2 / 3;
                world.spawnParticles(PALE, p.x + Math.cos(ang) * 0.6, p.y + player.getHeight() * f, p.z + Math.sin(ang) * 0.6, 1, 0, 0, 0, 0);
            }
        }
        // A glowing circle opening on the ceiling, right where you'll go through
        Vec3d hole = new Vec3d(p.x, a.ceiling - 0.05, p.z);
        double r = 0.25 + 0.75 * Math.min(1.0, f * 1.6);
        ring(world, hole, r, 20, GREEN);
        if (age % 3 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, hole.x, hole.y - 0.1, hole.z, 2, r * 0.4, 0.02, r * 0.4, 0.01);
        }
        // Light streaming up from you into it
        if (age % 2 == 1) {
            for (double y = p.y + player.getHeight(); y < hole.y; y += 0.6) {
                world.spawnParticles(PALE, p.x, y, p.z, 1, 0.08, 0.1, 0.08, 0);
            }
        }

        if (age % 4 == 0) {
            world.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.0f + f);
        }
        if (age == CHARGE_TICKS - 3) {
            world.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 0.9f, 1.4f);
        }
    }

    /** Up through the rock and out onto the surface. */
    private static void rise(ServerWorld world, ServerPlayerEntity player, Ascent a) {
        player.setNoGravity(false);
        Vec3d from = player.getPos();
        Vec3d dest = surface(world, player);
        if (dest == null) {
            // Someone built over it in the meantime
            player.sendMessage(Text.literal("The way up closed."), true);
            world.playSound(null, from.x, from.y, from.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.5f);
            return;
        }

        // Leaving: the ceiling swallows you in a flash of green
        world.spawnParticles(GREEN, from.x, a.ceiling - 0.1, from.z, 30, 0.4, 0.05, 0.4, 0);
        world.spawnParticles(ParticleTypes.END_ROD, from.x, a.ceiling - 0.2, from.z, 12, 0.3, 0.05, 0.3, 0.08);
        BlockState rock = world.getBlockState(BlockPos.ofFloored(from.x, a.ceiling + 0.1, from.z));
        if (!rock.isAir()) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, rock), from.x, a.ceiling - 0.1, from.z, 20, 0.4, 0.05, 0.4, 0.1);
        }
        world.playSound(null, from.x, from.y, from.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_2.value(), SoundCategory.PLAYERS, 1.0f, 1.3f);

        double distance = dest.y - from.y;
        AscendPayload.send(player, (int) MathHelper.clamp(6 + distance / 6, 8, 22));
        player.networkHandler.requestTeleport(dest.x, dest.y, dest.z, player.getYaw(), player.getPitch());
        // Pop up out of the ground
        player.setVelocity(0, 0.55, 0);
        player.velocityModified = true;
        player.fallDistance = 0;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 30, 0, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 10, 4, false, false, false));

        // Arriving: the ground bursts and a ring of green light rolls out
        BlockState ground = world.getBlockState(BlockPos.ofFloored(dest.x, dest.y - 0.5, dest.z));
        boolean water = world.getFluidState(BlockPos.ofFloored(dest.x, dest.y - 0.5, dest.z)).isIn(FluidTags.WATER);
        if (water) {
            world.spawnParticles(ParticleTypes.SPLASH, dest.x, dest.y + 0.1, dest.z, 60, 0.6, 0.1, 0.6, 0.3);
            world.spawnParticles(ParticleTypes.BUBBLE_POP, dest.x, dest.y + 0.1, dest.z, 20, 0.5, 0.1, 0.5, 0.05);
            world.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, SoundCategory.PLAYERS, 1.0f, 1.0f);
        } else if (!ground.isAir()) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), dest.x, dest.y + 0.1, dest.z, 40, 0.5, 0.1, 0.5, 0.25);
            world.playSound(null, dest.x, dest.y, dest.z, ground.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, 1.0f, 0.8f);
        }
        for (int i = 0; i < 36; i++) {
            double ang = i * Math.PI * 2 / 36;
            world.spawnParticles(GREEN, dest.x + Math.cos(ang) * 0.5, dest.y + 0.1, dest.z + Math.sin(ang) * 0.5, 0,
                    Math.cos(ang), 0, Math.sin(ang), 0.25);
        }
        world.spawnParticles(SHIFT, dest.x, dest.y + 1.0, dest.z, 30, 0.3, 0.8, 0.3, 0);
        world.spawnParticles(ParticleTypes.END_ROD, dest.x, dest.y + 0.5, dest.z, 16, 0.2, 0.6, 0.2, 0.12);
        world.spawnParticles(ParticleTypes.GLOW, dest.x, dest.y + 0.5, dest.z, 12, 0.4, 0.5, 0.4, 0.05);
        world.playSound(null, dest.x, dest.y, dest.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 1.8f);
        world.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        world.playSound(null, dest.x, dest.y, dest.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 2.0f);
    }

    private static void ring(ServerWorld world, Vec3d c, double r, int n, net.minecraft.particle.ParticleEffect effect) {
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            world.spawnParticles(effect, c.x + Math.cos(ang) * r, c.y, c.z + Math.sin(ang) * r, 1, 0, 0, 0, 0);
        }
    }
}
