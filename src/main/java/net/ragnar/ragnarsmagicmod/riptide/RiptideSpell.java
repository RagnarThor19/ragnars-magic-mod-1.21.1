package net.ragnar.ragnarsmagicmod.riptide;

import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.util.FallGuard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Riptide: does exactly what a Riptide III trident does when you let it go, on both sides as vanilla does (the
 * caster's client moves them, so the launch is instant). The server adds the water: a ring bursting out at the start,
 * spray trailing the spin, a splash where you come down, and keeps the fall from hurting until you land.
 */
public class RiptideSpell implements Spell {
    /** Riptide III's push, in blocks a tick. */
    public static final double STRENGTH = 3.0;
    /** How long the spin lasts, and what it does to whatever you crash into (both vanilla's). */
    public static final int SPIN_TICKS = 20;
    public static final float HIT_DAMAGE = 8f;
    /** The fall is forgiven until you land, or this long at most. */
    private static final int GUARD_TICKS = 20 * 10;
    private static final Identifier SAFE_FALL = Identifier.of(RagnarsMagicMod.MOD_ID, "riptide_safe_fall");

    /** Casters still in the air from a launch, and for how long. */
    private static final Map<UUID, int[]> FLYING = new HashMap<>();

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        Vec3d dir = player.getRotationVec(1f).normalize();
        player.addVelocity(dir.multiply(STRENGTH));
        player.useRiptide(SPIN_TICKS, HIT_DAMAGE, staff);
        if (player.isOnGround()) player.move(MovementType.SELF, new Vec3d(0, 1.2, 0)); // off the ground, as vanilla does
        if (!(world instanceof ServerWorld sw)) return true;

        FallGuard.hold(player, SAFE_FALL);
        FLYING.put(player.getUuid(), new int[1]);

        Vec3d c = player.getPos().add(0, player.getHeight() * 0.5, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), SoundCategory.PLAYERS, 1.0f, 1.0f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.AMBIENT_UNDERWATER_ENTER, SoundCategory.PLAYERS, 0.6f, 1.6f);
        ring(sw, c, dir, 1.1, 24);
        sw.spawnParticles(ParticleTypes.FALLING_WATER, c.x, c.y, c.z, 30, 0.7, 0.5, 0.7, 0);
        return true;
    }

    /** A ring of spray round {@code c}, square on to {@code dir}. */
    private static void ring(ServerWorld sw, Vec3d c, Vec3d dir, double radius, int points) {
        Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0));
        side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
        Vec3d up = side.crossProduct(dir).normalize();
        for (int i = 0; i < points; i++) {
            double a = i * MathHelper.TAU / points;
            Vec3d p = c.add(side.multiply(Math.cos(a) * radius)).add(up.multiply(Math.sin(a) * radius));
            sw.spawnParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, 3, 0.05, 0.05, 0.05, 0.1);
            sw.spawnParticles(ParticleTypes.BUBBLE_POP, p.x, p.y, p.z, 1, 0, 0, 0, 0.02);
        }
    }

    /** Spray behind everyone mid-launch; lifts their fall protection once they're down. */
    static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, int[]>> it = FLYING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, int[]> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            int age = ++e.getValue()[0];
            ServerWorld sw = p.getServerWorld();
            Vec3d c = p.getPos().add(0, p.getHeight() * 0.5, 0);
            if (p.isUsingRiptide()) {
                sw.spawnParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 6, 0.35, 0.35, 0.35, 0.05);
                sw.spawnParticles(ParticleTypes.FALLING_WATER, c.x, c.y, c.z, 2, 0.3, 0.3, 0.3, 0);
                if (p.isTouchingWater()) sw.spawnParticles(ParticleTypes.BUBBLE, c.x, c.y, c.z, 4, 0.3, 0.3, 0.3, 0.1);
                continue;
            }
            boolean down = age > 3 && (p.isOnGround() || p.isTouchingWater() || p.isClimbing() || p.getAbilities().flying);
            if (down || age > GUARD_TICKS || p.isDead()) {
                if (down && p.isOnGround()) {
                    sw.spawnParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.1, p.getZ(), 30, 0.6, 0.05, 0.6, 0.2);
                }
                FallGuard.release(p, SAFE_FALL);
                it.remove();
            }
        }
    }

    static void drop(ServerPlayerEntity player) {
        if (FLYING.remove(player.getUuid()) != null) FallGuard.release(player, SAFE_FALL);
    }

    static void clear() {
        FLYING.clear();
    }
}
