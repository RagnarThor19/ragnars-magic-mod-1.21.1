package net.ragnar.ragnarsmagicmod.jaunting;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Jaunting. Right-click throws the kunai (this is what costs XP and starts the cooldown). With a kunai
 * out, right-click flashes you to it wherever it is - in a wall, in a mob, mid-flight (you keep its speed; mind the
 * fall), across the
 * world or in another dimension - and shift-right-click dispels it. Both of those are free and work during the
 * cooldown.
 */
public class JauntingSpell implements Spell {
    /** Mid-flight jaunts keep all of the kunai's speed. */
    private static final double CARRY = 1.0;
    private static final double EFFECT_RANGE = 96.0;

    /** Players whose cast this tick was a throw, so only that starts the cooldown. */
    private static final Set<UUID> THREW = new HashSet<>();

    @Nullable
    private static JauntingMarks.Mark mark(PlayerEntity player) {
        MinecraftServer server = player.getServer();
        return server == null ? null : JauntingMarks.get(server, player.getUuid());
    }

    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        // The client can't see marks; it just lets the click through and the server decides
        return player.getWorld().isClient || mark(player) != null;
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        if (player.getWorld().isClient) return 0;
        return mark(player) == null && !player.isSneaking() ? tomeCost : 0;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return THREW.remove(player.getUuid()) ? tomeCooldown : 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        JauntingMarks.Mark mark = JauntingMarks.get(sw.getServer(), player.getUuid());

