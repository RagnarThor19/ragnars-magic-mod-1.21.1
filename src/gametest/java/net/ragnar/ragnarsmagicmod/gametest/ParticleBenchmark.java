package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;

import java.util.ArrayList;
import java.util.List;

/**
 * Not a test: a measurement. Casts every tome in turn, the same way (a player aiming at the floor a few blocks out,
 * three husks to hit), and counts the particle packets the server sends that one player over the next
 * {@link #WINDOW} ticks - a packet per particle call, per player in range, so it's straight network load.
 * Only runs with -Dragnarsmagicmod.particlebench=true (./gradlew runParticleBench); otherwise passes at once.
 */
public class ParticleBenchmark implements FabricGameTest {
    public static long packets;
    /** A tome's effect is over once this many ticks pass with no particles; none is followed past {@link #MAX}. */
    private static final int QUIET = 30, MAX = 600, SETTLE = 6;

    /** Everything one cast sent: in all, for how long, how fast while it lasted, and its busiest tick. */
    private record Result(SpellId spell, long packets, int active, long peak) {
        long perSecond() {
            return active <= 0 ? 0 : packets * 20 / active;
        }
    }

    /** Tick by tick until it goes quiet (or {@link #MAX}), noting the busiest tick and when the last packet went. */
    private static void step(TestContext ctx, int t, long[] stats, Runnable done) {
        ctx.waitAndRun(1, () -> {
            long now = packets - stats[1];
            stats[1] = packets;
            stats[0] = Math.max(stats[0], now);
            if (now > 0) stats[2] = t;
            if (t - stats[2] >= QUIET || t >= MAX) done.run();
            else step(ctx, t + 1, stats, done);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "particle_benchmark", tickLimit = 100000)
    public void measureEveryTome(TestContext ctx) {
        if (!Boolean.getBoolean("ragnarsmagicmod.particlebench")) {
            ctx.complete();
            return;
        }
        for (int x = 0; x <= 30; x++) for (int z = 0; z <= 30; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 12; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
        }
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getAbilities().invulnerable = true;
        List<SpellId> spells = new ArrayList<>();
        for (SpellId id : SpellId.values()) {
            Spell s = Spells.get(id);
            if (s != null && !(s instanceof PassiveSpell)) spells.add(id);
        }
        List<Result> results = new ArrayList<>();
        run(ctx, p, spells, 0, results);
    }

    /** Which tome each step has reached: a step that's already been run is never run twice. */
    private final java.util.Set<Integer> started = new java.util.HashSet<>();

    private void run(TestContext ctx, ServerPlayerEntity p, List<SpellId> spells, int i, List<Result> results) {
        if (!started.add(i)) return;
        if (i >= spells.size()) {
            results.sort((a, b) -> Long.compare(b.perSecond(), a.perSecond()));
            RagnarsMagicMod.LOGGER.info("[PBENCH] ===== particle packets to one nearby player =====");
            for (Result r : results) {
                RagnarsMagicMod.LOGGER.info("[PBENCH] {} perSecondWhileActive={} totalPerCast={} activeSeconds={} peakTick={}",
                        r.spell(), r.perSecond(), r.packets(), String.format("%.1f", r.active() / 20.0), r.peak());
            }
            p.discard();
            ctx.complete();
            return;
        }
        SpellId id = spells.get(i);
        // A clean slate: nothing left over from the last tome, the player back in place
        Box area = new Box(ctx.getAbsolutePos(new BlockPos(0, 0, 0))).expand(80);
        ctx.getWorld().getEntitiesByClass(Entity.class, area, e -> !(e instanceof ServerPlayerEntity)).forEach(Entity::discard);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(15, 1, 4)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 20f);
        p.setVelocity(Vec3d.ZERO);
        p.clearStatusEffects();
        p.extinguish();
        p.setHealth(p.getMaxHealth());
        for (int k = 0; k < 3; k++) {
            MobEntity h = EntityType.HUSK.create(ctx.getWorld());
            Vec3d hp = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(13 + k * 2, 1, 10 + k)));
            h.refreshPositionAndAngles(hp.x, hp.y, hp.z, 180f, 0f);
            h.setAiDisabled(true);
            h.setInvulnerable(true);
            ctx.getWorld().spawnEntity(h);
        }
        ctx.waitAndRun(SETTLE, () -> {
            packets = 0;
            try {
                Spells.get(id).cast(ctx.getWorld(), p, ItemStack.EMPTY);
            } catch (Exception e) {
                RagnarsMagicMod.LOGGER.info("[PBENCH] {} threw {}", id, e.toString());
            }
            long[] stats = {0, 0, 0}; // busiest tick, packets so far, tick of the last packet
            step(ctx, 1, stats, () -> {
                results.add(new Result(id, packets, (int) stats[2], stats[0]));
                // A Recalling mark keeps flickering: use it up so it doesn't count against every tome after
                if (id == SpellId.RECALLING) Spells.get(id).cast(p.getServerWorld(), p, ItemStack.EMPTY);
                run(ctx, p, spells, i + 1, results);
            });
        });
    }
}
