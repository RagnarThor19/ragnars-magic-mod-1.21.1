package net.ragnar.ragnarsmagicmod.sleep;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import net.ragnar.ragnarsmagicmod.sight.Sight;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Sleep Darts and Tome of Sleep Potions. Both put what they hit to sleep the same way: it goes drowsy for
 * {@link #DROWSY_TICKS} (slowed, nodding off), then sleeps for {@link #ASLEEP_TICKS} - rooted to the spot, head
 * hanging, Z's drifting up off it and the odd snore. Mobs stop thinking altogether (MobEntityMixin-style, see
 * SleepMobMixin); players can't walk, look around, hit or use anything and their screen goes dark. Taking any damage
 * wakes them up at once with a little pop.
 * <ul>
 *   <li><b>Sleep Darts</b> (beginner): a one second charge, then a fast dart that puts the one thing it hits to sleep.</li>
 *   <li><b>Sleep Potions</b> (advanced): lobs a potion up to about 30 blocks, depending on how high you aim; it bursts
 *       into a cloud that puts everything within {@link SleepPotionEntity#RADIUS} blocks to sleep.</li>
 * </ul>
 * Everything for them lives in this package, plus the {@code mixin/sleep} mixins.
 * <p>
 * To remove them:
 * <ol>
 *   <li>delete this package ({@code sleep} and {@code sleep/client}) and {@code mixin/sleep} (and its lines in
 *       ragnarsmagicmod.mixins.json)</li>
 *   <li>delete {@code Sleep.register()} in RagnarsMagicMod and {@code SleepClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code SLEEP_DARTS} and {@code SLEEP_POTIONS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_sleep_darts.json}, {@code models/item/tome_of_sleep_potions.json} and the
 *       {@code sleep} lines in en_us.json</li>
 * </ol>
 */
public final class Sleep {
    private Sleep() {}

    public static final int DROWSY_TICKS = 40;   // 2 seconds
    public static final int ASLEEP_TICKS = 200;  // 10 seconds
    public static final int TOTAL_TICKS = DROWSY_TICKS + ASLEEP_TICKS;

    /** How far a sleeping mob lets its head hang, in degrees of pitch. */
    private static final float HEAD_DROOP = 40f;
    private static final Identifier DROWSY_MODIFIER = Identifier.of(RagnarsMagicMod.MOD_ID, "sleep_drowsy");
    private static final Identifier ASLEEP_MODIFIER = Identifier.of(RagnarsMagicMod.MOD_ID, "sleep_asleep");
    /** The soft lavender everything about the spell is coloured. */
    public static final int COLOR = 0xB9A3FF;
    public static final DustParticleEffect DUST = new DustParticleEffect(new Vector3f(0.73f, 0.64f, 1.0f), 1.2f);
    /** Smaller motes, for anything that happens right in front of the caster's face. */
    public static final DustParticleEffect MOTE = new DustParticleEffect(new Vector3f(0.78f, 0.7f, 1.0f), 0.5f);

    public static final EntityType<SleepDartEntity> DART = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "sleep_dart"),
            FabricEntityTypeBuilder.<SleepDartEntity>create(SpawnGroup.MISC, SleepDartEntity::new)
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final EntityType<SleepPotionEntity> POTION = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "sleep_potion"),
            FabricEntityTypeBuilder.<SleepPotionEntity>create(SpawnGroup.MISC, SleepPotionEntity::new)
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(80)
                    .trackedUpdateRate(10)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_SLEEP_DARTS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_sleep_darts"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.SLEEP_DARTS, 10
            ).setCooldown(20 * 23) // 23 seconds
    );

    public static final TomeItem TOME_OF_SLEEP_POTIONS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_sleep_potions"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.SLEEP_POTIONS, 12
            ).setCooldown(20 * 22) // 22 seconds
    );

    /** Server -> everyone who can see it: {@code entityId} is now {@code phase} ({@link #AWAKE}, {@link #DROWSY} or {@link #ASLEEP}) for {@code ticks} more ticks. */
    public record StatePayload(int entityId, int phase, int ticks) implements CustomPayload {
        public static final Id<StatePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "sleep_state"));
        public static final PacketCodec<RegistryByteBuf, StatePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, StatePayload::entityId,
                PacketCodecs.VAR_INT, StatePayload::phase,
                PacketCodecs.VAR_INT, StatePayload::ticks,
                StatePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static final int AWAKE = 0, DROWSY = 1, ASLEEP = 2;

    private static final class Sleeper {
        final LivingEntity entity;
        int age;

        Sleeper(LivingEntity entity) {
            this.entity = entity;
        }

        int phase() {
            return age < DROWSY_TICKS ? DROWSY : ASLEEP;
        }

        int left() {
            return phase() == DROWSY ? DROWSY_TICKS - age : TOTAL_TICKS - age;
        }
    }

    /** Server: everyone drowsy or asleep, by UUID. */
    private static final Map<UUID, Sleeper> SLEEPERS = new HashMap<>();
    /** Client: entity id -> phase, kept by SleepClient. */
    public static final Map<Integer, Integer> CLIENT = new HashMap<>();

    /** Clouds left behind by burst potions: just for show. */
    private record Cloud(ServerWorld world, Vec3d at, int[] age) {}
    private static final List<Cloud> CLOUDS = new ArrayList<>();
    private static final int CLOUD_TICKS = 36;

    private static int phaseOf(Entity entity) {
        if (entity.getWorld().isClient) return CLIENT.getOrDefault(entity.getId(), AWAKE);
        Sleeper s = SLEEPERS.get(entity.getUuid());
        return s == null ? AWAKE : s.phase();
    }

    /** True while {@code entity} is fast asleep (not just drowsy). Works on both sides. */
    public static boolean isAsleep(Entity entity) {
        return phaseOf(entity) == ASLEEP;
    }

    /** True while {@code entity} is drowsy or asleep. Works on both sides. */
    public static boolean isSleepy(Entity entity) {
        return phaseOf(entity) != AWAKE;
    }

    public static void register() {
        // Lets staffs hand the tomes back and puts them in the beginner and advanced tome loot pools
        ModItems.TOMES.computeIfAbsent(SpellId.SLEEP_DARTS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_SLEEP_DARTS);
        ModItems.TOMES.computeIfAbsent(SpellId.SLEEP_POTIONS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_SLEEP_POTIONS);
        Spells.register(SpellId.SLEEP_DARTS, new SleepDartSpell());
        Spells.register(SpellId.SLEEP_POTIONS, new SleepPotionSpell());
        // At the end of the beginner and advanced tomes in the tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.addAfter(Lunging.TOME_OF_LUNGING, TOME_OF_SLEEP_DARTS);
            entries.addAfter(Sight.TOME_OF_SIGHT, TOME_OF_SLEEP_POTIONS);
        });

        PayloadTypeRegistry.playS2C().register(StatePayload.ID, StatePayload.CODEC);
        ServerTickEvents.END_WORLD_TICK.register(Sleep::tick);
        ServerTickEvents.END_WORLD_TICK.register(SleepDartSpell::tick);

        // Any hurt at all wakes you straight up
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (SLEEPERS.containsKey(entity.getUuid())) wake(entity, true);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (SLEEPERS.containsKey(entity.getUuid())) wake(entity, false);
        });

        // Sleeping players can't do anything with their hands
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> isAsleep(player) ? ActionResult.FAIL : ActionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> isAsleep(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> isAsleep(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> isAsleep(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                isAsleep(player) ? TypedActionResult.fail(player.getStackInHand(hand)) : TypedActionResult.pass(player.getStackInHand(hand)));

        // Players who come into view of a sleeper need to be told
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            Sleeper s = SLEEPERS.get(entity.getUuid());
            if (s != null && ServerPlayNetworking.canSend(viewer, StatePayload.ID)) {
                ServerPlayNetworking.send(viewer, new StatePayload(entity.getId(), s.phase(), s.left()));
            }
        });
        // The modifiers are temporary (never saved), but never leave anyone half asleep behind
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Sleeper s = SLEEPERS.remove(handler.player.getUuid());
            if (s != null) unlock(s.entity);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Sleeper s : SLEEPERS.values()) unlock(s.entity);
            SLEEPERS.clear();
            CLOUDS.clear();
            SleepDartSpell.clear();
        });
    }

    /** Bosses, armor stands and spectators can't be put to sleep. */
    static boolean canSleep(Entity e) {
        if (!(e instanceof LivingEntity living) || !living.isAlive()) return false;
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity || e instanceof ArmorStandEntity) return false;
        return !(e instanceof PlayerEntity p && (p.isSpectator() || Phantom.isPhantom(p)));
    }

    /**
     * Starts {@code e} nodding off. Hitting something that's already drowsy or asleep tops its sleep back up
     * instead of starting over. Returns false if it can't be put to sleep.
     */
    public static boolean putToSleep(ServerWorld world, LivingEntity e) {
        if (!canSleep(e)) return false;
        Sleeper s = SLEEPERS.get(e.getUuid());
        if (s != null) {
            if (s.phase() == ASLEEP) {
                s.age = DROWSY_TICKS;
                broadcast(e, ASLEEP, s.left());
            }
            return true;
        }
        s = new Sleeper(e);
        SLEEPERS.put(e.getUuid(), s);
        addModifier(e, EntityAttributes.GENERIC_MOVEMENT_SPEED, DROWSY_MODIFIER, -0.55);
        e.setSprinting(false);
        broadcast(e, DROWSY, DROWSY_TICKS);

        Vec3d head = e.getEyePos();
        world.playSound(null, head.x, head.y, head.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 0.6f);
        world.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_CAT_PURR, SoundCategory.PLAYERS, 0.9f, 0.7f);
        world.spawnParticles(DUST, head.x, head.y, head.z, 14, e.getWidth() * 0.4, 0.3, e.getWidth() * 0.4, 0.02);
        world.spawnParticles(ParticleTypes.ENCHANT, head.x, head.y + 0.3, head.z, 20, e.getWidth() * 0.4, 0.3, e.getWidth() * 0.4, 0.3);
        return true;
    }

    /** Wakes {@code e} up. With {@code pop}, it jolts awake with a little pop. */
    public static void wake(LivingEntity e, boolean pop) {
        Sleeper s = SLEEPERS.remove(e.getUuid());
        if (s == null) return;
        unlock(e);
        broadcast(e, AWAKE, 0);
        if (pop && e.getWorld() instanceof ServerWorld world) {
            Vec3d head = e.getEyePos();
            world.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.9f, 1.5f);
            world.playSound(null, head.x, head.y, head.z, SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, SoundCategory.PLAYERS, 1.0f, 1.2f);
            world.spawnParticles(ParticleTypes.POOF, head.x, head.y + 0.4, head.z, 6, 0.15, 0.1, 0.15, 0.03);
            world.spawnParticles(DUST, head.x, head.y + 0.5, head.z, 8, 0.25, 0.15, 0.25, 0.02);
        }
    }

    private static void tick(ServerWorld world) {
        if (!SLEEPERS.isEmpty()) tickSleepers(world);
        if (!CLOUDS.isEmpty()) tickClouds(world);
    }

    private static void tickSleepers(ServerWorld world) {
        Iterator<Sleeper> it = SLEEPERS.values().iterator();
        List<LivingEntity> done = new ArrayList<>();
        while (it.hasNext()) {
            Sleeper s = it.next();
            LivingEntity e = s.entity;
            if (e.isRemoved() || !e.isAlive()) {
                it.remove();
                unlock(e);
                continue;
            }
            if (e.getWorld() != world) continue;

            s.age++;
            if (s.age == DROWSY_TICKS) fallAsleep(world, e);
            if (s.age >= TOTAL_TICKS) {
                done.add(e);
                continue;
            }

            if (s.phase() == DROWSY) {
                // Yawning sparkles while it fights to stay awake
                if (s.age % 10 == 0) {
                    Vec3d head = e.getEyePos();
                    world.spawnParticles(DUST, head.x, head.y + 0.2, head.z, 2, 0.2, 0.1, 0.2, 0.0);
                }
                continue;
            }

            // Fast asleep: rooted where it lies, head hanging
            e.setVelocity(0, Math.min(0, e.getVelocity().y), 0);
            e.velocityModified = true;
            if (e instanceof MobEntity mob) {
                mob.getNavigation().stop();
                mob.setPitch(MathHelper.lerp(0.25f, mob.getPitch(), HEAD_DROOP));
                if (mob instanceof CreeperEntity creeper) creeper.setFuseSpeed(-1);
            }
            int asleep = s.age - DROWSY_TICKS;
            if (asleep % 50 == 25) {
                Vec3d head = e.getEyePos();
                float pitch = MathHelper.clamp(1.1f - e.getHeight() * 0.15f, 0.5f, 1.2f);
                world.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_FOX_SLEEP, SoundCategory.NEUTRAL, 0.8f, pitch);
            }
        }
        // Slept it off: wakes up on its own, quietly
        for (LivingEntity e : done) wake(e, false);
    }

    private static void fallAsleep(ServerWorld world, LivingEntity e) {
        removeModifier(e, EntityAttributes.GENERIC_MOVEMENT_SPEED, DROWSY_MODIFIER);
        addModifier(e, EntityAttributes.GENERIC_MOVEMENT_SPEED, ASLEEP_MODIFIER, -1.0);
        addModifier(e, EntityAttributes.GENERIC_JUMP_STRENGTH, ASLEEP_MODIFIER, -1.0);
        if (e instanceof MobEntity mob) {
            mob.setTarget(null);
            mob.setAttacking(false);
        }
        if (e instanceof PlayerEntity p) {
            p.stopUsingItem();
            p.setSprinting(false);
        }
        broadcast(e, ASLEEP, ASLEEP_TICKS);
        Vec3d head = e.getEyePos();
        world.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_FOX_SLEEP, SoundCategory.NEUTRAL, 0.9f, 0.9f);
        world.spawnParticles(DUST, head.x, head.y + 0.3, head.z, 10, e.getWidth() * 0.35, 0.2, e.getWidth() * 0.35, 0.01);
    }

    private static void unlock(LivingEntity e) {
        removeModifier(e, EntityAttributes.GENERIC_MOVEMENT_SPEED, DROWSY_MODIFIER);
        removeModifier(e, EntityAttributes.GENERIC_MOVEMENT_SPEED, ASLEEP_MODIFIER);
        removeModifier(e, EntityAttributes.GENERIC_JUMP_STRENGTH, ASLEEP_MODIFIER);
        if (e instanceof CreeperEntity creeper) creeper.setFuseSpeed(-1);
    }

    private static void addModifier(LivingEntity e, RegistryEntry<EntityAttribute> attribute, Identifier id, double amount) {
        EntityAttributeInstance inst = e.getAttributeInstance(attribute);
        if (inst != null && !inst.hasModifier(id)) {
            inst.addTemporaryModifier(new EntityAttributeModifier(id, amount, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void removeModifier(LivingEntity e, RegistryEntry<EntityAttribute> attribute, Identifier id) {
        EntityAttributeInstance inst = e.getAttributeInstance(attribute);
        if (inst != null) inst.removeModifier(id);
    }

    private static void broadcast(LivingEntity e, int phase, int ticks) {
        StatePayload payload = new StatePayload(e.getId(), phase, ticks);
        Set<ServerPlayerEntity> to = new HashSet<>(PlayerLookup.tracking(e));
        if (e instanceof ServerPlayerEntity self) to.add(self);
        for (ServerPlayerEntity p : to) {
            if (ServerPlayNetworking.canSend(p, StatePayload.ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    // ---------------------------------------------------------------------
    // The sleepy cloud a burst potion leaves hanging in the air
    // ---------------------------------------------------------------------

    static void cloud(ServerWorld world, Vec3d at) {
        CLOUDS.add(new Cloud(world, at, new int[1]));
    }

    private static void tickClouds(ServerWorld world) {
        Iterator<Cloud> it = CLOUDS.iterator();
        while (it.hasNext()) {
            Cloud c = it.next();
            if (c.world() != world) continue;
            int age = ++c.age()[0];
            if (age > CLOUD_TICKS) {
                it.remove();
                continue;
            }
            // Billows out to the edge of the burst, then thins away
            float k = age / (float) CLOUD_TICKS;
            double r = SleepPotionEntity.RADIUS * Math.min(1.0, 0.45 + k * 1.2);
            int count = (int) (7 * (1f - k)) + 1;
            for (int i = 0; i < count; i++) {
                double a = world.random.nextDouble() * MathHelper.TAU;
                double d = Math.sqrt(world.random.nextDouble()) * r;
                double x = c.at().x + Math.cos(a) * d, z = c.at().z + Math.sin(a) * d;
                double y = c.at().y + 0.2 + world.random.nextDouble() * 1.4;
                world.spawnParticles(ParticleTypes.CLOUD, x, y, z, 1, 0.1, 0.05, 0.1, 0.004);
                world.spawnParticles(DUST, x, y + 0.3, z, 1, 0.2, 0.2, 0.2, 0.0);
            }
            if (age % 4 == 0) {
                world.spawnParticles(ParticleTypes.WITCH, c.at().x, c.at().y + 0.8, c.at().z, 3, r * 0.5, 0.4, r * 0.5, 0.0);
            }
        }
    }
}
