package net.ragnar.ragnarsmagicmod.wizard;

import net.minecraft.entity.Entity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.CobwebProjectileEntity;
import net.ragnar.ragnarsmagicmod.entity.IceShardEntity;
import net.ragnar.ragnarsmagicmod.entity.RockEntity;
import net.ragnar.ragnarsmagicmod.boulders.BoulderEntity;
import net.ragnar.ragnarsmagicmod.boulders.Boulders;
import net.ragnar.ragnarsmagicmod.thunderstrike.Thunderstrike;
import net.ragnar.ragnarsmagicmod.item.spell.ImpalingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.StoneCannonSpell;
import net.minecraft.block.Blocks;
import net.minecraft.particle.BlockStateParticleEffect;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.AirCutSpell;
import net.ragnar.ragnarsmagicmod.item.spell.RisingSpikesSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SharpLeavesSpell;
import net.ragnar.ragnarsmagicmod.network.ZapPayload;
import net.ragnar.ragnarsmagicmod.poisondarts.PoisonDartEntity;
import net.ragnar.ragnarsmagicmod.poisondarts.PoisonDarts;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import net.ragnar.ragnarsmagicmod.sleep.SleepDartEntity;
import net.ragnar.ragnarsmagicmod.sleep.SleepPotionEntity;
import net.ragnar.ragnarsmagicmod.util.Slicer;

import java.util.function.Supplier;

/**
 * The tomes a Wandering Wizard can carry to defend himself. Each wizard spawns with three of them and takes turns
 * between them (see WanderingWizardEntity). They're the players' own Beginner and Advanced attack tomes, but his casts are gentler: everything they
 * hurt takes {@link WanderingWizardEntity#SPELL_DAMAGE_SCALE} of the damage, nothing he casts breaks blocks or sets
 * them alight, and he's slow to cast again.
 */
public enum WizardSpell {
    //            tome held                                     cooldown range shots gap
    FIREBALLS(() -> ModItems.TOME_OF_FIREBALLS,                    60, 20, 1, 0),
    ICE_SHARDS(() -> ModItems.TOME_ICE_SHARDS,                     70, 20, 3, 2),
    POISON_DARTS(() -> PoisonDarts.TOME_OF_POISON_DARTS,           80, 20, 1, 0),
    WITHER_SKULL(() -> ModItems.TOME_SKULLS,                       80, 20, 1, 0),
    FANGS(() -> ModItems.TOME_FANGS,                               70, 11, 1, 0),
    WIND_CHARGE(() -> ModItems.TOME_CHARGED_WIND,                  50, 16, 1, 0),
    SHARP_LEAVES(() -> ModItems.TOME_OF_SHARP_LEAVES,              50, 20, 2, 6),
    ZAP(() -> ModItems.TOME_ZAP,                                   80, 20, 1, 0),
    AIR_CUT(() -> ModItems.TOME_OF_THE_AIR_CUT,                    80, 20, 1, 0),
    RISING_SPIKES(() -> ModItems.TOME_RISING_SPIKES,              120, 14, 1, 0),
    SLEEP_DARTS(() -> Sleep.TOME_OF_SLEEP_DARTS,                  100, 20, 1, 0),
    SLEEP_POTIONS(() -> Sleep.TOME_OF_SLEEP_POTIONS,              120, 16, 1, 0),
    COBWEBS(() -> ModItems.TOME_OF_COBWEBS,                        90, 16, 1, 0),
    STONE_CANNON(() -> ModItems.TOME_OF_THE_STONE_CANNON,         110, 22, 1, 0),
    BOULDERS(() -> Boulders.TOME_OF_BOULDERS,                     100, 18, 1, 0),
    ROCKS(() -> ModItems.TOME_OF_ROCKS,                            60, 18, 2, 8),
    IMPALING(() -> ModItems.TOME_IMPALING,                        100, 20, 1, 0),
    /** Only one wizard in WanderingWizardEntity.THUNDERSTRIKE_ONE_IN carries this. */
    THUNDERSTRIKE(() -> ModItems.TOME_LIGHTNING_CASCADE,          140, 24, 1, 0);

    /** The big spells hit softer still when he casts them, on top of SPELL_DAMAGE_SCALE. */
    private static final float HEAVY = 0.5f;
    private static final float THUNDER = 0.3f;

    private final Supplier<Item> tome;
    /** Ticks between one cast (all its shots) and the wind-up of the next. */
    public final int cooldown;
    /** How close the target has to be before he'll cast. */
    public final double range;
    /** Shots per cast, and the ticks between them. */
    public final int shots, shotGap;

