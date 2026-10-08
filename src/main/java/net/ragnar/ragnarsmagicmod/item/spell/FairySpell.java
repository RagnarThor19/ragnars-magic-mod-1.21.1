package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.network.FairyPayload;
import net.ragnar.ragnarsmagicmod.util.FairyForm;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of the Fairy: you turn into a little glowing fairy for {@link #DURATION_TICKS} and fly. The flight itself is
 * simulated on the caster's client (FairyClient); the server keeps gravity off so nobody gets kicked for floating,
 * cancels fall damage while you're a fairy, counts down with chimes near the end, and turns you back - mid-air
 * if that's where you are, so mind the drop. Cast again to turn back early. The tome's cooldown starts once you're
 * a player again.
 */
public class FairySpell implements Spell {
    public static final int DURATION_TICKS = 200;  // 10s
    private static final DustParticleEffect WHITE = new DustParticleEffect(new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
    private static final DustParticleEffect PALE = new DustParticleEffect(new Vector3f(0.85f, 0.9f, 1.0f), 0.8f);

    private static final class Fairy {
        final ServerWorld world;
        int left = DURATION_TICKS;

        Fairy(ServerWorld world) {
            this.world = world;
        }
    }

    private static final Map<UUID, Fairy> FAIRIES = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(FairySpell::tick);
        // Players who come into view mid-flight need to be told
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            Fairy f = FAIRIES.get(entity.getUuid());
            if (f != null && entity instanceof ServerPlayerEntity sp) FairyPayload.sendTo(viewer, sp, f.left);
        });
        // Never let a fairy be saved: no gravity would stick to the player
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (FAIRIES.remove(handler.player.getUuid()) != null) restore(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID id : FAIRIES.keySet()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) restore(p);
            }
            FAIRIES.clear();
            FairyForm.SERVER.clear();
        });
    }

    public static boolean isFairy(PlayerEntity player) {
        return FAIRIES.containsKey(player.getUuid());
    }

    // Casting again turns you back: free, and allowed while the tome is cooling down
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return FairyForm.isFairy(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return FairyForm.isFairy(player) ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts when you turn back (see end)
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (FAIRIES.containsKey(player.getUuid())) {
            end(sp);
            return true;
        }
        if (net.ragnar.ragnarsmagicmod.phantom.Phantom.isPhantom(player)) {
            player.sendMessage(net.minecraft.text.Text.literal("Not while you're a spectre.").formatted(net.minecraft.util.Formatting.GRAY), true);
            return false;
        }

        FAIRIES.put(player.getUuid(), new Fairy(sw));
        FairyForm.SERVER.add(player.getUuid());
        player.setNoGravity(true);
        player.calculateDimensions();
        player.fallDistance = 0;
        FairyPayload.broadcast(sp, DURATION_TICKS);
        // A little hop up into the air as you shrink
        player.setVelocity(player.getVelocity().add(0, 0.35, 0));
        player.velocityModified = true;

        Vec3d c = player.getPos().add(0, 0.8, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ALLAY_ITEM_TAKEN, SoundCategory.PLAYERS, 1.2f, 1.3f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 0.6f, 1.7f);
        burst(sw, c, 1.0);
        return true;
    }

    private static void tick(ServerWorld world) {
        if (FAIRIES.isEmpty()) return;
        Iterator<Map.Entry<UUID, Fairy>> it = FAIRIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Fairy> entry = it.next();
            Fairy f = entry.getValue();
            if (f.world != world) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                FairyForm.SERVER.remove(entry.getKey());
                continue;
            }
            if (!player.isAlive() || player.getWorld() != world || --f.left <= 0) {
                it.remove();
                finish(player);
                continue;
            }

            player.fallDistance = 0;
            if (!player.hasNoGravity()) player.setNoGravity(true);
            Vec3d c = player.getPos().add(0, 0.25, 0);

            // A giggle now and then
            if (f.left % 55 == 30) {
                world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.PLAYERS, 0.8f, 1.3f + world.random.nextFloat() * 0.3f);
            }
            // The last three seconds: three falling chimes, so you know to find somewhere to land
            if (f.left == 60 || f.left == 40 || f.left == 20) {
                float pitch = f.left == 60 ? 1.6f : f.left == 40 ? 1.3f : 1.0f;
                world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 1.2f, pitch);
                world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, pitch);
            }
        }
    }

    /** Turn back early (cast again). */
    private static void end(ServerPlayerEntity player) {
        FAIRIES.remove(player.getUuid());
        finish(player);
    }

    private static void finish(ServerPlayerEntity player) {
        restore(player);
        FairyPayload.broadcast(player, 0);
        makeRoom(player);

        ServerWorld world = player.getServerWorld();
        Vec3d c = player.getPos().add(0, 0.9, 0);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.PLAYERS, 1.0f, 0.9f);
        world.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 12, 0.3, 0.5, 0.3, 0.02);
        burst(world, c, 1.4);

        // Only now does the cooldown start
        TomeItem tome = ModItems.TOME_OF_THE_FAIRY;
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(world, player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    /** Back to normal size and gravity. */
    private static void restore(ServerPlayerEntity player) {
        FairyForm.SERVER.remove(player.getUuid());
        player.setNoGravity(false);
        player.calculateDimensions();
    }

    /** Grown back to full size inside a gap? Step out to the nearest spot you fit. */
    private static void makeRoom(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (world.isSpaceEmpty(player)) return;
        Vec3d pos = player.getPos();
        for (double dy : new double[]{0.0, 0.5, 1.0, -0.5, -1.0, 1.5, -1.5}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Vec3d at = pos.add(dx, dy, dz);
                    Box box = player.getDimensions(EntityPose.STANDING).getBoxAt(at);
                    if (world.isSpaceEmpty(player, box)) {
                        player.networkHandler.requestTeleport(at.x, at.y, at.z, player.getYaw(), player.getPitch());
                        return;
                    }
                }
            }
        }
    }

    private static void burst(ServerWorld world, Vec3d c, double size) {
        world.spawnParticles(WHITE, c.x, c.y, c.z, 30, 0.4 * size, 0.4 * size, 0.4 * size, 0);
        world.spawnParticles(PALE, c.x, c.y, c.z, 15, 0.3 * size, 0.3 * size, 0.3 * size, 0);
        world.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 12, 0.2, 0.2, 0.2, 0.08);
    }
}
