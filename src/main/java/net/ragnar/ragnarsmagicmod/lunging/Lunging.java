package net.ragnar.ragnarsmagicmod.lunging;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.mixin.LivingEntityAttackAccessor;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Tome of Lunging: a passive tome. While it's on a staff you're carrying, tap right-click with a sword to lunge
 * {@link #LUNGE_DISTANCE} blocks forward and stab the first thing in your way with a full-strength swing. Land it and
 * you spring {@link #BACK_DISTANCE} blocks back out of reach; miss and you're left standing where the lunge ended.
 * <p>
 * If your off hand holds something you use with right-click (a shield, food, a bow), holding right-click uses it
 * as always and only a quick tap lunges, when you let go. Holding right-click never lunges more than once. Costs {@link #XP_COST} XP and puts the tome (and your sword's
 * hotbar icon, so you can see it) on a {@link #COOLDOWN} tick cooldown. Everything for it lives in this package,
 * plus LivingEntityAttackAccessor.
 * <p>
 * Movement is the client's to decide, so the client does the lunge (see LungingClient) and tells the server, which
 * checks it, takes the XP, sets the cooldown and lands the stab.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code lunging} and {@code lunging/client}) and LivingEntityAttackAccessor (and its line
 *       in ragnarsmagicmod.mixins.json)</li>
 *   <li>delete {@code Lunging.register()} in RagnarsMagicMod and {@code LungingClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code LUNGING} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_lunging.json} and the {@code lunging} line in en_us.json</li>
 * </ol>
 */
public final class Lunging {
    private Lunging() {}

    public static final double LUNGE_DISTANCE = 4, BACK_DISTANCE = 5;
    public static final int LUNGE_TICKS = 3;
    public static final int XP_COST = 2;
    public static final int COOLDOWN = 20 * 5;
    /** How far from the lunger the server still believes a stab could have landed (allows for a little lag). */
    private static final double MAX_STAB_REACH = 6.0;
    /** How long after starting a lunge the server still accepts its stab. */
    private static final int STAB_WINDOW = 10;

