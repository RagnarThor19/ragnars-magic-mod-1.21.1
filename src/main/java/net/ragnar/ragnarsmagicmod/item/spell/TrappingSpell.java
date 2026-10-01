package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Trapping. Sets a trap in the block under your feet: a seal of red flesh and little fangs flares up out
 * of the ground, then sinks away and the spot takes on the look of the block it sits in - all that's left is a
 * paper-thin lip only the sharp-eyed will spot (and a faint glimmer only you can see). The first creature to
 * step on it is seized by a jaw nearly three blocks tall that bursts out of the ground and snaps shut on it.
 *
 * <p>One trap at a time. Sneak-cast to discard it (free). The tome's cooldown starts when the trap is sprung.
 */
public class TrappingSpell implements Spell {
    private static final int ARM_TICKS = 20;          // the seal showing before it disguises itself
    private static final int SETTLE_TICKS = 8;        // the seal sinking away / the disguise growing in
    private static final float DAMAGE = 25f;

    // The bite
    private static final int RISE_TICKS = 5;
    private static final int SNAP_TICKS = 2;
    private static final int HOLD_TICKS = 22;
    private static final int SINK_TICKS = 12;
    private static final float OPEN_ANGLE = -78f;     // degrees each half leans out before snapping shut
    private static final double HINGE = 1.35;         // each half's hinge, out from the centre
    private static final double BURIED = -3.6;        // how deep the jaw starts (and ends)
    private static final double BITE_RADIUS = 1.4;

