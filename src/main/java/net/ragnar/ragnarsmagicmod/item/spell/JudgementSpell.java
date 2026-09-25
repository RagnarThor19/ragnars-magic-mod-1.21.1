package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Tome of Judgement. A thin beam of light marks the spot you're looking at; high above it a colossal sword
 * forms, point down, hangs for a heartbeat - then drops straight down and buries itself in the ground.
 * Everything around it is hit for enormous damage and hurled into the air. The blade stands a moment, then
 * crumbles away.
 */
public class JudgementSpell implements Spell {
    private static final double RANGE = 128.0;

    private static final int FORM_TICKS = 12;      // the sword taking shape up high
    private static final int HANG_TICKS = 8;       // the heartbeat before it drops
    private static final int FALL_TICKS = 7;       // the drop itself - fast, and speeding up
    private static final int STAND_TICKS = 40;     // embedded in the ground afterwards
    private static final int CRUMBLE_TICKS = 12;

    private static final float SCALE = 16f;        // the sword item scaled up: roughly 20 blocks from pommel to point
    private static final double TIP_OFFSET = SCALE * 0.62; // centre of the model to its point
    private static final double DROP_HEIGHT = 48;
    private static final double BURY_DEPTH = 3.0;

    private static final double RADIUS = 7.0;
    private static final float CENTER_DAMAGE = 60f;
    private static final float EDGE_DAMAGE = 20f;
    private static final double LAUNCH = 1.7;

    // In ModelTransformationMode.NONE the sword sprite lies in its XY plane, tip toward (+1, +1) - but the item
    // display renderer turns the model 180 degrees about Y before drawing it, so as seen through our
    // transformation the tip points toward (-1, +1) and the face toward -Z
    private static final Vector3f TIP_LOCAL = new Vector3f(-1, 1, 0).normalize();
    private static final Vector3f SIDE_LOCAL = new Vector3f(1, 1, 0).normalize();
    private static final Vector3f FACE_LOCAL = new Vector3f(0, 0, -1);

    private static final DustParticleEffect GOLD = new DustParticleEffect(new Vector3f(1.0f, 0.82f, 0.35f), 1.6f);
    private static final DustParticleEffect SPARK = new DustParticleEffect(new Vector3f(1.0f, 0.95f, 0.7f), 0.8f);

    private static final class Blade {
        final ServerWorld world;
        final UUID owner;
        final Vec3d target;      // where the point lands
        final Vector3f face;     // the flat of the blade faces the caster
        final DisplayEntity.ItemDisplayEntity sword;
        int age = 0;

        Blade(ServerWorld world, UUID owner, Vec3d target, Vector3f face, DisplayEntity.ItemDisplayEntity sword) {
            this.world = world;
            this.owner = owner;
            this.target = target;
            this.face = face;
            this.sword = sword;
        }
    }

