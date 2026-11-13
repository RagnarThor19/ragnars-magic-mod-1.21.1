package net.ragnar.ragnarsmagicmod.astral;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Astral Projection: an advanced tome. Your spirit slips out of your body and drifts free for
 * {@link #DURATION_TICKS} - through walls, over rooftops, round the corner of that cave - tied back to your body by a
 * glowing amethyst cord that won't stretch past {@link #LEASH} blocks. The world goes pale and violet, and every living
 * thing near you glows through the stone: red for monsters, green for animals, gold for players. Your body stays
 * behind, standing still and defenceless, with arcane runes drifting into it. When the time is up (or you cast again)
 * the cord reels you back in; if anything hurts your body, you're yanked back at once.
 * <p>
 * The server only keeps the clock and the body; the spirit itself - flight, camera, cord, colours and sounds - lives
 * on the caster's client (AstralClient). Nothing about the world changes, so there is nothing to sync to anyone else.
 * Everything for it lives in this package, plus the mixins in {@code mixin/astral} and
 * {@code assets/ragnarsmagicmod/shaders/post/astral.json}.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code astral} and {@code astral/client}) and {@code mixin/astral} (and their lines in
 *       ragnarsmagicmod.mixins.json)</li>
 *   <li>delete {@code AstralProjection.register()} in RagnarsMagicMod and {@code AstralClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code ASTRAL_PROJECTION} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_astral_projection.json}, {@code shaders/post/astral.json} and the
 *       {@code astral_projection} line in en_us.json</li>
 * </ol>
 */
public final class AstralProjection {
    private AstralProjection() {}

    public static final int DURATION_TICKS = 20 * 7;
    public static final int XP_COST = 16;
    public static final int COOLDOWN = 20 * 30;
    /** How far the spirit can drift from the body before the cord holds it back. */
    public static final double LEASH = 28.0;
    /** Ticks left when the body's heartbeat starts calling you back. */
    public static final int WARN_TICKS = 50;

