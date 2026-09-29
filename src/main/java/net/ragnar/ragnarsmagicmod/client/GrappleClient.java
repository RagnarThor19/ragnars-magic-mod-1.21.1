package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.item.spell.GrapplingSpell;
import net.ragnar.ragnarsmagicmod.network.GrapplePayloads;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tome of Grappling, client side. Draws every hook's chain - it whips out from the staff tip with a wave running
 * along it, pulls taut when it bites, and goes slack as it winds back in - and flies your own player along it:
 * a hard pull that ramps up, swinging round the anchor, WASD to steer, a pop up over the ledge when you arrive,
 * jump to let go and fling yourself on, sneak to let go gently. Speed widens your view and brings up wind.
 */
public final class GrappleClient {
    private GrappleClient() {}

    // The pull
    private static final double PULL = 0.2;          // acceleration toward the anchor, blocks/tick²
    private static final double REEL = 0.6;          // how fast the chain shortens, blocks/tick
    private static final double STEER = 0.05;
    private static final double MAX_SPEED = 2.2;
    private static final double ARRIVE = 1.6;
    private static final double MANTLE_BOOST = 0.55; // the hop up over the edge when you get there
    private static final double JUMP_FLING = 0.55;

    // The chain
    private static final int RETRACT_TICKS = 6;
    private static final float LINK = 0.3f;          // length and scale of one chain link
    private static final BlockState CHAIN = Blocks.CHAIN.getDefaultState();
    private static final ItemStack HOOK = new ItemStack(Items.TRIPWIRE_HOOK);

    private static final class Rope {
        final Vec3d anchor;
        final int entityId;
        final boolean pullSelf;
        final int travel;
        int age = 0;
        int retract = -1;
        Vec3d lastTip;

        Rope(Vec3d anchor, int entityId, boolean pullSelf, int travel) {
            this.anchor = anchor;
            this.entityId = entityId;
            this.pullSelf = pullSelf;
            this.travel = travel;
            this.lastTip = anchor;
        }

        boolean latched() {
            return age >= travel && retract < 0;
        }
    }

    private static final Map<Integer, Rope> ROPES = new HashMap<>();

