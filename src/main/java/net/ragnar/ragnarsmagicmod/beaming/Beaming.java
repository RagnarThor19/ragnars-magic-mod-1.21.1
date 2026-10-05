package net.ragnar.ragnarsmagicmod.beaming;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.logs.Logs;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

/**
 * Tome of Beaming: hold right-click to fire a solid, spinning, square beam - Guardian-style, but yours - that burns
 * {@link #DAMAGE_PER_SECOND} a second into whatever it touches. You get {@link #FUEL_TICKS} ticks of beam per charge
 * and can spend them in as many bursts as you like; the beam runs hotter (teal to orange) as the charge runs down.
 * Only once it's all spent does the tome overheat and go on cooldown. A fresh charge costs the tome's XP; carrying on
 * with a part-spent one is free. Everything for it lives in this package.
 * <p>
 * The staff treats it as a held spell (see ChanneledSpell); BeamingSpell runs it on the server and BeamingClient
 * draws the beam and plays its hum.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code beaming} and {@code beaming/client})</li>
 *   <li>delete {@code Beaming.register()} in RagnarsMagicMod and {@code BeamingClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code BEAMING} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_beaming.json} and the {@code beaming} line in en_us.json</li>
 * </ol>
 */
public final class Beaming {
    private Beaming() {}

    /** 3.5 seconds of beam per charge. */
    public static final int FUEL_TICKS = 70;
    public static final float DAMAGE_PER_SECOND = 16f;
    /** It hurts in pulses this many ticks apart, so 16 a second is 4 every 5 ticks. */
    public static final int HIT_EVERY = 5;
    public static final double RANGE = 32.0;

    public static final TomeItem TOME_OF_BEAMING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_beaming"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.BEAMING, 15
            ).setCooldown(20 * 12) // 12 seconds, from when the charge runs out
    );

    /** Server -> the caster and everyone who can see them: {@code entityId} started or stopped beaming, this hot (0..1). */
    public record BeamPayload(int entityId, boolean firing, float heat) implements CustomPayload {
        public static final Id<BeamPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "beaming_beam"));
        public static final PacketCodec<RegistryByteBuf, BeamPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, BeamPayload::entityId,
                PacketCodecs.BOOL, BeamPayload::firing,
                PacketCodecs.FLOAT, BeamPayload::heat,
                BeamPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> the caster: this many ticks of beam left in the charge. */
    public record FuelPayload(int fuel) implements CustomPayload {
        public static final Id<FuelPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "beaming_fuel"));
        public static final PacketCodec<RegistryByteBuf, FuelPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, FuelPayload::fuel, FuelPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void broadcast(ServerPlayerEntity caster, boolean firing, float heat) {
        BeamPayload payload = new BeamPayload(caster.getId(), firing, heat);
        if (ServerPlayNetworking.canSend(caster, BeamPayload.ID)) ServerPlayNetworking.send(caster, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(caster)) {
            if (p != caster && ServerPlayNetworking.canSend(p, BeamPayload.ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    static void sendFuel(ServerPlayerEntity caster, int fuel) {
        if (ServerPlayNetworking.canSend(caster, FuelPayload.ID)) ServerPlayNetworking.send(caster, new FuelPayload(fuel));
    }

    /** Where the beam leaves the caster: just ahead of them, low and to the side of their view, where the staff is. */
    public static Vec3d origin(PlayerEntity player, float tickDelta) {
        Vec3d eye = player.getCameraPosVec(tickDelta);
        Vec3d look = player.getRotationVec(tickDelta);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        double side = player.getMainArm() == net.minecraft.util.Arm.RIGHT ? 1 : -1;
        return eye.add(look.multiply(0.45)).add(right.multiply(0.3 * side)).add(up.multiply(-0.22));
    }

    /** What the beam hits: a block, an entity, or nothing out to {@link #RANGE} (a MISS at the far end). */
    public static HitResult trace(World world, PlayerEntity player, float tickDelta) {
        Vec3d eye = player.getCameraPosVec(tickDelta);
        Vec3d look = player.getRotationVec(tickDelta);
        Vec3d far = eye.add(look.multiply(RANGE));
        BlockHitResult block = world.raycast(new RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = block.getType() == HitResult.Type.MISS ? far : block.getPos();
        Box sweep = player.getBoundingBox().stretch(look.multiply(eye.distanceTo(end))).expand(1.0);
        EntityHitResult entity = ProjectileUtil.raycast(player, eye, end, sweep, e -> canHit(e, player), eye.squaredDistanceTo(end));
        return entity != null ? entity : block;
    }

    static boolean canHit(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || !le.canHit() || e instanceof ArmorStandEntity) return false;
        if (owner != null) {
            if (e == owner || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && owner.getUuid().equals(pet.getOwnerUuid())) return false;
            if (e.isTeammate(owner)) return false;
        }
        return true;
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BEAMING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_BEAMING);
        Spells.register(SpellId.BEAMING, new BeamingSpell());
        PayloadTypeRegistry.playS2C().register(BeamPayload.ID, BeamPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(FuelPayload.ID, FuelPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Logs.TOME_OF_LOGS, TOME_OF_BEAMING));
        ServerTickEvents.END_SERVER_TICK.register(BeamingSpell::sweep);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BeamingSpell.forget(handler.getPlayer()));
    }
}