    WizardSpell(Supplier<Item> tome, int cooldown, double range, int shots, int shotGap) {
        this.tome = tome;
        this.cooldown = cooldown;
        this.range = range;
        this.shots = shots;
        this.shotGap = shotGap;
    }

    public Item tome() {
        return tome.get();
    }

    /** The colour his runes and casting circles glow while he casts this. */
    public int color() {
        return switch (this) {
            case FIREBALLS -> 0xFF9A3C;
            case ICE_SHARDS -> 0xA8EEFF;
            case POISON_DARTS -> 0x8CE05A;
            case WITHER_SKULL -> 0x9C8CB8;
            case FANGS -> 0xD6DCE4;
            case WIND_CHARGE, AIR_CUT -> 0xE6F6FF;
            case SHARP_LEAVES -> 0x7AD64A;
            case ZAP -> 0xFFF07A;
            case RISING_SPIKES -> 0xD9A866;
            case SLEEP_DARTS, SLEEP_POTIONS -> 0xC2AEFF;
            case COBWEBS -> 0xF0F0F0;
            case STONE_CANNON -> 0xD2A679;
            case BOULDERS -> 0xB8B0A4;
            case ROCKS -> 0xC9BFB2;
            case IMPALING -> 0xC98E5A;
            case THUNDERSTRIKE -> 0x9AD8FF;
        };
    }

    /** At least this many ticks between two casts of this spell, however he rotates his tomes. */
    public int recast() {
        return switch (this) {
            case THUNDERSTRIKE -> 20 * 45;
            case STONE_CANNON, BOULDERS, IMPALING -> 20 * 10;
            default -> 0;
        };
    }

    /** True for the spells that put the target to sleep - once it's asleep, he's won and walks off. */
    public boolean sleeps() {
        return this == SLEEP_DARTS || this == SLEEP_POTIONS;
    }

