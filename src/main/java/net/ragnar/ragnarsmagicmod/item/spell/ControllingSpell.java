package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.BreezeEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.DrownedEntity;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.EvokerEntity;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.passive.AxolotlEntity;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.DolphinEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.passive.GlowSquidEntity;
import net.minecraft.entity.passive.GoatEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.PufferfishEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.passive.TadpoleEntity;
import net.minecraft.entity.passive.TurtleEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.BreezeWindChargeEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.LlamaSpitEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.TridentItem;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.network.PossessionPayloads;
import net.ragnar.ragnarsmagicmod.util.Aim;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Possession. Cast at a mob to leave your body and become it for {@link #DURATION} ticks: you see through its
 * eyes, walk, fly or swim with its own speed, and left click to use whatever it is known for (a creeper
 * explodes, a ghast spits fire, a skeleton shoots...). Right click to return early. Your body stays behind,
 * hidden from players and ignored by mobs. The cooldown starts once you are back.
 */
public class ControllingSpell implements Spell {
    public static final int DURATION = 20 * 30;
    private static final double RANGE = 24.0;
    private static final double AIM_CONE = 5.0;
    // Keeps the host inside the area the server keeps loaded and tracked around your body
    private static final double LEASH = 48.0;
    private static final double LEASH_WARN = 40.0;

    private static final DustParticleEffect AURA = new DustParticleEffect(new Vector3f(0.55f, 0.2f, 0.95f), 1.0f);

    private static final class Link {
        final MinecraftServer server;
        final UUID playerId;
        final int playerEntityId;
        final MobEntity mob;
        final boolean hadNoGravity;
        final boolean hadInvisibility;
        int age = 0;
        int nextAbility = 0;
        // Latest input from the client
        float forward, sideways, yaw, pitch;
        boolean jump, sneak, sprint;
        // Velocity we steer flying and swimming hosts with, kept apart from whatever their own code does to it
        Vec3d steer = Vec3d.ZERO;

        Link(MinecraftServer server, ServerPlayerEntity player, MobEntity mob) {
            this.server = server;
            this.playerId = player.getUuid();
            this.playerEntityId = player.getId();
            this.mob = mob;
            this.hadNoGravity = mob.hasNoGravity();
            this.hadInvisibility = player.hasStatusEffect(StatusEffects.INVISIBILITY);
            this.yaw = player.getYaw();
            this.pitch = player.getPitch();
        }
    }

    private static final Map<UUID, Link> BY_PLAYER = new HashMap<>();
    private static final Map<MobEntity, Link> BY_MOB = new IdentityHashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Link link : new ArrayList<>(BY_PLAYER.values())) tick(link);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Link link = BY_PLAYER.get(handler.player.getUuid());
            if (link != null) end(link, null);
        });
        // Late joiners still need to know whose bodies to hide
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            for (Link link : BY_PLAYER.values()) PossessionPayloads.sendHidden(handler.player, link.playerEntityId, true);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Link link : new ArrayList<>(BY_PLAYER.values())) end(link, null);
        });
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // applied when you come back
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return false;
        if (BY_PLAYER.containsKey(player.getUuid())) return false;
        ServerWorld sw = (ServerWorld) world;

        Entity target = Aim.target(sw, player, RANGE, AIM_CONE, ControllingSpell::isPossessable);
        if (!(target instanceof MobEntity mob)) {
            Entity boss = Aim.target(sw, player, RANGE, AIM_CONE, ControllingSpell::isBoss);
            player.sendMessage(Text.literal(boss != null ? "That mind is too powerful to control." : "No mind to take over."), true);
            return false;
        }
        start(sw, sp, mob);
        return true;
    }

    private static void start(ServerWorld world, ServerPlayerEntity player, MobEntity mob) {
        mob.stopRiding();
        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setAttacking(false);
        if (mob instanceof BatEntity bat) bat.setRoosting(false);

        Link link = new Link(world.getServer(), player, mob);
        BY_PLAYER.put(player.getUuid(), link);
        BY_MOB.put(mob, link);
        if (isFlyer(mob)) mob.setNoGravity(true);

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, DURATION + 20, 0, false, false, true));
        player.setVelocity(Vec3d.ZERO);
        player.velocityModified = true;
        calmMobs(world, player);
        PossessionPayloads.sendPossess(player, mob.getId(), DURATION);
        PossessionPayloads.broadcastHidden(world.getServer(), player.getId(), true);

        // A streak of soul fire from your body into the host
        Vec3d from = player.getEyePos();
        Vec3d to = mob.getBoundingBox().getCenter();
        Vec3d path = to.subtract(from);
        int steps = (int) Math.ceil(path.length() * 3);
        for (int i = 0; i <= steps; i++) {
            Vec3d p = from.add(path.multiply(i / (double) steps));
            world.spawnParticles(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : AURA, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.01);
        }
        world.spawnParticles(ParticleTypes.SOUL, from.x, from.y - 0.4, from.z, 12, 0.3, 0.5, 0.3, 0.02);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, to.x, to.y, to.z, 40, mob.getWidth() * 0.5, mob.getHeight() * 0.5, mob.getWidth() * 0.5, 0.1);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 0.7f);
        world.playSound(null, to.x, to.y, to.z, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }

    private static void tick(Link link) {
        ServerPlayerEntity player = link.server.getPlayerManager().getPlayer(link.playerId);
        MobEntity mob = link.mob;
        if (player == null || !player.isAlive()) {
            end(link, null);
            return;
        }
        if (mob.isRemoved() || !mob.isAlive()) {
            end(link, "Your host is gone.");
            return;
        }
        if (mob.getWorld() != player.getWorld()) {
            end(link, "The link snapped.");
            return;
        }
        if (++link.age >= DURATION) {
            end(link, "The link fades.");
            return;
        }
        double dist = mob.getPos().distanceTo(player.getPos());
        if (dist > LEASH) {
            end(link, "You strayed too far from your body.");
            return;
        }
        if (dist > LEASH_WARN && link.age % 10 == 0) {
            player.sendMessage(Text.literal("Your body is too far away...").formatted(net.minecraft.util.Formatting.DARK_PURPLE), true);
        }

        ServerWorld world = (ServerWorld) mob.getWorld();
        if (link.age % 10 == 0) calmMobs(world, player);
        if (link.age % 4 == 0) {
            // A faint aura so others can tell this one is being controlled
            world.spawnParticles(AURA, mob.getX(), mob.getBodyY(0.6), mob.getZ(), 1, mob.getWidth() * 0.4, mob.getHeight() * 0.3, mob.getWidth() * 0.4, 0);
        }
    }

    private static void end(Link link, String message) {
        BY_PLAYER.remove(link.playerId);
        BY_MOB.remove(link.mob);
        MobEntity mob = link.mob;
        if (!mob.isRemoved()) {
            mob.setNoGravity(link.hadNoGravity);
            mob.forwardSpeed = 0;
            mob.sidewaysSpeed = 0;
            mob.upwardSpeed = 0;
            mob.setJumping(false);
            if (mob.getWorld() instanceof ServerWorld w) {
                Vec3d c = mob.getBoundingBox().getCenter();
                w.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 25, mob.getWidth() * 0.5, mob.getHeight() * 0.5, mob.getWidth() * 0.5, 0.05);
            }
        }

        PossessionPayloads.broadcastHidden(link.server, link.playerEntityId, false);
        ServerPlayerEntity player = link.server.getPlayerManager().getPlayer(link.playerId);
        if (player == null) return;
        PossessionPayloads.sendPossess(player, -1, 0);
        if (!link.hadInvisibility) player.removeStatusEffect(StatusEffects.INVISIBILITY);
        if (message != null) player.sendMessage(Text.literal(message), true);

        ServerWorld world = player.getServerWorld();
        world.spawnParticles(ParticleTypes.SOUL, player.getX(), player.getBodyY(0.5), player.getZ(), 15, 0.3, 0.6, 0.3, 0.02);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.0f, 1.3f);

        // Not a cast, so start the cooldown here
        startCooldown(world, player);
    }

    private static void startCooldown(ServerWorld world, ServerPlayerEntity player) {
        var tome = ModItems.TOME_OF_CONTROLLING;
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(world, player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    // ---------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------

    /** True for a player whose body is sitting empty while they possess something (server side). */
    public static boolean isPossessing(Entity entity) {
        return entity instanceof PlayerEntity && !entity.getWorld().isClient && BY_PLAYER.containsKey(entity.getUuid());
    }

    public static void onSteer(ServerPlayerEntity player, PossessionPayloads.Steer s) {
        Link link = BY_PLAYER.get(player.getUuid());
        if (link == null) return;
        link.forward = Float.isFinite(s.forward()) ? MathHelper.clamp(s.forward(), -1f, 1f) : 0f;
        link.sideways = Float.isFinite(s.sideways()) ? MathHelper.clamp(s.sideways(), -1f, 1f) : 0f;
        link.jump = s.jump();
        link.sneak = s.sneak();
        link.sprint = s.sprint();
        if (Float.isFinite(s.yaw())) link.yaw = MathHelper.wrapDegrees(s.yaw());
        if (Float.isFinite(s.pitch())) link.pitch = MathHelper.clamp(s.pitch(), -90f, 90f);
    }

    public static void onAct(ServerPlayerEntity player, boolean leave) {
        Link link = BY_PLAYER.get(player.getUuid());
        if (link == null) return;
        if (leave) {
            end(link, null);
        } else if (link.age >= link.nextAbility && link.mob.isAlive()) {
            face(link);
            int cooldown = useAbility((ServerWorld) link.mob.getWorld(), link.mob, player);
            link.nextAbility = link.age + Math.max(4, cooldown);
        }
    }

    /**
     * Called from the mob's AI tick in place of its brain. Steers a possessed mob from the player's input and
     * returns true, or returns false for any other mob so its AI runs as usual.
     */
    public static boolean drive(MobEntity mob) {
        if (mob.getWorld().isClient) return false;
        Link link = BY_MOB.get(mob);
        if (link == null) return false;
        face(link);

        float forward = link.forward;
        float sideways = link.sideways;
        if (forward != 0 && sideways != 0) {
            forward *= MathHelper.SQUARE_ROOT_OF_TWO / 2f;
            sideways *= MathHelper.SQUARE_ROOT_OF_TWO / 2f;
        }

        boolean flying = isFlyer(mob);
        if (flying || (isSwimmer(mob) && mob.isTouchingWater())) {
            // Free movement along the look direction, space/sneak to rise and sink
            double yawRad = Math.toRadians(link.yaw);
            Vec3d look = Vec3d.fromPolar(link.pitch, link.yaw);
            Vec3d left = new Vec3d(Math.cos(yawRad), 0, Math.sin(yawRad));
            double vertical = (link.jump ? 1 : 0) - (link.sneak ? 1 : 0);
            Vec3d dir = look.multiply(forward).add(left.multiply(sideways)).add(0, vertical, 0);
            if (dir.lengthSquared() > 1) dir = dir.normalize();
            double speed = (flying ? flySpeed(mob) : swimSpeed(mob)) * (link.sprint ? 1.3 : 1.0);
            link.steer = link.steer.lerp(dir.multiply(speed), 0.25);

            mob.forwardSpeed = 0;
            mob.sidewaysSpeed = 0;
            mob.upwardSpeed = 0;
            mob.setJumping(false);
            mob.setVelocity(link.steer);
            if (flying) mob.fallDistance = 0;
            return true;
        }

        // On foot: feed the keys through the mob's own movement, exactly as its AI would, so it walks,
        // jumps and paddles at its natural speed
        link.steer = mob.getVelocity();
        float speed = (float) (mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                ? mob.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) : 0.25);
        if (link.sprint) speed *= 1.3f;
        mob.setMovementSpeed(speed);
        mob.forwardSpeed = forward * speed;
        mob.sidewaysSpeed = sideways * speed;
        mob.upwardSpeed = 0;
        boolean moving = forward != 0 || sideways != 0;
        // Hop up single blocks on its own, like a mob would
        mob.setJumping(link.jump || (moving && mob.horizontalCollision && mob.isOnGround()));
        return true;
    }

    private static void face(Link link) {
        MobEntity mob = link.mob;
        mob.setYaw(link.yaw);
        mob.setHeadYaw(link.yaw);
        mob.setBodyYaw(link.yaw);
        mob.setPitch(link.pitch);
    }

    public static boolean isControlled(MobEntity mob) {
        return !mob.getWorld().isClient && BY_MOB.containsKey(mob);
    }

    /** Drops any mob's grudge against the empty body. */
    private static void calmMobs(ServerWorld world, ServerPlayerEntity player) {
        for (MobEntity m : world.getEntitiesByClass(MobEntity.class, player.getBoundingBox().expand(48), m -> true)) {
            if (m.getTarget() == player) m.setTarget(null);
            // Only brain-driven mobs (piglins, hoglins...) have this memory; asking any other mob throws
            if (m.getBrain().hasMemoryModuleWithValue(MemoryModuleType.ATTACK_TARGET, player)) {
                m.getBrain().forget(MemoryModuleType.ATTACK_TARGET);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Which mobs, and how they move
    // ---------------------------------------------------------------------

    private static boolean isBoss(Entity e) {
        return e instanceof EnderDragonEntity || e instanceof WitherEntity || e instanceof ElderGuardianEntity || e instanceof WardenEntity;
    }

    private static boolean isPossessable(Entity e) {
        return e instanceof MobEntity mob && !isBoss(e) && !BY_MOB.containsKey(mob);
    }

    private static boolean isFlyer(MobEntity mob) {
        return mob instanceof FlyingEntity || mob instanceof BatEntity || mob instanceof VexEntity
                || mob instanceof BlazeEntity || mob.getNavigation() instanceof BirdNavigation;
    }

    private static boolean isSwimmer(MobEntity mob) {
        return mob instanceof WaterCreatureEntity || mob instanceof GuardianEntity || mob instanceof AxolotlEntity
                || mob instanceof TurtleEntity || mob instanceof DrownedEntity || mob instanceof FrogEntity;
    }

    /** Rough cruising speed in the air, in blocks per tick, matched to how fast each mob flies. */
    private static double flySpeed(MobEntity mob) {
        if (mob instanceof net.minecraft.entity.mob.PhantomEntity) return 0.5;
        if (mob instanceof VexEntity) return 0.4;
        if (mob instanceof BatEntity) return 0.35;
        if (mob instanceof AllayEntity) return 0.35;
        if (mob instanceof ParrotEntity) return 0.35;
        if (mob instanceof BeeEntity) return 0.3;
        if (mob instanceof net.minecraft.entity.mob.GhastEntity) return 0.25;
        if (mob instanceof BlazeEntity) return 0.2;
        if (mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_FLYING_SPEED)) {
            return MathHelper.clamp(mob.getAttributeValue(EntityAttributes.GENERIC_FLYING_SPEED) * 0.6, 0.15, 0.5);
        }
        return 0.25;
    }

    /** Rough cruising speed underwater, in blocks per tick. */
    private static double swimSpeed(MobEntity mob) {
        if (mob instanceof DolphinEntity) return 0.55;
        if (mob instanceof GuardianEntity) return 0.3;
        if (mob instanceof AxolotlEntity) return 0.3;
        if (mob instanceof SquidEntity) return 0.2;
        if (mob instanceof TurtleEntity) return 0.2;
        if (mob instanceof DrownedEntity) return 0.2;
        if (mob instanceof FrogEntity) return 0.2;
        if (mob instanceof TadpoleEntity) return 0.1;
        if (mob instanceof FishEntity) return 0.15;
        return 0.2;
    }

    // ---------------------------------------------------------------------
    // Left click: what the mob is known for
    // ---------------------------------------------------------------------

    /** Uses the host's signature move. Returns the ticks before it can be used again. */
    private static int useAbility(ServerWorld world, MobEntity mob, ServerPlayerEntity player) {
        Vec3d look = mob.getRotationVector();
        Vec3d eye = mob.getEyePos();

        if (mob instanceof CreeperEntity creeper) {
            creeper.ignite();
            return 100;
        }
        if (mob instanceof net.minecraft.entity.mob.GhastEntity) {
            FireballEntity fireball = new FireballEntity(world, mob, look, 1);
            fireball.setPosition(eye.x + look.x * 2.0, eye.y + look.y * 2.0 - 0.5, eye.z + look.z * 2.0);
            world.spawnEntity(fireball);
            sound(world, mob, SoundEvents.ENTITY_GHAST_SHOOT, 1.0f);
            return 30;
        }
        if (mob instanceof BlazeEntity) {
            for (int i = 0; i < 3; i++) {
                Vec3d dir = look.add(world.random.nextGaussian() * 0.05, world.random.nextGaussian() * 0.05, world.random.nextGaussian() * 0.05);
                SmallFireballEntity ball = new SmallFireballEntity(world, mob, dir);
                ball.setPosition(eye.x + look.x, eye.y + look.y - 0.2, eye.z + look.z);
                world.spawnEntity(ball);
            }
            sound(world, mob, SoundEvents.ENTITY_BLAZE_SHOOT, 1.0f);
            return 25;
        }
        if (mob instanceof BreezeEntity breeze) {
            BreezeWindChargeEntity charge = new BreezeWindChargeEntity(breeze, world);
            charge.setPosition(eye.x + look.x, eye.y + look.y - 0.2, eye.z + look.z);
            charge.setVelocity(look.x, look.y, look.z, 0.7f, 0.0f);
            world.spawnEntity(charge);
            sound(world, mob, SoundEvents.ENTITY_BREEZE_SHOOT, 1.0f);
            return 25;
        }
        if (mob instanceof SnowGolemEntity) {
            SnowballEntity snowball = new SnowballEntity(world, mob);
            snowball.setVelocity(mob, mob.getPitch(), mob.getYaw(), 0.0f, 1.6f, 1.0f);
            world.spawnEntity(snowball);
            sound(world, mob, SoundEvents.ENTITY_SNOW_GOLEM_SHOOT, 1.0f);
            return 6;
        }
        if (mob instanceof WitchEntity) {
            PotionEntity potion = new PotionEntity(world, mob);
            potion.setItem(PotionContentsComponent.createStack(Items.SPLASH_POTION, Potions.HARMING));
            potion.setVelocity(mob, mob.getPitch(), mob.getYaw(), -20.0f, 0.75f, 1.0f);
            world.spawnEntity(potion);
            sound(world, mob, SoundEvents.ENTITY_WITCH_THROW, 1.0f);
            return 40;
        }
        if (mob instanceof LlamaEntity llama) {
            LlamaSpitEntity spit = new LlamaSpitEntity(world, llama);
            spit.setVelocity(mob, mob.getPitch(), mob.getYaw(), 0.0f, 1.5f, 1.0f);
            world.spawnEntity(spit);
            sound(world, mob, SoundEvents.ENTITY_LLAMA_SPIT, 1.0f);
            return 20;
        }
        if (mob instanceof EvokerEntity) {
            conjureFangs(world, mob);
            sound(world, mob, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1.0f);
            return 40;
        }
        if (mob instanceof ShulkerEntity) {
            Entity target = Aim.target(world, mob, 20.0, 8.0, e -> e instanceof LivingEntity && e != player);
            if (target == null) return 4;
            world.spawnEntity(new ShulkerBulletEntity(world, mob, target, Direction.Axis.Y));
            sound(world, mob, SoundEvents.ENTITY_SHULKER_SHOOT, 2.0f);
            return 30;
        }
        if (mob instanceof EndermanEntity) {
            HitResult hit = world.raycast(new RaycastContext(eye, eye.add(look.multiply(24)),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mob));
            Vec3d dest = hit.getPos().subtract(look.multiply(0.8));
            Vec3d before = mob.getPos();
            if (mob.teleport(dest.x, dest.y, dest.z, true)) {
                world.playSound(null, before.x, before.y, before.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.0f, 1.0f);
                sound(world, mob, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.0f);
            }
            return 30;
        }
        if (mob instanceof GuardianEntity) {
            Entity target = Aim.target(world, mob, 16.0, 6.0, e -> e instanceof LivingEntity && e != player);
            if (target == null) return 4;
            Vec3d to = target.getBoundingBox().getCenter();
            Vec3d path = to.subtract(eye);
            int steps = (int) Math.ceil(path.length() * 3);
            for (int i = 0; i <= steps; i++) {
                Vec3d p = eye.add(path.multiply(i / (double) steps));
                world.spawnParticles(ParticleTypes.BUBBLE, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
                world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
            target.damage(world.getDamageSources().indirectMagic(mob, mob), 6.0f);
            sound(world, mob, SoundEvents.ENTITY_GUARDIAN_ATTACK, 1.0f);
            return 40;
        }
        if (mob instanceof PufferfishEntity puffer) {
            puffer.setPuffState(2);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, mob.getBoundingBox().expand(1.5), e -> e != mob && e != player)) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 0), mob);
            }
            sound(world, mob, SoundEvents.ENTITY_PUFFER_FISH_BLOW_UP, 1.0f);
            return 60;
        }
        if (mob instanceof SquidEntity) {
            Vec3d c = mob.getBoundingBox().getCenter();
            world.spawnParticles(mob instanceof GlowSquidEntity ? ParticleTypes.GLOW_SQUID_INK : ParticleTypes.SQUID_INK,
                    c.x, c.y, c.z, 40, 0.6, 0.6, 0.6, 0.1);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, mob.getBoundingBox().expand(4), e -> e != mob && e != player)) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0), mob);
            }
            sound(world, mob, mob instanceof GlowSquidEntity ? SoundEvents.ENTITY_GLOW_SQUID_SQUIRT : SoundEvents.ENTITY_SQUID_SQUIRT, 1.0f);
            return 60;
        }
        if (mob instanceof ChickenEntity) {
            mob.dropItem(Items.EGG);
            world.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENTITY_CHICKEN_EGG, SoundCategory.NEUTRAL, 1.0f,
                    (world.random.nextFloat() - world.random.nextFloat()) * 0.2f + 1.0f);
            return 40;
        }
        if (mob instanceof SheepEntity sheep) {
            BlockPos below = mob.getBlockPos().down();
            BlockState state = world.getBlockState(below);
            if (state.isOf(Blocks.GRASS_BLOCK)) {
                world.syncWorldEvent(net.minecraft.world.WorldEvents.BLOCK_BROKEN, below, net.minecraft.block.Block.getRawIdFromState(state));
                world.setBlockState(below, Blocks.DIRT.getDefaultState(), 2);
                sheep.onEatingGrass();
                world.sendEntityStatus(mob, (byte) 10);
            } else {
                mob.playAmbientSound();
            }
            return 40;
        }
        if (mob instanceof GoatEntity) {
            ram(world, mob, player, look);
            return 40;
        }
        if (mob instanceof RavagerEntity) {
            roar(world, mob, player);
            return 60;
        }

        ItemStack held = mob.getMainHandStack();
        if (held.getItem() instanceof BowItem || held.getItem() instanceof CrossbowItem) {
            boolean crossbow = held.getItem() instanceof CrossbowItem;
            ArrowEntity arrow = new ArrowEntity(world, mob, new ItemStack(Items.ARROW), held.copy());
            arrow.setVelocity(mob, mob.getPitch(), mob.getYaw(), 0.0f, crossbow ? 3.15f : 1.6f, 1.0f);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            world.spawnEntity(arrow);
            mob.swingHand(Hand.MAIN_HAND);
            sound(world, mob, crossbow ? SoundEvents.ITEM_CROSSBOW_SHOOT : SoundEvents.ENTITY_SKELETON_SHOOT, 1.0f);
            return crossbow ? 25 : 20;
        }
        if (held.getItem() instanceof TridentItem) {
            TridentEntity trident = new TridentEntity(world, mob, held.copyWithCount(1));
            trident.setVelocity(mob, mob.getPitch(), mob.getYaw(), 0.0f, 1.6f, 1.0f);
            trident.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            world.spawnEntity(trident);
            mob.swingHand(Hand.MAIN_HAND);
            sound(world, mob, SoundEvents.ENTITY_DROWNED_SHOOT, 1.0f);
            return 30;
        }

        if (mob.getAttributes().hasAttribute(EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
            // Plain melee: bite, punch or sting whatever is in front of it
            double reach = mob.getWidth() * 0.5 + 2.5;
            Entity target = Aim.target(world, mob, reach, 10.0, e -> e instanceof LivingEntity && e != player);
            mob.swingHand(Hand.MAIN_HAND);
            if (target != null) mob.tryAttack(target);
            return 12;
        }

        // Nothing fancy to do: at least it can make its noise
        mob.playAmbientSound();
        return 20;
    }

    private static void sound(ServerWorld world, Entity at, SoundEvent sound, float pitch) {
        world.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundCategory.HOSTILE, 1.0f, pitch);
    }

    /** A line of evoker fangs snapping up along the ground, like the evoker's own attack. */
    private static void conjureFangs(ServerWorld world, MobEntity mob) {
        float yawRad = (float) Math.toRadians(mob.getYaw() + 90.0f);
        for (int i = 0; i < 16; i++) {
            double d = 1.25 * (i + 1);
            double x = mob.getX() + Math.cos(yawRad) * d;
            double z = mob.getZ() + Math.sin(yawRad) * d;
            // Find the ground near the evoker's feet
            BlockPos pos = BlockPos.ofFloored(x, mob.getY() + 2, z);
            Double groundY = null;
            for (int dy = 0; dy < 6; dy++) {
                BlockPos below = pos.down(dy + 1);
                if (world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
                        && world.getBlockState(below.up()).getCollisionShape(world, below.up()).isEmpty()) {
                    groundY = (double) below.getY() + 1;
                    break;
                }
            }
            if (groundY == null) continue;
            world.spawnEntity(new EvokerFangsEntity(world, x, groundY, z, yawRad, i, mob));
        }
    }

    /** Goats charge forward and send whatever they hit flying. */
    private static void ram(ServerWorld world, MobEntity mob, ServerPlayerEntity player, Vec3d look) {
        Vec3d flat = new Vec3d(look.x, 0, look.z);
        if (flat.lengthSquared() < 1.0e-4) return;
        flat = flat.normalize();
        mob.addVelocity(flat.x * 1.2, 0.1, flat.z * 1.2);
        mob.velocityModified = true;
        List<LivingEntity> hit = world.getEntitiesByClass(LivingEntity.class,
                mob.getBoundingBox().offset(flat.multiply(1.5)).expand(1.0), e -> e != mob && e != player);
        for (LivingEntity e : hit) {
            mob.tryAttack(e);
            e.takeKnockback(2.5, -flat.x, -flat.z);
        }
        sound(world, mob, hit.isEmpty() ? SoundEvents.ENTITY_GOAT_PREPARE_RAM : SoundEvents.ENTITY_GOAT_RAM_IMPACT, 1.0f);
    }

    /** The ravager's roar: everything close by is thrown back. */
    private static void roar(ServerWorld world, MobEntity mob, ServerPlayerEntity player) {
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, mob.getBoundingBox().expand(4.0), e -> e != mob && e != player)) {
            double dx = e.getX() - mob.getX();
            double dz = e.getZ() - mob.getZ();
            double len = Math.max(Math.sqrt(dx * dx + dz * dz), 0.001);
            e.damage(world.getDamageSources().mobAttack(mob), 6.0f);
            e.addVelocity(dx / len * 1.5, 0.3, dz / len * 1.5);
            e.velocityModified = true;
        }
        Vec3d c = mob.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 30, 1.5, 0.5, 1.5, 0.1);
        sound(world, mob, SoundEvents.ENTITY_RAVAGER_ROAR, 1.0f);
    }
}
