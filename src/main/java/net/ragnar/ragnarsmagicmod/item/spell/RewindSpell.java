package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.network.RewindPayload;
import org.joml.Vector3f;

import java.util.*;

/**
 * Tome of Rewind. Every player's last 7 seconds are remembered - where they stood, where they looked, their
 * health, hunger, fire and air. Casting plays it backwards: you walk your own steps in reverse while your
 * hearts tick back. Cast again to stop where you are.
 *
 * <p>Only the player's own state is rewound. Inventory, items on the ground, blocks and containers are never
 * touched, so nothing can be duplicated.
 */
public class RewindSpell implements Spell {
    public static final int HISTORY_TICKS = 20 * 7;
    public static final int SPEED = 2; // moments of history replayed per tick
    private static final int ARM_COOLDOWN = 6;

    /** One remembered moment. */
    private record Moment(RegistryKey<World> world, Vec3d pos, float yaw, float pitch, float health, float absorption,
                          int food, float saturation, int fireTicks, int air, float fallDistance) {
        static Moment of(ServerPlayerEntity p) {
            return new Moment(p.getWorld().getRegistryKey(), p.getPos(), p.getYaw(), p.getPitch(), p.getHealth(),
                    p.getAbsorptionAmount(), p.getHungerManager().getFoodLevel(), p.getHungerManager().getSaturationLevel(),
                    p.getFireTicks(), p.getAir(), p.fallDistance);
        }
    }

    private static final class Rewind {
        final Deque<Moment> remaining; // newest first: the order we walk back through them
        final int total;

        Rewind(Deque<Moment> remaining) {
            this.remaining = remaining;
            this.total = remaining.size();
        }
    }

