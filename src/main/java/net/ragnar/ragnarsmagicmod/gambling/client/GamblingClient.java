package net.ragnar.ragnarsmagicmod.gambling.client;

import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.gambling.Gambling;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tome of Gambling, client side: the wheel of fortune.
 * <p>
 * Your own spin pops up above your hotbar: your stake sits in the iron hub, the slices show what each pays (a barrier
 * for LOSE, your item for DOUBLE, your item glinting on gold for the TRIPLE), the lamps chase round the rim and the
 * pointer clacks against every peg. It spins up fast and winds down slowly - the last few pegs crawl past - and stops
 * where the server already decided it would. Then the slice lights up, the lamps flash (green for a win, gold for the
 * jackpot, dark for a loss) and the result is spelled out over the wheel.
 * <p>
 * Everyone else sees the same wheel turning on a little sign over the spinner's head, and hears the same clicks and
 * the same ending - the chime of a win, fireworks for the jackpot, or a sad "womp womp" and the stake crumbling away.
 */
public final class GamblingClient {
    private GamblingClient() {}

    private static final int HOLD = 40;  // ticks the result stays up
    private static final int FADE = 10;  // ticks it takes to fade away
    private static final int TURNS = 6;  // full turns before the slow crawl at the end
    private static final double EASE = 3.4;

    private static final RenderLayer SIGN = RenderLayer.of("ragnarsmagicmod_gambling_wheel",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .build(false));

    private static final class Spin {
        final ItemStack bet;
        final int slice;
        final double thetaFinal;
        int age;
        int lastPeg;

        Spin(ItemStack bet, int slice, float land, int elapsed) {
            this.bet = bet;
            this.slice = slice;
            // Clockwise turning brings the slices past the pointer in falling order: slice i is entered at
            // phi = (i+1)*SLICE and left at i*SLICE, so stopping "land" of the way in means phi = (i+1-land)*SLICE
            this.thetaFinal = Math.PI * 2 * TURNS - (slice + 1 - land) * WheelArt.SLICE;
            this.age = elapsed;
            this.lastPeg = peg(theta(elapsed));
        }

        double theta(float age) {
            float t = MathHelper.clamp(age / Gambling.SPIN_TICKS, 0f, 1f);
            return thetaFinal * (1 - Math.pow(1 - t, EASE));
        }

        int pay() {
            return Gambling.WHEEL[slice];
        }

        boolean stopped(float age) {
            return age >= Gambling.SPIN_TICKS;
        }
    }

    private record Pending(int entityId, int delay, SoundEvent sound, float volume, float pitch) {}

    private static final Map<Integer, Spin> SPINS = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Gambling.SpinPayload.ID, (payload, context) -> {
            Spin s = new Spin(payload.bet(), payload.slice(), payload.land(), payload.elapsed());
            SPINS.put(payload.entityId(), s);
            if (payload.elapsed() == 0) {
                // The stake drops into the machine with a clunk, and off it goes
                sound(context.client(), payload.entityId(), SoundEvents.BLOCK_CRAFTER_CRAFT, 0.9f, 0.85f);
                PENDING.add(new Pending(payload.entityId(), 3, SoundEvents.BLOCK_VAULT_EJECT_ITEM, 0.5f, 0.6f));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SPINS.clear();
            PENDING.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(GamblingClient::tick);
        HudRenderCallback.EVENT.register(GamblingClient::renderHud);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(GamblingClient::renderSigns);
    }

    /** True while your own wheel is on screen. */
    public static boolean isShowing() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && SPINS.containsKey(client.player.getId());
    }

    private static int peg(double theta) {
        return (int) Math.floor(theta / WheelArt.SLICE);
    }

