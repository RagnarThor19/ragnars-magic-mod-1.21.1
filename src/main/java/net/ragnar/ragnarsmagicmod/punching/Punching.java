package net.ragnar.ragnarsmagicmod.punching;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
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
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Punching: the caster cocks a fist back and dashes up to {@link #DASH_DISTANCE} blocks along where they're
 * looking - stopping short at the first thing in the way, or at a wall - and throws one catastrophic punch. Whatever
 * the fist lands on takes {@link #PUNCH_DAMAGE} and is sent flying; everything else near the impact is caught in the
 * shockwave for less ({@link #AREA_DAMAGE} at most) and knocked away from it. It never breaks a block, and never hurts
 * the caster, their pets or their teammates.
 * <p>
 * The server drives the dash and the hit; PunchingClient draws the wind-up and punch on the caster's body and the
 * shockwave rings. To remove it: delete this package, {@code Punching.register()} in RagnarsMagicMod,
 * {@code PunchingClient.init()} in RagnarsMagicModClient, its line in MightyPushPoseMixin, {@code PUNCHING} in SpellId,
 * {@code models/item/tome_of_punching.json} and its en_us.json line.
 */
public final class Punching {
    private Punching() {}

    public static final int XP_COST = 8;
    public static final int COOLDOWN = 10 * 20;

    public static final double DASH_DISTANCE = 7.0;
    /** Blocks per tick: 7 blocks in about 5 ticks. */
    private static final double DASH_SPEED = 1.5;
    private static final int MAX_DASH_TICKS = 9;

    public static final float PUNCH_DAMAGE = 14f;
    /** How hard a direct hit throws, along the punch, and up. */
    public static final double PUNCH_FLING = 3.4, PUNCH_LIFT = 0.85;
    public static final float AREA_DAMAGE = 7f, AREA_DAMAGE_EDGE = 2f;
    public static final double AREA_RADIUS = 5.0;
    /** How far in front of the caster the fist lands. */
    private static final double REACH = 1.3;

    public static final TomeItem TOME_OF_PUNCHING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_punching"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.PUNCHING, XP_COST
            ).setCooldown(COOLDOWN)
    );

    /** Server -> clients: {@code casterId} has started a dash (cock the fist back). */
    public record DashPayload(int casterId) implements CustomPayload {
        public static final Id<DashPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "punch_dash"));
        public static final PacketCodec<RegistryByteBuf, DashPayload> CODEC = PacketCodec.tuple(PacketCodecs.VAR_INT, DashPayload::casterId, DashPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> clients: the punch landed at {@code x, y, z}, thrown along {@code dirX, dirZ}. */
    public record ImpactPayload(int casterId, double x, double y, double z, float dirX, float dirZ, boolean direct) implements CustomPayload {
        public static final Id<ImpactPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "punch_impact"));
        public static final PacketCodec<RegistryByteBuf, ImpactPayload> CODEC = PacketCodec.of(
                (p, buf) -> {
                    buf.writeVarInt(p.casterId);
                    buf.writeDouble(p.x);
                    buf.writeDouble(p.y);
                    buf.writeDouble(p.z);
                    buf.writeFloat(p.dirX);
                    buf.writeFloat(p.dirZ);
                    buf.writeBoolean(p.direct);
                },
                buf -> new ImpactPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** A dash under way. */
    private static final class Dash {
        final Vec3d start, dir;
        final double distance;
        int ticks;
        /** The speed sent last tick: the client moves by it before the server sees it, so it's already on its way. */
        double inFlight;

        Dash(Vec3d start, Vec3d dir, double distance) {
            this.start = start;
            this.dir = dir;
            this.distance = distance;
        }
    }

    private static final Map<UUID, Dash> DASHES = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.PUNCHING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_PUNCHING);
        Spells.register(SpellId.PUNCHING, new PunchingSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Crows.TOME_OF_CROWS, TOME_OF_PUNCHING));
        PayloadTypeRegistry.playS2C().register(DashPayload.ID, DashPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ImpactPayload.ID, ImpactPayload.CODEC);
        ServerTickEvents.END_SERVER_TICK.register(Punching::tick);
    }

    // ---------------------------------------------------------------------
    // The dash
    // ---------------------------------------------------------------------

    /** Starts {@code player}'s dash along where they're looking (level), or punches at once if something's right there. */
    static void start(ServerPlayerEntity player) {
        ServerWorld sw = player.getServerWorld();
        Vec3d dir = Vec3d.fromPolar(0f, player.getYaw());
        double distance = dashDistance(sw, player, dir);
        broadcast(sw, player.getPos(), new DashPayload(player.getId()));
        Vec3d at = player.getPos();
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_CAMEL_DASH, SoundCategory.PLAYERS, 1.4f, 0.75f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_CAMEL_DASH, SoundCategory.PLAYERS, 0.9f, 1.3f);
        sw.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.1, at.z, 8, 0.3, 0.02, 0.3, 0.05);
        if (distance < 0.4) {
            impact(player, dir);
            return;
        }
        DASHES.put(player.getUuid(), new Dash(at, dir, distance));
    }

    /**
     * How far {@code player} dashes along {@code dir}: up to {@link #DASH_DISTANCE}, stopping a fist's reach short of the
     * first thing standing in the way, or of a wall. Public so tests can check it.
     */
    public static double dashDistance(ServerWorld sw, PlayerEntity player, Vec3d dir) {
        double distance = DASH_DISTANCE;
        // Walls, at knee and chest height
        for (double h : new double[]{0.5, 1.3}) {
            Vec3d from = player.getPos().add(0, h, 0);
            BlockHitResult hit = sw.raycast(new RaycastContext(from, from.add(dir.multiply(DASH_DISTANCE + 0.4)),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() != HitResult.Type.MISS) distance = Math.min(distance, hit.getPos().distanceTo(from) - player.getWidth() / 2 - 0.15);
        }
        // The nearest thing in the way
        LivingEntity target = firstInPath(sw, player, dir, DASH_DISTANCE + REACH + 1.0);
        if (target != null) {
            Vec3d to = target.getPos().subtract(player.getPos());
            double along = to.x * dir.x + to.z * dir.z;
            distance = Math.min(distance, along - target.getWidth() / 2 - player.getWidth() / 2 - 0.6);
        }
        return Math.max(0.0, distance);
    }

    @Nullable
    private static LivingEntity firstInPath(ServerWorld sw, PlayerEntity player, Vec3d dir, double range) {
        Vec3d p = player.getPos();
        Box area = player.getBoundingBox().stretch(dir.multiply(range)).expand(1.5, 1.0, 1.5);
        LivingEntity best = null;
        double bestAlong = Double.MAX_VALUE;
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, area, e -> canHit(e, player))) {
            Vec3d to = e.getPos().subtract(p);
            double along = to.x * dir.x + to.z * dir.z;
            double side = Math.abs(to.x * dir.z - to.z * dir.x);
            if (along <= 0 || along > range || side > e.getWidth() / 2 + 0.9 || Math.abs(to.y) > 2.5) continue;
            if (along < bestAlong) {
                bestAlong = along;
                best = e;
            }
        }
        return best;
    }

    private static void tick(MinecraftServer server) {
        if (DASHES.isEmpty()) return;
        List<Runnable> punches = new ArrayList<>();
        DASHES.entrySet().removeIf(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || p.isSpectator()) return true;
            Dash d = e.getValue();
            d.ticks++;
            Vec3d moved = p.getPos().subtract(d.start);
            double done = moved.x * d.dir.x + moved.z * d.dir.z;
            double left = d.distance - done - d.inFlight;
            Box front = p.getBoundingBox().stretch(d.dir.multiply(REACH + 0.3)).expand(0.3, 0.1, 0.3);
            boolean contact = !p.getServerWorld().getEntitiesByClass(LivingEntity.class, front, x -> canHit(x, p)).isEmpty();
            if (contact || left <= 0.15 || d.ticks >= MAX_DASH_TICKS || (d.ticks > 2 && p.horizontalCollision)) {
                punches.add(() -> impact(p, d.dir));
                return true;
            }
            double speed = Math.min(DASH_SPEED, left);
            d.inFlight = speed;
            p.setVelocity(d.dir.x * speed, 0.0, d.dir.z * speed);
            p.velocityModified = true;
            p.fallDistance = 0f;
            // A wake of air behind
            Vec3d at = p.getPos();
            p.getServerWorld().spawnParticles(ParticleTypes.CLOUD, at.x - d.dir.x * 0.5, at.y + 0.9, at.z - d.dir.z * 0.5, 2, 0.2, 0.4, 0.2, 0.01);
            return false;
        });
        punches.forEach(Runnable::run);
    }

    // ---------------------------------------------------------------------
    // The punch
    // ---------------------------------------------------------------------

    /** The punch itself, thrown by {@code player} along {@code dir}. Public so tests can throw one. */
    public static void impact(ServerPlayerEntity player, Vec3d dir) {
        ServerWorld sw = player.getServerWorld();
        Vec3d fist = player.getPos().add(0, player.getHeight() * 0.62, 0).add(dir.multiply(REACH));

        // Whatever the fist lands on
        Box knuckles = new Box(fist, fist).expand(1.0, 1.0, 1.0).stretch(dir.multiply(0.8));
        List<LivingEntity> direct = sw.getEntitiesByClass(LivingEntity.class, knuckles, e -> canHit(e, player));
        for (LivingEntity e : direct) {
            hurt(sw, e, PUNCH_DAMAGE, player);
            fling(e, dir.multiply(PUNCH_FLING).add(0, PUNCH_LIFT, 0), 0.5);
            Vec3d c = e.getBoundingBox().getCenter();
            sw.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, c.x, c.y, c.z, 10, 0.3, 0.4, 0.3, 0.2);
            sw.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 30, 0.3, 0.5, 0.3, 0.7);
        }

        // The shockwave
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(fist, fist).expand(AREA_RADIUS + 1), e -> canHit(e, player))) {
            if (direct.contains(e)) continue;
            Vec3d c = e.getBoundingBox().getCenter();
            double d = c.distanceTo(fist);
            if (d > AREA_RADIUS) continue;
            float k = (float) (1.0 - d / AREA_RADIUS);
            hurt(sw, e, MathHelper.lerp(k, AREA_DAMAGE_EDGE, AREA_DAMAGE), player);
            Vec3d out = c.subtract(fist).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? dir : out.normalize();
            // Pushed away from the blow, and a little along it
            fling(e, out.multiply(0.7 + 1.5 * k).add(dir.multiply(0.4 * k)).add(0, 0.35 + 0.45 * k, 0), 0.6);
        }

        // The caster stops dead, rocked back a little by it
        player.setVelocity(-dir.x * 0.3, 0.12, -dir.z * 0.3);
        player.velocityModified = true;
        player.fallDistance = 0f;
        player.swingHand(Hand.MAIN_HAND, true);

        effects(sw, player, fist, dir, !direct.isEmpty());
        broadcast(sw, fist, new ImpactPayload(player.getId(), fist.x, fist.y, fist.z, (float) dir.x, (float) dir.z, !direct.isEmpty()));
    }

    private static void effects(ServerWorld sw, ServerPlayerEntity player, Vec3d fist, Vec3d dir, boolean direct) {
        // The blow: a white burst, a cone of air blasted out along the punch, sparks and swept air
        sw.spawnParticles(ParticleTypes.FLASH, fist.x, fist.y, fist.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.EXPLOSION, fist.x + dir.x * 0.8, fist.y, fist.z + dir.z * 0.8, 1, 0, 0, 0, 0);
        for (int i = 0; i < 28; i++) {
            double sx = (sw.random.nextDouble() - 0.5) * 0.6, sy = (sw.random.nextDouble() - 0.5) * 0.6, sz = (sw.random.nextDouble() - 0.5) * 0.6;
            sw.spawnParticles(ParticleTypes.CLOUD, fist.x, fist.y, fist.z, 0, dir.x + sx, sy + 0.05, dir.z + sz, 0.9);
        }
        sw.spawnParticles(ParticleTypes.SWEEP_ATTACK, fist.x + dir.x * 0.6, fist.y, fist.z + dir.z * 0.6, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.CRIT, fist.x, fist.y, fist.z, 40, 0.4, 0.4, 0.4, 0.9);

        // The ground under it heaves: a ring of dust thrown up, and grit everywhere
        BlockPos below = groundBelow(sw, fist);
        if (below != null) {
            BlockState ground = sw.getBlockState(below);
            double gy = below.getY() + 1.0;
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                double cos = Math.cos(a), sin = Math.sin(a);
                sw.spawnParticles(ParticleTypes.POOF, fist.x + cos, gy + 0.1, fist.z + sin, 0, cos, 0.03, sin, 0.5);
                if (i % 2 == 0) sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, ground),
                        fist.x + cos * 2.8, gy, fist.z + sin * 2.8, 1, 0.2, 0, 0.2, 0);
            }
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), fist.x, gy + 0.2, fist.z, 70, 1.6, 0.2, 1.6, 0.25);
        }
        // Punched a wall: it showers grit
        BlockPos wall = BlockPos.ofFloored(fist.add(dir.multiply(0.9)));
        BlockState wallState = sw.getBlockState(wall);
        if (!wallState.isAir() && !wallState.getCollisionShape(sw, wall).isEmpty()) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wallState), fist.x + dir.x * 0.5, fist.y, fist.z + dir.z * 0.5, 60, 0.3, 0.5, 0.3, 0.3);
            sw.playSound(null, fist.x, fist.y, fist.z, wallState.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, 1.6f, 0.5f);
        }

        // Sounds nothing else in the mod makes: a deep crunch of metal and stone, a crack, and a brutal smack if it connects
        sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.BLOCK_HEAVY_CORE_BREAK, SoundCategory.PLAYERS, 2.2f, 0.5f);
        sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.BLOCK_NETHERITE_BLOCK_BREAK, SoundCategory.PLAYERS, 2.0f, 0.55f);
        sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.BLOCK_TRIAL_SPAWNER_BREAK, SoundCategory.PLAYERS, 1.6f, 0.6f);
        sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.BLOCK_VAULT_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        if (direct) {
            sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.ENTITY_ZOGLIN_ATTACK, SoundCategory.PLAYERS, 1.8f, 0.7f);
            sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.ITEM_WOLF_ARMOR_BREAK, SoundCategory.PLAYERS, 1.6f, 0.6f);
            sw.playSound(null, fist.x, fist.y, fist.z, SoundEvents.ENTITY_HOGLIN_ATTACK, SoundCategory.PLAYERS, 1.4f, 0.5f);
        }
        ShakePayload.around(sw, fist, 14, direct ? 0.9f : 0.6f, 14);
    }

    @Nullable
    private static BlockPos groundBelow(ServerWorld sw, Vec3d from) {
        BlockPos.Mutable p = BlockPos.ofFloored(from).mutableCopy();
        for (int i = 0; i < 5; i++, p.move(0, -1, 0)) {
            if (!sw.getBlockState(p).getCollisionShape(sw, p).isEmpty()) return p.toImmutable();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void hurt(ServerWorld sw, LivingEntity e, float amount, PlayerEntity player) {
        e.timeUntilRegen = 0;
        e.damage(sw.getDamageSources().playerAttack(player), amount);
    }

    /** Throws {@code e} by {@code push}; knockback resistance only takes off up to {@code resistShare} of it. */
    private static void fling(LivingEntity e, Vec3d push, double resistShare) {
        double resist = MathHelper.clamp(e.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), 0, 1);
        e.setVelocity(push.multiply(1.0 - resistShare * resist));
        e.velocityModified = true;
        e.velocityDirty = true;
    }

    static boolean canHit(Entity e, PlayerEntity player) {
        if (!(e instanceof LivingEntity le) || e == player || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof PlayerEntity p && p.isCreative()) return false;
        if (e instanceof IllusionEntity) return false;
        if (e instanceof TameableEntity pet && player.getUuid().equals(pet.getOwnerUuid())) return false;
        return !e.isTeammate(player);
    }

    private static void broadcast(ServerWorld sw, Vec3d at, CustomPayload payload) {
        for (ServerPlayerEntity p : sw.getPlayers()) {
            if (p.squaredDistanceTo(at) < 96 * 96 && ServerPlayNetworking.canSend(p, payload.getId())) ServerPlayNetworking.send(p, payload);
        }
    }

    /** True while {@code player} is mid-dash (server). */
    public static boolean dashing(PlayerEntity player) {
        return DASHES.containsKey(player.getUuid());
    }
}
