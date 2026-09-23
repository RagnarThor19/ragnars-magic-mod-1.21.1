package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.mixin.MobEntityAccessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Raises a squad of undead around the caster for {@link #LIFETIME_TICKS} ticks. They hunt down
 * every nearby creature (hostile, boss or passive) but never players, villagers, traders or the
 * caster's pets. They crumble back into the ground when their time runs out or they are killed,
 * dropping nothing.
 */
public class DeadSpell implements Spell {
    private static final String TAG = "ragnarsmagicmod_undead";
    private static final int COUNT = 5;
    private static final int LIFETIME_TICKS = 20 * 8;
    private static final double SPAWN_RADIUS = 2.5;
    private static final double REGROUP_DISTANCE = 8.0;

    private record Kind(EntityType<? extends MobEntity> type, int weight, Item weapon, boolean needsHelmet) {}

    // weapon null = keep what the mob spawns with (skeleton bows, wither skeleton swords...)
    private static final List<Kind> KINDS = List.of(
            new Kind(EntityType.ZOMBIE, 3, Items.IRON_SWORD, true),
            new Kind(EntityType.SKELETON, 3, null, true),
            new Kind(EntityType.HUSK, 2, Items.IRON_AXE, false),
            new Kind(EntityType.STRAY, 2, null, true),
            new Kind(EntityType.BOGGED, 1, null, true),
            new Kind(EntityType.DROWNED, 1, Items.TRIDENT, true),
            new Kind(EntityType.WITHER_SKELETON, 1, null, false),
            new Kind(EntityType.ZOMBIFIED_PIGLIN, 1, null, false)
    );

    private record Summon(ServerWorld world, MobEntity mob, UUID casterId, int expiresAt) {}

    private static final List<Summon> ACTIVE = new ArrayList<>();
    private static final Set<UUID> LIVE = new HashSet<>();
    private static int serverTicks = 0;

    /** Registers the lifetime ticker and the no-drops / cleanup hooks. Call once at init. */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            serverTicks++;
            Iterator<Summon> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Summon s = it.next();
                MobEntity mob = s.mob();
                if (mob.isRemoved()) {
                    LIVE.remove(mob.getUuid());
                    it.remove();
                } else if (serverTicks >= s.expiresAt()) {
                    crumble(s.world(), mob);
                    it.remove();
                } else {
                    tickSummon(s);
                }
            }
        });
        // Killed summons vanish instead of dying, so they never drop loot or XP
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof MobEntity mob) || !mob.getCommandTags().contains(TAG)) return true;
            if (mob.getWorld() instanceof ServerWorld sw) crumble(sw, mob);
            return false;
        });
        // Summons saved mid-spell (server stopped) are cleared out when they load again
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity.getCommandTags().contains(TAG) && !LIVE.contains(entity.getUuid())) entity.discard();
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ServerWorld sw = (ServerWorld) world;

        int raised = 0;
        double offset = sw.random.nextDouble() * Math.PI * 2.0;
        for (int i = 0; i < COUNT; i++) {
            double a = offset + i * Math.PI * 2.0 / COUNT;
            Vec3d around = player.getPos().add(Math.cos(a) * SPAWN_RADIUS, 0, Math.sin(a) * SPAWN_RADIUS);
            if (raise(sw, player, pickKind(sw), around)) raised++;
        }
        if (raised == 0) return false;

        Vec3d c = player.getPos();
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 1.2f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.5f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 2.0f, 0.7f);
        sw.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y + 0.2, c.z, 20, SPAWN_RADIUS * 0.6, 0.1, SPAWN_RADIUS * 0.6, 0.03);
        return true;
    }

    private static Kind pickKind(ServerWorld world) {
        int total = 0;
        for (Kind k : KINDS) total += k.weight();
        int roll = world.random.nextInt(total);
        for (Kind k : KINDS) {
            roll -= k.weight();
            if (roll < 0) return k;
        }
        return KINDS.get(0);
    }

    private static boolean raise(ServerWorld world, PlayerEntity caster, Kind kind, Vec3d around) {
        MobEntity mob = kind.type().create(world);
        if (mob == null) return false;
        Vec3d spot = findStandingSpot(world, mob, around);
        if (spot == null) return false;

        mob.refreshPositionAndAngles(spot.x, spot.y, spot.z, world.random.nextFloat() * 360f, 0f);
        mob.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(spot)), SpawnReason.MOB_SUMMONED, null);
        // No babies or chicken jockeys
        if (mob instanceof ZombieEntity zombie) zombie.setBaby(false);
        if (mob.hasVehicle()) {
            Entity vehicle = mob.getVehicle();
            mob.stopRiding();
            if (vehicle != null) vehicle.discard();
        }

        if (kind.weapon() != null) mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(kind.weapon()));
        // A helmet keeps the sun-burning ones from catching fire
        if (kind.needsHelmet()) mob.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setEquipmentDropChance(slot, 0f);
        mob.setCanPickUpLoot(false);
        mob.addCommandTag(TAG);
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, LIFETIME_TICKS, 0, false, false));
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, LIFETIME_TICKS, 0, false, false));

        UUID casterId = caster.getUuid();
        var targets = ((MobEntityAccessor) mob).ragnarsmagicmod$getTargetSelector();
        targets.clear(goal -> true);
        targets.add(1, new ActiveTargetGoal<>(mob, LivingEntity.class, 2, true, false, e -> isValidTarget(e, casterId)));

        LIVE.add(mob.getUuid());
        world.spawnEntity(mob);
        ACTIVE.add(new Summon(world, mob, casterId, serverTicks + LIFETIME_TICKS));

        // Clawing up out of the ground
        BlockState ground = world.getBlockState(BlockPos.ofFloored(spot).down());
        if (!ground.isAir()) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), spot.x, spot.y + 0.1, spot.z, 25, 0.35, 0.1, 0.35, 0.1);
        }
        world.spawnParticles(ParticleTypes.SOUL, spot.x, spot.y + 0.5, spot.z, 8, 0.3, 0.5, 0.3, 0.02);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, spot.x, spot.y + 0.3, spot.z, 6, 0.3, 0.2, 0.3, 0.01);
        world.playSound(null, spot.x, spot.y, spot.z, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.PLAYERS, 1.0f, 0.6f);
        return true;
    }

    private static boolean isValidTarget(LivingEntity e, UUID casterId) {
        if (e == null || !e.isAlive()) return false;
        if (e instanceof PlayerEntity || e instanceof ArmorStandEntity || e instanceof MerchantEntity) return false;
        if (e.getCommandTags().contains(TAG)) return false;
        return !(e instanceof TameableEntity pet && casterId.equals(pet.getOwnerUuid()));
    }

    private static void tickSummon(Summon s) {
        MobEntity mob = s.mob();
        ServerWorld world = s.world();
        if (world.getTime() % 6 == 0) {
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + 0.1, mob.getZ(), 1, 0.2, 0.05, 0.2, 0.0);
        }
        // With nothing to fight, stay with the caster
        if (mob.getTarget() == null && world.getTime() % 10 == 0) {
            PlayerEntity caster = world.getPlayerByUuid(s.casterId());
            if (caster != null && mob.squaredDistanceTo(caster) > REGROUP_DISTANCE * REGROUP_DISTANCE) {
                mob.getNavigation().startMovingTo(caster, 1.1);
            }
        }
    }

    /** The summon falls apart into soul dust. */
    private static void crumble(ServerWorld world, MobEntity mob) {
        Vec3d c = mob.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 12, 0.3, 0.5, 0.3, 0.04);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 10, 0.3, 0.5, 0.3, 0.02);
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.BONE_BLOCK.getDefaultState()),
                c.x, c.y, c.z, 15, 0.3, 0.5, 0.3, 0.1);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SKELETON_DEATH, SoundCategory.HOSTILE, 0.6f, 1.4f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.HOSTILE, 1.2f, 1.0f);
        LIVE.remove(mob.getUuid());
        mob.discard();
    }

    /** A nearby spot (a few blocks up or down) where the mob fits and stands on solid ground. */
    private static Vec3d findStandingSpot(ServerWorld world, MobEntity mob, Vec3d around) {
        BlockPos base = BlockPos.ofFloored(around);
        for (int dy : new int[]{0, 1, -1, 2, -2, 3}) {
            BlockPos feet = base.up(dy);
            Vec3d spot = Vec3d.ofBottomCenter(feet);
            if (!world.getBlockState(feet.down()).isSideSolidFullSquare(world, feet.down(), net.minecraft.util.math.Direction.UP)) continue;
            if (world.isSpaceEmpty(mob, mob.getType().getDimensions().getBoxAt(spot)) && !world.containsFluid(mob.getType().getDimensions().getBoxAt(spot))) {
                return spot;
            }
        }
        return null;
    }
}