    public static final TomeItem TOME_OF_LUNGING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_lunging"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.LUNGING, XP_COST
            ).setCooldown(COOLDOWN)
    );

    /** Client -> server: I've just lunged. */
    public record StartPayload() implements CustomPayload {
        public static final Id<StartPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "lunge_start"));
        public static final PacketCodec<RegistryByteBuf, StartPayload> CODEC = PacketCodec.unit(new StartPayload());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: my lunge ran into {@code entityId}. */
    public record StabPayload(int entityId) implements CustomPayload {
        public static final Id<StabPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "lunge_stab"));
        public static final PacketCodec<RegistryByteBuf, StabPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, StabPayload::entityId, StabPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Set by LungingClient: handles a right-click that {@link #triggers} a lunge on this side. True if it was taken up
     * (so nothing else happens); false to let it carry on, e.g. to raise a shield.
     */
    public static Predicate<PlayerEntity> clientUse = p -> false;

    /** Server: the tick each player's current lunge started, and whether it has stabbed yet. */
    private static final Map<UUID, int[]> LUNGES = new HashMap<>();

    public static void register() {
        // Lets staffs hand the tome back and puts it in the beginner tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.LUNGING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_LUNGING);
        Spells.register(SpellId.LUNGING, new LungingSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Bubbles.TOME_OF_BUBBLES, TOME_OF_LUNGING));

        PayloadTypeRegistry.playC2S().register(StartPayload.ID, StartPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StabPayload.ID, StabPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartPayload.ID, (payload, context) -> start(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(StabPayload.ID, (payload, context) -> stab(context.player(), payload.entityId()));
        ServerTickEvents.END_SERVER_TICK.register(server -> LUNGES.entrySet().removeIf(e -> server.getTicks() - e.getValue()[0] > STAB_WINDOW * 4));

        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack held = player.getStackInHand(hand);
            if (world.isClient) {
                if (!triggers(player, hand)) return TypedActionResult.pass(held);
                return clientUse.test(player) ? TypedActionResult.consume(held) : TypedActionResult.pass(held);
            }
            // The right-click that came with a lunge: swallow it, so it doesn't raise a shield or the like as well
            int[] lunge = LUNGES.get(player.getUuid());
            boolean justLunged = lunge != null && player.getServer() != null && player.getServer().getTicks() - lunge[0] <= 3;
            return justLunged ? TypedActionResult.success(held) : TypedActionResult.pass(held);
        });
    }

    /** True if this right-click can lunge: a sword in the main hand and the tome on a staff you carry. */
    public static boolean triggers(PlayerEntity player, net.minecraft.util.Hand hand) {
        if (hand != net.minecraft.util.Hand.MAIN_HAND || !(player.getMainHandStack().getItem() instanceof SwordItem)) return false;
        if (player.isSpectator() || player.hasVehicle() || player.getAbilities().flying || player.isFallFlying()) return false;
        return !StaffItem.findStaffWith(player, TOME_OF_LUNGING).isEmpty();
    }

    /**
     * True if right-click also means something for {@code player}'s off hand (a shield, food, a bow): then holding it
     * is for that, and only a quick tap lunges.
     */
    public static boolean tapOnly(PlayerEntity player) {
        return player.getOffHandStack().getUseAction() != UseAction.NONE;
    }

    /** True if {@code player} may lunge right now: off cooldown and able to pay. */
    public static boolean canLunge(PlayerEntity player) {
        if (player.getItemCooldownManager().isCoolingDown(TOME_OF_LUNGING)) return false;
        return player.isCreative() || xpPoints(player) >= XP_COST;
    }

    /** True if a lunge should stab {@code e}. */
    public static boolean canStab(PlayerEntity player, Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || !le.isAttackable() || e == player.getVehicle()) return false;
        return !(e instanceof TameableEntity pet && player.getUuid().equals(pet.getOwnerUuid()));
    }

    /** Server: a lunge has begun. Public so tests can call it. */
    public static void start(ServerPlayerEntity player) {
        if (!player.isAlive() || !(player.getMainHandStack().getItem() instanceof SwordItem)) return;
        if (StaffItem.findStaffWith(player, TOME_OF_LUNGING).isEmpty() || !canLunge(player)) return;
        if (!player.isCreative()) player.addExperience(-XP_COST);
        player.getItemCooldownManager().set(TOME_OF_LUNGING, COOLDOWN);
        player.getItemCooldownManager().set(player.getMainHandStack().getItem(), COOLDOWN); // shown on the sword
        player.incrementStat(net.minecraft.stat.Stats.USED.getOrCreateStat(TOME_OF_LUNGING));
        player.fallDistance = 0;
        LUNGES.put(player.getUuid(), new int[]{player.getServer().getTicks(), 0});
        // The lunger hears their own on their side; this is for everyone else
        player.getServerWorld().playSound(player, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_TRIDENT_THROW.value(),
                SoundCategory.PLAYERS, 0.7f, 1.5f);
    }

    /** Server: the lunge ran into {@code entityId}. Public so tests can call it. */
    public static void stab(ServerPlayerEntity player, int entityId) {
        int[] lunge = LUNGES.get(player.getUuid());
        if (lunge == null || lunge[1] != 0 || player.getServer().getTicks() - lunge[0] > STAB_WINDOW) return;
        Entity target = player.getServerWorld().getEntityById(entityId);
        if (target == null || !canStab(player, target)) return;
        if (target.getBoundingBox().squaredMagnitude(player.getEyePos()) > MAX_STAB_REACH * MAX_STAB_REACH) return;
        lunge[1] = 1;
        // A full-strength swing, with everything a normal one brings: crits, enchantments, knockback, durability
        ((LivingEntityAttackAccessor) player).ragnarsmagicmod$setLastAttackedTicks(100);
        player.attack(target);
        player.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
        player.fallDistance = 0;
        player.getServerWorld().playSound(player, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_BREEZE_JUMP,
                SoundCategory.PLAYERS, 0.4f, 1.7f);
    }

    /** {@code player}'s XP as points, worked out the same way the staff does. */
    private static int xpPoints(PlayerEntity p) {
        int total = 0;
        for (int i = 0; i < p.experienceLevel; i++) total += toNext(i);
        return total + Math.round(p.experienceProgress * toNext(p.experienceLevel));
    }

    private static int toNext(int level) {
        if (level >= 30) return 112 + 9 * (level - 30);
        if (level >= 15) return 37 + 5 * (level - 15);
        return 7 + 2 * level;
    }
}
