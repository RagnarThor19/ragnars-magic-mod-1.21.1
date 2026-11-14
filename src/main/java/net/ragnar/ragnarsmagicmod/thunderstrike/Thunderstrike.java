package net.ragnar.ragnarsmagicmod.thunderstrike;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Thunderstrike (the master tome that was the Tome of Lightning Cascade; same item). Calls down one colossal
 * bolt of lightning on the spot you aim at, and the lightning doesn't stop there.
 * <ol>
 *   <li><b>Warning</b> ({@link #WARN_TICKS}, 2.5 s): a dark blocky storm cloud boils up overhead with lightning
 *       flickering inside it, the ground under it crackles with static (and so does anything standing there), thunder
 *       rolls, and stepped leaders creep down out of the cloud while streamers reach up to meet them - then a rising
 *       electric whine.</li>
 *   <li><b>Strike</b>: a giant branching bolt with flickering return strokes, a sky-wide flash and thunder that
 *       arrives later the further off you are. Everything within {@link #STRIKE_RADIUS} takes {@link #CENTER_DAMAGE}
 *       at the centre down to {@link #EDGE_DAMAGE} at the edge, and catches fire; creepers come out charged.</li>
 *   <li><b>Chain lightning</b>: {@link #CHAINS} arcs leap from the strike to the nearest creatures and ricochet on from
 *       one to the next, up to {@link #JUMPS} times each, weaker each jump.</li>
 * </ol>
 * The server runs the timeline and the damage; every client draws it (ThunderstrikeClient) from the three payloads.
 * Nothing in the world is set alight or changed.
 */
public final class Thunderstrike {
    private Thunderstrike() {}

    public static final int XP_COST = 45;
    public static final int COOLDOWN = 20 * 20;

    public static final double RANGE = 96;
    public static final int WARN_TICKS = 50;
    /** How high over the strike the storm cloud's underside hangs. */
    public static final double CLOUD_HEIGHT = 22;
    public static final double STRIKE_RADIUS = 5.5;
    public static final float CENTER_DAMAGE = 40f, EDGE_DAMAGE = 16f;
    public static final int CHAINS = 3, JUMPS = 5, JUMP_TICKS = 3;
    public static final double CHAIN_START_RANGE = 12, JUMP_RANGE = 7;
    public static final float ARC_DAMAGE = 14f, ARC_DECAY = 0.85f;
    /** Ticks the storm hangs about after the strike (for the clients' cloud to fade, and the echoes). */
    static final int AFTER_TICKS = 70;
    private static final double VIEW_RANGE = 400;

    // ---------------------------------------------------------------------
    // Payloads
    // ---------------------------------------------------------------------

    private static void writeVec(RegistryByteBuf buf, Vec3d v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3d readVec(RegistryByteBuf buf) {
        return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    /** Server -> clients: a storm is gathering over {@code ground}; it strikes in {@code ticks}. */
    public record Warn(Vec3d ground, long seed, int ticks) implements CustomPayload {
        public static final Id<Warn> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "thunder_warn"));
        public static final PacketCodec<RegistryByteBuf, Warn> CODEC = PacketCodec.of(
                (p, buf) -> {
                    writeVec(buf, p.ground);
                    buf.writeLong(p.seed);
                    buf.writeVarInt(p.ticks);
                },
                buf -> new Warn(readVec(buf), buf.readLong(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> clients: the bolt comes down on {@code ground} now. */
    public record Strike(Vec3d ground, long seed) implements CustomPayload {
        public static final Id<Strike> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "thunder_strike"));
        public static final PacketCodec<RegistryByteBuf, Strike> CODEC = PacketCodec.of(
                (p, buf) -> {
                    writeVec(buf, p.ground);
                    buf.writeLong(p.seed);
                },
                buf -> new Strike(readVec(buf), buf.readLong()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> clients: an arc of chain lightning leaps from {@code from} to {@code to}. */
    public record Arc(Vec3d from, Vec3d to, float power) implements CustomPayload {
        public static final Id<Arc> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "thunder_arc"));
        public static final PacketCodec<RegistryByteBuf, Arc> CODEC = PacketCodec.of(
                (p, buf) -> {
                    writeVec(buf, p.from);
                    writeVec(buf, p.to);
                    buf.writeFloat(p.power);
                },
                buf -> new Arc(readVec(buf), readVec(buf), buf.readFloat()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---------------------------------------------------------------------
    // Storms under way
    // ---------------------------------------------------------------------

    /** One link of chain lightning, waiting for its tick. */
    private record Jump(int at, LivingEntity from, Vec3d fromPoint, LivingEntity to, float damage) {}

    static final class Storm {
        final ServerWorld world;
        final UUID owner;
        final Vec3d ground;
        final long seed;
        final List<Jump> jumps = new ArrayList<>();
        int age;

        Storm(ServerWorld world, UUID owner, Vec3d ground, long seed) {
            this.world = world;
            this.owner = owner;
            this.ground = ground;
            this.seed = seed;
        }
    }

    private static final List<Storm> STORMS = new ArrayList<>();

    public static void register() {
        Spells.register(SpellId.LIGHTNING_CASCADE, new ThunderstrikeSpell());
        PayloadTypeRegistry.playS2C().register(Warn.ID, Warn.CODEC);
        PayloadTypeRegistry.playS2C().register(Strike.ID, Strike.CODEC);
        PayloadTypeRegistry.playS2C().register(Arc.ID, Arc.CODEC);
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (STORMS.isEmpty()) return;
            Iterator<Storm> it = STORMS.iterator();
            while (it.hasNext()) {
                Storm s = it.next();
                if (s.world == world && !tick(s)) it.remove();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> STORMS.clear());
    }

    /**
     * The ground where {@code player} aims, or null if they aim at nothing: the top of the block if they aim at a top,
     * otherwise the floor in front of whatever they hit (the foot of a wall, a cave floor).
     */
    public static Vec3d aim(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVector().multiply(RANGE));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos.Mutable m = hit.getBlockPos().offset(hit.getSide()).mutableCopy();
        for (int i = 0; i < 48 && m.getY() > world.getBottomY(); i++) {
            BlockPos below = m.down();
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) break;
            m.move(0, -1, 0);
        }
        return new Vec3d(m.getX() + 0.5, m.getY(), m.getZ() + 0.5);
    }

    /** Starts a storm over {@code ground}. Public so tests can call it directly. */
    public static Storm start(ServerWorld world, PlayerEntity caster, Vec3d ground) {
        Storm s = new Storm(world, caster.getUuid(), ground, world.getRandom().nextLong());
        STORMS.add(s);
        send(world, ground, new Warn(ground, s.seed, WARN_TICKS));
        world.playSound(null, ground.x, ground.y + CLOUD_HEIGHT, ground.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 3.0f, 0.5f);
        world.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.ITEM_TRIDENT_THUNDER.value(), SoundCategory.PLAYERS, 0.8f, 0.6f);
        return s;
    }

    /** Returns false once the storm is spent. */
    private static boolean tick(Storm s) {
        int age = ++s.age;
        ServerWorld w = s.world;
        Vec3d g = s.ground;
        if (age < WARN_TICKS) {
            warning(s, age);
            return true;
        }
        if (age == WARN_TICKS) strike(s);
        // The chain lightning, link by link
        for (Iterator<Jump> it = s.jumps.iterator(); it.hasNext(); ) {
            Jump j = it.next();
            if (j.at() > age) continue;
            it.remove();
            jump(s, j);
        }
        // Thunder rolling off the hills
        int after = age - WARN_TICKS;
        if (after == 14 || after == 34) {
            w.playSound(null, g.x, g.y + 10, g.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, after == 14 ? 5f : 3f, 0.45f);
        }
        if (after < 40 && after % 3 == 0) {
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, g.x, g.y + 0.3, g.z, 6, 2.0, 0.2, 2.0, 0.2);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, g.x, g.y + 0.5, g.z, 2, 1.2, 0.2, 1.2, 0.02);
        }
        return after < AFTER_TICKS || !s.jumps.isEmpty();
    }

    private static void warning(Storm s, int age) {
        ServerWorld w = s.world;
        Vec3d g = s.ground;
        // Static on everything standing under it
        if (age % 3 == 0) {
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, zone(g, STRIKE_RADIUS + 1), e -> e.isAlive() && !e.isSpectator())) {
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getBodyY(0.8), e.getZ(), 2, e.getWidth() * 0.4, e.getHeight() * 0.3, e.getWidth() * 0.4, 0.05);
            }
        }
        // Crackling, faster and faster
        int every = Math.max(2, 9 - age / 7);
        if (age % every == 0) {
            double a = w.getRandom().nextDouble() * Math.PI * 2, r = w.getRandom().nextDouble() * STRIKE_RADIUS;
            w.playSound(null, g.x + Math.cos(a) * r, g.y, g.z + Math.sin(a) * r, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT,
                    SoundCategory.WEATHER, 0.35f + age / 120f, 1.6f + w.getRandom().nextFloat() * 0.4f);
        }
        if (age == 18 || age == 33) {
            w.playSound(null, g.x, g.y + CLOUD_HEIGHT, g.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 2.5f, 0.55f);
        }
        if (age == 24) w.playSound(null, g.x, g.y, g.z, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.WEATHER, 2.5f, 0.6f);
        // The air about to break
        if (age == WARN_TICKS - 14) w.playSound(null, g.x, g.y + 2, g.z, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.WEATHER, 2.2f, 1.7f);
        ShakePayload.around(w, g, 20, 0.08f + 0.004f * age, 2);
    }

    // ---------------------------------------------------------------------
    // The strike
    // ---------------------------------------------------------------------

    private static void strike(Storm s) {
        ServerWorld w = s.world;
        Vec3d g = s.ground;
        PlayerEntity caster = w.getPlayerByUuid(s.owner);
        send(w, g, new Strike(g, s.seed));
        ShakePayload.around(w, g, 48, 1.5f, 18);
        w.spawnParticles(ParticleTypes.FLASH, g.x, g.y + 1, g.z, 2, 0.5, 0.5, 0.5, 0);
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, g.x, g.y + 0.5, g.z, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, g.x, g.y + 0.5, g.z, 120, 2.5, 0.6, 2.5, 0.6);
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI * 2 / 32;
            w.spawnParticles(ParticleTypes.CLOUD, g.x + Math.cos(a), g.y + 0.3, g.z + Math.sin(a), 0, Math.cos(a), 0.05, Math.sin(a), 0.7);
        }

        DamageSource source = lightning(w, caster);
        Set<UUID> chained = new HashSet<>(); // the bolt's own victims: the chains reach out past them
        LightningEntity charge = EntityType.LIGHTNING_BOLT.create(w);
        if (charge != null) {
            charge.refreshPositionAndAngles(g.x, g.y, g.z, 0f, 0f);
            charge.setCosmetic(true);
        }
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, zone(g, STRIKE_RADIUS + 2), e -> e.isAlive() && !e.isSpectator())) {
            if (friendly(e, s.owner, caster)) continue;
            double d = Math.hypot(e.getX() - g.x, e.getZ() - g.z) - e.getWidth() / 2;
            // As real lightning does: a creeper caught near it comes out charged
            if (e instanceof CreeperEntity creeper && charge != null && d <= STRIKE_RADIUS + 2) creeper.onStruckByLightning(w, charge);
            if (d > STRIKE_RADIUS) continue;
            float f = (float) MathHelper.clamp(d / STRIKE_RADIUS, 0, 1);
            chained.add(e.getUuid());
            e.timeUntilRegen = 0;
            e.damage(source, MathHelper.lerp(f, CENTER_DAMAGE, EDGE_DAMAGE));
            e.setOnFireFor(5);
            Vec3d out = new Vec3d(e.getX() - g.x, 0, e.getZ() - g.z);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            e.addVelocity(out.x * 0.5 * (1 - f), 0.45 * (1 - f) + 0.1, out.z * 0.5 * (1 - f));
            e.velocityModified = true;
        }

        // The chain lightning, worked out now; each link lands a few ticks after the last
        Vec3d top = g.add(0, 1.2, 0);
        int made = 0;
        for (int c = 0; c < CHAINS; c++) {
            LivingEntity from = null;
            Vec3d fromPoint = top;
            for (int k = 0; k < JUMPS; k++) {
                LivingEntity next = nearest(w, from == null ? fromPoint : from.getPos(), k == 0 ? CHAIN_START_RANGE : JUMP_RANGE, chained, s.owner, caster);
                if (next == null) break;
                chained.add(next.getUuid());
                s.jumps.add(new Jump(WARN_TICKS + 2 + c + k * JUMP_TICKS, from, fromPoint, next, ARC_DAMAGE * (float) Math.pow(ARC_DECAY, k)));
                from = next;
                made++;
            }
        }
        // Nobody about: the arcs still crackle out across the ground
        if (made == 0) {
            for (int i = 0; i < CHAINS; i++) {
                double a = w.getRandom().nextDouble() * Math.PI * 2, r = 3 + w.getRandom().nextDouble() * 4;
                double x = g.x + Math.cos(a) * r, z = g.z + Math.sin(a) * r;
                Vec3d to = new Vec3d(x, w.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z)), z);
                send(w, g, new Arc(top, to, 0.7f));
            }
        }
    }

    private static void jump(Storm s, Jump j) {
        ServerWorld w = s.world;
        LivingEntity target = j.to();
        if (!target.isAlive() || target.isRemoved() || target.getWorld() != w) return;
        Vec3d from = j.from() != null && j.from().isAlive() ? j.from().getPos().add(0, j.from().getHeight() * 0.6, 0) : j.fromPoint();
        Vec3d to = target.getPos().add(0, target.getHeight() * 0.6, 0);
        send(w, to, new Arc(from, to, j.damage() / ARC_DAMAGE));
        PlayerEntity caster = w.getPlayerByUuid(s.owner);
        target.timeUntilRegen = 0;
        if (target.damage(lightning(w, caster), j.damage())) {
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 15, 2, false, false, true));
        }
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 18, target.getWidth() * 0.4, target.getHeight() * 0.3, target.getWidth() * 0.4, 0.25);
        w.playSound(null, to.x, to.y, to.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER, 1.0f, 1.3f + w.getRandom().nextFloat() * 0.4f);
    }

    private static LivingEntity nearest(ServerWorld w, Vec3d at, double range, Set<UUID> taken, UUID owner, PlayerEntity caster) {
        return w.getEntitiesByClass(LivingEntity.class, new Box(at, at).expand(range),
                        e -> e.isAlive() && !e.isSpectator() && !taken.contains(e.getUuid()) && !friendly(e, owner, caster)
                                && e.squaredDistanceTo(at) <= range * range)
                .stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(at))).orElse(null);
    }

    /** Lightning damage that counts as the caster's (kills, Lifesteal), with the lightning death message. */
    private static DamageSource lightning(ServerWorld w, PlayerEntity caster) {
        var type = w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(DamageTypes.LIGHTNING_BOLT);
        return new DamageSource(type, null, caster);
    }

    private static boolean friendly(Entity e, UUID owner, PlayerEntity caster) {
        if (e.getUuid().equals(owner) || e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && owner.equals(pet.getOwnerUuid())) return true;
        return caster != null && e.isTeammate(caster);
    }

    private static Box zone(Vec3d g, double r) {
        return new Box(g.x - r, g.y - 2, g.z - r, g.x + r, g.y + 5, g.z + r);
    }

    private static void send(ServerWorld w, Vec3d at, CustomPayload payload) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) <= VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, payload.getId())) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** For tests: how many storms are brewing or striking. */
    public static int active() {
        return STORMS.size();
    }
}
