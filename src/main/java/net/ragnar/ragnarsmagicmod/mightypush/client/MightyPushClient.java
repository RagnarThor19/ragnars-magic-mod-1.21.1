package net.ragnar.ragnarsmagicmod.mightypush.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.Input;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.mightypush.MightyPush;
import net.ragnar.ragnarsmagicmod.mightypush.MightyPushCast;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client half of the Tome of Mighty Pushing (see MightyPush).
 * <ul>
 *   <li>The caster spreads their arms into a cross (MightyPushPoseMixin calls {@link #pose}); your own view swings to
 *       third person while you cast, and your keys can't move you.</li>
 *   <li>"MIGHTY PUSH!" slams onto the screens of everyone near as it goes, with a white flash.</li>
 *   <li>While it charges, specks of light are drawn into the caster and the rings of a great lilac eye turn beneath
 *       them.</li>
 *   <li>The barrier is a shimmering shell, clear in the middle and bright at its edge like a heat haze, with a ring of
 *       light where it meets the ground.</li>
 *   <li>The ground heaves up in a wave as it passes - copies of the real blocks, lifted and tipped outward and let
 *       down again, never the blocks themselves - and chunks of earth are flung out ahead of it. All of it is drawn
 *       here, so the server never touches a block.</li>
 * </ul>
 */
public final class MightyPushClient {
    private MightyPushClient() {}

    private static final int SHOUT_TICKS = 34;
    private static final int FLASH_TICKS = 10;
    /** How far behind the barrier's face the ground is still heaving. */
    private static final double BAND = 2.6;
    private static final int MAX_DEBRIS = 70;
    /** How near a cast has to be for its words to show on your screen. */
    private static final double HEAR_RANGE = 64;

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_mighty_push",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** A patch of ground the barrier will pass over: the top block there, and how far the barrier must reach for it. */
    private record Column(BlockPos pos, BlockState state, double reach, Vec3d out) {}

    /** A chunk of earth flung out ahead of the barrier. */
    private static final class Debris {
        final BlockState state;
        Vec3d pos, prev, vel;
        final Vec3d axis;
        final float size, spinSpeed;
        float spin, prevSpin;
        final double landY;
        int age;

        Debris(BlockState state, Vec3d pos, Vec3d vel, Vec3d axis, float size, float spinSpeed) {
            this.state = state;
            this.pos = this.prev = pos;
            this.vel = vel;
            this.axis = axis;
            this.size = size;
            this.spinSpeed = spinSpeed;
            this.landY = pos.y;
        }
    }

    private static final class Cast {
        final int casterId;
        final boolean airborne;
        int age;
        int pushAge = -1;
        Vec3d center;
        double groundY;
        final List<Column> columns = new ArrayList<>();
        int reached;
        final List<Debris> debris = new ArrayList<>();
        boolean ended;
        int endAge;

        Cast(int casterId, boolean airborne) {
            this.casterId = casterId;
            this.airborne = airborne;
        }

        boolean released() {
            return pushAge >= 0;
        }
    }

    private static final Map<Integer, Cast> CASTS = new HashMap<>();
    private static Perspective perspectiveBefore;
    private static int flash;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(MightyPush.ChargePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            CASTS.put(payload.casterId(), new Cast(payload.casterId(), payload.airborne()));
            if (client.player != null && client.player.getId() == payload.casterId()) {
                if (perspectiveBefore == null) perspectiveBefore = client.options.getPerspective();
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MightyPush.ReleasePayload.ID, (payload, context) -> {
            Cast c = CASTS.computeIfAbsent(payload.casterId(), id -> new Cast(id, false));
            MinecraftClient client = context.client();
            if (client.world == null) return;
            c.center = new Vec3d(payload.center());
            c.pushAge = 0;
            survey(client.world, c);
            if (client.player != null && client.player.getPos().distanceTo(c.center) < MightyPushCast.MAX_RADIUS + 15) flash = FLASH_TICKS;
        });
        ClientPlayNetworking.registerGlobalReceiver(MightyPush.EndPayload.ID, (payload, context) -> {
            Cast c = CASTS.get(payload.casterId());
            if (c != null) c.ended = true;
            MinecraftClient client = context.client();
            if (client.player != null && client.player.getId() == payload.casterId()) restoreView(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CASTS.clear();
            flash = 0;
            restoreView(client);
        });
        ClientTickEvents.END_CLIENT_TICK.register(MightyPushClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(MightyPushClient::renderBlocks);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(MightyPushClient::renderLight);
        HudRenderCallback.EVENT.register(MightyPushClient::renderHud);
    }

    private static void restoreView(MinecraftClient client) {
        if (perspectiveBefore != null) client.options.setPerspective(perspectiveBefore);
        perspectiveBefore = null;
    }

    // ---------------------------------------------------------------------
    // The caster
    // ---------------------------------------------------------------------

    /** True while {@code entity} is casting (its pose is set, and if it's you, your keys are held). */
    private static Cast casting(Entity entity) {
        Cast c = CASTS.get(entity.getId());
        return c == null || c.ended ? null : c;
    }

    /** Arms rising out into a cross as the chant goes on, held wide through the push, chin up as it goes. */
    public static void pose(LivingEntity entity, BipedEntityModel<?> model, float tickDelta) {
        Cast c = casting(entity);
        if (c == null) return;
        float t = c.age + tickDelta;
        float raise = MathHelper.clamp(t / 30f, 0f, 1f);
        raise = raise * raise * (3f - 2f * raise);
        float tremble = c.released() ? 0f : 0.03f * MathHelper.sin(t * 2.3f) * raise;
        float spread = MathHelper.HALF_PI * raise;
        setArm(model.rightArm, spread + tremble);
        setArm(model.leftArm, -spread - tremble);
        if (c.released()) {
            // Palms thrust outward, and the head thrown back
            model.rightArm.yaw = 0.25f;
            model.leftArm.yaw = -0.25f;
            model.head.pitch = Math.min(model.head.pitch, -0.35f);
        }
    }

    private static void setArm(ModelPart arm, float roll) {
        arm.pitch = 0f;
        arm.yaw = 0f;
        arm.roll = roll;
    }

    /** Called after the keyboard input ticks: while you're casting, you stay put. */
    public static void captureInput(Input input) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || casting(client.player) == null) return;
        input.movementForward = 0;
        input.movementSideways = 0;
        input.pressingForward = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
        input.jumping = false;
        input.sneaking = false;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (flash > 0 && !client.isPaused()) flash--;
        if (client.world == null || client.isPaused() || CASTS.isEmpty()) return;
        ClientWorld world = client.world;
        Iterator<Cast> it = CASTS.values().iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            c.age++;
            if (c.released()) c.pushAge++;
            if (c.ended) c.endAge++;
            Entity caster = world.getEntityById(c.casterId);
            if (!c.released() && caster != null) chargeParticles(world, c, caster);
            if (c.released()) heave(world, c);
            tickDebris(world, c);
            // Gone once the barrier's faded and the last chunk has come down
            if ((c.ended && c.endAge > 30 && c.debris.isEmpty()) || c.age > 400) it.remove();
        }
    }

    /** Specks of light sucked in to the caster, and grit lifting off the ground under them. */
    private static void chargeParticles(ClientWorld world, Cast c, Entity caster) {
        Random r = world.random;
        Vec3d mid = caster.getPos().add(0, caster.getHeight() * 0.55, 0);
        float k = MathHelper.clamp(c.age / (float) MightyPushCast.CHARGE_TICKS, 0f, 1f);
        int n = 2 + (int) (6 * k);
        for (int i = 0; i < n; i++) {
            Vec3d d = randomDir(r).multiply(4 + r.nextDouble() * 5);
            // Portal specks fly from (pos + velocity) in to pos
            world.addParticle(ParticleTypes.PORTAL, mid.x, mid.y, mid.z, d.x, d.y, d.z);
        }
        if (r.nextFloat() < k * 0.3f) {
            Vec3d d = randomDir(r).multiply(3 + r.nextDouble() * 3);
            world.addParticle(ParticleTypes.END_ROD, mid.x + d.x, mid.y + d.y, mid.z + d.z, -d.x * 0.08, -d.y * 0.08, -d.z * 0.08);
        }
        BlockPos below = caster.getBlockPos().down();
        BlockState ground = world.getBlockState(below);
        if (!c.airborne && !ground.isAir() && r.nextFloat() < 0.3f + 0.6f * k) {
            double a = r.nextDouble() * Math.PI * 2, rad = 0.8 + r.nextDouble() * 3;
            world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), caster.getX() + Math.cos(a) * rad,
                    caster.getY() + 0.1, caster.getZ() + Math.sin(a) * rad, 0, 0.3 + r.nextDouble() * 0.3, 0);
        }
    }

    /** Works out, once, which bit of ground the barrier will pass over and when. */
    private static void survey(ClientWorld world, Cast c) {
        Vec3d o = c.center;
        double max = MightyPushCast.MAX_RADIUS;
        var down = world.raycast(new RaycastContext(o, o.add(0, -40, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        c.groundY = down.getType() == HitResult.Type.MISS ? o.y - 40 : down.getPos().y;
        int top = (int) Math.floor(o.y) + 3, bottom = (int) Math.floor(c.groundY) - 6;
        int r = (int) Math.ceil(max);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double h = Math.sqrt(dx * dx + dz * dz);
                if (h > max) continue;
                int x = (int) Math.floor(o.x) + dx, z = (int) Math.floor(o.z) + dz;
                for (int y = top; y >= bottom; y--) {
                    p.set(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (s.isAir() || s.getCollisionShape(world, p).isEmpty()) continue;
                    if (s.getRenderType() != BlockRenderType.MODEL) break;
                    double vertical = o.y - (y + 1);
                    double reach = Math.sqrt(h * h + vertical * vertical);
                    if (reach <= max && reach > 1.5) {
                        Vec3d out = new Vec3d(x + 0.5 - o.x, 0, z + 0.5 - o.z);
                        out = out.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : out.normalize();
                        c.columns.add(new Column(p.toImmutable(), s, reach, out));
                    }
                    break;
                }
            }
        }
        c.columns.sort((a, b) -> Double.compare(a.reach(), b.reach()));
    }

    /** As the barrier reaches each bit of ground: sometimes a chunk of it flies, sometimes a puff of dust. */
    private static void heave(ClientWorld world, Cast c) {
        if (c.pushAge > MightyPushCast.PUSH_TICKS) return;
        double radius = MightyPushCast.radiusAt(c.pushAge);
        Random r = world.random;
        while (c.reached < c.columns.size() && c.columns.get(c.reached).reach() <= radius) {
            Column col = c.columns.get(c.reached++);
            Vec3d top = Vec3d.ofBottomCenter(col.pos().up());
            if (c.debris.size() < MAX_DEBRIS && r.nextFloat() < 0.045f) {
                Vec3d vel = col.out().multiply(0.3 + r.nextDouble() * 0.35).add(0, 0.45 + r.nextDouble() * 0.45, 0);
                c.debris.add(new Debris(col.state(), top.add(0, 0.3, 0), vel, randomDir(r), 0.45f + r.nextFloat() * 0.3f,
                        8f + r.nextFloat() * 18f));
            }
            if (r.nextFloat() < 0.12f) {
                world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, col.state()), top.x, top.y + 0.1, top.z,
                        col.out().x * 0.3, 0.25, col.out().z * 0.3);
            }
            if (col.reach() > 6 && r.nextFloat() < 0.05f) {
                world.addParticle(ParticleTypes.CLOUD, top.x, top.y + 0.3, top.z, col.out().x * 0.25, 0.04, col.out().z * 0.25);
            }
        }
    }

    private static void tickDebris(ClientWorld world, Cast c) {
        Iterator<Debris> it = c.debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            d.age++;
            d.prev = d.pos;
            d.prevSpin = d.spin;
            d.vel = d.vel.multiply(0.98).add(0, -0.05, 0);
            d.pos = d.pos.add(d.vel);
            d.spin += d.spinSpeed;
            boolean landed = d.age > 6 && d.vel.y < 0 && (d.pos.y < d.landY - 0.2 || !world.getBlockState(BlockPos.ofFloored(d.pos)).isAir());
            if (landed || d.age > 80) {
                for (int i = 0; i < 6; i++) {
                    world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, d.state), d.pos.x, d.pos.y + 0.2, d.pos.z,
                            (world.random.nextDouble() - 0.5) * 0.3, 0.2, (world.random.nextDouble() - 0.5) * 0.3);
                }
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing: the ground and the debris
    // ---------------------------------------------------------------------

    private static void renderBlocks(WorldRenderContext context) {
        if (CASTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        var blocks = client.getBlockRenderManager();
        boolean drew = false;

        for (Cast c : CASTS.values()) {
            if (c.released() && c.pushAge <= MightyPushCast.PUSH_TICKS + 4) {
                double radius = MightyPushCast.radiusAt(c.pushAge + tickDelta);
                // The ground heaving up in a wave just behind the barrier's face
                for (int i = lowerBound(c.columns, radius - BAND); i < c.columns.size(); i++) {
                    Column col = c.columns.get(i);
                    if (col.reach() > radius) break;
                    double x = (radius - col.reach()) / BAND;
                    float wave = (float) Math.sin(Math.PI * x);
                    float strength = (float) (1.0 - 0.45 * col.reach() / MightyPushCast.MAX_RADIUS);
                    float lift = 1.15f * wave * strength;
                    if (lift < 0.02f) continue;
                    ms.push();
                    ms.translate(col.pos().getX() + 0.5 - cam.x, col.pos().getY() + 0.5 + lift - cam.y, col.pos().getZ() + 0.5 - cam.z);
                    // Tipped outward, away from the caster, as it rides the wave
                    ms.multiply(RotationAxis.of(new org.joml.Vector3f((float) -col.out().z, 0, (float) col.out().x)).rotation(0.3f * wave));
                    ms.translate(-0.5, -0.5, -0.5);
                    blocks.renderBlockAsEntity(col.state(), ms, buffers, WorldRenderer.getLightmapCoordinates(world, col.pos().up()), OverlayTexture.DEFAULT_UV);
                    ms.pop();
                    drew = true;
                }
            }
            // Chunks of earth tumbling through the air
            for (Debris d : c.debris) {
                Vec3d p = d.prev.lerp(d.pos, tickDelta);
                ms.push();
                ms.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                ms.multiply(RotationAxis.of(d.axis.toVector3f()).rotationDegrees(MathHelper.lerp(tickDelta, d.prevSpin, d.spin)));
                ms.scale(d.size, d.size, d.size);
                ms.translate(-0.5, -0.5, -0.5);
                blocks.renderBlockAsEntity(d.state, ms, buffers, WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(p)), OverlayTexture.DEFAULT_UV);
                ms.pop();
                drew = true;
            }
        }
        if (drew) buffers.draw();
    }

    /** The first column at least {@code reach} out. */
    private static int lowerBound(List<Column> columns, double reach) {
        int lo = 0, hi = columns.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (columns.get(mid).reach() < reach) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ---------------------------------------------------------------------
    // Drawing: light
    // ---------------------------------------------------------------------

    private static Matrix4f pose = new Matrix4f();
    private static Vec3d camPos = Vec3d.ZERO;

    private static void renderLight(WorldRenderContext context) {
        if (CASTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        camPos = context.camera().getPos();
        Vec3d fwd = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(GLOW);

        for (Cast c : CASTS.values()) {
            Entity caster = client.world.getEntityById(c.casterId);
            if (!c.released()) {
                if (caster != null) eye(vc, c, caster, tickDelta, right, up);
                continue;
            }
            float t = c.pushAge + tickDelta;
            float p = MathHelper.clamp(t / MightyPushCast.PUSH_TICKS, 0f, 1f);
            if (p >= 1f && t > MightyPushCast.PUSH_TICKS + 12) continue;
            double radius = MightyPushCast.radiusAt(t);
            float fade = p < 0.75f ? 1f : Math.max(0f, 1f - (t - MightyPushCast.PUSH_TICKS * 0.75f) / (MightyPushCast.PUSH_TICKS * 0.25f + 12));
            // The barrier: clear in the middle, bright at the edge, with a fainter echo just inside it
            shell(vc, c.center, radius, 0.95f, 0.92f, 1f, 0.55f * fade);
            shell(vc, c.center, radius * 0.94, 0.8f, 0.75f, 1f, 0.22f * fade);
            // Where it meets the ground
            double h = c.center.y - c.groundY;
            if (radius > h) {
                double ring = Math.sqrt(radius * radius - h * h);
                flatRing(vc, new Vec3d(c.center.x, c.groundY + 0.06, c.center.z), (float) ring, 1.4f, 0.95f, 0.95f, 1f, 0.6f * fade);
            }
            // The flash as it goes
            float f = MathHelper.clamp(1f - t / 10f, 0f, 1f);
            if (f > 0f) {
                glow(vc, c.center, right, up, 4f + 8f * (1f - f), 1f, 1f, 1f, f);
                glow(vc, c.center, right, up, 12f, 0.75f, 0.6f, 1f, 0.5f * f);
            }
        }
        buffers.draw(GLOW);
    }

    /** The great lilac eye beneath a charging caster: rings closing in, a glow at its heart, an aura round the caster. */
    private static void eye(VertexConsumer vc, Cast c, Entity caster, float tickDelta, Vec3d right, Vec3d up) {
        float t = c.age + tickDelta;
        float k = MathHelper.clamp(t / 20f, 0f, 1f);
        Vec3d at = caster.getLerpedPos(tickDelta);
        Vec3d floor = c.airborne ? at.add(0, -0.3, 0) : at.add(0, 0.05, 0);
        for (int i = 0; i < 6; i++) {
            float frac = (i / 6f + t * 0.012f) % 1f;
            float rad = 0.6f + 5.5f * (1f - frac);
            float a = 0.28f * k * MathHelper.sin(frac * MathHelper.PI);
            flatRing(vc, floor, rad, 0.14f, 0.72f, 0.55f, 1f, a);
        }
        flatRing(vc, floor, 6.2f, 0.1f, 0.6f, 0.45f, 1f, 0.35f * k);
        disc(vc, floor, 0.9f, 0.8f, 0.65f, 1f, 0.6f * k);
        float charge = MathHelper.clamp(t / MightyPushCast.CHARGE_TICKS, 0f, 1f);
        float pulse = 0.85f + 0.15f * MathHelper.sin(t * 0.6f);
        glow(vc, at.add(0, caster.getHeight() * 0.55, 0), right, up, (1.2f + 1.8f * charge) * pulse, 0.7f, 0.55f, 1f, 0.35f * k);
    }

    /** A sphere seen like a bubble of heat haze: see-through facing you, glowing where you look across its surface. */
    private static void shell(VertexConsumer vc, Vec3d c, double radius, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || radius < 0.2) return;
        int lat = 24, lon = 40;
        for (int i = 0; i < lat; i++) {
            double t0 = Math.PI * i / lat, t1 = Math.PI * (i + 1) / lat;
            for (int j = 0; j < lon; j++) {
                double p0 = Math.PI * 2 * j / lon, p1 = Math.PI * 2 * (j + 1) / lon;
                shellVertex(vc, c, radius, t0, p0, r, g, b, alpha);
                shellVertex(vc, c, radius, t1, p0, r, g, b, alpha);
                shellVertex(vc, c, radius, t1, p1, r, g, b, alpha);
                shellVertex(vc, c, radius, t0, p1, r, g, b, alpha);
            }
        }
    }

    private static void shellVertex(VertexConsumer vc, Vec3d c, double radius, double theta, double phi, float r, float g, float b, float alpha) {
        Vec3d n = new Vec3d(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
        Vec3d p = c.add(n.multiply(radius));
        Vec3d view = camPos.subtract(p);
        double len = view.length();
        double facing = len < 1e-4 ? 1 : Math.abs(n.dotProduct(view) / len);
        float edge = (float) Math.pow(1 - facing, 3);
        v(vc, p, r, g, b, alpha * edge);
    }

    private static void glow(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        int n = 32;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3d e0 = c.add(right.multiply(Math.cos(t0) * size)).add(up.multiply(Math.sin(t0) * size));
            Vec3d e1 = c.add(right.multiply(Math.cos(t1) * size)).add(up.multiply(Math.sin(t1) * size));
            v(vc, c, r, g, b, alpha);
            v(vc, c, r, g, b, alpha);
            v(vc, e1, r, g, b, 0f);
            v(vc, e0, r, g, b, 0f);
        }
    }

    private static void disc(VertexConsumer vc, Vec3d c, float radius, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        int n = 24;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            v(vc, c, r, g, b, alpha);
            v(vc, c, r, g, b, alpha);
            v(vc, c.add(Math.cos(t1) * radius, 0, Math.sin(t1) * radius), r, g, b, 0f);
            v(vc, c.add(Math.cos(t0) * radius, 0, Math.sin(t0) * radius), r, g, b, 0f);
        }
    }

    /** A glowing band lying flat at {@code radius}, bright along its middle and soft at both edges. */
    private static void flatRing(VertexConsumer vc, Vec3d c, float radius, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || radius <= 0.05f) return;
        int n = Math.max(24, Math.min(160, (int) (radius * 5)));
        float in = Math.max(0f, radius - width), out = radius + width * 0.5f;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
            v(vc, c.add(c0 * in, 0, s0 * in), r, g, b, 0f);
            v(vc, c.add(c1 * in, 0, s1 * in), r, g, b, 0f);
            v(vc, c.add(c1 * radius, 0, s1 * radius), r, g, b, alpha);
            v(vc, c.add(c0 * radius, 0, s0 * radius), r, g, b, alpha);
            v(vc, c.add(c0 * radius, 0, s0 * radius), r, g, b, alpha);
            v(vc, c.add(c1 * radius, 0, s1 * radius), r, g, b, alpha);
            v(vc, c.add(c1 * out, 0, s1 * out), r, g, b, 0f);
            v(vc, c.add(c0 * out, 0, s0 * out), r, g, b, 0f);
        }
    }

    private static void v(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - camPos.x), (float) (p.y - camPos.y), (float) (p.z - camPos.z)).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }

    private static Vec3d randomDir(Random r) {
        Vec3d d = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
        return d.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : d.normalize();
    }

    // ---------------------------------------------------------------------
    // On screen: the words, and the flash
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        float tickDelta = counter.getTickDelta(false);

        if (flash > 0) {
            float a = (flash - tickDelta) / FLASH_TICKS;
            int alpha = (int) (MathHelper.clamp(a * a, 0f, 1f) * 200);
            context.fill(0, 0, w, h, alpha << 24 | 0xF4F0FF);
        }
        if (client.options.hudHidden) return;

        // The nearest cast within earshot has the floor
        Cast speaking = null;
        double best = HEAR_RANGE;
        for (Cast c : CASTS.values()) {
            Entity caster = client.world.getEntityById(c.casterId);
            if (caster == null) continue;
            double d = caster.distanceTo(client.player);
            if (d < best) {
                best = d;
                speaking = c;
            }
        }
        if (speaking == null) return;
        Cast c = speaking;

        if (c.released() && c.pushAge < SHOUT_TICKS) {
            // The shout: slammed onto the screen, shaking
            float t = c.pushAge + tickDelta;
            float scale = 3.6f + 2.4f * Math.max(0f, 1f - t / 4f);
            float a = Math.min(1f, (SHOUT_TICKS - t) / 8f);
            float shake = 2.5f * Math.max(0f, 1f - t / 14f);
            int jitter = (int) (MathHelper.sin(t * 9f) * shake);
            line(context, client, Text.literal("MIGHTY PUSH!").formatted(Formatting.BOLD), w, (int) (h * 0.3f), scale, a, 0xFFFFFF, jitter);
        }
    }

    /** One line of big text, centred, with a deep purple shadow under it. */
    private static void line(DrawContext context, MinecraftClient client, Text text, int w, int y, float scale, float alpha, int color, int jitter) {
        int a = (int) (MathHelper.clamp(alpha, 0f, 1f) * 255);
        if (a < 5) return;
        var tr = client.textRenderer;
        RenderSystem.enableBlend();
        context.getMatrices().push();
        context.getMatrices().translate(w / 2f + jitter, y, 0);
        context.getMatrices().scale(scale, scale, 1f);
        int x = -tr.getWidth(text) / 2;
        context.drawText(tr, text, x + 1, 1, (int) (a * 0.85f) << 24 | 0x4A1C9C, false);
        context.drawText(tr, text, x, 0, a << 24 | color, false);
        context.getMatrices().pop();
        RenderSystem.disableBlend();
    }
}
