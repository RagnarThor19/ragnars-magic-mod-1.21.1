package net.ragnar.ragnarsmagicmod.gambling;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Gambling: an advanced tome. Hold a stack of something in your other hand and cast: the stack goes onto a
 * wheel of fortune that spins for {@link #SPIN_TICKS} and lands on LOSE (55%), DOUBLE (40%) or the single golden
 * TRIPLE slice (5%). On average the wheel keeps a little, so it can't be farmed for items - it's a gamble, and the
 * house has the edge.
 * <p>
 * <b>Why it can't duplicate anything.</b> The moment you cast, the stack is taken out of your hand and held here, on
 * the server, in {@link #SPINS} - it isn't in your inventory, so there's nothing to drop, swap, stash or lose on death
 * while the wheel turns. The result is decided right then; the wheel the clients draw is only a picture of it. A spin
 * is paid out exactly once, by whichever of these happens first, and each one takes the spin out of the map before it
 * pays anything:
 * <ul>
 *   <li>the wheel stops: the winnings go back to your hand / inventory (or at your feet if it's full);</li>
 *   <li>you die: they drop where you died, like the rest of your things (kept, with keepInventory);</li>
 *   <li>you leave (quit, kick, time out): they go into your inventory just before it's saved
 *       (GamblingPlayerManagerMixin, on PlayerManager.remove);</li>
 *   <li>the server stops: likewise, before the players are saved.</li>
 * </ul>
 * If the server crashes mid-spin the stake is lost - never doubled.
 * <p>
 * What may go on the wheel: stackable things, plus music discs, tomes and enchanted books (so no tools, armour,
 * shulker boxes or bundles), nothing with items inside it, and nothing in the {@code ragnarsmagicmod:cannot_gamble}
 * item tag (server owners can add to it with a datapack). A disc, tome or book pays out as single items, with its
 * enchantments and everything else on it copied.
 * <p>
 * Everything for it lives in this package and {@code mixin/gambling}. To remove it: delete both (and the mixin's line
 * in ragnarsmagicmod.mixins.json), {@code Gambling.register()} in RagnarsMagicMod, {@code GamblingClient.init()} in
 * RagnarsMagicModClient, {@code GAMBLING} in SpellId, {@code models/item/tome_of_gambling.json},
 * {@code data/ragnarsmagicmod/tags/item/cannot_gamble.json} and the {@code gambling} line in en_us.json.
 */
public final class Gambling {
    private Gambling() {}

    public static final int XP_COST = 5;
    public static final int COOLDOWN = 25 * 20;
    /** How long the wheel turns before it stops, in ticks. Clients draw exactly this. */
    public static final int SPIN_TICKS = 90;

    /** What each of the wheel's slices pays: 0 = lost, 2 = doubled, 3 = tripled. 11 lose, 8 double, 1 triple. */
    public static final int[] WHEEL = {3, 0, 2, 0, 2, 0, 2, 0, 2, 0, 0, 2, 0, 2, 0, 2, 0, 2, 0, 0};
    public static final int SLICES = WHEEL.length;

    public static final TagKey<Item> CANNOT_GAMBLE = TagKey.of(RegistryKeys.ITEM, Identifier.of(RagnarsMagicMod.MOD_ID, "cannot_gamble"));

    public static final TomeItem TOME_OF_GAMBLING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_gambling"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.GAMBLING, XP_COST
            ).setCooldown(COOLDOWN)
    );

    /**
     * Server -> the spinner and everyone watching them: player {@code entityId} put {@code bet} on the wheel, which will
     * stop on {@code slice}, {@code land} of the way through it (0 = just in, 1 = nearly out). {@code elapsed} ticks of
     * the spin have already gone by (for players who only now came into view).
     */
    public record SpinPayload(int entityId, ItemStack bet, int slice, float land, int elapsed) implements CustomPayload {
        public static final Id<SpinPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "gambling_spin"));
        public static final PacketCodec<RegistryByteBuf, SpinPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, SpinPayload::entityId,
                ItemStack.PACKET_CODEC, SpinPayload::bet,
                PacketCodecs.VAR_INT, SpinPayload::slice,
                PacketCodecs.FLOAT, SpinPayload::land,
                PacketCodecs.VAR_INT, SpinPayload::elapsed,
                SpinPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** A stake on the wheel. It exists only here until it's paid out. */
    private static final class Spin {
        final ItemStack bet;
        final Hand hand;
        final int slice;
        final float land;
        int elapsed;

        Spin(ItemStack bet, Hand hand, int slice, float land) {
            this.bet = bet;
            this.hand = hand;
            this.slice = slice;
            this.land = land;
        }

        int payout() {
            return bet.getCount() * WHEEL[slice];
        }
    }

    private static final Map<UUID, Spin> SPINS = new HashMap<>();

    /** Game tests only: the slice the next spin lands on (-1 = left to chance). */
    public static int forcedSlice = -1;

    public static boolean isSpinning(ServerPlayerEntity player) {
        return SPINS.containsKey(player.getUuid());
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.GAMBLING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_GAMBLING);
        Spells.register(SpellId.GAMBLING, new GamblingSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries ->
                entries.addAfter(net.ragnar.ragnarsmagicmod.astral.AstralProjection.TOME_OF_ASTRAL_PROJECTION, TOME_OF_GAMBLING)); // end of the advanced tomes

        PayloadTypeRegistry.playS2C().register(SpinPayload.ID, SpinPayload.CODEC);
        ServerTickEvents.END_SERVER_TICK.register(Gambling::tick);

        // Died mid-spin: the winnings fall with the rest of your things
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) {
                Spin s = SPINS.remove(player.getUuid());
                if (s != null) payOnDeath(player, s);
            }
        });
        // Players who come into view mid-spin see the rest of it
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            Spin s = SPINS.get(entity.getUuid());
            if (s != null && ServerPlayNetworking.canSend(viewer, SpinPayload.ID)) {
                ServerPlayNetworking.send(viewer, payload(entity.getId(), s));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID id : new ArrayList<>(SPINS.keySet())) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                Spin s = SPINS.remove(id);
                if (p != null && s != null) pay(p, s);
            }
            SPINS.clear();
        });
    }

    /** The unstackable things the wheel takes anyway: music discs, tomes and enchanted books (none can hold items). */
    static boolean unstackableButWelcome(ItemStack stack) {
        return stack.isOf(Items.ENCHANTED_BOOK)
                || stack.getItem() instanceof TomeItem
                || stack.contains(DataComponentTypes.JUKEBOX_PLAYABLE);
    }

    /** Why {@code stack} can't go on the wheel, or null if it can. */
    static String refusal(ItemStack stack) {
        if (stack.isEmpty()) return "Hold what you want to bet in your other hand.";
        if (stack.isIn(CANNOT_GAMBLE)) return "The wheel won't take that.";
        if (!unstackableButWelcome(stack) && (stack.getMaxCount() <= 1 || stack.isDamageable())) {
            return "Only stackable things, music discs, tomes and enchanted books can go on the wheel.";
        }
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if ((container != null && container.stream().findAny().isPresent()) || (bundle != null && !bundle.isEmpty())) {
            return "Empty it first.";
        }
        return null;
    }

    /** Put the stack in your other hand on the wheel. */
    static boolean cast(ServerWorld world, ServerPlayerEntity player, ItemStack staff) {
        if (SPINS.containsKey(player.getUuid())) {
            player.sendMessage(Text.literal("The wheel is still spinning.").formatted(Formatting.GRAY), true);
            return false;
        }
        Hand hand = player.getMainHandStack() == staff ? Hand.OFF_HAND : Hand.MAIN_HAND;
        ItemStack held = player.getStackInHand(hand);
        String why = refusal(held);
        if (why != null) {
            player.sendMessage(Text.literal(why).formatted(Formatting.GRAY), true);
            return false;
        }

        // Out of your hand and onto the wheel - from here on it only exists in SPINS
        ItemStack bet = held.copy();
        player.setStackInHand(hand, ItemStack.EMPTY);

        // Decide it now: 55% lose, 40% double, 5% triple
        Random r = world.random;
        float roll = r.nextFloat();
        int want = roll < 0.05f ? 3 : roll < 0.45f ? 2 : 0;
        List<int[]> matches = new ArrayList<>();
        for (int i = 0; i < SLICES; i++) if (WHEEL[i] == want) matches.add(new int[]{i});
        int slice = matches.get(r.nextInt(matches.size()))[0];
        if (forcedSlice >= 0) {
            slice = forcedSlice;
            forcedSlice = -1;
        }
        // Where in the slice it stops; often agonisingly close to an edge
        float land = r.nextFloat() < 0.45f
                ? (r.nextBoolean() ? 0.04f + r.nextFloat() * 0.1f : 0.86f + r.nextFloat() * 0.1f)
                : 0.15f + r.nextFloat() * 0.7f;

        Spin spin = new Spin(bet, hand, slice, land);
        SPINS.put(player.getUuid(), spin);
        SpinPayload payload = payload(player.getId(), spin);
        if (ServerPlayNetworking.canSend(player, SpinPayload.ID)) ServerPlayNetworking.send(player, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(player)) {
            if (p != player && ServerPlayNetworking.canSend(p, SpinPayload.ID)) ServerPlayNetworking.send(p, payload);
        }
        return true;
    }

    private static SpinPayload payload(int entityId, Spin s) {
        return new SpinPayload(entityId, s.bet.copy(), s.slice, s.land, s.elapsed);
    }

    private static void tick(MinecraftServer server) {
        if (SPINS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Spin>> it = SPINS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Spin> entry = it.next();
            Spin s = entry.getValue();
            if (++s.elapsed < SPIN_TICKS) continue;
            it.remove();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                // Can't happen: leaving pays out first (onPlayerLeaving). Never pay twice, so just say so.
                RagnarsMagicMod.LOGGER.warn("Tome of Gambling: a spin ended with its player gone; stake dropped");
                continue;
            }
            pay(player, s);
        }
    }

    /** PlayerManager.remove, just before the player is saved: settle their spin now. */
    public static void onPlayerLeaving(ServerPlayerEntity player) {
        Spin s = SPINS.remove(player.getUuid());
        if (s != null) pay(player, s);
    }

    /** The winnings back to the hand they came from (if it's free), then the inventory, then the floor. */
    private static void pay(ServerPlayerEntity player, Spin s) {
        boolean first = true;
        for (ItemStack stack : split(s)) {
            if (first && player.isAlive() && player.getStackInHand(s.hand).isEmpty()) {
                player.setStackInHand(s.hand, stack);
            } else {
                player.getInventory().offerOrDrop(stack);
            }
            first = false;
        }
    }

    private static void payOnDeath(ServerPlayerEntity player, Spin s) {
        ServerWorld world = player.getServerWorld();
        boolean keep = world.getGameRules().getBoolean(GameRules.KEEP_INVENTORY);
        for (ItemStack stack : split(s)) {
            if (keep) player.getInventory().insertStack(stack);
            if (!stack.isEmpty()) ItemScatterer.spawn(world, player.getX(), player.getY(), player.getZ(), stack);
        }
    }

    /** The payout, in stacks no bigger than the item allows. */
    private static List<ItemStack> split(Spin s) {
        List<ItemStack> out = new ArrayList<>();
        int left = s.payout();
        int max = Math.max(1, s.bet.getMaxCount());
        while (left > 0) {
            int n = Math.min(left, max);
            out.add(s.bet.copyWithCount(n));
            left -= n;
        }
        return out;
    }
}
