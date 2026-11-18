package net.ragnar.ragnarsmagicmod.blocktome;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.boulders.Boulders;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

/**
 * Tome of Blocks: copies {@link #COUNT} blocks from around you (they lift out of the ground as glinting enchanted
 * ghosts of themselves, the real blocks stay put) into a small ring that bobs and spins around your head, shedding
 * enchanting-table letters now and then. Each press fires one: a slow, gently homing, tumbling block that does
 * {@link #DAMAGE}. Hold the button and they go in a fast barrage instead. Many blocks carry a trait of what they are,
 * ice freezes, netherrack burns, slime bounces on, TNT explodes... (see BlockTrait). Unfired blocks last
 * {@link #LIFETIME} ticks. Summoning costs the XP; the shots are free; the cooldown starts once the ring is empty.
 * <p>
 * Server-side only apart from one number: the caster's client is told how many blocks they have left, so the staff
 * knows a press is a free shot and not a fresh (paid) summon. The blocks are item displays, so there's no renderer.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code blocktome} and {@code blocktome/client})</li>
 *   <li>delete {@code BlockTome.register()} in RagnarsMagicMod and {@code BlockTomeClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code BLOCKS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_blocks.json} and the {@code tome_of_blocks} line in en_us.json</li>
 * </ol>
 */
public final class BlockTome {
    private BlockTome() {}

    public static final int COUNT = 10;
    public static final float DAMAGE = 8f;
    /** 90 seconds. */
    public static final int LIFETIME = 20 * 90;
    public static final int XP_COST = 12;
    public static final int COOLDOWN = 20 * 25;

    public static final TomeItem TOME_OF_BLOCKS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_blocks"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.BLOCKS, XP_COST
            ).setCooldown(COOLDOWN) // from when the last block is gone
    );

    /** How many blocks the receiving player has orbiting them. */
    public record CountPayload(int count) implements CustomPayload {
        public static final Id<CountPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "blocks_count"));
        public static final PacketCodec<RegistryByteBuf, CountPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, CountPayload::count, CountPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void sendCount(PlayerEntity player, int count) {
        if (player instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, CountPayload.ID)) {
            ServerPlayNetworking.send(sp, new CountPayload(count));
        }
    }

    /** "was pummeled by": counts as the caster's kill. */
    static DamageSource damageSource(ServerWorld world, @Nullable PlayerEntity owner) {
        return owner != null ? world.getDamageSources().thrown(owner, owner) : world.getDamageSources().generic();
    }

    /** Not the caster, their pets, illusions or teammates. */
    static boolean canHit(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof PlayerEntity p && p.isCreative()) return false;
        if (owner != null) {
            if (e == owner || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && owner.getUuid().equals(pet.getOwnerUuid())) return false;
            if (e.isTeammate(owner)) return false;
        }
        return true;
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BLOCKS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_BLOCKS);
        Spells.register(SpellId.BLOCKS, new BlocksSpell());
        PayloadTypeRegistry.playS2C().register(CountPayload.ID, CountPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Boulders.TOME_OF_BOULDERS, TOME_OF_BLOCKS));
        ServerTickEvents.END_WORLD_TICK.register(BlocksSpell::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BlocksSpell.dismiss(handler.getPlayer(), false));
    }
}
