package net.ragnar.ragnarsmagicmod.phantom;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of the Phantom: you leave your body behind as a spectre for {@link #DURATION_TICKS} and fly - the Tome of the
 * Fairy's big brother. Flight is far faster and goes exactly where you steer it, with no drift (hold sprint to surge
 * faster still); it's simulated on the caster's client (PhantomClient) and watched from just behind, like the fairy
 * and the dragon. While you're a spectre nothing can hurt you (but /kill and the void still work), you drift straight
 * through blocks, and you can't touch anything: no hitting, breaking, placing or using. When the time runs out - or
 * you cast again to turn back early - you're solid again right where you are. Inside a wall, that means suffocating
 * until you dig yourself out, so mind the countdown. The tome's cooldown starts once you're solid again.
 * Everything for it lives in this package, plus the mixins in {@code mixin/phantom}.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code phantom} and {@code phantom/client}) and {@code mixin/phantom} (and their lines
 *       in ragnarsmagicmod.mixins.json)</li>
 *   <li>delete {@code Phantom.register()} in RagnarsMagicMod and {@code PhantomClient.init()} in RagnarsMagicModClient,
 *       and the phantom check in FairySpell</li>
 *   <li>delete {@code PHANTOM} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_the_phantom.json} and the {@code phantom} line in en_us.json</li>
 * </ol>
 */
public final class Phantom {
    private Phantom() {}

    public static final int DURATION_TICKS = 20 * 8;
    public static final int XP_COST = 30;
    public static final int COOLDOWN = 20 * 120;
    /** Ticks left when the countdown warnings start. */
    public static final int WARN_TICKS = 60;