    // Your own flight
    private static boolean pulling = false;
    private static double ropeLength;
    private static int pullTicks;
    private static boolean jumpHeld;
    private static float fov, prevFov;
    private static WindSound wind;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(GrapplePayloads.Attach.ID, (payload, context) -> {
            ROPES.put(payload.playerId(), new Rope(new Vec3d(payload.x(), payload.y(), payload.z()),
                    payload.entityId(), payload.pullSelf(), payload.travel()));
            ClientPlayerEntity player = context.client().player;
            if (player != null && payload.playerId() == player.getId()) pulling = false; // a fresh hook starts over
        });
        ClientPlayNetworking.registerGlobalReceiver(GrapplePayloads.End.ID, (payload, context) -> {
            Rope r = ROPES.get(payload.playerId());
            if (r != null && r.retract < 0) r.retract = 0;
            ClientPlayerEntity player = context.client().player;
            if (player != null && payload.playerId() == player.getId()) pulling = false;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ROPES.clear();
            pulling = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(GrappleClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(GrappleClient::render);
    }

    /** How much wider to draw the view right now, for the speed rush (read by GameRendererMixin). */
    public static float fovMultiplier(float tickDelta) {
        return 1f + MathHelper.lerp(tickDelta, prevFov, fov);
    }

    // Ticking

    private static void tick(MinecraftClient client) {
        prevFov = fov;
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            ROPES.clear();
            pulling = false;
            return;
        }
        if (client.isPaused()) return;

        for (Iterator<Map.Entry<Integer, Rope>> it = ROPES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Rope> e = it.next();
            Rope r = e.getValue();
            Entity owner = world.getEntityById(e.getKey());
            if (owner == null || (r.retract >= 0 && ++r.retract > RETRACT_TICKS)) { it.remove(); continue; }
            r.age++;
            if (r.age == r.travel) {
                if (owner == player) {
                    ScreenShake.kick(0.3f, 6);
                    if (r.pullSelf) startPull(player, r);
                }
            } else if (r.age < r.travel) {
                // Sparks trailing off the flying hook
                Vec3d tip = r.lastTip;
                world.addParticle(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 0, 0, 0);
            }
        }

        Rope mine = ROPES.get(player.getId());
        if (pulling && (mine == null || !mine.latched() || !player.isAlive())) pulling = false;
        if (pulling) pull(client, player, mine);
        jumpHeld = player.input != null && player.input.jumping;

        float target = pulling ? (float) Math.min(0.22, player.getVelocity().length() * 0.1) : 0f;
        fov += (target - fov) * 0.25f;
    }

    private static void startPull(ClientPlayerEntity player, Rope r) {
        pulling = true;
        pullTicks = 0;
        ropeLength = anchorOf(player.clientWorld, r, 1f).distanceTo(pullPoint(player));
        if (player.isOnGround()) player.addVelocity(0, 0.35, 0);
        if (wind == null || wind.isDone()) {
            wind = new WindSound(player);
            MinecraftClient.getInstance().getSoundManager().play(wind);
        }
    }

    private static void pull(MinecraftClient client, ClientPlayerEntity player, Rope r) {
        Entity hooked = r.entityId >= 0 ? player.getWorld().getEntityById(r.entityId) : null;
        if (r.entityId >= 0 && (hooked == null || !hooked.isAlive())) { release(player, false); return; }

        Vec3d anchor = anchorOf(player.clientWorld, r, 1f);
        Vec3d to = anchor.subtract(pullPoint(player));
        double dist = to.length();
        Vec3d dir = dist < 1.0e-4 ? Vec3d.ZERO : to.multiply(1.0 / dist);
        pullTicks++;

        boolean jumping = player.input != null && player.input.jumping;
        if (jumping && !jumpHeld) { release(player, true); return; }
        if (player.input != null && player.input.sneaking) { release(player, false); return; }
        if (dist < ARRIVE) {
            // Made it: hop up and over whatever we were pulled to
            Vec3d v = player.getVelocity().multiply(0.35);
            double up = anchor.y > player.getY() - 0.5 ? MANTLE_BOOST : 0.2;
            player.setVelocity(v.x + dir.x * 0.2, Math.max(v.y, up), v.z + dir.z * 0.2);
            release(player, false);
            return;
        }
        if (pullTicks > GrapplingSpell.MAX_PULL_TICKS) { release(player, false); return; }

        double ramp = Math.min(1.0, pullTicks / 6.0);
        Vec3d v = player.getVelocity().add(dir.multiply(PULL * ramp));

        // Steering: WASD pushes you sideways around the anchor, never along the chain
        Vec3d look = player.getRotationVector();
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? Vec3d.ZERO : right.normalize();
        Vec3d steer = right.multiply(-player.input.movementSideways).add(look.multiply(player.input.movementForward * 0.6));
        steer = steer.subtract(dir.multiply(steer.dotProduct(dir)));
        v = v.add(steer.multiply(STEER));

        // The chain winds in, and never lets you get further away than it is long: that's what makes you swing
        ropeLength = Math.max(ARRIVE, Math.min(ropeLength, dist) - REEL * ramp);
        if (dist > ropeLength) {
            double outward = v.dotProduct(dir);
            if (outward < 0) v = v.subtract(dir.multiply(outward));
            v = v.add(dir.multiply((dist - ropeLength) * 0.2));
        }
        // Scrape up walls instead of sticking to them
        if (player.horizontalCollision && anchor.y > player.getY()) v = v.add(0, 0.15, 0);

        double speed = v.length();
        if (speed > MAX_SPEED) v = v.multiply(MAX_SPEED / speed);
        player.setVelocity(v);
        player.fallDistance = 0;

        // Wind streaking past
        if (speed > 0.8) {
            Random rand = player.getRandom();
            for (int i = 0; i < 2; i++) {
                Vec3d at = player.getEyePos().add(v.normalize().multiply(2.5))
                        .add(rand.nextGaussian() * 1.0, rand.nextGaussian() * 0.8, rand.nextGaussian() * 1.0);
                player.getWorld().addParticle(ParticleTypes.CLOUD, at.x, at.y, at.z, -v.x * 0.4, -v.y * 0.4, -v.z * 0.4);
            }
        }
    }

    private static void release(ClientPlayerEntity player, boolean jumped) {
        pulling = false;
        if (jumped) {
            Vec3d v = player.getVelocity();
            Vec3d look = player.getRotationVector();
            player.setVelocity(v.x + look.x * 0.3, Math.max(v.y, 0) + JUMP_FLING, v.z + look.z * 0.3);
            ScreenShake.kick(0.2f, 5);
        }
        Rope r = ROPES.get(player.getId());
        if (r != null && r.retract < 0) r.retract = 0;
        if (ClientPlayNetworking.canSend(GrapplePayloads.Release.ID)) ClientPlayNetworking.send(new GrapplePayloads.Release(jumped));
    }

    /** Where the chain pulls on you: about waist-high. */
    private static Vec3d pullPoint(ClientPlayerEntity player) {
        return player.getPos().add(0, player.getHeight() * 0.55, 0);
    }

    private static Vec3d anchorOf(ClientWorld world, Rope r, float tickDelta) {
        if (r.entityId >= 0) {
            Entity e = world.getEntityById(r.entityId);
            if (e != null) return e.getLerpedPos(tickDelta).add(0, e.getHeight() * 0.5, 0);
        }
        return r.anchor;
    }

    // Drawing

    private static void render(WorldRenderContext context) {
        if (ROPES.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        for (Map.Entry<Integer, Rope> e : ROPES.entrySet()) {
            if (!(world.getEntityById(e.getKey()) instanceof LivingEntity owner)) continue;
            Rope r = e.getValue();
            Vec3d from = staffTip(client, owner, tickDelta);
            Vec3d anchor = anchorOf(world, r, tickDelta);
            float age = r.age + tickDelta;

            // How far out the hook is: whipping out, taut, or winding back in
            float out;
            float slack = 0f;
            if (r.retract >= 0) {
                float t = MathHelper.clamp((r.retract + tickDelta) / RETRACT_TICKS, 0f, 1f);
                out = 1f - t * t;
                slack = MathHelper.sin(t * MathHelper.PI);
            } else if (age < r.travel) {
                float t = age / r.travel;
                out = 1f - (1f - t) * (1f - t);
            } else {
                out = 1f;
            }
            Vec3d tip = from.add(anchor.subtract(from).multiply(out));
            r.lastTip = tip;
            float wave = r.retract < 0 && age < r.travel + 4 ? 1f - MathHelper.clamp(age / (r.travel + 4), 0f, 1f) : 0f;
            drawChain(ms, buffers, world, cam, from, tip, age, wave, slack);
            drawHook(ms, buffers, world, cam, from, tip);
        }
        buffers.draw();
    }

    /** Chain links from {@code from} to {@code to}, each turned a quarter from the last, like a real chain. */
    private static void drawChain(MatrixStack ms, VertexConsumerProvider buffers, ClientWorld world, Vec3d cam,
                                  Vec3d from, Vec3d to, float age, float wave, float slack) {
        Vec3d span = to.subtract(from);
        double len = span.length();
        if (len < 0.05) return;
        Vec3d dir = span.multiply(1.0 / len);
        Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0));
        side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
        Vec3d bend = side.crossProduct(dir).normalize();

        int links = Math.max(1, (int) Math.ceil(len / LINK));
        Vec3d prev = from;
        for (int i = 1; i <= links; i++) {
            double f = i / (double) links;
            Vec3d p = from.add(span.multiply(f));
            // A wave running down the chain as it's thrown, and a droop while it winds back in
            double env = Math.sin(f * Math.PI);
            p = p.add(side.multiply(Math.sin(f * 9.0 - age * 1.6) * 0.35 * wave * env))
                    .add(bend.multiply(Math.cos(f * 7.0 - age * 1.3) * 0.2 * wave * env))
                    .add(0, -slack * env * Math.min(1.5, len * 0.15), 0);
            Vec3d seg = p.subtract(prev);
            double segLen = seg.length();
            if (segLen > 1.0e-4) {
                Vec3d mid = prev.add(seg.multiply(0.5));
                Vec3d d = seg.multiply(1.0 / segLen);
                ms.push();
                ms.translate(mid.x - cam.x, mid.y - cam.y, mid.z - cam.z);
                ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) d.x, (float) d.y, (float) d.z));
                if (i % 2 == 0) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90f));
                ms.scale(LINK * 1.6f, (float) segLen * 1.02f, LINK * 1.6f);
                ms.translate(-0.5, -0.5, -0.5);
                int light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(mid));
                MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(CHAIN, ms, buffers, light, OverlayTexture.DEFAULT_UV);
                ms.pop();
            }
            prev = p;
        }
    }

    /** A hook at the end of the chain, pointing the way it flew. */
    private static void drawHook(MatrixStack ms, VertexConsumerProvider buffers, ClientWorld world, Vec3d cam, Vec3d from, Vec3d tip) {
        Vec3d d = tip.subtract(from);
        if (d.lengthSquared() < 1.0e-4) return;
        d = d.normalize();
        ms.push();
        ms.translate(tip.x - cam.x, tip.y - cam.y, tip.z - cam.z);
        ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) d.x, (float) d.y, (float) d.z));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180f)); // the hook's point leads
        ms.scale(0.6f, 0.6f, 0.6f);
        int light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(tip));
        MinecraftClient.getInstance().getItemRenderer().renderItem(HOOK, ModelTransformationMode.FIXED, light,
                OverlayTexture.DEFAULT_UV, ms, buffers, world, 0);
        ms.pop();
    }

    /** The end of the staff: beside the crosshair in first person, at the hand otherwise. */
    private static Vec3d staffTip(MinecraftClient client, LivingEntity caster, float tickDelta) {
        Vec3d eye = caster.getCameraPosVec(tickDelta);
        Vec3d look = caster.getRotationVec(tickDelta);
        int side = staffSide(caster);
        if (caster == client.player && client.options.getPerspective().isFirstPerson() && client.getCameraEntity() == caster) {
            Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
            right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
            Vec3d up = right.crossProduct(look).normalize();
            return eye.add(look.multiply(0.8)).add(right.multiply(0.3 * side)).add(up.multiply(-0.2));
        }
        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, caster.prevBodyYaw, caster.bodyYaw);
        Vec3d fwd = Vec3d.fromPolar(0, bodyYaw);
        Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
        return caster.getLerpedPos(tickDelta).add(0, caster.isInSneakingPose() ? 1.1 : 1.35, 0)
                .add(fwd.multiply(0.5)).add(right.multiply(0.36 * side)).add(look.multiply(0.35));
    }

    private static int staffSide(LivingEntity caster) {
        Hand hand = caster instanceof ClientPlayerEntity p ? SpellSwitcher.findStaffHand(p) : null;
        Arm arm = caster.getMainArm();
        if (hand == Hand.OFF_HAND) arm = arm.getOpposite();
        return arm == Arm.RIGHT ? 1 : -1;
    }

    /** Rushing air that follows you and gets louder the faster you go. */
    private static final class WindSound extends MovingSoundInstance {
        private final ClientPlayerEntity player;

        WindSound(ClientPlayerEntity player) {
            super(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS, SoundInstance.createRandom());
            this.player = player;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = 0.0001f;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
        }

        @Override
        public boolean shouldAlwaysPlay() {
            return true;
        }

        @Override
        public void tick() {
            if (player.isRemoved()) { setDone(); return; }
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            double speed = player.getVelocity().length();
            float target = pulling ? (float) MathHelper.clamp((speed - 0.3) * 0.5, 0.0, 0.8) : 0f;
            volume += (target - volume) * (pulling ? 0.3f : 0.15f);
            pitch = 0.8f + (float) Math.min(speed, 2.0) * 0.3f;
            if (!pulling && volume < 0.01f) setDone();
        }
    }
}