    private static final List<Blade> BLADES = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Blade> it = BLADES.iterator();
            while (it.hasNext()) {
                Blade b = it.next();
                if (b.world == world && !tick(b)) {
                    TempEntities.discard(b.sword);
                    it.remove();
                }
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        Vec3d target = findTarget(sw, player);
        if (target == null) {
            player.sendMessage(Text.literal("Nothing there to strike."), true);
            return false;
        }

        // The flat of the blade turned toward whoever called it down
        Vec3d toCaster = player.getPos().subtract(target);
        Vector3f face = new Vector3f((float) toCaster.x, 0, (float) toCaster.z);
        if (face.lengthSquared() < 1.0e-4f) face.set(0, 0, 1);
        face.normalize();

        DisplayEntity.ItemDisplayEntity sword = EntityType.ITEM_DISPLAY.create(sw);
        if (sword == null) return false;
        sword.setItemStack(new ItemStack(Items.NETHERITE_SWORD));
        sword.setTransformationMode(ModelTransformationMode.NONE);
        sword.setBrightness(Brightness.FULL);
        sword.setViewRange(8.0f);
        sword.setTeleportDuration(1);
        sword.setGlowing(true);
        sword.setGlowColorOverride(0xFFD27F);
        Vec3d start = swordCenter(target, 0.0);
        sword.refreshPositionAndAngles(start.x, start.y, start.z, 0f, 0f);
        sword.setTransformation(transform(face, 0.001f));
        TempEntities.track(sword);
        sw.spawnEntity(sword);
        BLADES.add(new Blade(sw, player.getUuid(), target, face, sword));

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.5f, 0.5f);
        sw.playSound(null, target.x, target.y + DROP_HEIGHT, target.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 6.0f, 0.5f);
        sw.playSound(null, target.x, target.y, target.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_3.value(), SoundCategory.PLAYERS, 3.0f, 0.6f);
        return true;
    }

    /** The ground at the spot being looked at (under a creature, if one is in the way). */
    private static Vec3d findTarget(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVector().multiply(RANGE));
        BlockHitResult block = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        Vec3d reach = block.getType() == HitResult.Type.MISS ? end : block.getPos();
        EntityHitResult entity = ProjectileUtil.raycast(player, eye, reach, new Box(eye, reach).expand(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator(), eye.squaredDistanceTo(reach));

        Vec3d aimed;
        if (entity != null) aimed = entity.getEntity().getPos().add(0, 0.5, 0);
        else if (block.getType() == HitResult.Type.BLOCK) aimed = block.getPos().add(Vec3d.of(block.getSide().getVector()).multiply(0.5));
        else return null;
        return groundBelow(world, aimed);
    }

    /** The top of the first solid block at or below {@code from}. */
    private static Vec3d groundBelow(ServerWorld world, Vec3d from) {
        BlockPos.Mutable pos = BlockPos.ofFloored(from).mutableCopy();
        for (int i = 0; i < 64 && pos.getY() > world.getBottomY(); i++) {
            BlockState s = world.getBlockState(pos);
            if (!s.getCollisionShape(world, pos).isEmpty()) {
                return new Vec3d(from.x, pos.getY() + s.getCollisionShape(world, pos).getMax(net.minecraft.util.math.Direction.Axis.Y), from.z);
            }
            pos.move(0, -1, 0);
        }
        return null;
    }

    /** Where the model's centre sits so its point is {@code height} above the target (negative = buried). */
    private static Vec3d swordCenter(Vec3d target, double height) {
        return target.add(0, TIP_OFFSET + height, 0);
    }

    /** Point straight down, flat of the blade facing {@code face}. */
    private static AffineTransformation transform(Vector3f face, float size) {
        Vector3f tip = new Vector3f(0, -1, 0);
        Vector3f side = new Vector3f(face).cross(tip).normalize();
        Matrix3f target = new Matrix3f(tip, side, face);
        Matrix3f local = new Matrix3f(TIP_LOCAL, SIDE_LOCAL, FACE_LOCAL);
        Matrix3f rotation = target.mul(local.transpose());
        return new AffineTransformation(new Matrix4f().mul(new Matrix4f(rotation)).scale(SCALE * size));
    }

    private static void place(Blade b, double height, float size, int interpolation) {
        Vec3d c = swordCenter(b.target, height);
        b.sword.setPosition(c.x, c.y, c.z);
        b.sword.setTransformation(transform(b.face, size));
        b.sword.setStartInterpolation(0);
        b.sword.setInterpolationDuration(interpolation);
    }

    /** Returns false once the sword is gone. */
    private static boolean tick(Blade b) {
        ServerWorld world = b.world;
        int age = ++b.age;
        Vec3d t = b.target;
        int dropAt = FORM_TICKS + HANG_TICKS;
        int impactAt = dropAt + FALL_TICKS;

        // A thin beam of light marking where it will land, until it does
        if (age < impactAt && age % 2 == 0) {
            for (double y = 0.2; y < DROP_HEIGHT; y += 1.5) {
                world.spawnParticles(ParticleTypes.END_ROD, t.x, t.y + y, t.z, 1, 0.02, 0.2, 0.02, 0);
            }
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8 + age * 0.2;
                world.spawnParticles(SPARK, t.x + Math.cos(a) * 1.5, t.y + 0.1, t.z + Math.sin(a) * 1.5, 1, 0, 0, 0, 0);
            }
        }

        if (age <= FORM_TICKS) {
            // Takes shape high above, light gathering into it
            float grow = age / (float) FORM_TICKS;
            place(b, DROP_HEIGHT, 1f - (1f - grow) * (1f - grow), 1);
            Vec3d c = swordCenter(t, DROP_HEIGHT);
            world.spawnParticles(GOLD, c.x, c.y, c.z, 12, 1.5, TIP_OFFSET * 0.6, 1.5, 0);
            world.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 4, 1.0, TIP_OFFSET * 0.5, 1.0, 0.05);
            return true;
        }
        if (age <= dropAt) {
            // The heartbeat before it falls: a faint tremble
            double tremble = Math.sin(age * 2.5) * 0.08;
            place(b, DROP_HEIGHT + tremble, 1f, 1);
            if (age == dropAt) {
                world.playSound(null, t.x, t.y + DROP_HEIGHT / 2, t.z, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 6.0f, 0.5f);
                world.playSound(null, t.x, t.y, t.z, SoundEvents.ITEM_MACE_SMASH_AIR, SoundCategory.PLAYERS, 4.0f, 0.5f);
            }
            return true;
        }
        if (age < impactAt) {
            // Straight down, faster and faster
            double f = (age - dropAt) / (double) FALL_TICKS;
            double height = MathHelper.lerp(f * f, DROP_HEIGHT, -BURY_DEPTH);
            place(b, height, 1f, 1);
            Vec3d tip = t.add(0, height, 0);
            world.spawnParticles(ParticleTypes.END_ROD, tip.x, tip.y + 2, tip.z, 10, 0.4, 3.0, 0.4, 0.02);
            world.spawnParticles(ParticleTypes.CLOUD, tip.x, tip.y + 4, tip.z, 6, 0.8, 4.0, 0.8, 0.01);
            return true;
        }
        if (age == impactAt) {
            place(b, -BURY_DEPTH, 1f, 1);
            b.sword.setGlowing(false);
            impact(b);
            return true;
        }

        int standing = age - impactAt;
        if (standing < STAND_TICKS) {
            // Buried, smoking, with the odd crumb of earth still falling from it
            if (standing % 4 == 0) {
                world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, t.x, t.y + 0.5, t.z, 2, 0.8, 0.2, 0.8, 0.01);
            }
            if (standing % 6 == 0) {
                Vec3d edge = swordCenter(t, -BURY_DEPTH).add(0, world.getRandom().nextDouble() * 6 - 3, 0);
                world.spawnParticles(SPARK, edge.x, edge.y, edge.z, 3, 0.3, 0.8, 0.3, 0);
            }
            return true;
        }
        int crumble = standing - STAND_TICKS;
        if (crumble == 0) {
            // Breaks up and sinks away
            place(b, -BURY_DEPTH - TIP_OFFSET * 0.5, 0.001f, CRUMBLE_TICKS);
            world.playSound(null, t.x, t.y, t.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 2.0f, 0.6f);
            world.playSound(null, t.x, t.y, t.z, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        }
        if (crumble < CRUMBLE_TICKS) {
            Vec3d c = swordCenter(t, -BURY_DEPTH);
            world.spawnParticles(GOLD, c.x, c.y, c.z, 10, 0.8, TIP_OFFSET * 0.5 * (1 - crumble / (double) CRUMBLE_TICKS), 0.8, 0);
            world.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 3, 0.6, TIP_OFFSET * 0.4, 0.6, 0.06);
            return true;
        }
        return false;
    }

    private static void impact(Blade b) {
        ServerWorld world = b.world;
        Vec3d t = b.target;
        PlayerEntity owner = world.getPlayerByUuid(b.owner);

        // Sound: the ground itself giving way
        world.playSound(null, t.x, t.y, t.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 5.0f, 0.5f);
        world.playSound(null, t.x, t.y, t.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 5.0f, 0.55f);
        world.playSound(null, t.x, t.y, t.z, SoundEvents.ITEM_TRIDENT_THUNDER.value(), SoundCategory.PLAYERS, 5.0f, 0.6f);
        world.playSound(null, t.x, t.y, t.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 3.0f, 0.5f);
        ShakePayload.around(world, t, 24, 1.2f, 22);

        // Sight: a flash, a blast, and the earth thrown out in a ring
        world.spawnParticles(ParticleTypes.FLASH, t.x, t.y + 1, t.z, 2, 0.5, 0.5, 0.5, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, t.x, t.y + 0.5, t.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.GUST_EMITTER_LARGE, t.x, t.y + 0.5, t.z, 1, 0, 0, 0, 0);
        BlockState ground = world.getBlockState(BlockPos.ofFloored(t.x, t.y - 0.5, t.z));
        BlockStateParticleEffect debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, ground.isAir() ? net.minecraft.block.Blocks.DIRT.getDefaultState() : ground);
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI * 2 / 64;
            for (double r = 1.5; r <= RADIUS; r += 1.5) {
                world.spawnParticles(debris, t.x + Math.cos(a) * r, t.y + 0.2, t.z + Math.sin(a) * r, 2, 0.2, 0.3, 0.2, 0.3);
            }
            world.spawnParticles(ParticleTypes.CLOUD, t.x + Math.cos(a) * 2, t.y + 0.3, t.z + Math.sin(a) * 2, 0,
                    Math.cos(a), 0.05, Math.sin(a), 0.6);
            world.spawnParticles(ParticleTypes.DUST_PLUME, t.x + Math.cos(a) * RADIUS * 0.6, t.y + 0.2, t.z + Math.sin(a) * RADIUS * 0.6,
                    1, 0.3, 0.1, 0.3, 0.05);
        }
        world.spawnParticles(GOLD, t.x, t.y + 3, t.z, 80, 2.5, 3.0, 2.5, 0);
        world.spawnParticles(ParticleTypes.END_ROD, t.x, t.y + 2, t.z, 40, 1.0, 2.0, 1.0, 0.4);

        // Everything around it: struck, and sent flying
        Box area = new Box(t, t).expand(RADIUS, RADIUS, RADIUS);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator())) {
            if (isFriendly(e, owner, b.owner)) continue;
            double dist = Math.sqrt(e.squaredDistanceTo(t.x, e.getY(), t.z));
            if (dist > RADIUS + e.getWidth() / 2) continue;
            float falloff = (float) MathHelper.clamp(dist / RADIUS, 0.0, 1.0);
            e.timeUntilRegen = 0;
            e.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().magic(),
                    MathHelper.lerp(falloff, CENTER_DAMAGE, EDGE_DAMAGE));
            Vec3d out = e.getPos().subtract(t);
            Vec3d flat = new Vec3d(out.x, 0, out.z);
            flat = flat.lengthSquared() < 1.0e-4 ? Vec3d.ZERO : flat.normalize();
            double lift = LAUNCH * (1.1 - falloff * 0.5);
            e.addVelocity(flat.x * 0.7, lift, flat.z * 0.7);
            e.velocityModified = true;
            e.fallDistance = 0;
        }
    }

    private static boolean isFriendly(Entity e, PlayerEntity owner, UUID ownerId) {
        if (e.getUuid().equals(ownerId) || e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return true;
        return owner != null && e.isTeammate(owner);
    }
}