    public static final TomeItem TOME_OF_THE_PHANTOM = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_the_phantom"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.PHANTOM, XP_COST
            ).setCooldown(COOLDOWN) // starts when you're solid again
    );

    /** Server -> everyone who can see them: player {@code entityId} is a spectre for {@code ticks} more ticks (0 = solid again). */
    public record StatePayload(int entityId, int ticks) implements CustomPayload {
        public static final Id<StatePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "phantom"));
        public static final PacketCodec<RegistryByteBuf, StatePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, StatePayload::entityId,
                PacketCodecs.VAR_INT, StatePayload::ticks,
                StatePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static final class Spectre {
        final ServerWorld world;
        int left = DURATION_TICKS;
        boolean inWall;

        Spectre(ServerWorld world) {
            this.world = world;
        }
    }

    private static final Map<UUID, Spectre> SPECTRES = new HashMap<>();
    /** Who is a spectre right now, server side by UUID (the same as SPECTRES' keys) and client side by entity id. */
    private static final Set<UUID> SERVER = new HashSet<>();
    public static final Set<Integer> CLIENT = new HashSet<>();

    public static boolean isPhantom(Entity entity) {
        if (!(entity instanceof PlayerEntity)) return false;
        return entity.getWorld().isClient ? CLIENT.contains(entity.getId()) : SERVER.contains(entity.getUuid());
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.PHANTOM, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_THE_PHANTOM);
        Spells.register(SpellId.PHANTOM, new PhantomSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Lunging.TOME_OF_LUNGING, TOME_OF_THE_PHANTOM));

        PayloadTypeRegistry.playS2C().register(StatePayload.ID, StatePayload.CODEC);
        ServerTickEvents.END_WORLD_TICK.register(Phantom::tick);

        // Nothing hurts a spectre (but /kill and the void still work)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !isPhantom(entity) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY));

        // ...and a spectre can't touch anything. Right-clicking with a staff still works, so you can turn back.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> isPhantom(player) ? ActionResult.FAIL : ActionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> isPhantom(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> isPhantom(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                isPhantom(player) && !(player.getStackInHand(hand).getItem() instanceof StaffItem) ? ActionResult.FAIL : ActionResult.PASS);

        // Players who come into view mid-flight need to be told
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            Spectre s = SPECTRES.get(entity.getUuid());
            if (s != null && ServerPlayNetworking.canSend(viewer, StatePayload.ID)) {
                ServerPlayNetworking.send(viewer, new StatePayload(entity.getId(), s.left));
            }
        });
        // Never let a spectre be saved: no gravity would stick to the player
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (SPECTRES.remove(handler.player.getUuid()) != null) restore(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID id : SPECTRES.keySet()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) restore(p);
            }
            SPECTRES.clear();
            SERVER.clear();
        });
    }

    /** Leave your body (or, as a spectre already, turn back). */
    static boolean cast(ServerWorld world, ServerPlayerEntity player) {
        if (SPECTRES.containsKey(player.getUuid())) {
            SPECTRES.remove(player.getUuid());
            finish(player);
            return true;
        }
        if (net.ragnar.ragnarsmagicmod.util.FairyForm.isFairy(player)) {
            player.sendMessage(Text.literal("Not while you're a fairy.").formatted(Formatting.GRAY), true);
            return false;
        }

        SPECTRES.put(player.getUuid(), new Spectre(world));
        SERVER.add(player.getUuid());
        player.setNoGravity(true);
        player.fallDistance = 0;
        player.extinguish();
        broadcast(player, DURATION_TICKS);
        // Rise up out of your body
        player.setVelocity(player.getVelocity().multiply(0.3).add(0, 0.45, 0));
        player.velocityModified = true;

        Vec3d c = player.getPos().add(0, 1.0, 0);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ALLAY_DEATH, SoundCategory.PLAYERS, 1.0f, 0.55f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE, SoundCategory.PLAYERS, 0.45f, 1.9f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SOUL_SAND_BREAK, SoundCategory.PLAYERS, 1.0f, 0.7f);

        // The soul tears free: a column of souls and soul fire bursting up out of you
        world.spawnParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 18, 0.35, 0.6, 0.35, 0.06);
        world.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y + 0.3, c.z, 10, 0.3, 0.5, 0.3, 0.05);
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y - 0.6, c.z, 30, 0.45, 0.1, 0.45, 0.05);
        world.spawnParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 25, 0.4, 0.8, 0.4, 0.05);
        return true;
    }

    private static void tick(ServerWorld world) {
        if (SPECTRES.isEmpty()) return;
        Iterator<Map.Entry<UUID, Spectre>> it = SPECTRES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Spectre> entry = it.next();
            Spectre s = entry.getValue();
            if (s.world != world) continue;
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                SERVER.remove(entry.getKey());
                continue;
            }
            if (!player.isAlive() || player.getWorld() != world || --s.left <= 0) {
                it.remove();
                finish(player);
                continue;
            }

            player.fallDistance = 0;
            player.setFireTicks(0);
            if (!player.hasNoGravity()) player.setNoGravity(true);
            Vec3d c = player.getPos().add(0, 1.0, 0);

            // Slipping into and out of solid blocks
            boolean inWall = insideBlocks(player);
            if (inWall != s.inWall) {
                s.inWall = inWall;
                if (inWall) {
                    world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SCULK_VEIN_PLACE, SoundCategory.PLAYERS, 1.0f, 0.55f);
                    world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SOUL_SAND_STEP, SoundCategory.PLAYERS, 0.8f, 0.6f);
                } else {
                    world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SOUL_SOIL_STEP, SoundCategory.PLAYERS, 0.8f, 1.4f);
                }
                BlockState state = world.getBlockState(BlockPos.ofFloored(c));
                if (!state.isAir()) {
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), c.x, c.y, c.z, 20, 0.35, 0.6, 0.35, 0.1);
                }
                world.spawnParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 4, 0.3, 0.5, 0.3, 0.02);
            }

            // Whispers from the other side now and then
            if (s.left % 70 == 35) {
                world.playSound(null, c.x, c.y, c.z, SoundEvents.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS.value(), SoundCategory.PLAYERS, 0.9f, 0.9f + world.random.nextFloat() * 0.3f);
            }
            if (s.left % 110 == 80) {
                world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PHANTOM_AMBIENT, SoundCategory.PLAYERS, 0.45f, 1.5f + world.random.nextFloat() * 0.3f);
            }
            // The last three seconds: the pull back to the living world builds up
            if (s.left == WARN_TICKS) {
                world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, SoundCategory.PLAYERS, 1.4f, 0.8f);
            }
            if (s.inWall && (s.left == 40 || s.left == 20)) {
                world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_STRAY_AMBIENT, SoundCategory.PLAYERS, 1.0f, s.left == 40 ? 0.8f : 0.6f);
            }
        }
    }

    private static void finish(ServerPlayerEntity player) {
        restore(player);
        broadcast(player, 0);

        ServerWorld world = player.getServerWorld();
        Vec3d c = player.getPos().add(0, 1.0, 0);
        if (insideBlocks(player)) {
            // Solid again, inside the wall
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SKELETON_HORSE_DEATH, SoundCategory.PLAYERS, 1.0f, 0.7f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GRAVEL_HIT, SoundCategory.PLAYERS, 1.2f, 0.5f);
            player.sendMessage(Text.literal("You turned solid inside the wall!").formatted(Formatting.DARK_RED), true);
        } else {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_VEX_DEATH, SoundCategory.PLAYERS, 1.0f, 0.6f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_VAULT_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        }
        world.spawnParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 14, 0.3, 0.6, 0.3, 0.04);
        world.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 8, 0.3, 0.5, 0.3, 0.03);
        world.spawnParticles(ParticleTypes.GLOW, c.x, c.y, c.z, 20, 0.35, 0.7, 0.35, 0.04);

        // Only now does the cooldown start
        TomeItem tome = TOME_OF_THE_PHANTOM;
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(world, player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    /** Back to normal gravity; walls are solid again from the next tick (see PhantomPlayerMixin). */
    private static void restore(ServerPlayerEntity player) {
        SERVER.remove(player.getUuid());
        player.setNoGravity(false);
        player.noClip = false;
    }

    /** Is any of the player's body inside a solid block? */
    public static boolean insideBlocks(PlayerEntity player) {
        Box box = player.getBoundingBox().contract(0.05);
        return !player.getWorld().isSpaceEmpty(player, box);
    }

    private static void broadcast(ServerPlayerEntity player, int ticks) {
        StatePayload payload = new StatePayload(player.getId(), ticks);
        if (ServerPlayNetworking.canSend(player, StatePayload.ID)) ServerPlayNetworking.send(player, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(player)) {
            if (p != player && ServerPlayNetworking.canSend(p, StatePayload.ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    /** Ticks this player has left as a spectre, or 0. Server side. */
    public static int ticksLeft(PlayerEntity player) {
        Spectre s = SPECTRES.get(player.getUuid());
        return s == null ? 0 : s.left;
    }
}
