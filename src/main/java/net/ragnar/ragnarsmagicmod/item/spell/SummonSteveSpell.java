package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.entity.SteveEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Tome of Steve: calls down a squad of {@link #SQUAD} Steves where you're looking. A glowing pentagon traces itself
 * onto the ground with light rising from each corner, then five bolts of lightning strike its corners one after
 * another - harmless, all show - and each one leaves a Steve standing in it, geared for his role (see
 * {@link SteveEntity.Role}). They fight for you for 30 seconds and never touch you. Calling a new squad sends the old
 * one home.
 */
public class SummonSteveSpell implements Spell {
    public static final int SQUAD = 5;
    private static final double RANGE = 24.0;
    private static final double RING = 2.4;
    /** Ticks of ritual before the first bolt, then ticks between bolts. */
    private static final int RITUAL_TICKS = 24, BOLT_EVERY = 4;
    private static final DustParticleEffect GLYPH = new DustParticleEffect(new Vector3f(0.35f, 0.9f, 1f), 1.1f);
    private static final DustParticleEffect GLYPH_HOT = new DustParticleEffect(new Vector3f(0.85f, 1f, 1f), 1.4f);

    private static final List<Ritual> RITUALS = new ArrayList<>();
    private static boolean tickRegistered;

    private static final class Ritual {
        final ServerWorld world;
        final UUID owner;
        final Vec3d center;
        final Vec3d[] corners = new Vec3d[SQUAD];
        int age;

        Ritual(ServerWorld world, UUID owner, Vec3d center, float yaw) {
            this.world = world;
            this.owner = owner;
            this.center = center;
            for (int i = 0; i < SQUAD; i++) {
                double a = Math.toRadians(yaw) + Math.PI * 2 * i / SQUAD;
                corners[i] = standingSpot(world, center.add(-Math.sin(a) * RING, 0, Math.cos(a) * RING));
            }
        }
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        if (!tickRegistered) {
            ServerTickEvents.END_SERVER_TICK.register(server -> RITUALS.removeIf(r -> !tick(r)));
            tickRegistered = true;
        }
        BlockHitResult hit = world.raycast(new RaycastContext(player.getEyePos(),
                player.getEyePos().add(player.getRotationVec(1f).multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            player.sendMessage(Text.literal("No ground in sight.").formatted(Formatting.GRAY), true);
            return false;
        }
        Vec3d center = standingSpot(sw, hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(0.5)));

        // A new squad sends the old one home, and cancels a squad still on its way
        dismiss(sw, player.getUuid());
        RITUALS.removeIf(r -> r.owner.equals(player.getUuid()));

        RITUALS.add(new Ritual(sw, player.getUuid(), center, player.getYaw()));
        sw.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 1.2f, 0.7f);
        sw.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.0f, 0.6f);
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ITEM_TRIDENT_THUNDER.value(), SoundCategory.PLAYERS, 0.5f, 1.4f);
        return true;
    }

    /** Sends every Steve {@code owner} has out home. */
    public static void dismiss(ServerWorld world, UUID owner) {
        for (ServerWorld w : world.getServer().getWorlds()) {
            for (SteveEntity s : w.getEntitiesByType(ModEntities.STEVE, steve -> owner.equals(steve.getOwnerId()))) s.vanish();
        }
    }

    // ---------------------------------------------------------------------
    // The ritual
    // ---------------------------------------------------------------------

    /** One tick of a ritual. False once the whole squad is down. */
    private static boolean tick(Ritual r) {
        r.age++;
        ServerWorld w = r.world;
        float build = Math.min(1f, r.age / (float) RITUAL_TICKS);

        // The pentagon traces itself out, edge by edge, then burns brighter until the strikes
        if (r.age % 2 == 0) {
            for (int i = 0; i < SQUAD; i++) {
                Vec3d a = r.corners[i], b = r.corners[(i + 2) % SQUAD]; // a pentagram: every second corner
                int dots = 10;
                for (int d = 0; d <= dots * build; d++) {
                    Vec3d p = a.lerp(b, d / (double) dots);
                    w.spawnParticles(build >= 1 ? GLYPH_HOT : GLYPH, p.x, p.y + 0.1, p.z, 1, 0.02, 0, 0.02, 0);
                }
            }
            // A circle round it
            for (int i = 0; i < 24; i++) {
                double ang = Math.PI * 2 * i / 24 + r.age * 0.03;
                w.spawnParticles(GLYPH, r.center.x + Math.cos(ang) * (RING + 0.4), r.center.y + 0.1, r.center.z + Math.sin(ang) * (RING + 0.4), 1, 0, 0, 0, 0);
            }
        }
        // Light rising off every corner still waiting for its Steve
        for (int i = 0; i < SQUAD; i++) {
            if (r.age >= strikeAt(i)) continue;
            Vec3d c = r.corners[i];
            w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y + 0.2, c.z, 1, 0.12, 0.05, 0.12, 0.02 + 0.06 * build);
        }
        if (r.age % 8 == 0 && r.age < RITUAL_TICKS) {
            w.playSound(null, r.center.x, r.center.y, r.center.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.2f, 0.5f + build);
        }

        // The strikes, one corner after another
        for (int i = 0; i < SQUAD; i++) {
            if (r.age == strikeAt(i)) strike(r, i);
        }
        return r.age < strikeAt(SQUAD - 1);
    }

    private static int strikeAt(int i) {
        return RITUAL_TICKS + i * BOLT_EVERY;
    }

    /** Lightning on corner {@code i} - all light and noise, no fire, no damage - and a Steve standing in it. */
    private static void strike(Ritual r, int i) {
        ServerWorld w = r.world;
        Vec3d c = r.corners[i];
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
        if (bolt != null) {
            bolt.setCosmetic(true);
            bolt.refreshPositionAfterTeleport(c);
            w.spawnEntity(bolt);
        }

        PlayerEntity owner = w.getPlayerByUuid(r.owner);
        SteveEntity steve = ModEntities.STEVE.create(w);
        if (steve != null && owner != null) {
            float face = (float) Math.toDegrees(Math.atan2(c.x - r.center.x, r.center.z - c.z)); // facing out from the ring
            steve.refreshPositionAndAngles(c.x, c.y, c.z, face, 0f);
            steve.setHeadYaw(face);
            steve.setBodyYaw(face);
            steve.initialize(w, w.getLocalDifficulty(BlockPos.ofFloored(c)), SpawnReason.MOB_SUMMONED, null);
            steve.enlist(owner, SteveEntity.Role.values()[i]);
            w.spawnEntity(steve);
        }
        w.spawnParticles(ParticleTypes.FLASH, c.x, c.y + 1, c.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 0.1, c.z, 16, 0.4, 0.05, 0.4, 0.12);
        w.spawnParticles(GLYPH_HOT, c.x, c.y + 1, c.z, 20, 0.3, 0.8, 0.3, 0);
        w.playSound(null, c.x, c.y, c.z, i == 0 ? SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value() : SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(),
                SoundCategory.PLAYERS, 1.2f, 0.9f);
        ShakePayload.around(w, c, 8, 0.25f, 6);
        if (i == SQUAD - 1) {
            w.playSound(null, r.center.x, r.center.y, r.center.z, SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.7f, 1.3f);
            w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, r.center.x, r.center.y + 1, r.center.z, 40, RING * 0.5, 0.6, RING * 0.5, 0.4);
        }
    }

    /** The nearest spot at or just around {@code p} where a Steve can stand: on solid ground, with room for him. */
    private static Vec3d standingSpot(World world, Vec3d p) {
        BlockPos base = BlockPos.ofFloored(p);
        for (int dy = 0; dy <= 6; dy++) {
            for (int sign : new int[]{1, -1}) {
                BlockPos at = base.up(dy * sign);
                Box room = new Box(at.getX() + 0.2, at.getY(), at.getZ() + 0.2, at.getX() + 0.8, at.getY() + 1.8, at.getZ() + 0.8);
                if (world.isSpaceEmpty(room) && !world.getBlockState(at.down()).getCollisionShape(world, at.down()).isEmpty()) {
                    return new Vec3d(p.x, at.getY(), p.z);
                }
                if (dy == 0) break;
            }
        }
        return p;
    }
}
