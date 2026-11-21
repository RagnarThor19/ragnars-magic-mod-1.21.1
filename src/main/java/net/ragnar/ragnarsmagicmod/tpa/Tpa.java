package net.ragnar.ragnarsmagicmod.tpa;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;
import net.minecraft.village.VillagerProfession;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of TPA: a passive tome. While it's in your inventory (or on a staff you carry) you can type
 * {@code /tpa <player>}; that player is told you want to teleport to them and has {@link #TIMEOUT} ticks to type
 * {@code /acc} (or click [Accept]). Anyone can accept; only the tome's holder can ask.
 * <p>
 * It's unique: it isn't in ModItems.TOMES, so it never turns up in loot chests or Wandering Wizard swaps. The only
 * way to get one is from librarian villagers, who sell it for {@link #MIN_PRICE}-{@link #MAX_PRICE} diamonds.
 * <p>
 * To remove it: delete this package, {@code Tpa.register()} in RagnarsMagicMod, {@code TPA} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_tpa.json} and its en_us.json line.
 */
public final class Tpa {
    private Tpa() {}

    /** How long a request stays open: 30 seconds. */
    public static final int TIMEOUT = 30 * 20;
    public static final int MIN_PRICE = 8, MAX_PRICE = 15;

    public static final TomeItem TOME_OF_TPA = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_tpa"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.TPA, 0
            ).setCooldown(0)
    );

    /** An open request: {@code requester} wants to go to {@code target}. Keyed by requester, so one each. */
    private record Request(UUID target, long expires) {}

    private static final Map<UUID, Request> REQUESTS = new HashMap<>();

    public static void register() {
        Spells.register(SpellId.TPA, new PassiveSpell("type /tpa <player> to ask to teleport to them; they type /acc to accept."));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("tpa")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(Tpa::runTpa)));
            dispatcher.register(CommandManager.literal("acc").executes(Tpa::runAcc));
        });

        // Tell the asker when nobody answered
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0 || REQUESTS.isEmpty()) return;
            REQUESTS.entrySet().removeIf(e -> {
                if (e.getValue().expires() > server.getTicks()) return false;
                ServerPlayerEntity requester = server.getPlayerManager().getPlayer(e.getKey());
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(e.getValue().target());
                if (requester != null) {
                    String name = target != null ? target.getName().getString() : "them";
                    requester.sendMessage(Text.literal("Your teleport request to " + name + " expired.").formatted(Formatting.GRAY));
                }
                return true;
            });
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> REQUESTS.clear());

        // Novice and apprentice librarians each have a fair chance to sell it
        for (int level = 1; level <= 2; level++) {
            TradeOfferHelper.registerVillagerOffers(VillagerProfession.LIBRARIAN, level, factories ->
                    factories.add((entity, random) -> new TradeOffer(
                            new TradedItem(Items.DIAMOND, random.nextBetween(MIN_PRICE, MAX_PRICE)),
                            new ItemStack(TOME_OF_TPA), 3, 10, 0.05f)));
        }
    }

    private static int runTpa(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity requester = ctx.getSource().getPlayerOrThrow();
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        String error = request(requester, target);
        if (error != null) {
            ctx.getSource().sendError(Text.literal(error));
            return 0;
        }
        return 1;
    }

    private static int runAcc(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        String error = accept(ctx.getSource().getPlayerOrThrow());
        if (error != null) {
            ctx.getSource().sendError(Text.literal(error));
            return 0;
        }
        return 1;
    }

    /** Sends {@code requester}'s request to {@code target}. Returns why it couldn't, or null if it was sent. */
    public static String request(ServerPlayerEntity requester, ServerPlayerEntity target) {
        if (!PassiveTomes.has(requester, TOME_OF_TPA)) return "You need the Tome of TPA in your inventory to use /tpa.";
        if (requester == target) return "You can't teleport to yourself.";

        MinecraftServer server = requester.getServer();
        REQUESTS.put(requester.getUuid(), new Request(target.getUuid(), server.getTicks() + TIMEOUT));

        String name = requester.getName().getString();
        Text accept = Text.literal("[Accept]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/acc"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Let " + name + " teleport to you"))));
        target.sendMessage(Text.literal(name + " wants to teleport to you. Type ").formatted(Formatting.GOLD)
                .append(Text.literal("/acc").formatted(Formatting.YELLOW))
                .append(Text.literal(" within " + TIMEOUT / 20 + " seconds to accept. ").formatted(Formatting.GOLD))
                .append(accept));
        target.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.5f, 1.2f);
        requester.sendMessage(Text.literal("Teleport request sent to " + target.getName().getString() + ".").formatted(Formatting.GOLD));
        return null;
    }

    /** {@code target} accepts the newest open request to them. Returns why it couldn't, or null once it's done. */
    public static String accept(ServerPlayerEntity target) {
        MinecraftServer server = target.getServer();
        UUID newest = null;
        long newestExpires = Long.MIN_VALUE;
        for (Map.Entry<UUID, Request> e : REQUESTS.entrySet()) {
            Request r = e.getValue();
            if (r.target().equals(target.getUuid()) && r.expires() > server.getTicks() && r.expires() > newestExpires) {
                newest = e.getKey();
                newestExpires = r.expires();
            }
        }
        if (newest == null) return "You have no teleport requests.";
        REQUESTS.remove(newest);

        ServerPlayerEntity requester = server.getPlayerManager().getPlayer(newest);
        if (requester == null || requester.isRemoved()) return "That player is no longer online.";

        ServerWorld from = requester.getServerWorld();
        from.playSound(null, requester.getX(), requester.getY(), requester.getZ(),
                SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.0f);
        requester.stopRiding();
        requester.teleport(target.getServerWorld(), target.getX(), target.getY(), target.getZ(), requester.getYaw(), requester.getPitch());
        requester.fallDistance = 0f;
        target.getServerWorld().playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.0f);

        requester.sendMessage(Text.literal(target.getName().getString() + " accepted your teleport request.").formatted(Formatting.GOLD));
        target.sendMessage(Text.literal(requester.getName().getString() + " teleported to you.").formatted(Formatting.GOLD));
        return null;
    }

    /** True if {@code requester} has an open request to {@code target}. For tests. */
    public static boolean pending(ServerPlayerEntity requester, ServerPlayerEntity target) {
        Request r = REQUESTS.get(requester.getUuid());
        return r != null && r.target().equals(target.getUuid()) && r.expires() > requester.getServer().getTicks();
    }
}