    // ---------------------------------------------------------------------
    // Ticking: clicks, the stop, the result
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            SPINS.clear();
            PENDING.clear();
            return;
        }
        if (client.isPaused()) return;

        Iterator<Pending> pi = PENDING.iterator();
        List<Pending> later = new ArrayList<>();
        while (pi.hasNext()) {
            Pending p = pi.next();
            pi.remove();
            if (p.delay <= 1) sound(client, p.entityId, p.sound, p.volume, p.pitch);
            else later.add(new Pending(p.entityId, p.delay - 1, p.sound, p.volume, p.pitch));
        }
        PENDING.addAll(later);

        Iterator<Map.Entry<Integer, Spin>> it = SPINS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Spin> entry = it.next();
            int id = entry.getKey();
            Spin s = entry.getValue();
            s.age++;
            if (s.age <= Gambling.SPIN_TICKS) {
                double theta = s.theta(s.age);
                int peg = peg(theta);
                if (peg != s.lastPeg) {
                    // Fast: a quick light rattle. Slow: each clack lands heavy and on its own.
                    double speed = (theta - s.theta(s.age - 1)) / WheelArt.SLICE;
                    float slow = 1f - (float) MathHelper.clamp(speed / 2.5, 0, 1);
                    sound(client, id, SoundEvents.BLOCK_COMPARATOR_CLICK, 0.3f + 0.45f * slow, 1.75f - 0.75f * slow);
                    s.lastPeg = peg;
                }
            }
            if (s.age == Gambling.SPIN_TICKS) {
                sound(client, id, SoundEvents.BLOCK_TRIPWIRE_CLICK_ON, 0.8f, 0.7f);
                reveal(client, id, s);
            }
            if (s.age > Gambling.SPIN_TICKS + HOLD + FADE) it.remove();
        }
    }

    private static void reveal(MinecraftClient client, int id, Spin s) {
        ClientWorld world = client.world;
        Entity e = world.getEntityById(id);
        int pay = s.pay();
        if (pay == 0) {
            // Womp womp
            PENDING.add(new Pending(id, 3, SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value(), 1.0f, 0.75f));
            PENDING.add(new Pending(id, 10, SoundEvents.BLOCK_NOTE_BLOCK_DIDGERIDOO.value(), 1.0f, 0.56f));
            PENDING.add(new Pending(id, 3, SoundEvents.ENTITY_ITEM_BREAK, 0.8f, 0.8f));
            PENDING.add(new Pending(id, 4, SoundEvents.BLOCK_CRAFTER_FAIL, 0.7f, 0.9f));
        } else {
            // A rising chime: a major arpeggio on the pling, longer for the jackpot
            float[] notes = pay == 3 ? new float[]{0.75f, 0.94f, 1.12f, 1.5f, 1.89f, 2.0f} : new float[]{1.0f, 1.26f, 1.5f, 2.0f};
            for (int i = 0; i < notes.length; i++) {
                PENDING.add(new Pending(id, 2 + i * 2, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, notes[i]));
            }
            PENDING.add(new Pending(id, 3, SoundEvents.BLOCK_VAULT_EJECT_ITEM, 0.9f, pay == 3 ? 1.2f : 1.0f));
            if (pay == 3) {
                PENDING.add(new Pending(id, 12, SoundEvents.BLOCK_NOTE_BLOCK_COW_BELL.value(), 0.9f, 1.0f));
                PENDING.add(new Pending(id, 14, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR, 1.0f, 1.0f));
                PENDING.add(new Pending(id, 22, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 1.0f, 1.0f));
            }
        }
        if (e == null) return;

        Random r = world.random;
        // Seen through your own eyes, the burst happens out in front of you rather than in your face
        boolean ownView = e == client.getCameraEntity() && client.options.getPerspective().isFirstPerson();
        Vec3d hand = ownView
                ? e.getEyePos().add(e.getRotationVector().multiply(2.2)).add(0, -0.6, 0)
                : e.getPos().add(0, e.getHeight() * 0.65, 0).add(e.getRotationVector().multiply(0.45));
        float spread = ownView ? 0.5f : 1f;
        if (pay == 0) {
            ItemStackParticleEffect bits = new ItemStackParticleEffect(ParticleTypes.ITEM, s.bet);
            for (int i = 0; i < (ownView ? 12 : 20); i++) {
                world.addParticle(bits, hand.x, hand.y, hand.z, r.nextGaussian() * 0.08 * spread, (0.05 + r.nextDouble() * 0.15) * spread,
                        r.nextGaussian() * 0.08 * spread);
            }
            for (int i = 0; i < 6; i++) {
                world.addParticle(ParticleTypes.SMOKE, hand.x + r.nextGaussian() * 0.15, hand.y, hand.z + r.nextGaussian() * 0.15, 0, 0.04, 0);
            }
        } else {
            for (int i = 0; i < (ownView ? 8 : 12); i++) {
                double sp = ownView ? 0.9 : 0.4;
                world.addParticle(ParticleTypes.HAPPY_VILLAGER, hand.x + r.nextGaussian() * sp, hand.y + r.nextGaussian() * sp * 0.6,
                        hand.z + r.nextGaussian() * sp, 0, 0.02, 0);
            }
            if (pay == 3) {
                Vec3d up = e.getPos().add(0, e.getHeight() + 1.6, 0);
                world.addFireworkParticle(up.x, up.y, up.z, 0, 0, 0, List.of(new FireworkExplosionComponent(
                        FireworkExplosionComponent.Type.STAR, IntList.of(0xFFD700, 0xFFF176), IntList.of(0xFFFFFF), true, true)));
            }
        }
    }

    private static void sound(MinecraftClient client, int entityId, SoundEvent sound, float volume, float pitch) {
        if (client.world == null) return;
        Entity e = client.world.getEntityById(entityId);
        if (e == null) return;
        client.world.playSound(e.getX(), e.getY() + 1.0, e.getZ(), sound, SoundCategory.PLAYERS, volume, pitch, false);
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static WheelArt.State state(Spin s, float age) {
        boolean spinning = !s.stopped(age);
        float since = age - Gambling.SPIN_TICKS;
        float flash = spinning ? 0f : (since < 24 ? (((int) since / 3) % 2 == 0 ? 1f : 0.35f) : 0.6f);
        float alpha = since > HOLD ? MathHelper.clamp(1f - (since - HOLD) / FADE, 0f, 1f) : 1f;
        return new WheelArt.State(s.theta(age), age, spinning, spinning ? -1 : s.slice, flash, alpha);
    }

    /** How far the pointer is knocked by the peg that has just gone past it. */
    private static int kick(double theta) {
        double frac = theta / WheelArt.SLICE - Math.floor(theta / WheelArt.SLICE);
        return (int) Math.round(Math.max(0, 1 - frac / 0.35) * 2);
    }

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        Spin s = SPINS.get(client.player.getId());
        if (s == null) return;
        float td = counter.getTickDelta(false);
        float age = Math.min(s.age + td, Gambling.SPIN_TICKS + HOLD + FADE);
        WheelArt.State st = state(s, age);
        if (st.alpha() <= 0.01f) return;

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        float k = MathHelper.clamp((h - 80) / 100f, 1f, 2f);
        // Pops up with a little overshoot
        float pop = MathHelper.clamp(age / 7f, 0f, 1f);
        float popK = pop >= 1f ? 1f : (float) (1 + 2.2 * Math.pow(pop - 1, 3) + 1.2 * Math.pow(pop - 1, 2));
        float size = WheelArt.SIZE * k;
        float cx = w / 2f;
        float cy = h - 62 - size / 2f;

        MatrixStack m = context.getMatrices();
        m.push();
        m.translate(cx, cy, 0);
        m.scale(k * popK, k * popK, 1f);

        // A soft drop shadow, then the wheel itself, in one batch
        Matrix4f pose = m.peek().getPositionMatrix();
        VertexConsumer vc = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
        int shadow = Math.round(0x50 * st.alpha()) << 24;
        WheelArt.paint((x, y, c) -> quad(vc, pose, x + 1, y + 2, shadow), new WheelArt.State(st.theta(), st.time(), st.spinning(), st.landed(), 0, 1));
        WheelArt.paint((x, y, c) -> quad(vc, pose, x, y, c), st);
        context.draw();

        // What each slice pays: a barrier for LOSE, your stake for DOUBLE and TRIPLE
        ItemStack barrier = new ItemStack(Items.BARRIER);
        for (int i = 0; i < Gambling.SLICES; i++) {
            double ang = (i + 0.5) * WheelArt.SLICE + st.theta();
            float ix = (float) (Math.sin(ang) * 20.5);
            float iy = (float) (-Math.cos(ang) * 20.5);
            int pay = Gambling.WHEEL[i];
            boolean dim = st.landed() >= 0 && st.landed() != i;
            float sc = pay == 3 ? 0.56f : pay == 2 ? 0.46f : 0.36f;
            if (!st.spinning() && st.landed() == i) sc *= 1.25f + 0.1f * MathHelper.sin(age * 0.6f);
            if (dim && st.alpha() < 0.9f) continue;
            m.push();
            m.translate(ix, iy, 10);
            m.scale(sc, sc, 1);
            if (dim) context.setShaderColor(0.55f, 0.55f, 0.55f, 1f);
            context.drawItem(pay == 0 ? barrier : s.bet, -8, -8);
            context.setShaderColor(1f, 1f, 1f, 1f);
            m.pop();
        }
        // The stake, in the hub
        m.push();
        m.translate(0, 0, 10);
        m.scale(0.6f, 0.6f, 1);
        context.drawItem(s.bet, -8, -8);
        m.pop();

        // The pointer, over everything
        m.push();
        m.translate(0, 0, 300);
        Matrix4f top = m.peek().getPositionMatrix();
        VertexConsumer pv = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
        WheelArt.paintPointer((x, y, c) -> quad(pv, top, x, y, c), st.spinning() ? kick(st.theta()) : 0, st.alpha());
        context.draw();
        m.pop();
        m.pop();

        // Words over the wheel: the stake while it turns, the verdict once it stops
        int ty = Math.round(cy - size / 2f - 14);
        int a = MathHelper.clamp(Math.round(st.alpha() * 255), 4, 255) << 24;
        Text name = s.bet.getName();
        if (st.spinning()) {
            Text line = Text.literal(s.bet.getCount() + " × ").append(name);
            context.drawCenteredTextWithShadow(client.textRenderer, line, Math.round(cx), ty, a | 0xC8C8C8);
        } else {
            int pay = s.pay();
            int won = s.bet.getCount() * pay;
            Text verdict = pay == 3 ? Text.literal("JACKPOT!").formatted(Formatting.BOLD)
                    : pay == 2 ? Text.literal("DOUBLED!").formatted(Formatting.BOLD)
                    : Text.literal("LOST").formatted(Formatting.BOLD);
            int color = pay == 3 ? (((int) age / 3) % 2 == 0 ? 0xFFE34D : 0xFFFFFF) : pay == 2 ? 0x55FF55 : 0xFF5555;
            m.push();
            m.translate(cx, ty - 10, 0);
            float bump = MathHelper.clamp((age - Gambling.SPIN_TICKS) / 4f, 0f, 1f);
            float vs = 1.5f + 0.5f * (1 - bump);
            m.scale(vs, vs, 1);
            context.drawCenteredTextWithShadow(client.textRenderer, verdict, 0, -4, a | color);
            m.pop();
            Text detail = pay == 0 ? Text.literal("-" + s.bet.getCount() + " ").append(name)
                    : Text.literal("+" + won + " ").append(name);
            context.drawCenteredTextWithShadow(client.textRenderer, detail, Math.round(cx), ty + 2, a | (pay == 0 ? 0xAA7777 : 0xE0E0E0));
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f pose, int x, int y, int argb) {
        vc.vertex(pose, x, y, 0).color(argb);
        vc.vertex(pose, x, y + 1, 0).color(argb);
        vc.vertex(pose, x + 1, y + 1, 0).color(argb);
        vc.vertex(pose, x + 1, y, 0).color(argb);
    }

    /** Other players' wheels, on a sign over their heads. */
    private static void renderSigns(WorldRenderContext context) {
        if (SPINS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        Camera camera = context.camera();
        Vec3d cam = camera.getPos();
        float td = context.tickCounter().getTickDelta(false);
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = null;

        for (Map.Entry<Integer, Spin> entry : SPINS.entrySet()) {
            Entity e = client.world.getEntityById(entry.getKey());
            // Your own wheel is on your screen already; the sign is for everyone else
            if (e == null || e == client.player) continue;
            Spin s = entry.getValue();
            float age = Math.min(s.age + td, Gambling.SPIN_TICKS + HOLD + FADE);
            WheelArt.State st = state(s, age);
            if (st.alpha() <= 0.01f) continue;

            Vec3d center = e.getLerpedPos(td).add(0, e.getHeight() + 1.45, 0);
            Vec3d forward = center.subtract(cam);
            forward = new Vec3d(forward.x, 0, forward.z);
            if (forward.lengthSquared() < 1e-4) continue;
            forward = forward.normalize();
            Vec3d up = new Vec3d(0, 1, 0);
            Vec3d right = forward.crossProduct(up);
            float pop = MathHelper.clamp(age / 7f, 0f, 1f);
            double px = (1.0 / 30.0) * (0.6 + 0.4 * pop);
            Vec3d rel = center.subtract(cam);

            if (vc == null) vc = buffers.getBuffer(SIGN);
            VertexConsumer out = vc;
            WheelArt.Sink sink = (x, y, c) -> {
                Vec3d p0 = rel.add(right.multiply(x * px)).subtract(up.multiply(y * px));
                Vec3d rx = right.multiply(px);
                Vec3d dy = up.multiply(-px);
                put(out, pose, p0, c);
                put(out, pose, p0.add(dy), c);
                put(out, pose, p0.add(dy).add(rx), c);
                put(out, pose, p0.add(rx), c);
            };
            WheelArt.paint(sink, st);
            // The pointer sits just in front of the wheel
            Vec3d nudge = forward.multiply(-0.01);
            WheelArt.Sink front = (x, y, c) -> {
                Vec3d p0 = rel.add(nudge).add(right.multiply(x * px)).subtract(up.multiply(y * px));
                Vec3d rx = right.multiply(px);
                Vec3d dy = up.multiply(-px);
                put(out, pose, p0, c);
                put(out, pose, p0.add(dy), c);
                put(out, pose, p0.add(dy).add(rx), c);
                put(out, pose, p0.add(rx), c);
            };
            WheelArt.paintPointer(front, st.spinning() ? kick(st.theta()) : 0, st.alpha());
        }
        if (vc != null) buffers.draw(SIGN);
    }

    private static void put(VertexConsumer vc, Matrix4f pose, Vec3d p, int argb) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(argb);
    }
}
