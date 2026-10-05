package net.ragnar.ragnarsmagicmod.sight.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.sight.OreKind;
import net.ragnar.ragnarsmagicmod.sight.Sight;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client half of the Tome of Sight (see Sight). A sonar shell of pale gold rolls out from where you cast it, seen
 * through the rock. Every ore it washes over pops into view - a glowing outline in its own colour, around the whole
 * vein, through every wall - with a ripple, a wisp of light flying from it to you and its own note pinging from where
 * it sits, so a rich vein plays a little run of notes. A tally of what you've found counts up above the hotbar as the
 * wave goes. The outlines shimmer for {@link #HOLD} ticks, then fade.
 */
public final class SightClient {
    private SightClient() {}

    /** How long the wave takes to reach the edge, and how long the ores stay lit after that. */
    static final int WAVE_TICKS = 28, HOLD = 20 * 12, FADE = 40;
    private static final int POP_TICKS = 7, RIPPLE_TICKS = 12;
    private static final int WISPS_PER_TICK = 5, TALLY_KINDS = 5;

    /** Drawn over everything, with no depth: what makes the ores visible through the rock. */
    static final RenderLayer XRAY = RenderLayer.of("ragnarsmagicmod_sight_xray",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 32768, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .build(false));

    private static final class Ore {
        final BlockPos pos;
        final OreKind kind;
        final double dist;
        int revealedAt = -1;

        Ore(BlockPos pos, OreKind kind, Vec3d origin) {
            this.pos = pos;
            this.kind = kind;
            this.dist = Vec3d.ofCenter(pos).distanceTo(origin);
        }
    }

    private static final class Scan {
        final ClientWorld world;
        final Vec3d origin;
        final List<Ore> ores = new ArrayList<>();
        final Set<Long> all = new HashSet<>();
        final Map<OreKind, Integer> tally = new EnumMap<>(OreKind.class);
        int age, next;

        Scan(ClientWorld world, Vec3d origin) {
            this.world = world;
            this.origin = origin;
        }
    }

    @Nullable private static Scan scan;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Sight.ScanPayload.ID, (payload, context) -> {
            ClientWorld world = context.client().world;
            if (world == null) return;
            Scan s = new Scan(world, new Vec3d(payload.origin()));
            for (Sight.Found f : payload.ores()) {
                s.ores.add(new Ore(f.pos(), f.kind(), s.origin));
                s.all.add(f.pos().asLong());
            }
            s.ores.sort((a, b) -> Double.compare(a.dist, b.dist));
            scan = s;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> scan = null);
        ClientTickEvents.END_CLIENT_TICK.register(SightClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SightClient::render);
    }

    /** How far the wave has got, {@code age} ticks in: quick at first, easing off as it nears the edge. */
    static double waveRadius(float age) {
        float t = MathHelper.clamp(age / WAVE_TICKS, 0f, 1f);
        return Sight.RADIUS * (1.0 - (1.0 - t) * (1.0 - t)) + 0.5 * t;
    }

    // ---------------------------------------------------------------------
    // Ticking: the wave reaching ores
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        Scan s = scan;
        if (s == null || client.isPaused()) return;
        if (client.world != s.world || client.player == null) {
            scan = null;
            return;
        }
        s.age++;
        if (s.age > WAVE_TICKS + HOLD) {
            scan = null;
            return;
        }
        if (s.age == WAVE_TICKS + HOLD - FADE) {
            s.world.playSound(client.player.getX(), client.player.getEyeY(), client.player.getZ(),
                    SoundEvents.ITEM_SPYGLASS_STOP_USING, SoundCategory.PLAYERS, 0.8f, 0.6f, false);
        }
        if (s.age > WAVE_TICKS) return;

        // Everything the wave has washed over since last tick
        double r = waveRadius(s.age);
        Map<OreKind, Ore> pinged = new EnumMap<>(OreKind.class);
        int wisps = 0;
        Vec3d chest = client.player.getPos().add(0, 1.0, 0);
        while (s.next < s.ores.size() && s.ores.get(s.next).dist <= r) {
            Ore o = s.ores.get(s.next++);
            o.revealedAt = s.age;
            s.tally.merge(o.kind, 1, Integer::sum);
            pinged.putIfAbsent(o.kind, o);
            if (wisps < WISPS_PER_TICK) {
                wisps++;
                // Flies from the ore to just above the given point, dipping 1.2 into it at the very end
                Vec3d from = Vec3d.ofCenter(o.pos);
                Vec3d to = chest.add(0, 1.2, 0);
                s.world.addParticle(ParticleTypes.VAULT_CONNECTION, to.x, to.y, to.z, from.x - to.x, from.y - to.y, from.z - to.z);
            }
        }
        // One note per kind per tick, rung from where that ore sits
        for (Ore o : pinged.values()) {
            Vec3d at = Vec3d.ofCenter(o.pos);
            s.world.playSound(at.x, at.y, at.z, SoundEvents.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE.value(), SoundCategory.PLAYERS,
                    1.0f, o.kind.pitch(), false);
            if (o.kind.rare) {
                s.world.playSound(at.x, at.y, at.z, SoundEvents.ITEM_LODESTONE_COMPASS_LOCK, SoundCategory.PLAYERS, 1.0f, 1.5f, false);
                s.world.playSound(at.x, at.y, at.z, SoundEvents.BLOCK_NOTE_BLOCK_FLUTE.value(), SoundCategory.PLAYERS, 0.7f, o.kind.pitch(), false);
            }
        }
        if (!pinged.isEmpty()) client.inGameHud.setOverlayMessage(tallyText(s), false);
        if (s.age == WAVE_TICKS) finish(client, s);
    }

    /** The wave reached the edge: the final tally, and a closing sound - or a shrug if it found nothing. */
    private static void finish(MinecraftClient client, Scan s) {
        if (client.player == null) return;
        double x = client.player.getX(), y = client.player.getEyeY(), z = client.player.getZ();
        if (s.ores.isEmpty()) {
            client.inGameHud.setOverlayMessage(Text.literal("No ores within " + (int) Sight.RADIUS + " blocks").formatted(Formatting.GRAY), false);
            s.world.playSound(x, y, z, SoundEvents.BLOCK_VAULT_REJECT_REWARDED_PLAYER, SoundCategory.PLAYERS, 0.8f, 1.2f, false);
        } else {
            client.inGameHud.setOverlayMessage(tallyText(s), false);
            s.world.playSound(x, y, z, SoundEvents.BLOCK_VAULT_INSERT_ITEM, SoundCategory.PLAYERS, 1.0f, 1.3f, false);
        }
    }

    /** Each kind found behind a diamond mark in its colour, rarest first; past {@link #TALLY_KINDS} kinds, "+N more". */
    private static Text tallyText(Scan s) {
        MutableText text = Text.empty();
        OreKind[] kinds = OreKind.values();
        int shown = 0, more = 0;
        for (int i = kinds.length - 1; i >= 0; i--) {
            Integer n = s.tally.get(kinds[i]);
            if (n == null) continue;
            if (shown == TALLY_KINDS) {
                more++;
                continue;
            }
            if (shown++ > 0) text.append(Text.literal("  "));
            Style color = Style.EMPTY.withColor(TextColor.fromRgb(kinds[i].rgb));
            text.append(Text.literal("◆").setStyle(color.withBold(true)));
            text.append(Text.literal(n + " " + kinds[i].label).setStyle(color));
        }
        if (more > 0) text.append(Text.literal("  +" + more + " more").formatted(Formatting.GRAY));
        return text;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        Scan s = scan;
        if (s == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        float t = s.age + tickDelta;
        Vec3d cam = context.camera().getPos();
        Vec3d fwd = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();

        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(XRAY);

        if (t < WAVE_TICKS + 8) SightDraw.wave(vc, pose, s.origin.subtract(cam), t);

        float fade = MathHelper.clamp((WAVE_TICKS + HOLD - t) / FADE, 0f, 1f);
        for (Ore o : s.ores) {
            if (o.revealedAt < 0) break; // nearest first, so nothing past here is lit yet
            float since = t - o.revealedAt;
            Vec3d c = Vec3d.ofCenter(o.pos).subtract(cam);
            // Pops in a size too big with a flash, settles, then shimmers, each ore a little out of step
            float pop = 1f - MathHelper.clamp(since / POP_TICKS, 0f, 1f);
            float scale = 1f + 0.4f * pop * pop;
            float flash = pop * 1.2f;
            float shimmer = 0.75f + 0.25f * MathHelper.sin(t * 0.18f + (float) o.dist * 1.3f);
            float bright = (shimmer + flash) * fade;
            SightDraw.ore(vc, pose, c, scale, o.pos, s.all::contains, o.kind, bright);
            if (since < RIPPLE_TICKS) {
                float k = since / RIPPLE_TICKS;
                SightDraw.ring(vc, pose, c, right, up, 0.6f + 1.4f * k, 0.12f * (1f - k), o.kind, 0.9f * (1f - k));
            }
            if (o.kind.rare) {
                float beat = 0.5f + 0.5f * MathHelper.sin(t * 0.3f + (float) o.dist);
                SightDraw.glow(vc, pose, c, right, up, 0.9f + 0.25f * beat, o.kind, (0.25f + 0.2f * beat) * fade);
            }
        }
        // The always-pass depth phase leaves the depth test as it finds it, and here it's still on
        RenderSystem.disableDepthTest();
        buffers.draw(XRAY);
        RenderSystem.enableDepthTest();
    }
}