        if (player.isSneaking()) {
            if (mark == null) {
                player.sendMessage(Text.literal("No kunai out.").formatted(Formatting.GRAY), true);
                return false;
            }
            dispel(sw.getServer(), sp, mark);
            return true;
        }
        if (mark != null) return jaunt(sw.getServer(), sp, mark);
        throwKunai(sw, sp);
        return true;
    }

    // ---------------------------------------------------------------------
    // Throw
    // ---------------------------------------------------------------------

    private static void throwKunai(ServerWorld sw, ServerPlayerEntity player) {
        JauntingKunaiEntity kunai = new JauntingKunaiEntity(sw, player);
        kunai.setVelocity(player, player.getPitch(), player.getYaw(), 0.0f, JauntingKunaiEntity.SPEED, 0.0f);
        JauntingMarks.set(sw.getServer(), player.getUuid(), new JauntingMarks.Mark(kunai.getUuid(), sw.getRegistryKey(), kunai.getPos()));
        sw.spawnEntity(kunai);
        THREW.add(player.getUuid());

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1.0f, 1.45f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.3f, 1.8f);
    }

    // ---------------------------------------------------------------------
    // Jaunt
    // ---------------------------------------------------------------------

    private static boolean jaunt(MinecraftServer server, ServerPlayerEntity player, JauntingMarks.Mark mark) {
        ServerWorld dest = server.getWorld(mark.world());
        if (dest == null) {
            JauntingMarks.remove(server, player.getUuid());
            player.sendMessage(Text.literal("Your kunai is out of reach.").formatted(Formatting.GRAY), true);
            return false;
        }

        Vec3d spot;
        Vec3d carry = Vec3d.ZERO;
        JauntingKunaiEntity kunai = dest.getEntity(mark.kunai()) instanceof JauntingKunaiEntity k && !k.isRemoved() ? k : null;
        if (kunai != null) {
            Entity host = kunai.host();
            if (host != null) {
                // Right into whatever it's stuck in
                spot = safeSpot(dest, player, host.getPos(), null);
            } else if (kunai.isFlying()) {
                // Snatched out of the air: you fly on with its speed, and you're falling from wherever it was
                double half = player.getDimensions(EntityPose.STANDING).height() / 2;
                spot = safeSpot(dest, player, kunai.getPos().subtract(0, half, 0), kunai.facing());
                carry = kunai.getVelocity().multiply(CARRY);
            } else {
                spot = landingSpot(dest, player, kunai.getPos(), kunai.facing());
            }
        } else {
            // Left behind in a chunk that isn't loaded: load it so there's ground to land on. The kunai itself
            // goes away when it loads in and finds its mark gone.
            dest.getChunk(BlockPos.ofFloored(mark.pos()));
            spot = landingSpot(dest, player, mark.pos(), null);
        }

        JauntingMarks.remove(server, player.getUuid());
        if (kunai != null) kunai.fizzle(dest);

        ServerWorld origin = player.getServerWorld();
        Vec3d from = player.getPos();
        burst(origin, from, player, false);

        Entity arrived = player.teleportTo(new TeleportTarget(dest, spot, carry, player.getYaw(), player.getPitch(), TeleportTarget.NO_OP));
        if (arrived == null) return false;
        arrived.setVelocity(carry);
        arrived.velocityModified = true;
        arrived.fallDistance = 0f;

        burst(dest, spot, player, true);
        boolean streak = origin == dest;
        Vec3d mid = from.add(0, 1, 0), end = spot.add(0, 1, 0);
        JauntPayload payload = new JauntPayload(mid.toVector3f(), end.toVector3f(), streak, false);
        for (ServerPlayerEntity p : dest.getPlayers()) {
            if (p == player) continue;
            if (p.getPos().distanceTo(spot) < EFFECT_RANGE || streak && p.getPos().distanceTo(from) < EFFECT_RANGE) {
                JauntPayload.send(p, payload);
            }
        }
        JauntPayload.send(player, new JauntPayload(mid.toVector3f(), end.toVector3f(), streak, true));
        return true;
    }

    /** Crack of lightning where the jaunter leaves and arrives. */
    private static void burst(ServerWorld w, Vec3d feet, PlayerEntity player, boolean arriving) {
        Vec3d c = feet.add(0, 1, 0);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, arriving ? 50 : 30, 0.35, 0.7, 0.35, 0.7);
        w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, arriving ? 12 : 6, 0.25, 0.5, 0.25, 0.08);
        if (arriving) {
            w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.9f, 1.5f);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 1.3f);
        } else {
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6f, 1.9f);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.35f, 1.7f);
        }
    }

    // ---------------------------------------------------------------------
    // Dispel
    // ---------------------------------------------------------------------

    private static void dispel(MinecraftServer server, ServerPlayerEntity player, JauntingMarks.Mark mark) {
        JauntingMarks.remove(server, player.getUuid());
        ServerWorld w = server.getWorld(mark.world());
        if (w != null && w.getEntity(mark.kunai()) instanceof JauntingKunaiEntity kunai) {
            Vec3d p = kunai.getPos();
            w.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.PLAYERS, 0.6f, 1.6f);
            kunai.fizzle(w);
        }
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.PLAYERS, 0.4f, 1.9f);
        player.sendMessage(Text.literal("Kunai dispelled.").formatted(Formatting.GRAY), true);
    }

    // ---------------------------------------------------------------------
    // Where to put the jaunter
    // ---------------------------------------------------------------------

    /**
     * Next to a kunai lying stuck in something: backed out along the way it flew, at about the height where it
     * would be in your hand.
     */
    private static Vec3d landingSpot(ServerWorld w, PlayerEntity player, Vec3d at, @Nullable Vec3d facing) {
        return safeSpot(w, player, at.subtract(0, 0.9, 0), facing);
    }

    /**
     * The closest place to {@code feet} the player fits, trying a little back along {@code facing} (the way the
     * kunai was heading) first and then up and down. Falls back to the first gap above.
     */
    static Vec3d safeSpot(ServerWorld w, PlayerEntity player, Vec3d feet, @Nullable Vec3d facing) {
        EntityDimensions dims = player.getDimensions(EntityPose.STANDING);
        double[] backs = facing == null ? new double[]{0, 0.4, 0.8} : new double[]{0, 0.35, 0.7, 1.1, 1.6};
        double[] ups = {0, 0.5, -0.5, 0.9, -0.9, 1.4, -1.4, 1.9};
        for (double back : backs) {
            for (double up : ups) {
                Vec3d base = facing == null ? feet : feet.subtract(facing.multiply(back));
                Vec3d at = base.add(0, up, 0);
                if (facing == null && back > 0) {
                    // No direction to back out along: try around it instead
                    for (int i = 0; i < 4; i++) {
                        double a = i * Math.PI / 2;
                        Vec3d side = at.add(Math.cos(a) * back, 0, Math.sin(a) * back);
                        if (fits(w, player, dims, side)) return side;
                    }
                } else if (fits(w, player, dims, at)) {
                    return at;
                }
            }
        }
        for (int up = 1; up <= 12; up++) {
            Vec3d at = new Vec3d(feet.x, Math.floor(feet.y) + up, feet.z);
            if (fits(w, player, dims, at)) return at;
        }
        return feet;
    }

    private static boolean fits(ServerWorld w, PlayerEntity player, EntityDimensions dims, Vec3d feet) {
        return w.isSpaceEmpty(player, dims.getBoxAt(feet).contract(1.0E-6));
    }
}
