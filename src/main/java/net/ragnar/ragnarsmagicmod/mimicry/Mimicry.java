package net.ragnar.ragnarsmagicmod.mimicry;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;

/**
 * Tome of Mimicry: look at a block and become it. You snap to the block grid where you stand and turn into a perfect
 * copy of that block (a crafting table, a barrel, a chest, a bit of stone wall...). Your body, armor, held items, name
 * and shadow vanish for everyone, you included, and mobs lose track of you. Move, attack or get hurt and you pop back
 * out. Cast again to drop it yourself. With the tome selected, right-clicking a chest copies it rather than opening it. Lasts up to {@link #DURATION} ticks; the cooldown starts when you reappear.
 * Prop hunt, in survival.
 * <p>
 * MimicrySpell runs it on the server; MimicryClient hides the disguised players' bodies. The block itself is a
 * display entity, so it needs nothing else on the client.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code mimicry} and {@code mimicry/client})</li>
 *   <li>delete {@code Mimicry.register()} in RagnarsMagicMod and {@code MimicryClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete the {@code Mimicry} lines in MobEntityMixin, LivingEntityMixin and EntityRenderDispatcherMixin</li>
 *   <li>delete {@code MIMICRY} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_mimicry.json} and the {@code tome_of_mimicry} line in en_us.json</li>
 * </ol>
 */
public final class Mimicry {
    private Mimicry() {}

    /** Two minutes. */
    public static final int DURATION = 20 * 120;
    public static final int XP_COST = 5;
    public static final int COOLDOWN = 20 * 4;
    public static final double REACH = 6.0;

    public static final TomeItem TOME_OF_MIMICRY = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_mimicry"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.MIMICRY, XP_COST
            ).setCooldown(COOLDOWN) // from when you reappear
    );

    /** Server -> everyone who can see them: player {@code entityId} is (or no longer is) disguised as a block. */
    public record DisguisePayload(int entityId, boolean disguised) implements CustomPayload {
        public static final Id<DisguisePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "mimicry"));
        public static final PacketCodec<RegistryByteBuf, DisguisePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, DisguisePayload::entityId,
                PacketCodecs.BOOL, DisguisePayload::disguised,
                DisguisePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void broadcast(ServerPlayerEntity player, boolean disguised) {
        DisguisePayload payload = new DisguisePayload(player.getId(), disguised);
        if (ServerPlayNetworking.canSend(player, DisguisePayload.ID)) ServerPlayNetworking.send(player, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(player)) {
            if (p != player && ServerPlayNetworking.canSend(p, DisguisePayload.ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    /** True while {@code entity} is a disguised player (server side; mobs ask this before picking a target). */
    public static boolean isDisguised(Entity entity) {
        return entity instanceof PlayerEntity p && !p.getWorld().isClient && MimicrySpell.isDisguised(p);
    }

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.MIMICRY, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_MIMICRY);
        Spells.register(SpellId.MIMICRY, new MimicrySpell());
        PayloadTypeRegistry.playS2C().register(DisguisePayload.ID, DisguisePayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Bubbles.TOME_OF_BUBBLES, TOME_OF_MIMICRY));

        ServerTickEvents.END_SERVER_TICK.register(MimicrySpell::tick);
        // Players who walk into view have to be told, or they'd see a person standing in the block
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (entity instanceof ServerPlayerEntity sp && MimicrySpell.isDisguised(sp) && ServerPlayNetworking.canSend(viewer, DisguisePayload.ID)) {
                ServerPlayNetworking.send(viewer, new DisguisePayload(sp.getId(), true));
            }
        });
        // Getting hurt or swinging at something gives you away
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (entity instanceof PlayerEntity p && MimicrySpell.isDisguised(p)) MimicrySpell.reveal(p, true);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, target, hit) -> {
            if (!world.isClient && MimicrySpell.isDisguised(player)) MimicrySpell.reveal(player, true);
            return ActionResult.PASS;
        });
        // With the tome selected, right-clicking a chest or crafting table copies it instead of opening it (on both
        // sides: the client sends the click on, and the server casts). On cooldown, blocks open as usual.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof StaffItem staff) || StaffItem.getSelectedTome(stack) != TOME_OF_MIMICRY) return ActionResult.PASS;
            if (player.getItemCooldownManager().isCoolingDown(TOME_OF_MIMICRY) && !(!world.isClient && MimicrySpell.isDisguised(player))) {
                return ActionResult.PASS;
            }
            staff.use(world, player, hand);
            return ActionResult.SUCCESS;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MimicrySpell.drop(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> MimicrySpell.clear());
    }
}