    /** Shot {@code shot} (from 0) of one cast at {@code target}. */
    void cast(ServerWorld world, WanderingWizardEntity wizard, LivingEntity target, int shot, int volley) {
        Vec3d eye = wizard.getEyePos();
        Vec3d aim = target.getBoundingBox().getCenter();
        Vec3d dir = aim.subtract(eye).normalize();
        Vec3d tip = eye.add(dir.multiply(0.8)).add(0, -0.2, 0);
        double flat = Math.sqrt(MathHelper.square(aim.x - eye.x) + MathHelper.square(aim.z - eye.z));

        switch (this) {
            case FIREBALLS -> {
                WizardFireball fireball = new WizardFireball(world, wizard, spread(world, dir, 2.0));
                fireball.setPosition(tip.x, tip.y, tip.z);
                world.spawnEntity(fireball);
                sound(world, tip, SoundEvents.ENTITY_BLAZE_SHOOT, 0.7f, 1.3f);
                sound(world, tip, SoundEvents.ITEM_FIRECHARGE_USE, 0.5f, 1.0f);
                world.spawnParticles(ParticleTypes.FLAME, tip.x, tip.y, tip.z, 6, 0.1, 0.1, 0.1, 0.03);
            }
            case ICE_SHARDS -> {
                IceShardEntity shard = new IceShardEntity(world, wizard);
                shard.setVolley(volley);
                shard.setPosition(tip.x, tip.y, tip.z);
                Vec3d d = spread(world, dir.add(0, flat * 0.004, 0), 2.5 + 1.5 * shot);
                shard.setVelocity(d.multiply(2.2));
                world.spawnEntity(shard);
                float pitch = 1.3f + 0.2f * shot;
                sound(world, tip, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, pitch);
                sound(world, tip, SoundEvents.ENTITY_SNOWBALL_THROW, 0.6f, pitch);
                world.spawnParticles(ParticleTypes.SNOWFLAKE, tip.x, tip.y, tip.z, 3, 0.05, 0.05, 0.05, 0.02);
            }
            case POISON_DARTS -> {
                PoisonDartEntity dart = new PoisonDartEntity(world, wizard);
                dart.setPosition(tip.x, tip.y, tip.z);
                dart.setVelocity(dir.x, dir.y + flat * 0.003, dir.z, 2.6f, 1.5f);
                world.spawnEntity(dart);
                sound(world, tip, SoundEvents.ENTITY_LLAMA_SPIT, 0.9f, 1.7f);
                sound(world, tip, SoundEvents.ENTITY_BREEZE_SHOOT, 0.35f, 2.0f);
                world.spawnParticles(PoisonDarts.MOTE, tip.x, tip.y, tip.z, 5, 0.05, 0.05, 0.05, 0.0);
            }
            case WITHER_SKULL -> {
                WizardSkull skull = new WizardSkull(world, wizard, spread(world, dir, 1.5));
                skull.setPosition(tip.x, tip.y, tip.z);
                world.spawnEntity(skull);
                sound(world, tip, SoundEvents.ENTITY_WITHER_SHOOT, 0.6f, 1.2f);
                world.spawnParticles(ParticleTypes.SMOKE, tip.x, tip.y, tip.z, 8, 0.15, 0.15, 0.15, 0.03);
                world.spawnParticles(ParticleTypes.SOUL, tip.x, tip.y, tip.z, 3, 0.15, 0.15, 0.15, 0.03);
            }
            case FANGS -> fangs(world, wizard, target);
            case WIND_CHARGE -> {
                WindChargeEntity charge = new WindChargeEntity(world, tip.x, tip.y, tip.z, dir);
                charge.setOwner(wizard);
                charge.setVelocity(dir.multiply(1.3));
                charge.velocityModified = true;
                world.spawnEntity(charge);
                sound(world, tip, SoundEvents.ENTITY_WIND_CHARGE_THROW, 1.0f, 1.0f);
                world.spawnParticles(ParticleTypes.SMALL_GUST, tip.x, tip.y, tip.z, 1, 0, 0, 0, 0);
            }
            case SHARP_LEAVES -> {
                Slicer.fire(world, wizard, spread(world, dir, 1.5), SharpLeavesSpell.plainLeaf());
                sound(world, tip, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 2.0f);
                sound(world, tip, SoundEvents.BLOCK_AZALEA_LEAVES_STEP, 1.0f, 1.5f);
            }
            case ZAP -> zap(world, wizard, target, tip);
            case AIR_CUT -> {
                Slicer.fire(world, wizard, spread(world, dir, 1.5), AirCutSpell.style());
                sound(world, tip, SoundEvents.ENTITY_BREEZE_SHOOT, 0.8f, 1.2f);
                sound(world, tip, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.4f);
            }
            case RISING_SPIKES -> RisingSpikesSpell.start(world, wizard, dir);
            case SLEEP_DARTS -> {
                SleepDartEntity dart = new SleepDartEntity(world, wizard);
                dart.setPosition(tip.x, tip.y, tip.z);
                dart.setVelocity(dir.x, dir.y + flat * 0.004, dir.z, SleepDartEntity.SPEED, 1.5f);
                world.spawnEntity(dart);
                sound(world, tip, SoundEvents.ENTITY_ARROW_SHOOT, 0.8f, 1.9f);
                world.spawnParticles(Sleep.MOTE, tip.x, tip.y, tip.z, 6, 0.08, 0.08, 0.08, 0.0);
            }
            case SLEEP_POTIONS -> {
                // Lobbed the way a witch throws her potions
                SleepPotionEntity potion = new SleepPotionEntity(world, wizard);
                Vec3d lead = target.getVelocity();
                double dx = target.getX() + lead.x - wizard.getX();
                double dy = target.getEyeY() - 1.1 - wizard.getY();
                double dz = target.getZ() + lead.z - wizard.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                potion.setPitch(potion.getPitch() + 20f);
                potion.setVelocity(dx, dy + d * 0.2, dz, 0.75f, 6f);
                world.spawnEntity(potion);
                sound(world, tip, SoundEvents.ENTITY_SPLASH_POTION_THROW, 0.8f, 0.7f);
                sound(world, tip, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.2f);
            }
            case STONE_CANNON -> {
                // The spike forms beside him and spins up before it fires (the tome's own charge)
                StoneCannonSpell.fireFrom(world, wizard, target, HEAVY);
                sound(world, tip, SoundEvents.BLOCK_GRINDSTONE_USE, 0.6f, 0.7f);
            }
            case BOULDERS -> {
                BoulderEntity boulder = BoulderEntity.castFrom(world, wizard, target, HEAVY);
                Vec3d c = boulder.center();
                sound(world, c, SoundEvents.BLOCK_DEEPSLATE_BREAK, 1.5f, 0.5f);
                sound(world, c, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 1.2f, 0.5f);
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                        c.x, c.y, c.z, 30, 0.4, 0.4, 0.4, 0.2);
            }
            case ROCKS -> {
                RockEntity rock = new RockEntity(world, wizard);
                rock.setPosition(tip.x, tip.y, tip.z);
                rock.setVelocity(lob(world, tip, target, 0.05, 1.4, shot));
                world.spawnEntity(rock);
                sound(world, tip, SoundEvents.BLOCK_STONE_PLACE, 0.8f, 1.2f);
                sound(world, tip, SoundEvents.ENTITY_WITCH_THROW, 1.0f, 0.6f);
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                        tip.x, tip.y, tip.z, 8, 0.15, 0.15, 0.15, 0.1);
            }
            case IMPALING -> {
                if (!ImpalingSpell.eruptUnder(world, wizard, target, HEAVY)) {
                    // Nothing to raise it from (it's in the air or over water): fangs instead
                    fangs(world, wizard, target);
                }
            }
            case THUNDERSTRIKE -> {
                Thunderstrike.start(world, wizard, groundUnder(world, target), THUNDER);
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 20, 0.3, 0.3, 0.3, 0.2);
            }
            case COBWEBS -> {
                CobwebProjectileEntity web = new CobwebProjectileEntity(world, wizard);
                web.setPosition(tip.x, tip.y, tip.z);
                web.setVelocity(dir.x, dir.y + flat * 0.012, dir.z, 1.8f, 1.5f);
                world.spawnEntity(web);
                sound(world, tip, SoundEvents.ENTITY_SNOWBALL_THROW, 0.6f, 0.6f);
                sound(world, tip, SoundEvents.ENTITY_SPIDER_AMBIENT, 0.3f, 1.6f);
            }
        }
    }

    private static void sound(ServerWorld world, Vec3d at, net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.HOSTILE, volume, pitch);
    }

    /**
     * A throw that comes down on {@code target}: picks a flight time from the distance at about {@code speed}, and the
     * velocity that gets there under {@code gravity}. The second and later shots of a cast land a little off.
     */
    private static Vec3d lob(ServerWorld world, Vec3d from, LivingEntity target, double gravity, double speed, int shot) {
        Vec3d to = target.getPos().add(0, target.getHeight() * 0.4, 0);
        if (shot > 0) to = to.add((world.random.nextDouble() - 0.5) * 2.0, 0, (world.random.nextDouble() - 0.5) * 2.0);
        Vec3d d = to.subtract(from);
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        double t = MathHelper.clamp(flat / speed, 4, 40);
        // A touch extra across, for the air slowing it down
        return new Vec3d(d.x / t * 1.05, d.y / t + 0.5 * gravity * t, d.z / t * 1.05);
    }

    /** The ground under {@code target}: where a storm or a spike comes up for it. */
    private static Vec3d groundUnder(ServerWorld world, LivingEntity target) {
        BlockPos.Mutable m = target.getBlockPos().mutableCopy();
        for (int i = 0; i < 24 && m.getY() > world.getBottomY(); i++) {
            BlockPos below = m.down();
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()) break;
            m.move(0, -1, 0);
        }
        return new Vec3d(target.getX(), m.getY(), target.getZ());
    }

    /** Tilts {@code dir} a random amount, up to {@code degrees}, so he doesn't hit dead centre every time. */
    private static Vec3d spread(ServerWorld world, Vec3d dir, double degrees) {
        Vec3d n1 = dir.crossProduct(new Vec3d(0, 1, 0));
        n1 = n1.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : n1.normalize();
        Vec3d n2 = dir.crossProduct(n1).normalize();
        double spin = world.random.nextDouble() * Math.PI * 2;
        double tilt = Math.toRadians(degrees) * Math.sqrt(world.random.nextDouble());
        Vec3d off = n1.multiply(Math.cos(spin)).add(n2.multiply(Math.sin(spin)));
        return dir.multiply(Math.cos(tilt)).add(off.multiply(Math.sin(tilt))).normalize();
    }

    /** A line of evoker fangs snapping up along the ground toward the target, the way an evoker does it. */
    private static void fangs(ServerWorld world, WanderingWizardEntity wizard, LivingEntity target) {
        double minY = Math.min(target.getY(), wizard.getY());
        double maxY = Math.max(target.getY(), wizard.getY()) + 1.0;
        float yaw = (float) MathHelper.atan2(target.getZ() - wizard.getZ(), target.getX() - wizard.getX());
        for (int i = 0; i < 9; i++) {
            double step = 1.25 * (i + 1);
            double x = wizard.getX() + MathHelper.cos(yaw) * step;
            double z = wizard.getZ() + MathHelper.sin(yaw) * step;
            BlockPos pos = BlockPos.ofFloored(x, maxY, z);
            double y = Double.NaN;
            // Down from the higher of the two to the lower, for the first block a fang can stand on
            while (pos.getY() >= MathHelper.floor(minY) - 1) {
                BlockPos below = pos.down();
                if (world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) {
                    if (world.isAir(pos) || world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                        y = pos.getY();
                    }
                    break;
                }
                pos = below;
            }
            if (Double.isNaN(y)) continue;
            world.spawnEntity(new EvokerFangsEntity(world, x, y, z, yaw, i, wizard));
        }
        world.playSound(null, wizard.getX(), wizard.getY(), wizard.getZ(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.HOSTILE, 0.8f, 1.1f);
    }

    /** As the Tome of Zap: an instant bolt at whatever is in the way, drawn by the same client code. */
    private static void zap(ServerWorld world, WanderingWizardEntity wizard, LivingEntity target, Vec3d from) {
        double range = 24;
        Vec3d eye = wizard.getEyePos();
        Vec3d aim = target.getBoundingBox().getCenter().add(
                (world.random.nextDouble() - 0.5) * 0.5, (world.random.nextDouble() - 0.5) * 0.5, (world.random.nextDouble() - 0.5) * 0.5);
        Vec3d look = aim.subtract(eye).normalize();
        Vec3d reach = eye.add(look.multiply(range));
        BlockHitResult blockHit = world.raycast(new RaycastContext(eye, reach,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, wizard));
        boolean hitBlock = blockHit.getType() != HitResult.Type.MISS;
        Vec3d end = hitBlock ? blockHit.getPos() : reach;
        EntityHitResult entityHit = ProjectileUtil.raycast(wizard, eye, end, new Box(eye, end).expand(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e.canHit()
                        && !(e instanceof Leashable l && l.getLeashHolder() == wizard),
                eye.squaredDistanceTo(end));
        Entity hit = entityHit != null ? entityHit.getEntity() : null;
        Vec3d hitPos = hit != null ? hit.getBoundingBox().getCenter().lerp(entityHit.getPos(), 0.5) : end;

        int kind = hit != null ? ZapPayload.ENTITY : hitBlock ? ZapPayload.BLOCK : ZapPayload.MISS;
        Vec3d normal = kind == ZapPayload.BLOCK ? Vec3d.of(blockHit.getSide().getVector()) : look.multiply(-1);
        ZapPayload.broadcast(world, wizard.getId(), from, hitPos, kind, normal);
        world.playSound(null, wizard.getBlockPos(), net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.ZAP_CAST, SoundCategory.HOSTILE, 1.0f, 1.0f);
        world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 0.9f, 1.7f);

        if (hit instanceof LivingEntity le) {
            le.damage(world.getDamageSources().mobAttack(wizard), 7.5f);
            le.addVelocity(look.x * 0.4, 0.12, look.z * 0.4);
            le.velocityModified = true;
            Vec3d c = le.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 20, le.getWidth() * 0.5, le.getHeight() * 0.4, le.getWidth() * 0.5, 0.3);
        }
    }

    /** A blaze fireball that never sets blocks alight, and only singes for a moment. */
    static final class WizardFireball extends SmallFireballEntity {
        WizardFireball(World world, LivingEntity owner, Vec3d dir) {
            super(world, owner, dir);
        }

        @Override
        protected void onEntityHit(EntityHitResult hit) {
            if (!(getWorld() instanceof ServerWorld)) return;
            Entity e = hit.getEntity();
            int fire = e.getFireTicks();
            e.setOnFireFor(3f);
            if (!e.damage(getDamageSources().fireball(this, getOwner()), 5f)) e.setFireTicks(fire);
        }

        @Override
        protected void onBlockHit(BlockHitResult hit) {
            // Nothing catches fire
        }
    }

    /** A wither skull that bursts into soul smoke instead of exploding, with only a short wither. */
    static final class WizardSkull extends WitherSkullEntity {
        WizardSkull(World world, LivingEntity owner, Vec3d dir) {
            super(world, owner, dir);
        }

        @Override
        protected void onEntityHit(EntityHitResult hit) {
            if (!(getWorld() instanceof ServerWorld)) return;
            if (hit.getEntity() instanceof LivingEntity le && le.damage(getDamageSources().witherSkull(this, getOwner()), 6f)) {
                le.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0), getOwner());
            }
        }

        @Override
        protected void onCollision(HitResult hit) {
            if (!(getWorld() instanceof ServerWorld sw)) return;
            if (hit.getType() == HitResult.Type.ENTITY) onEntityHit((EntityHitResult) hit);
            Vec3d at = hit.getPos();
            sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.03);
            sw.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.02);
            sw.playSound(null, at.x, at.y, at.z, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.HOSTILE, 1.5f, 0.8f);
            discard();
        }
    }
}
