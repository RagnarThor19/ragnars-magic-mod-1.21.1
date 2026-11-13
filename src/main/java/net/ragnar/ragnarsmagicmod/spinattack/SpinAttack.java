package net.ragnar.ragnarsmagicmod.spinattack;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.gambling.Gambling;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Spin Attack: an advanced tome. With a sword in hand (and the staff in the other), you whirl round three
 * times in under a second, and every spin is a fully charged swing of that sword at everything around you:
 * <ul>
 *   <li>the damage is the sword's own - its attack damage, Sharpness / Smite / Bane of Arthropods, Fire Aspect, and
 *       (with the sword in your main hand) Strength or Weakness - so the better the sword, the harder the spin;</li>
 *   <li>the better the sword, the wider the ring too: {@link #BASE_RADIUS}, plus a little for every point of damage
 *       over a wooden sword's, for each level of Sharpness and for each level of Sweeping Edge;</li>
 *   <li>the first two spins barely nudge what they hit; the third reaches {@link #LAST_REACH}x as far and throws it,
 *       and half the time it's a critical hit: half again the damage, a harder throw, and the crit sparks and sound.</li>
 * </ul>
 * Everyone sees you whirl with your arms flung out (SpinAttackClient), and in first person your view whirls round with
 * you, with a shake on each spin.
 * <p>
 * Everything for it lives in this package (and SpinAttackRenderMixin). To remove it: delete this package and
 * {@code client.SpinAttackRenderMixin} (and its line in ragnarsmagicmod.mixins.json, and the SpinAttackClient.pose
 * call in MightyPushPoseMixin), {@code SpinAttack.register()} in RagnarsMagicMod and {@code SpinAttackClient.init()} in
 * RagnarsMagicModClient, {@code SPIN_ATTACK} in SpellId, {@code models/item/tome_of_spin_attack.json} and its
 * en_us.json line.
 */
public final class SpinAttack {
    private SpinAttack() {}

    public static final int XP_COST = 5;
    public static final int COOLDOWN = 5 * 20;

    /** Ticks between spins; the whole attack is three of them. */
    public static final int SPIN_TICKS = 6, SPINS = 3;
    public static final double BASE_RADIUS = 2.5;
    /** How much wider the ring gets per point of sword damage over a wooden sword's, per Sharpness and per Sweeping Edge level. */
    public static final double RADIUS_PER_DAMAGE = 0.25, RADIUS_PER_SHARPNESS = 0.2, RADIUS_PER_SWEEPING = 0.25;
    static final double WOODEN_SWORD_DAMAGE = 4.0;
    public static final float CRIT_CHANCE = 0.5f, CRIT_DAMAGE = 1.5f;
    /** Knockback: a light nudge for the first two spins, a real throw for the last, and harder still on a crit. */
    static final double NUDGE = 0.06, THROW = 1.15, CRIT_THROW = 1.75, THROW_LIFT = 0.4;
    /** The last spin reaches a little further than the first two. */
    public static final double LAST_REACH = 1.15;

    public static final TomeItem TOME_OF_SPIN_ATTACK = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_spin_attack"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.SPIN_ATTACK, XP_COST
            ).setCooldown(COOLDOWN)
    );

    /** Server -> nearby players: {@code playerId} has started spinning, sword in the {@code offHand} or not. */
    public record SpinPayload(int playerId, boolean offHand) implements CustomPayload {
        public static final Id<SpinPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "spin_attack"));
        public static final PacketCodec<RegistryByteBuf, SpinPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, SpinPayload::playerId, PacketCodecs.BOOL, SpinPayload::offHand, SpinPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** One spin attack under way. */
    private static final class Spin {
        final Hand hand;
        final boolean crit;
        int age = 0;

        Spin(Hand hand, boolean crit) {
            this.hand = hand;
            this.crit = crit;
        }
    }

    private static final Map<UUID, Spin> SPINS_UNDERWAY = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.SPIN_ATTACK, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_SPIN_ATTACK);
        Spells.register(SpellId.SPIN_ATTACK, new SpinAttackSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Gambling.TOME_OF_GAMBLING, TOME_OF_SPIN_ATTACK));
        PayloadTypeRegistry.playS2C().register(SpinPayload.ID, SpinPayload.CODEC);
        ServerTickEvents.END_SERVER_TICK.register(SpinAttack::tick);
    }

    /** The hand holding a sword (the main hand first), or null if neither does. */
    public static Hand swordHand(LivingEntity player) {
        if (player.getMainHandStack().isIn(ItemTags.SWORDS)) return Hand.MAIN_HAND;
        if (player.getOffHandStack().isIn(ItemTags.SWORDS)) return Hand.OFF_HAND;
        return null;
    }

    public static boolean isSpinning(ServerPlayerEntity player) {
        return SPINS_UNDERWAY.containsKey(player.getUuid());
    }

    /** Starts the spins. The first lands this very tick. */
    static void start(ServerPlayerEntity player, Hand hand) {
        Spin spin = new Spin(hand, player.getRandom().nextFloat() < CRIT_CHANCE);
        SPINS_UNDERWAY.put(player.getUuid(), spin);
        SpinPayload payload = new SpinPayload(player.getId(), hand == Hand.OFF_HAND);
        for (ServerPlayerEntity viewer : player.getServerWorld().getPlayers()) {
            if (viewer.squaredDistanceTo(player) < 96 * 96 && ServerPlayNetworking.canSend(viewer, SpinPayload.ID)) ServerPlayNetworking.send(viewer, payload);
        }
        spinOnce(player, spin, 0);
    }

    private static void tick(MinecraftServer server) {
        if (SPINS_UNDERWAY.isEmpty()) return;
        Iterator<Map.Entry<UUID, Spin>> it = SPINS_UNDERWAY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Spin> e = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(e.getKey());
            Spin spin = e.getValue();
            if (player == null || !player.isAlive()) {
                it.remove();
                continue;
            }
            spin.age++;
            if (spin.age % SPIN_TICKS == 0) spinOnce(player, spin, spin.age / SPIN_TICKS);
            if (spin.age >= (SPINS - 1) * SPIN_TICKS) it.remove();
        }
    }

    // ---------------------------------------------------------------------
    // One spin
    // ---------------------------------------------------------------------

    private static void spinOnce(ServerPlayerEntity player, Spin spin, int index) {
        ItemStack sword = player.getStackInHand(spin.hand);
        if (!sword.isIn(ItemTags.SWORDS)) {
            SPINS_UNDERWAY.remove(player.getUuid()); // it was put away: the whirl stops
            return;
        }
        ServerWorld world = player.getServerWorld();
        boolean last = index == SPINS - 1, crit = last && spin.crit;
        double radius = radius(world, player, sword, spin.hand) * (last ? LAST_REACH : 1.0);
        player.swingHand(spin.hand, true);

        int hits = 0;
        Box around = player.getBoundingBox().expand(radius, 1.0, radius);
        for (LivingEntity target : world.getEntitiesByClass(LivingEntity.class, around, t -> canHit(player, t))) {
            double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
            if (dx * dx + dz * dz > (radius + target.getWidth() / 2) * (radius + target.getWidth() / 2)) continue;
            if (hit(world, player, sword, spin.hand, target, last, crit)) hits++;
        }
        if (hits > 0) sword.damage(1, player, spin.hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);

        effects(world, player, radius, index, last, crit);
    }

    /** One full-strength swing of {@code sword} at {@code target}. */
    private static boolean hit(ServerWorld world, ServerPlayerEntity player, ItemStack sword, Hand hand, LivingEntity target,
                               boolean last, boolean crit) {
        DamageSource source = player.getDamageSources().playerAttack(player);
        float damage = EnchantmentHelper.getDamage(world, sword, target, source, (float) swordDamage(player, sword, hand));
        if (crit) damage *= CRIT_DAMAGE;

        Vec3d before = target.getVelocity();
        target.timeUntilRegen = 0; // three swings in under a second: none of them is swallowed
        if (!target.damage(source, damage)) return false;
        player.onAttacking(target);
        EnchantmentHelper.onTargetDamaged(world, target, source, sword);

        // Knockback is ours to give: a nudge, then a throw
        Vec3d out = new Vec3d(target.getX() - player.getX(), 0, target.getZ() - player.getZ());
        out = out.lengthSquared() < 1.0e-4 ? Vec3d.fromPolar(0, player.getYaw()) : out.normalize();
        double resist = 1.0 - target.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        if (last) {
            double push = (crit ? CRIT_THROW : THROW) * resist;
            target.setVelocity(before.x * 0.3 + out.x * push, Math.max(before.y, 0) + THROW_LIFT * (crit ? 1.3 : 1.0) * resist, before.z * 0.3 + out.z * push);
        } else {
            target.setVelocity(before.add(out.multiply(NUDGE * resist)));
        }
        target.velocityModified = true;

        if (crit) {
            world.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getBodyY(0.6), target.getZ(), 14, 0.3, 0.4, 0.3, 0.35);
            world.spawnParticles(ParticleTypes.ENCHANTED_HIT, target.getX(), target.getBodyY(0.6), target.getZ(), 8, 0.3, 0.4, 0.3, 0.2);
        } else {
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getBodyY(0.5), target.getZ(), 2, 0.2, 0.2, 0.2, 0.1);
        }
        return true;
    }

    private static boolean canHit(ServerPlayerEntity player, LivingEntity t) {
        if (t == player || !t.isAlive() || t.isSpectator() || t instanceof IllusionEntity) return false;
        if (t instanceof ArmorStandEntity stand && stand.isMarker()) return false;
        if (t instanceof TameableEntity pet && player.getUuid().equals(pet.getOwnerUuid())) return false;
        return !t.isTeammate(player);
    }

    private static void effects(ServerWorld world, ServerPlayerEntity player, double radius, int index, boolean last, boolean crit) {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        float pitch = 0.85f + 0.15f * index;
        world.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, pitch);
        world.playSound(null, x, y, z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 0.35f, 1.6f + 0.15f * index);
        if (last) world.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.0f, 0.9f);
        if (crit) {
            world.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.2f, 1.0f);
            world.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0f, 0.8f);
        }

        // The sweep of the blade all the way round, a little further out each time
        int sweeps = last ? 12 : 8;
        double r = radius * (0.55 + 0.1 * index);
        double turn = index * 0.4;
        for (int i = 0; i < sweeps; i++) {
            double a = turn + i * Math.PI * 2 / sweeps;
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, x + Math.cos(a) * r, y + 1.0, z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
        if (last) {
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                world.spawnParticles(ParticleTypes.CLOUD, x + Math.cos(a) * 0.8, y + 0.2, z + Math.sin(a) * 0.8, 0,
                        Math.cos(a), 0.02, Math.sin(a), 0.35);
            }
        }

        // The caster's own view: a shake on top of the whirl
        if (ServerPlayNetworking.canSend(player, ShakePayload.ID)) {
            ServerPlayNetworking.send(player, new ShakePayload(last ? (crit ? 1.0f : 0.7f) : 0.3f, last ? 10 : SPIN_TICKS));
        }
    }

    // ---------------------------------------------------------------------
    // The sword
    // ---------------------------------------------------------------------

    /**
     * A full-strength swing of {@code sword}, before enchantments: in the main hand, everything the player's attack
     * damage counts (Strength, Weakness...); in the off hand, a bare fist plus the sword's own damage.
     */
    public static double swordDamage(LivingEntity player, ItemStack sword, Hand hand) {
        if (hand == Hand.MAIN_HAND) return player.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        double damage = player.getAttributeBaseValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        AttributeModifiersComponent mods = sword.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        for (AttributeModifiersComponent.Entry entry : mods.modifiers()) {
            if (entry.attribute().matches(EntityAttributes.GENERIC_ATTACK_DAMAGE) && entry.modifier().operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                damage += entry.modifier().value();
            }
        }
        var strength = player.getStatusEffect(StatusEffects.STRENGTH);
        if (strength != null) damage += 3 * (strength.getAmplifier() + 1);
        var weakness = player.getStatusEffect(StatusEffects.WEAKNESS);
        if (weakness != null) damage -= 4 * (weakness.getAmplifier() + 1);
        return Math.max(damage, 0);
    }

    /** How far the spin reaches with {@code sword}: further for a stronger sword, Sharpness and Sweeping Edge. */
    public static double radius(ServerWorld world, LivingEntity player, ItemStack sword, Hand hand) {
        double extra = Math.max(0, swordDamage(player, sword, hand) - WOODEN_SWORD_DAMAGE);
        return BASE_RADIUS + RADIUS_PER_DAMAGE * Math.min(extra, 6)
                + RADIUS_PER_SHARPNESS * level(world, sword, Enchantments.SHARPNESS)
                + RADIUS_PER_SWEEPING * level(world, sword, Enchantments.SWEEPING_EDGE);
    }

    private static int level(ServerWorld world, ItemStack stack, RegistryKey<Enchantment> key) {
        return world.getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(key)
                .map(e -> EnchantmentHelper.getLevel(e, stack)).orElse(0);
    }

    /** For tests: how far into its spins a player is (or -1 if not spinning). */
    public static int spinAge(ServerPlayerEntity player) {
        Spin s = SPINS_UNDERWAY.get(player.getUuid());
        return s == null ? -1 : s.age;
    }

    /** For tests: settles whether the last spin of a spin under way is a critical hit. */
    public static void forceCrit(ServerPlayerEntity player, boolean crit) {
        Spin s = SPINS_UNDERWAY.get(player.getUuid());
        if (s == null) return;
        Spin c = new Spin(s.hand, crit);
        c.age = s.age;
        SPINS_UNDERWAY.put(player.getUuid(), c);
    }
}
