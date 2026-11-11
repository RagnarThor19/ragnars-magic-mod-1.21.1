package net.ragnar.ragnarsmagicmod.groundpound;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Ground Pound: a passive tome. While it's in your inventory (or on a staff you carry), tap crouch in mid-air
 * at least {@link #MIN_HEIGHT} blocks up: you hang for a heartbeat, then slam straight down. The fall hurts half as much,
 * and if you live through the landing the ground bursts: every monster within reach (and anything else that's after
 * you) is hurt and flung outward, more the further you fell. Die from the fall and there's no pound, just the fall.
 * <p>
 * Crouching on the ground (sneaking, Leaping's wind-up) and on a wall (Climbing's hold) never pounds; holding crouch
 * from a leap doesn't either, it takes a fresh tap in the air. Crouching while gliding drops you out of the glide into
 * a slam. Villagers, animals, pets and other players are never caught in it.
 * <p>
 * The slam whooshes like a swung mace and the landing is the mace's own ground smash (the heavy one from high up), with
 * a ring of dust pillars of whatever you landed on and a jolt of the camera. The client moves the player (see
 * GroundPoundClient); the server halves the fall (GroundPoundFallMixin) and does the burst. To remove it: delete this
 * package, that mixin (and its line in ragnarsmagicmod.mixins.json), {@code GroundPound.register()} in RagnarsMagicMod,
 * {@code GroundPoundClient.init()} in RagnarsMagicModClient, {@code GroundPoundClient.active()} in ClimbingClient,
 * GlidingClient and JumpingClient, {@code GROUND_POUND} in SpellId, it in PassiveTomes.register,
 * {@code models/item/tome_of_ground_pound.json} and its en_us.json line.
 */
public final class GroundPound {
    private GroundPound() {}

    /** You have to be at least this high off the ground to pound, so a crouch during an ordinary jump never does. */
    public static final double MIN_HEIGHT = 2.5;
    /** How much of the fall damage a pound takes off. */
    public static final float FALL_REDUCTION = 0.5f;
    /** A fall this long (or more) gives the biggest burst. */
    public static final double FULL_DROP = 20.0;
    /** The burst's reach and damage, from the smallest pound to the biggest. */
    public static final double MIN_RADIUS = 2.5, MAX_RADIUS = 6.0;
    public static final float MIN_DAMAGE = 3f, MAX_DAMAGE = 12f;
    /** A pound that never lands anywhere (it shouldn't happen) is forgotten after this long. */
    private static final int FORGET_AFTER = 20 * 15;

    public static final TomeItem TOME_OF_GROUND_POUND = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_ground_pound"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.GROUND_POUND, 0
            ).setCooldown(0)
    );

    /** Client -> server: {@link #START}ed a pound, {@link #LANDED} it, or it was {@link #CANCELLED} (landed in water...). */
    public record PoundPayload(int phase) implements CustomPayload {
        public static final Id<PoundPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "ground_pound"));
        public static final PacketCodec<RegistryByteBuf, PoundPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, PoundPayload::phase, PoundPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static final int START = 0, LANDED = 1, CANCELLED = 2;

    /** Players mid-pound (server side): the height they started from, and the server tick they started on. */
    private static final Map<UUID, double[]> POUNDING = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.GROUND_POUND, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_GROUND_POUND);
        Spells.register(SpellId.GROUND_POUND, new PassiveSpell("tap crouch in mid-air to slam down: half the fall damage, and the ground bursts around you."));
        PayloadTypeRegistry.playC2S().register(PoundPayload.ID, PoundPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PoundPayload.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            switch (payload.phase()) {
                case START -> start(p);
                case LANDED -> land(p);
                default -> POUNDING.remove(p.getUuid());
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(GroundPound::tick);
    }

    /** Server: whether {@code player} is mid-pound (their fall is halved). */
    public static boolean isPounding(PlayerEntity player) {
        return POUNDING.containsKey(player.getUuid());
    }

    /** Server: {@code player} has started a pound from where they are now. Public so tests can call it. */
    public static void start(ServerPlayerEntity player) {
        if (!player.isAlive() || player.isSpectator() || !PassiveTomes.has(player, TOME_OF_GROUND_POUND)) return;
        POUNDING.put(player.getUuid(), new double[]{player.getY(), player.getServer().getTicks()});
        // Everyone else hears the slam begin; the pounder hears it on their side
        player.getServerWorld().playSound(player, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_MACE_SMASH_AIR,
                SoundCategory.PLAYERS, 0.8f, 0.8f);
    }

    /**
     * Server: {@code player} has landed their pound. Their (halved) fall has already been dealt with by now, so if it
     * killed them there's nothing more; otherwise the ground bursts. Public so tests can call it.
     */
    public static void land(ServerPlayerEntity player) {
        double[] pound = POUNDING.remove(player.getUuid());
        if (pound == null || !player.isAlive()) return;
        double drop = pound[0] - player.getY();
        if (drop < 1.0) return;
        burst(player, drop);
    }

    private static void tick(MinecraftServer server) {
        if (POUNDING.isEmpty()) return;
        int now = server.getTicks();
        POUNDING.entrySet().removeIf(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            return p == null || !p.isAlive() || p.isTouchingWater() || p.hasVehicle() || now - e.getValue()[1] > FORGET_AFTER;
        });
    }

    /** How strong a pound from {@code drop} blocks up is, from 0 to 1. */
    public static float strength(double drop) {
        return (float) MathHelper.clamp((drop - MIN_HEIGHT) / (FULL_DROP - MIN_HEIGHT), 0.0, 1.0);
    }

    /** The ground bursts around {@code player}, who has just pounded down from {@code drop} blocks up. */
    private static void burst(ServerPlayerEntity player, double drop) {
        ServerWorld sw = player.getServerWorld();
        float k = strength(drop);
        double radius = MathHelper.lerp(k, MIN_RADIUS, MAX_RADIUS);
        float damage = MathHelper.lerp(k, MIN_DAMAGE, MAX_DAMAGE);
        Vec3d at = player.getPos();

        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, player.getBoundingBox().expand(radius, 2.0, radius),
                e -> e != player && e.isAlive() && caught(e, player))) {
            double dist = Math.sqrt(e.squaredDistanceTo(at.x, e.getY(), at.z));
            if (dist > radius) continue;
            float near = (float) (1.0 - 0.5 * dist / radius); // full at the centre, half at the edge
            e.damage(player.getDamageSources().playerAttack(player), damage * near);
            Vec3d out = new Vec3d(e.getX() - at.x, 0, e.getZ() - at.z);
            if (out.lengthSquared() < 1.0e-4) out = new Vec3d(1, 0, 0);
            out = out.normalize();
            e.takeKnockback((0.5 + 0.8 * k) * near, -out.x, -out.z);
            e.setVelocity(e.getVelocity().add(0, (0.25 + 0.3 * k) * near, 0));
            e.velocityModified = true;
        }
        effects(sw, player, k, radius);
    }

    /** Monsters, and anything else that's after you. Never villagers, animals, your pets, other players or armour stands. */
    private static boolean caught(LivingEntity e, ServerPlayerEntity player) {
        if (e instanceof PlayerEntity || e instanceof ArmorStandEntity) return false;
        if (e instanceof net.minecraft.entity.passive.TameableEntity t && t.isTamed()) return false;
        return e instanceof Monster || e instanceof MobEntity m && m.getTarget() == player;
    }

    /** A ring of dust pillars of the ground you hit, a puff of air, and the mace's smash. */
    private static void effects(ServerWorld sw, ServerPlayerEntity player, float k, double radius) {
        BlockPos below = player.getBlockPos().down();
        BlockState ground = sw.getBlockState(below);
        if (ground.isAir()) ground = sw.getBlockState(below.down());
        double x = player.getX(), y = player.getY(), z = player.getZ();
        if (!ground.isAir()) {
            BlockStateParticleEffect dust = new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, ground);
            int n = (int) (16 + 16 * k);
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                double r = 0.6 + (radius - 0.6) * 0.35;
                sw.spawnParticles(dust, x + Math.cos(a) * r, y + 0.1, z + Math.sin(a) * r, 0, Math.cos(a), 0.6 + 0.6 * k, Math.sin(a), 0.4);
            }
        }
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI * 2 / 10;
            sw.spawnParticles(ParticleTypes.CLOUD, x, y + 0.1, z, 0, Math.cos(a), 0.02, Math.sin(a), 0.25 + 0.2 * k);
        }
        sw.playSound(null, x, y, z, k > 0.5f ? SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY : SoundEvents.ITEM_MACE_SMASH_GROUND,
                SoundCategory.PLAYERS, 0.8f + 0.4f * k, 1.0f);
        if (!ground.isAir()) {
            sw.playSound(null, x, y, z, ground.getSoundGroup().getFallSound(), SoundCategory.PLAYERS, 1.0f, 0.8f);
        }
    }
}