    private static final Map<UUID, Deque<Moment>> HISTORY = new HashMap<>();
    private static final Map<UUID, Rewind> REWINDING = new HashMap<>();
    private static final DustParticleEffect GHOST = new DustParticleEffect(new Vector3f(0.55f, 0.85f, 1.0f), 1.0f);
    private static final DustParticleEffect GOLD = new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.4f), 0.8f);

    /** Starts remembering everyone's movements. Called once at startup. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(RewindSpell::tick);
        // Death or a new body wipes the slate: you can't rewind out of a death
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            HISTORY.remove(newPlayer.getUuid());
            REWINDING.remove(newPlayer.getUuid());
        });
    }

    /** While true the player can't be hurt - their health is being wound back anyway. */
    public static boolean isRewinding(PlayerEntity player) {
        return !player.getWorld().isClient && !REWINDING.isEmpty() && REWINDING.containsKey(player.getUuid());
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return isRewinding(player) ? 0 : tomeCost; // stopping it is free
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        // Just started: only a short lock, so the tome can still be used to stop it. The real cooldown starts
        // when the rewind ends (or right now, if this cast was the one that stopped it).
        return isRewinding(player) ? ARM_COOLDOWN : tomeCooldown;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(player instanceof ServerPlayerEntity sp) || !(world instanceof ServerWorld sw)) return false;

        if (REWINDING.containsKey(player.getUuid())) {
            finish(sp, true);
            return true;
        }

        Deque<Moment> history = HISTORY.get(player.getUuid());
        if (history == null || history.size() < 10) {
            player.sendMessage(Text.literal("There's nothing to rewind yet."), true);
            return false;
        }
        // Only this world's part of the past, newest first
        Deque<Moment> path = new ArrayDeque<>();
        Iterator<Moment> newestFirst = history.descendingIterator();
        while (newestFirst.hasNext()) {
            Moment m = newestFirst.next();
            if (m.world() != world.getRegistryKey()) break;
            path.addLast(m);
        }
        if (path.size() < 10) return false;

        if (player.hasVehicle()) player.stopRiding();
        REWINDING.put(player.getUuid(), new Rewind(path));
        history.clear();

        List<Vec3d> points = new ArrayList<>(path.size());
        for (Moment m : path) points.add(m.pos());
        RewindPayload.send(sp, true, points);

        Vec3d p = player.getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.5f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.5f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 0.6f);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y + 1, p.z, 40, 0.4, 0.8, 0.4, 0.1);
        return true;
    }

    private static void tick(MinecraftServer server) {
        // Remember
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (REWINDING.containsKey(p.getUuid())) continue;
            if (!p.isAlive() || p.isSpectator()) { HISTORY.remove(p.getUuid()); continue; }
            Deque<Moment> h = HISTORY.computeIfAbsent(p.getUuid(), id -> new ArrayDeque<>());
            h.addLast(Moment.of(p));
            while (h.size() > HISTORY_TICKS) h.removeFirst();
        }
        HISTORY.keySet().removeIf(id -> server.getPlayerManager().getPlayer(id) == null);

        // Replay backwards
        for (Map.Entry<UUID, Rewind> e : new ArrayList<>(REWINDING.entrySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive()) { REWINDING.remove(e.getKey()); continue; }
            Rewind r = e.getValue();
            if (r.remaining.isEmpty() || r.remaining.peekFirst().world() != p.getWorld().getRegistryKey()) {
                finish(p, false);
                continue;
            }
            afterimage((ServerWorld) p.getWorld(), p.getPos(), p.getHeight());
            Moment m = null;
            for (int i = 0; i < SPEED && !r.remaining.isEmpty(); i++) m = r.remaining.pollFirst();
            apply(p, m);
            effects(p, r);
            if (r.remaining.isEmpty()) finish(p, false);
        }
    }

    /** Puts the player back exactly as they were at that moment. */
    private static void apply(ServerPlayerEntity p, Moment m) {
        p.networkHandler.requestTeleport(m.pos().x, m.pos().y, m.pos().z, m.yaw(), m.pitch(), EnumSet.noneOf(PositionFlag.class));
        p.setVelocity(Vec3d.ZERO);
        p.setHealth(Math.min(m.health(), p.getMaxHealth()));
        p.setAbsorptionAmount(m.absorption());
        p.getHungerManager().setFoodLevel(m.food());
        p.getHungerManager().setSaturationLevel(m.saturation());
        p.setFireTicks(m.fireTicks());
        p.setAir(m.air());
        p.fallDistance = m.fallDistance();
    }

    private static void effects(ServerPlayerEntity p, Rewind r) {
        ServerWorld world = (ServerWorld) p.getWorld();
        int done = r.total - r.remaining.size();
        // A clock ticking the wrong way
        if (done % 8 < SPEED) {
            world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.8f, 0.6f);
        }
        if (done % 20 < SPEED) {
            world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.6f);
        }
        // Motes of light swirling the wrong way round
        double a = -done * 0.5;
        for (int i = 0; i < 2; i++) {
            double ang = a + i * Math.PI;
            world.spawnParticles(GOLD, p.getX() + Math.cos(ang) * 0.8, p.getY() + 1.0 + Math.sin(done * 0.2) * 0.6,
                    p.getZ() + Math.sin(ang) * 0.8, 1, 0, 0, 0, 0);
        }
    }

    /** The ghost of where you just were, left behind as you're pulled back. */
    private static void afterimage(ServerWorld world, Vec3d at, float height) {
        for (double y = 0.2; y < height; y += 0.45) {
            world.spawnParticles(GHOST, at.x, at.y + y, at.z, 1, 0.12, 0.05, 0.12, 0);
        }
    }

    private static void finish(ServerPlayerEntity p, boolean cancelled) {
        REWINDING.remove(p.getUuid());
        RewindPayload.send(p, false, List.of());
        p.setVelocity(Vec3d.ZERO);
        p.velocityModified = true;

        // Never leave anyone inside a wall someone built in the meantime
        BlockPos.Mutable pos = p.getBlockPos().mutableCopy();
        for (int i = 0; i < 4 && !p.getWorld().isSpaceEmpty(p, p.getDimensions(p.getPose()).getBoxAt(Vec3d.ofBottomCenter(pos))); i++) {
            pos.move(0, 1, 0);
        }
        if (!pos.equals(p.getBlockPos())) {
            p.networkHandler.requestTeleport(p.getX(), pos.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                    EnumSet.of(PositionFlag.Y_ROT, PositionFlag.X_ROT));
        }

        ServerWorld world = (ServerWorld) p.getWorld();
        world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 1.0f, 1.5f);
        world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 20, 0.3, 0.6, 0.3, 0.08);
        world.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);

        // Now the tome goes on cooldown
        ItemStack staff = StaffItem.findStaffWith(p, ModItems.TOME_OF_REWIND);
        if (!staff.isEmpty()) {
            StaffItem.applyCooldown(world, p, staff, ModItems.TOME_OF_REWIND, ModItems.TOME_OF_REWIND.getCooldown());
        } else {
            p.getItemCooldownManager().set(ModItems.TOME_OF_REWIND, ModItems.TOME_OF_REWIND.getCooldown());
        }
    }
}