    public static final TomeItem TOME_OF_ASTRAL_PROJECTION = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_astral_projection"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.ASTRAL_PROJECTION, XP_COST
            ).setCooldown(COOLDOWN) // starts once you're back in your body
    );

    /**
     * Server -> the caster. {@code ticks > 0}: you're out of your body for that many more ticks. {@code ticks == 0}: the
     * cord reels you back in; {@code jolted} if it's because your body was hurt.
     */
    public record StatePayload(int ticks, boolean jolted) implements CustomPayload {
        public static final Id<StatePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "astral_projection"));
        public static final PacketCodec<RegistryByteBuf, StatePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, StatePayload::ticks,
                PacketCodecs.BOOL, StatePayload::jolted,
                StatePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static final class Projection {
        final ServerWorld world;
        int left = DURATION_TICKS;

        Projection(ServerWorld world) {
            this.world = world;
        }
    }

    private static final Map<UUID, Projection> PROJECTING = new HashMap<>();

    /** Is this player's spirit out of their body right now? Server side. */
    public static boolean isProjecting(ServerPlayerEntity player) {
        return PROJECTING.containsKey(player.getUuid());
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.ASTRAL_PROJECTION, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_ASTRAL_PROJECTION);
        Spells.register(SpellId.ASTRAL_PROJECTION, new AstralProjectionSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries ->
                entries.addAfter(net.ragnar.ragnarsmagicmod.geysir.Geysir.TOME_OF_GEYSIR, TOME_OF_ASTRAL_PROJECTION)); // end of the advanced tomes

        PayloadTypeRegistry.playS2C().register(StatePayload.ID, StatePayload.CODEC);
        ServerTickEvents.END_WORLD_TICK.register(AstralProjection::tick);

        // Anything that hurts the empty body yanks the spirit straight back (the hit still lands)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (amount > 0 && entity instanceof ServerPlayerEntity player && PROJECTING.remove(player.getUuid()) != null) {
                finish(player, true);
            }
            return true;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PROJECTING.remove(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PROJECTING.clear());
    }

    /** Leave your body (or, already out of it, go back). */
    static boolean cast(ServerWorld world, ServerPlayerEntity player) {
        if (PROJECTING.remove(player.getUuid()) != null) {
            finish(player, false);
            return true;
        }
        if (net.ragnar.ragnarsmagicmod.util.FairyForm.isFairy(player)
                || net.ragnar.ragnarsmagicmod.phantom.Phantom.isPhantom(player)
                || net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell.isPossessing(player)) {
            player.sendMessage(Text.literal("Your spirit is already elsewhere.").formatted(Formatting.GRAY), true);
            return false;
        }

        PROJECTING.put(player.getUuid(), new Projection(world));
        send(player, DURATION_TICKS, false);

        // The spirit slips free: a soft, glassy unravelling, heard by everyone near the body
        Vec3d c = player.getPos().add(0, 1.2, 0);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_CHISELED_BOOKSHELF_PICKUP_ENCHANTED, SoundCategory.PLAYERS, 1.0f, 0.75f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_LARGE_AMETHYST_BUD_BREAK, SoundCategory.PLAYERS, 1.0f, 0.6f);
        // Everyone else sees it burst out of you (the caster's view is still inside that head)
        others(world, player, ParticleTypes.REVERSE_PORTAL, c, 40, 0.3, 0.6, 0.06);
        others(world, player, ParticleTypes.END_ROD, c.add(0, 0.5, 0), 8, 0.2, 0.3, 0.03);
        return true;
    }

    private static void tick(ServerWorld world) {
        if (PROJECTING.isEmpty()) return;
        Iterator<Map.Entry<UUID, Projection>> it = PROJECTING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Projection> entry = it.next();
            Projection p = entry.getValue();
            if (p.world != world) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!player.isAlive() || player.getWorld() != world || --p.left <= 0) {
                it.remove();
                finish(player, false);
                continue;
            }

            // The empty body: purple motes rising off it, and runes drifting in to keep its place
            Vec3d c = player.getPos().add(0, 1.0, 0);
            if (p.left % 2 == 0) {
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y - 0.6, c.z, 1, 0.25, 0.1, 0.25, 0.01);
            }
            if (p.left % 4 == 0) {
                double a = p.left * 0.7;
                // Count 0: the "offset" is where the rune starts from; it flies in to the chest
                world.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y, c.z, 0,
                        Math.cos(a) * 1.4, 0.4 + world.random.nextDouble() * 0.6, Math.sin(a) * 1.4, 1.0);
            }
            if (p.left % 25 == 0) {
                world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SMALL_AMETHYST_BUD_PLACE, SoundCategory.PLAYERS,
                        0.5f, 1.2f + world.random.nextFloat() * 0.5f);
            }
        }
    }

    /** Back into the body; the cooldown starts now. */
    private static void finish(ServerPlayerEntity player, boolean jolted) {
        send(player, 0, jolted);
        ServerWorld world = player.getServerWorld();
        Vec3d c = player.getPos().add(0, 1.2, 0);
        // A deep thump as the spirit lands back in, and a breath
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_LAND, SoundCategory.PLAYERS, 1.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_MEDIUM_AMETHYST_BUD_PLACE, SoundCategory.PLAYERS, 1.0f, 0.55f);
        if (jolted) {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ELDER_GUARDIAN_AMBIENT_LAND, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
        others(world, player, ParticleTypes.REVERSE_PORTAL, c, 30, 0.25, 0.5, 0.02);
        others(world, player, ParticleTypes.END_ROD, c, 10, 0.3, 0.5, 0.05);

        TomeItem tome = TOME_OF_ASTRAL_PROJECTION;
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(world, player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    /** Particles for everyone but the caster. */
    private static void others(ServerWorld world, ServerPlayerEntity caster, ParticleEffect effect, Vec3d at, int count,
                               double spread, double spreadY, double speed) {
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer != caster) {
                world.spawnParticles(viewer, effect, false, at.x, at.y, at.z, count, spread, spreadY, spread, speed);
            }
        }
    }

    private static void send(ServerPlayerEntity player, int ticks, boolean jolted) {
        if (ServerPlayNetworking.canSend(player, StatePayload.ID)) {
            ServerPlayNetworking.send(player, new StatePayload(ticks, jolted));
        }
    }
}
