package net.ragnar.ragnarsmagicmod.logs;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Logs (see Logs). Casting only arms it, and only the caster can tell: a quiet sound and a line on their
 * own action bar. The first hit from an entity in the next {@link #WINDOW} ticks is cancelled and swapped for a log.
 */
public class LogsSpell implements Spell {
    /** How long the substitution stays ready after casting (2.5 seconds). */
    public static final int WINDOW = 50;

    /** Log size: about as tall and wide as a player. */
    private static final float LOG_WIDTH = 0.62f, LOG_HEIGHT = 1.7f;
    private static final int STAND_TICKS = 4, TOPPLE_TICKS = 8, LIE_TICKS = 30;

    private static final ParticleEffect WHITE = new DustParticleEffect(new Vector3f(1f, 1f, 1f), 2.4f);
    private static final BlockState LOG = Blocks.OAK_LOG.getDefaultState();
    private static final ParticleEffect SPLINTERS = new BlockStateParticleEffect(ParticleTypes.BLOCK, LOG);

    /** Armed players and the ticks they have left. */
    private static final Map<UUID, Integer> ARMED = new HashMap<>();
    private static final List<FallingLog> LOGS = new ArrayList<>();

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts when the window ends or the substitution goes off
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        // On the client this passes, so the hand doesn't even swing for anyone watching
        if (!(world instanceof ServerWorld) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (ARMED.containsKey(player.getUuid())) {
            sp.sendMessage(Text.literal("Substitution is already ready.").formatted(Formatting.GRAY), true);
            return false;
        }
        ARMED.put(player.getUuid(), WINDOW);
        // Only the caster hears and sees this
        sp.playSoundToPlayer(SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.PLAYERS, 0.5f, 1.6f);
        sp.playSoundToPlayer(SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 0.8f, 1.3f);
        sp.sendMessage(Text.literal("Substitution ready...").formatted(Formatting.WHITE, Formatting.ITALIC), true);
        return true;
    }

    // ---------------------------------------------------------------------
    // The substitution
    // ---------------------------------------------------------------------

    /** Called for every hit on a player. True if it was swapped for a log (and so never lands). */
    static boolean trySubstitute(ServerPlayerEntity player, DamageSource source) {
        if (!ARMED.containsKey(player.getUuid())) return false;
        Entity attacker = source.getAttacker();
        if (attacker == null || attacker == player || attacker.getWorld() != player.getWorld()) return false;
        // Still flinching from the last hit: this one wouldn't land anyway, so don't waste the log on it
        if (player.timeUntilRegen > 10) return false;

        ARMED.remove(player.getUuid());
        substitute(player.getServerWorld(), player, attacker);
        startCooldown(player);
        return true;
    }

    private static void substitute(ServerWorld sw, ServerPlayerEntity player, Entity attacker) {
        Vec3d from = player.getPos();
        Vec3d away = from.subtract(attacker.getPos()).multiply(1, 0, 1);
        if (away.lengthSquared() < 1e-4) away = player.getRotationVector().multiply(-1, 0, -1);
        if (away.lengthSquared() < 1e-4) away = new Vec3d(1, 0, 0);
        away = away.normalize();

        poof(sw, from.add(0, player.getHeight() / 2, 0), true);
        FallingLog.drop(sw, from, away);

        Vec3d spot = behind(sw, player, attacker);
        if (spot != null) {
            Vec3d look = attacker.getEyePos().subtract(spot.add(0, player.getStandingEyeHeight(), 0));
            float yaw = (float) (MathHelper.atan2(look.z, look.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
            float pitch = (float) -(MathHelper.atan2(look.y, look.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN);
            Logs.SwapPayload.send(player);
            player.teleport(sw, spot.x, spot.y, spot.z, Set.of(), yaw, pitch);
            player.setHeadYaw(yaw);
            player.setVelocity(Vec3d.ZERO);
            player.velocityModified = true;
            player.fallDistance = 0f;
            poof(sw, spot.add(0, player.getHeight() / 2, 0), false);
        }

        // Where did they go? The mob loses track of you for a moment
        if (attacker instanceof MobEntity mob && mob.getTarget() == player) {
            mob.setTarget(null);
            mob.getNavigation().stop();
        }
    }

    /**
     * A spot behind {@code attacker} the player fits in, preferably with ground under it: straight behind first,
     * then swinging round to the sides, a little up or down as needed. Null if there's nowhere at all.
     */
    @Nullable
    private static Vec3d behind(ServerWorld sw, PlayerEntity player, Entity attacker) {
        EntityDimensions dims = player.getDimensions(EntityPose.STANDING);
        double base = attacker.getWidth() / 2 + dims.width() / 2 + 0.7;
        double[] dists = {base, base + 0.6, Math.max(0.6, base - 0.4), base + 1.3};
        int[] angles = {0, 25, -25, 55, -55, 90, -90, 135, -135};
        double[] ups = {0, 0.6, -0.6, 1.1, -1.1, 1.6, -1.6, 2.1};
        for (int pass = 0; pass < 2; pass++) {
            for (int angle : angles) {
                Vec3d back = Vec3d.fromPolar(0f, attacker.getYaw() + angle).multiply(-1);
                for (double dist : dists) {
                    for (double up : ups) {
                        Vec3d at = attacker.getPos().add(back.multiply(dist)).add(0, up, 0);
                        if (!sw.isSpaceEmpty(player, dims.getBoxAt(at).contract(1.0E-6))) continue;
                        if (pass == 0 && !hasGround(sw, player, at)) continue;
                        return at;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasGround(ServerWorld sw, PlayerEntity player, Vec3d feet) {
        return !sw.isSpaceEmpty(player, new Box(feet.x - 0.25, feet.y - 1.6, feet.z - 0.25, feet.x + 0.25, feet.y, feet.z + 0.25));
    }

    /** The white smoke burst: a big one where you were hit, a smaller one where you come out. */
    private static void poof(ServerWorld sw, Vec3d c, boolean hit) {
        sw.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, hit ? 45 : 18, 0.35, 0.55, 0.35, hit ? 0.07 : 0.04);
        sw.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, hit ? 25 : 10, 0.3, 0.5, 0.3, hit ? 0.09 : 0.05);
        sw.spawnParticles(WHITE, c.x, c.y, c.z, hit ? 18 : 6, 0.45, 0.6, 0.45, 0);
        if (hit) {
            sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            sw.spawnParticles(SPLINTERS, c.x, c.y, c.z, 25, 0.3, 0.5, 0.3, 0.15);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.2f, 1.0f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.9f, 1.3f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 1.2f, 0.8f);
        } else {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.6f, 1.5f);
        }
    }

    private static void startCooldown(ServerPlayerEntity player) {
        int cooldown = Logs.TOME_OF_LOGS.getCooldown();
        ItemStack staff = StaffItem.findStaffWith(player, Logs.TOME_OF_LOGS);
        if (!staff.isEmpty()) StaffItem.applyCooldown(player.getWorld(), player, staff, Logs.TOME_OF_LOGS, cooldown);
        else player.getItemCooldownManager().set(Logs.TOME_OF_LOGS, cooldown);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Integer>> it = ARMED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            // Nobody hit you: the window closes quietly and the cooldown starts
            it.remove();
            startCooldown(p);
        }
        LOGS.removeIf(log -> !log.tick());
    }

    static void clear() {
        ARMED.clear();
        for (FallingLog log : LOGS) TempEntities.discard(log.display);
        LOGS.clear();
    }

    // ---------------------------------------------------------------------
    // The log
    // ---------------------------------------------------------------------

    /**
     * The log left behind: a block display about the size of a player. It pops up in the smoke, drops to the
     * ground with a thud, stands a moment, topples over away from whoever hit it, lies there, then puffs away.
     */
    private static final class FallingLog {
        final ServerWorld world;
        final DisplayEntity.BlockDisplayEntity display;
        final Vector3f axis; // what it topples around
        Vec3d pos;
        double vy = 0.16;
        int age, landedAt = -1;

        private FallingLog(ServerWorld world, DisplayEntity.BlockDisplayEntity display, Vec3d pos, Vec3d away) {
            this.world = world;
            this.display = display;
            this.pos = pos;
            // Turning round this axis tips the top over toward "away"
            this.axis = new Vector3f((float) away.z, 0f, (float) -away.x).normalize();
        }

        static void drop(ServerWorld sw, Vec3d feet, Vec3d away) {
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(sw);
            if (d == null) return;
            Vec3d start = feet.add(0, 0.2, 0);
            d.setBlockState(LOG);
            d.setTeleportDuration(2);
            d.refreshPositionAndAngles(start.x, start.y, start.z, 0f, 0f);
            d.setTransformation(shape(0f));
            TempEntities.track(d);
            sw.spawnEntity(d);
            LOGS.add(new FallingLog(sw, d, start, away));
        }

        /** Upright, or tipped {@code angle} radians round the bottom edge, resting on its side at the end. */
        private static AffineTransformation shape(float angle) {
            return shape(angle, new Vector3f(1, 0, 0));
        }

        private static AffineTransformation shape(float angle, Vector3f axis) {
            float t = angle / MathHelper.HALF_PI;
            return new AffineTransformation(new Matrix4f()
                    .translate(0f, LOG_WIDTH / 2 * t, 0f) // so it lies on top of the ground, not half in it
                    .rotate(angle, axis)
                    .scale(LOG_WIDTH, LOG_HEIGHT, LOG_WIDTH)
                    .translate(-0.5f, 0f, -0.5f));
        }

        /** False once it's gone. */
        boolean tick() {
            if (display.isRemoved()) return false;
            age++;

            if (landedAt < 0) {
                vy -= 0.06;
                Vec3d next = pos.add(0, vy, 0);
                BlockHitResult hit = vy < 0 ? world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, display)) : null;
                if (hit != null && hit.getType() != HitResult.Type.MISS) {
                    pos = hit.getPos();
                    landedAt = age;
                    world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_WOOD_FALL, SoundCategory.PLAYERS, 1.2f, 0.7f);
                    world.spawnParticles(SPLINTERS, pos.x, pos.y + 0.05, pos.z, 12, 0.25, 0.02, 0.25, 0.1);
                } else {
                    pos = next;
                    if (age > 80) return vanish(); // fell into the void or a very deep hole
                }
                display.setPosition(pos);
                return true;
            }

            int toppling = age - landedAt - STAND_TICKS;
            if (toppling > 0 && toppling <= TOPPLE_TICKS) {
                // Slow to start, then falls fast, like a real log going over
                float t = toppling / (float) TOPPLE_TICKS;
                display.setTransformation(shape(MathHelper.HALF_PI * t * t, axis));
                display.setStartInterpolation(0);
                display.setInterpolationDuration(1);
                if (toppling == TOPPLE_TICKS) {
                    world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.PLAYERS, 0.5f, 1.4f);
                    world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_WOOD_FALL, SoundCategory.PLAYERS, 1.2f, 0.6f);
                    Vec3d along = new Vec3d(-axis.z(), 0, axis.x()).multiply(LOG_HEIGHT / 2);
                    Vec3d mid = pos.add(along);
                    world.spawnParticles(SPLINTERS, mid.x, mid.y + 0.1, mid.z, 20, Math.abs(along.x) + 0.2, 0.05, Math.abs(along.z) + 0.2, 0.1);
                    world.spawnParticles(ParticleTypes.POOF, mid.x, mid.y + 0.1, mid.z, 6, Math.abs(along.x) + 0.2, 0.05, Math.abs(along.z) + 0.2, 0.02);
                }
            }
            if (toppling > TOPPLE_TICKS + LIE_TICKS) return vanish();
            return true;
        }

        private boolean vanish() {
            Vec3d along = landedAt >= 0 ? new Vec3d(-axis.z(), 0, axis.x()).multiply(LOG_HEIGHT / 2) : new Vec3d(0, LOG_HEIGHT / 2, 0);
            Vec3d c = pos.add(along).add(0, 0.3, 0);
            world.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 20, 0.5, 0.25, 0.5, 0.03);
            world.spawnParticles(WHITE, c.x, c.y, c.z, 8, 0.5, 0.25, 0.5, 0);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.5f, 1.8f);
            TempEntities.discard(display);
            return false;
        }
    }
}