    private static final BlockState SHELL = Blocks.COBBLED_DEEPSLATE.getDefaultState();
    private static final BlockState FLESH = Blocks.NETHER_WART_BLOCK.getDefaultState();
    private static final BlockState FANG = Blocks.POINTED_DRIPSTONE.getDefaultState()
            .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP)
            .with(PointedDripstoneBlock.THICKNESS, Thickness.TIP);

    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.55f, 0.02f, 0.05f), 1.4f);
    private static final DustParticleEffect EMBER = new DustParticleEffect(new Vector3f(0.95f, 0.15f, 0.1f), 0.8f);
    private static final DustParticleEffect GLIMMER = new DustParticleEffect(new Vector3f(0.8f, 0.1f, 0.1f), 0.5f);

    // ------------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------------

    private static final class Trap {
        final ServerWorld world;
        final UUID owner;
        final BlockPos ground;        // the block the trap is set in
        final Vec3d center;           // top-centre of that block
        BlockState disguise;
        int age;
        DisplayEntity.BlockDisplayEntity seal;
        DisplayEntity.BlockDisplayEntity plate;
        final List<DisplayEntity.BlockDisplayEntity> fangs = new ArrayList<>();

        Trap(ServerWorld world, UUID owner, BlockPos ground) {
            this.world = world;
            this.owner = owner;
            this.ground = ground;
            this.center = new Vec3d(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
            this.disguise = world.getBlockState(ground);
        }

        boolean armed() {
            return age >= ARM_TICKS;
        }
    }

    /** One display piece of the jaw: a block whose model box is placed by the half's pose each tick. */
    private record Piece(DisplayEntity.BlockDisplayEntity display, int half, Quaternionf rot, Vector3f origin,
                         Vector3f size, Vector3f anchor) {}

    private static final class Bite {
        final ServerWorld world;
        final UUID owner;
        final Vec3d center;
        final float yaw;
        final List<UUID> victims = new ArrayList<>();
        final List<Piece> pieces = new ArrayList<>();
        int age;

        Bite(ServerWorld world, UUID owner, Vec3d center, float yaw) {
            this.world = world;
            this.owner = owner;
            this.center = center;
            this.yaw = yaw;
        }
    }

    private static final Map<UUID, Trap> TRAPS = new HashMap<>();
    private static final List<Bite> BITES = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(TrappingSpell::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Trap t : TRAPS.values()) removeTrapDisplays(t);
            for (Bite b : BITES) for (Piece p : b.pieces) TempEntities.discard(p.display);
            TRAPS.clear();
            BITES.clear();
        });
    }

    private static boolean hasTrap(PlayerEntity player) {
        return !player.getWorld().isClient && TRAPS.containsKey(player.getUuid());
    }

    // ------------------------------------------------------------------------------------------------
    // Casting
    // ------------------------------------------------------------------------------------------------

    // Discarding is free and works whenever
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return player.isSneaking() && hasTrap(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return player.isSneaking() ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts when the trap is sprung
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Trap existing = TRAPS.get(player.getUuid());

        if (player.isSneaking()) {
            if (existing == null) {
                player.sendMessage(Text.literal("You have no trap set."), true);
                return false;
            }
            TRAPS.remove(player.getUuid());
            discard(existing);
            player.sendMessage(Text.literal("Trap discarded."), true);
            return true;
        }

        if (existing != null) {
            player.sendMessage(Text.literal("You already have a trap set • Sneak-cast to discard it"), true);
            return false;
        }
        if (!player.isOnGround()) {
            player.sendMessage(Text.literal("You need to be standing on solid ground."), true);
            return false;
        }

        BlockPos ground = BlockPos.ofFloored(player.getX(), player.getY() - 0.05, player.getZ());
        BlockState state = sw.getBlockState(ground);
        if (!state.isSideSolidFullSquare(sw, ground, Direction.UP) || state.hasBlockEntity()) {
            player.sendMessage(Text.literal("The trap won't take hold here."), true);
            return false;
        }
        for (Trap t : TRAPS.values()) {
            if (t.world == sw && t.ground.equals(ground)) {
                player.sendMessage(Text.literal("There's already a trap here."), true);
                return false;
            }
        }

        Trap trap = new Trap(sw, player.getUuid(), ground);
        TRAPS.put(player.getUuid(), trap);
        spawnSeal(trap);
        placementFx(trap);
        player.sendMessage(Text.literal("Trap set • Sneak-cast to discard it"), true);
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Setting the trap
    // ------------------------------------------------------------------------------------------------

    private static Brightness lightAt(ServerWorld world, BlockPos pos) {
        return new Brightness(world.getLightLevel(LightType.BLOCK, pos), world.getLightLevel(LightType.SKY, pos));
    }

    private static DisplayEntity.BlockDisplayEntity display(ServerWorld world, Vec3d at, BlockState state) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
        if (d == null) return null;
        d.setBlockState(state);
        d.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        TempEntities.track(d);
        world.spawnEntity(d);
        return d;
    }

    /** Model box [0,1]^3 scaled to {@code size}, with its {@code anchor} point (in box fractions) at {@code at}. */
    private static AffineTransformation box(Vector3f at, Quaternionf rot, Vector3f size, Vector3f anchor) {
        Vector3f offset = new Vector3f(size).mul(anchor);
        rot.transform(offset);
        return new AffineTransformation(new Vector3f(at).sub(offset), new Quaternionf(rot), new Vector3f(size), null);
    }

    private static void spawnSeal(Trap t) {
        ServerWorld w = t.world;
        // A disc of raw red flesh, pulsing in the ground
        t.seal = display(w, t.center, FLESH);
        if (t.seal != null) {
            t.seal.setBrightness(Brightness.FULL);
            t.seal.setTransformation(box(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(0.05f, 0.02f, 0.05f), new Vector3f(0.5f, 0, 0.5f)));
            t.seal.setStartInterpolation(0);
            t.seal.setInterpolationDuration(4);
        }
        // A ring of little fangs poking up through it
        for (int i = 0; i < 8; i++) {
            DisplayEntity.BlockDisplayEntity fang = display(w, t.center, FANG);
            if (fang == null) continue;
            fang.setBrightness(Brightness.FULL);
            fang.setGlowing(true);
            fang.setGlowColorOverride(0xB01818);
            fang.setTransformation(fangPose(i, -0.5f, 0.001f));
            t.fangs.add(fang);
        }
    }

    /** The {@code i}th seal fang, its base at height {@code y} above the trap, scaled by {@code s}. */
    private static AffineTransformation fangPose(int i, float y, float s) {
        double a = i * Math.PI / 4 + Math.PI / 8;
        float r = 0.33f;
        Vector3f base = new Vector3f((float) Math.cos(a) * r, y, (float) Math.sin(a) * r);
        // Leaning outward a touch, like teeth around a mouth
        Quaternionf lean = new Quaternionf().rotateY((float) -a).rotateZ(-0.35f);
        return box(base, lean, new Vector3f(0.35f * s, 0.55f * s, 0.35f * s), new Vector3f(0.5f, 0, 0.5f));
    }

    private static void placementFx(Trap t) {
        ServerWorld w = t.world;
        Vec3d c = t.center;
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.PLAYERS, 0.9f, 0.6f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.PLAYERS, 1.2f, 0.7f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_CROSSBOW_LOADING_END.value(), SoundCategory.PLAYERS, 1.0f, 0.5f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 0.8f, 0.55f);

        BlockStateParticleEffect dirt = new BlockStateParticleEffect(ParticleTypes.BLOCK, t.disguise);
        w.spawnParticles(dirt, c.x, c.y + 0.1, c.z, 30, 0.4, 0.05, 0.4, 0.15);
        w.spawnParticles(ParticleTypes.SOUL, c.x, c.y + 0.2, c.z, 10, 0.3, 0.05, 0.3, 0.03);
        w.spawnParticles(ParticleTypes.CRIMSON_SPORE, c.x, c.y + 0.5, c.z, 40, 0.6, 0.4, 0.6, 0.02);
        // A ring of red light snapping inward
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            double r = 1.8;
            w.spawnParticles(EMBER, c.x + Math.cos(a) * r, c.y + 0.08, c.z + Math.sin(a) * r, 0,
                    -Math.cos(a), 0, -Math.sin(a), 0.12);
            w.spawnParticles(BLOOD, c.x + Math.cos(a) * 0.9, c.y + 0.05, c.z + Math.sin(a) * 0.9, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------

    private static void tick(ServerWorld world) {
        if (!TRAPS.isEmpty()) {
            Iterator<Trap> it = TRAPS.values().iterator();
            while (it.hasNext()) {
                Trap t = it.next();
                if (t.world != world || !world.isChunkLoaded(t.ground)) continue;
                if (!tickTrap(t)) it.remove();
            }
        }
        if (!BITES.isEmpty()) {
            BITES.removeIf(b -> b.world == world && !tickBite(b));
        }
    }

    /** Returns false once the trap is gone (sprung, or its ground broken). */
    private static boolean tickTrap(Trap t) {
        ServerWorld w = t.world;
        int age = ++t.age;
        Vec3d c = t.center;

        // Lost its footing: the trap crumbles
        BlockState now = w.getBlockState(t.ground);
        if (!now.isSideSolidFullSquare(w, t.ground, Direction.UP)) {
            fizzle(t);
            return false;
        }
        if (now != t.disguise) {
            t.disguise = now;
            if (t.plate != null) t.plate.setBlockState(now);
        }

        if (age == 1 && t.seal != null) {
            // The seal spreads out across the block
            t.seal.setTransformation(box(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(0.9f, 0.025f, 0.9f), new Vector3f(0.5f, 0, 0.5f)));
            t.seal.setStartInterpolation(0);
            t.seal.setInterpolationDuration(5);
            for (int i = 0; i < t.fangs.size(); i++) {
                DisplayEntity.BlockDisplayEntity f = t.fangs.get(i);
                f.setTransformation(fangPose(i, 0.0f, 1f));
                f.setStartInterpolation(0);
                f.setInterpolationDuration(4 + i % 3);
            }
        }

        if (age < ARM_TICKS) {
            // Pulsing and spitting embers while it beds in
            if (age % 2 == 0) {
                double spin = age * 0.35;
                for (int i = 0; i < 3; i++) {
                    double a = spin + i * Math.PI * 2 / 3;
                    w.spawnParticles(EMBER, c.x + Math.cos(a) * 0.6, c.y + 0.1, c.z + Math.sin(a) * 0.6, 1, 0, 0.05, 0, 0);
                }
                w.spawnParticles(ParticleTypes.CRIMSON_SPORE, c.x, c.y + 0.3, c.z, 3, 0.3, 0.2, 0.3, 0);
            }
            if (age % 6 == 0) {
                w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SCULK_SENSOR_CLICKING, SoundCategory.PLAYERS, 0.5f, 0.6f);
            }
            return true;
        }

        if (age == ARM_TICKS) {
            // The fangs and the flesh sink away; the ground's own look grows over them
            for (int i = 0; i < t.fangs.size(); i++) {
                DisplayEntity.BlockDisplayEntity f = t.fangs.get(i);
                f.setGlowing(false);
                f.setTransformation(fangPose(i, -0.6f, 0.6f));
                f.setStartInterpolation(0);
                f.setInterpolationDuration(SETTLE_TICKS);
            }
            if (t.seal != null) {
                t.seal.setTransformation(box(new Vector3f(0, -0.03f, 0), new Quaternionf(), new Vector3f(0.6f, 0.02f, 0.6f), new Vector3f(0.5f, 0, 0.5f)));
                t.seal.setStartInterpolation(0);
                t.seal.setInterpolationDuration(SETTLE_TICKS);
            }
            t.plate = display(w, c, t.disguise);
            if (t.plate != null) {
                t.plate.setBrightness(lightAt(w, t.ground.up()));
                t.plate.setTransformation(box(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(0.2f, 0.001f, 0.2f), new Vector3f(0.5f, 0, 0.5f)));
            }
            w.playSound(null, c.x, c.y, c.z, t.disguise.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 0.6f);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SCULK_CATALYST_BREAK, SoundCategory.PLAYERS, 0.6f, 0.6f);
        }
        if (age == ARM_TICKS + 1 && t.plate != null) {
            // Barely thicker than paper - a faint lip around the edge is the only giveaway
            t.plate.setTransformation(box(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(1.002f, 0.012f, 1.002f), new Vector3f(0.5f, 0, 0.5f)));
            t.plate.setStartInterpolation(0);
            t.plate.setInterpolationDuration(SETTLE_TICKS);
        }
        if (age > ARM_TICKS && age < ARM_TICKS + SETTLE_TICKS) {
            BlockStateParticleEffect dust = new BlockStateParticleEffect(ParticleTypes.BLOCK, t.disguise);
            w.spawnParticles(dust, c.x, c.y + 0.05, c.z, 4, 0.3, 0.02, 0.3, 0.02);
        }
        if (age == ARM_TICKS + SETTLE_TICKS) {
            for (DisplayEntity.BlockDisplayEntity f : t.fangs) TempEntities.discard(f);
            t.fangs.clear();
            if (t.seal != null) TempEntities.discard(t.seal);
            t.seal = null;
            w.spawnParticles(ParticleTypes.SMOKE, c.x, c.y + 0.05, c.z, 6, 0.25, 0.01, 0.25, 0.005);
        }

        // Every so often, a tiny glimmer only the owner can see, so they can find it again
        if (age % 30 == 0) {
            if (w.getPlayerByUuid(t.owner) instanceof ServerPlayerEntity owner && owner.getWorld() == w) {
                w.spawnParticles(owner, GLIMMER, false, c.x, c.y + 0.08, c.z, 2, 0.2, 0.0, 0.2, 0);
            }
        }

        LivingEntity victim = findVictim(t);
        if (victim != null) {
            spring(t, victim);
            return false;
        }
        return true;
    }

    private static LivingEntity findVictim(Trap t) {
        Vec3d c = t.center;
        Box top = new Box(c.x - 0.45, c.y - 0.1, c.z - 0.45, c.x + 0.45, c.y + 0.6, c.z + 0.45);
        PlayerEntity owner = t.world.getPlayerByUuid(t.owner);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : t.world.getEntitiesByClass(LivingEntity.class, top, e -> e.isAlive() && !e.isSpectator())) {
            if (isFriendly(e, owner, t.owner) || e instanceof ArmorStandEntity) continue;
            if (e.getY() > c.y + 0.3) continue; // flying or jumping over it
            double d = e.squaredDistanceTo(c.x, e.getY(), c.z);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean isFriendly(Entity e, PlayerEntity owner, UUID ownerId) {
        if (e.getUuid().equals(ownerId) || e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return true;
        return owner != null && e.isTeammate(owner);
    }

    // ------------------------------------------------------------------------------------------------
    // Springing
    // ------------------------------------------------------------------------------------------------

    private static void spring(Trap t, LivingEntity victim) {
        ServerWorld w = t.world;
        Vec3d c = t.center;
        removeTrapDisplays(t);

        Bite b = new Bite(w, t.owner, c, victim.getBodyYaw());
        b.victims.add(victim.getUuid());
        buildJaw(b, lightAt(w, t.ground.up()));
        BITES.add(b);
        hold(b, victim);

        // The ground cracks open
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.6f, 1.4f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1.4f, 0.6f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        BlockStateParticleEffect debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, t.disguise);
        w.spawnParticles(debris, c.x, c.y + 0.1, c.z, 60, 0.8, 0.1, 0.8, 0.3);
        w.spawnParticles(ParticleTypes.DUST_PLUME, c.x, c.y + 0.1, c.z, 12, 0.8, 0.05, 0.8, 0.05);
        ShakePayload.around(w, c, 10, 0.5f, 10);

        // Let the owner know, and only now start the cooldown
        if (w.getServer().getPlayerManager().getPlayer(t.owner) instanceof ServerPlayerEntity owner) {
            owner.sendMessage(Text.literal("Your trap was sprung!"), true);
            TomeItem tome = ModItems.TOME_OF_TRAPPING;
            ItemStack staff = StaffItem.findStaffWith(owner, tome);
            if (!staff.isEmpty()) StaffItem.applyCooldown(owner.getWorld(), owner, staff, tome, tome.getCooldown());
            else owner.getItemCooldownManager().set(tome, tome.getCooldown());
        }
    }

    /**
     * Builds both halves of the jaw. Each half is a curved wall hinged at the ground: a rocky shell outside, red
     * flesh inside, fangs on the flesh and a row of great fangs along the rim. In a half's own frame x runs along
     * the hinge, y up, and +z points in toward the middle.
     */
    private static void buildJaw(Bite b, Brightness light) {
        // Segments up the curve: how far each leans in, how long and how wide it is
        float[] lean = {0f, 16f, 36f, 60f};
        float[] length = {1.05f, 0.95f, 0.8f, 0.6f};
        float[] width = {2.8f, 2.5f, 2.0f, 1.35f};
        float shell = 0.42f;

        for (int half = 0; half < 2; half++) {
            Vector3f base = new Vector3f();
            float fangShift = half == 0 ? 0.18f : -0.18f; // seen from the middle, the two rows interlock
            for (int s = 0; s < lean.length; s++) {
                Quaternionf rot = new Quaternionf().rotateX((float) Math.toRadians(lean[s]));
                // Rocky outer shell
                piece(b, half, SHELL, light, rot, base, new Vector3f(width[s], length[s] + 0.06f, shell), new Vector3f(0.5f, 0, 1));
                // Flesh lining
                Vector3f lining = new Vector3f(0, 0.04f, 0);
                rot.transform(lining).add(base);
                piece(b, half, FLESH, light, rot, lining, new Vector3f(width[s] - 0.2f, length[s] - 0.04f, 0.1f), new Vector3f(0.5f, 0, 0));

                // Fangs jutting in from the lining on the middle segments
                if (s == 1 || s == 2) {
                    int n = s == 1 ? 4 : 3;
                    for (int i = 0; i < n; i++) {
                        float x = (i - (n - 1) / 2f) * (width[s] - 0.5f) / (n - 1) + fangShift;
                        Vector3f at = new Vector3f(x, length[s] * 0.5f, 0.08f);
                        rot.transform(at).add(base);
                        // Point in toward the middle and a little up
                        Quaternionf fr = new Quaternionf(rot).rotateX((float) Math.toRadians(70));
                        piece(b, half, FANG, light, fr, at, new Vector3f(0.45f, 0.75f, 0.45f), new Vector3f(0.5f, 0, 0.5f));
                    }
                }

                Vector3f step = new Vector3f(0, length[s], 0);
                rot.transform(step);
                base.add(step);
            }

            // The great fangs along the rim, carrying the curve on over the middle
            Quaternionf tip = new Quaternionf().rotateX((float) Math.toRadians(lean[lean.length - 1] + 18));
            int n = 4;
            for (int i = 0; i < n; i++) {
                float x = (i - (n - 1) / 2f) * 0.42f + fangShift;
                float len = (i == 0 || i == n - 1) ? 0.8f : 1.05f;
                Vector3f at = new Vector3f(x, 0, -shell * 0.4f).add(base);
                piece(b, half, FANG, light, tip, at, new Vector3f(0.6f, len, 0.6f), new Vector3f(0.5f, 0, 0.5f));
            }
        }
        pose(b, BURIED, OPEN_ANGLE, 1);
    }

    private static void piece(Bite b, int half, BlockState state, Brightness light, Quaternionf rot, Vector3f origin,
                              Vector3f size, Vector3f anchor) {
        DisplayEntity.BlockDisplayEntity d = display(b.world, b.center, state);
        if (d == null) return;
        d.setBrightness(light);
        d.setViewRange(1.5f);
        b.pieces.add(new Piece(d, half, new Quaternionf(rot), new Vector3f(origin), size, anchor));
    }

    /** Places both halves: hinges {@code rise} blocks above the ground, each leaning by {@code angle} degrees. */
    private static void pose(Bite b, double rise, float angle, int interpolation) {
        for (Piece p : b.pieces) {
            float yaw = (float) Math.toRadians(-b.yaw) + (p.half == 0 ? 0f : (float) Math.PI);
            Quaternionf facing = new Quaternionf().rotateY(yaw);
            Quaternionf hinge = new Quaternionf(facing).rotateX((float) Math.toRadians(angle));

            Vector3f hingeAt = new Vector3f(0, (float) rise, (float) -HINGE);
            facing.transform(hingeAt);
            Vector3f at = new Vector3f(p.origin);
            hinge.transform(at).add(hingeAt);

            p.display.setTransformation(box(at, new Quaternionf(hinge).mul(p.rot), p.size, p.anchor));
            p.display.setStartInterpolation(0);
            p.display.setInterpolationDuration(interpolation);
        }
    }

    private static void hold(Bite b, LivingEntity e) {
        Vec3d c = b.center;
        e.setVelocity(0, Math.min(0, e.getVelocity().y), 0);
        e.velocityModified = true;
        if (e.squaredDistanceTo(c.x, e.getY(), c.z) > 0.04) {
            e.requestTeleport(c.x, e.getY(), c.z);
        }
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 5, 9, false, false));
    }

    private static List<LivingEntity> victims(Bite b) {
        List<LivingEntity> list = new ArrayList<>();
        for (UUID id : b.victims) {
            if (b.world.getEntity(id) instanceof LivingEntity e && e.isAlive()) list.add(e);
        }
        return list;
    }

    /** Returns false once the jaw is gone. */
    private static boolean tickBite(Bite b) {
        ServerWorld w = b.world;
        int age = ++b.age;
        Vec3d c = b.center;
        int snapAt = RISE_TICKS;
        int shutAt = snapAt + SNAP_TICKS;
        int openAt = shutAt + HOLD_TICKS;
        int goneAt = openAt + SINK_TICKS;

        if (age <= RISE_TICKS) {
            // Bursting up out of the ground, gaping
            float f = age / (float) RISE_TICKS;
            float eased = 1f - (1f - f) * (1f - f);
            pose(b, MathHelper.lerp(eased, BURIED, 0.0), OPEN_ANGLE - 6f * eased, 1);
            BlockState ground = w.getBlockState(BlockPos.ofFloored(c.x, c.y - 0.5, c.z));
            BlockStateParticleEffect debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, ground.isAir() ? Blocks.DIRT.getDefaultState() : ground);
            for (int side = -1; side <= 1; side += 2) {
                double yaw = Math.toRadians(-b.yaw);
                double hx = -Math.sin(yaw) * HINGE * side;
                double hz = -Math.cos(yaw) * HINGE * side;
                w.spawnParticles(debris, c.x + hx, c.y + 0.2, c.z + hz, 14, 0.9, 0.3, 0.9, 0.25);
            }
            for (LivingEntity e : victims(b)) hold(b, e);
            if (age == RISE_TICKS) {
                w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.PLAYERS, 2.0f, 0.5f);
            }
            return true;
        }

        if (age <= shutAt) {
            // SNAP
            float f = (age - snapAt) / (float) SNAP_TICKS;
            pose(b, 0.0, MathHelper.lerp(f * f, OPEN_ANGLE - 6f, 0f), 1);
            for (LivingEntity e : victims(b)) hold(b, e);
            if (age == shutAt) chomp(b);
            return true;
        }

        if (age <= openAt) {
            // Clamped shut, grinding
            float grind = (float) Math.sin(age * 1.7) * 2.5f * (1f - (age - shutAt) / (float) HOLD_TICKS);
            pose(b, Math.sin(age * 2.3) * 0.03, grind, 1);
            for (LivingEntity e : victims(b)) {
                hold(b, e);
                if ((age - shutAt) % 5 == 0) {
                    w.spawnParticles(BLOOD, e.getX(), e.getY() + e.getHeight() * 0.5, e.getZ(), 6, 0.3, 0.4, 0.3, 0);
                }
            }
            if ((age - shutAt) % 7 == 3) {
                w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.PLAYERS, 0.5f, 0.5f);
            }
            if (age % 3 == 0) {
                w.spawnParticles(ParticleTypes.CRIMSON_SPORE, c.x, c.y + 1.5, c.z, 3, 0.6, 0.8, 0.6, 0);
            }
            if (age == openAt) {
                w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.PLAYERS, 1.5f, 0.6f);
                w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.0f, 1.2f);
            }
            return true;
        }

        if (age < goneAt) {
            // Loosens, then drags itself back under
            float f = (age - openAt) / (float) SINK_TICKS;
            pose(b, MathHelper.lerp(f * f, 0.0, BURIED), MathHelper.lerp(Math.min(1f, f * 2f), 0f, -25f), 1);
            if (age % 2 == 0) {
                BlockState ground = w.getBlockState(BlockPos.ofFloored(c.x, c.y - 0.5, c.z));
                if (!ground.isAir()) {
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), c.x, c.y + 0.1, c.z, 8, 0.9, 0.05, 0.9, 0.1);
                }
            }
            return true;
        }

        for (Piece p : b.pieces) TempEntities.discard(p.display);
        b.pieces.clear();
        w.spawnParticles(ParticleTypes.SMOKE, c.x, c.y + 0.1, c.z, 15, 0.7, 0.05, 0.7, 0.01);
        return false;
    }

    /** The jaws meet: everything caught inside is bitten. */
    private static void chomp(Bite b) {
        ServerWorld w = b.world;
        Vec3d c = b.center;
        PlayerEntity owner = w.getPlayerByUuid(b.owner);

        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_RAVAGER_ATTACK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.8f, 0.6f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 2.0f, 0.5f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6f, 0.5f);
        ShakePayload.around(w, c, 8, 0.9f, 14);

        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y + 1.4, c.z, 3, 0.4, 0.5, 0.4, 0);
        w.spawnParticles(ParticleTypes.CRIT, c.x, c.y + 2.2, c.z, 30, 0.5, 0.4, 0.5, 0.5);
        w.spawnParticles(BLOOD, c.x, c.y + 1.4, c.z, 40, 0.6, 0.8, 0.6, 0);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, FLESH), c.x, c.y + 1.5, c.z, 30, 0.5, 0.6, 0.5, 0.2);

        Box area = new Box(c.x - BITE_RADIUS, c.y - 0.5, c.z - BITE_RADIUS, c.x + BITE_RADIUS, c.y + 3.0, c.z + BITE_RADIUS);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator())) {
            if (isFriendly(e, owner, b.owner) || e instanceof ArmorStandEntity) continue;
            if (e.squaredDistanceTo(c.x, e.getY(), c.z) > BITE_RADIUS * BITE_RADIUS) continue;
            if (!b.victims.contains(e.getUuid())) b.victims.add(e.getUuid());
            e.timeUntilRegen = 0;
            e.damage(owner != null ? w.getDamageSources().indirectMagic(owner, owner) : w.getDamageSources().magic(), DAMAGE);
            w.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, e.getX(), e.getY() + e.getHeight() * 0.6, e.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Clearing up
    // ------------------------------------------------------------------------------------------------

    private static void removeTrapDisplays(Trap t) {
        for (DisplayEntity.BlockDisplayEntity f : t.fangs) TempEntities.discard(f);
        t.fangs.clear();
        if (t.seal != null) TempEntities.discard(t.seal);
        if (t.plate != null) TempEntities.discard(t.plate);
        t.seal = null;
        t.plate = null;
    }

    /** Discarded on purpose: the trap unravels in a puff of embers. */
    private static void discard(Trap t) {
        removeTrapDisplays(t);
        ServerWorld w = t.world;
        Vec3d c = t.center;
        w.spawnParticles(EMBER, c.x, c.y + 0.1, c.z, 20, 0.35, 0.05, 0.35, 0);
        w.spawnParticles(ParticleTypes.SMOKE, c.x, c.y + 0.1, c.z, 10, 0.3, 0.05, 0.3, 0.02);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, t.disguise), c.x, c.y + 0.05, c.z, 12, 0.3, 0.02, 0.3, 0.05);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_SCULK_CATALYST_BREAK, SoundCategory.PLAYERS, 0.8f, 1.2f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.4f, 1.4f);
    }

    /** Its ground was broken out from under it. */
    private static void fizzle(Trap t) {
        discard(t);
        if (t.world.getServer().getPlayerManager().getPlayer(t.owner) instanceof ServerPlayerEntity owner) {
            owner.sendMessage(Text.literal("Your trap was destroyed."), true);
        }
    }
}
