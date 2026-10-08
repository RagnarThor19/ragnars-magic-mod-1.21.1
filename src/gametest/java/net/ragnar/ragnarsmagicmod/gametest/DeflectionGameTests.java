package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.DeflectionSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;

/**
 * Tome of Deflection: projectiles that strike the pane go back where you look (and at whoever's near your crosshair),
 * ones that pass beside it or come too late don't, and other players' projectile spells go back and hurt them.
 */
public class DeflectionGameTests implements FabricGameTest {
    // Everything happens across the diagonal of the 8x8 test area, which is walled in with barriers

    /** Long enough for a freshly joined mock player to stop shrugging off damage. */
    private static final int SETTLE = 70;

    private static ServerPlayerEntity player(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.getInventory().clear();
        p.setHealth(p.getMaxHealth());
        return p;
    }

    private static void face(ServerPlayerEntity p, Vec3d target, float yawOffset) {
        Vec3d d = target.subtract(p.getEyePos());
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f + yawOffset;
        float pitch = (float) -(MathHelper.atan2(d.y, d.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN);
        p.setYaw(yaw);
        p.setPitch(pitch);
        p.setHeadYaw(yaw);
        p.prevYaw = yaw;
        p.prevPitch = pitch;
    }

    private static void cast(ServerPlayerEntity p, SpellId id) {
        Spells.get(id).cast(p.getServerWorld(), p, new ItemStack(ModItems.NETHERITE_STAFF));
    }

    private static SkeletonEntity skeleton(TestContext ctx, BlockPos rel) {
        SkeletonEntity s = ctx.spawnEntity(EntityType.SKELETON, rel);
        s.setAiDisabled(true);
        return s;
    }

    /** A skeleton's arrow, {@code ahead} blocks in front of {@code p} and {@code aside} blocks to the side, flying straight at them. */
    private static ArrowEntity arrowAt(TestContext ctx, SkeletonEntity shooter, ServerPlayerEntity p, double ahead, double aside) {
        ArrowEntity arrow = new ArrowEntity(ctx.getWorld(), shooter, new ItemStack(Items.ARROW), null);
        Vec3d look = p.getRotationVector();
        Vec3d side = new Vec3d(-look.z, 0, look.x).normalize();
        Vec3d start = p.getEyePos().add(look.multiply(ahead)).add(side.multiply(aside));
        arrow.setPosition(start.x, start.y - 0.2, start.z);
        arrow.setVelocity(look.multiply(-1.6));
        ctx.getWorld().spawnEntity(arrow);
        return arrow;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_arrow")
    public void anArrowThatHitsThePaneGoesBackAtTheSkeleton(TestContext ctx) {
        ServerPlayerEntity p = player(ctx, new BlockPos(0, 1, 0));
        SkeletonEntity skeleton = skeleton(ctx, new BlockPos(7, 1, 7));
        face(p, skeleton.getBoundingBox().getCenter(), 0f);
        cast(p, SpellId.DEFLECTION);
        ctx.assertTrue(DeflectionSpell.isActive(p), "pane up");
        ArrowEntity arrow = arrowAt(ctx, skeleton, p, 4, 0);

        ctx.waitAndRun(4, () -> ctx.assertTrue(arrow.getOwner() == p, "turned back: the arrow is the player's now"));
        ctx.waitAndRun(25, () -> {
            ctx.assertTrue(skeleton.getHealth() < skeleton.getMaxHealth(), "the skeleton was hit by its own arrow");
            p.discard();
            ctx.complete();
        });
    }

    /** Looking a few degrees off the skeleton still sends its arrow home. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_assist")
    public void aimingRoughlyAtTheSkeletonIsEnough(TestContext ctx) {
        ServerPlayerEntity p = player(ctx, new BlockPos(0, 1, 0));
        SkeletonEntity skeleton = skeleton(ctx, new BlockPos(7, 1, 7));
        face(p, skeleton.getBoundingBox().getCenter(), 4f);
        cast(p, SpellId.DEFLECTION);
        arrowAt(ctx, skeleton, p, 4, 0);
        ctx.waitAndRun(25, () -> {
            ctx.assertTrue(skeleton.getHealth() < skeleton.getMaxHealth(), "hit, though the aim was 4 degrees off");
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_miss")
    public void anArrowPassingBesideThePaneIsNotTurned(TestContext ctx) {
        ServerPlayerEntity p = player(ctx, new BlockPos(0, 1, 0));
        SkeletonEntity skeleton = skeleton(ctx, new BlockPos(7, 1, 7));
        face(p, skeleton.getBoundingBox().getCenter(), 0f);
        cast(p, SpellId.DEFLECTION);
        ArrowEntity arrow = arrowAt(ctx, skeleton, p, 4, DeflectionSpell.HALF_WIDTH + 1.0);
        ctx.waitAndRun(6, () -> {
            ctx.assertTrue(arrow.getOwner() == skeleton, "still the skeleton's");
            ctx.assertTrue(arrow.getVelocity().dotProduct(p.getRotationVector()) <= 0, "still flying the way it was");
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_late")
    public void tooLateAndThePaneIsGone(TestContext ctx) {
        ServerPlayerEntity p = player(ctx, new BlockPos(0, 1, 0));
        SkeletonEntity skeleton = skeleton(ctx, new BlockPos(7, 1, 7));
        face(p, skeleton.getBoundingBox().getCenter(), 0f);
        cast(p, SpellId.DEFLECTION);
        ctx.waitAndRun(DeflectionSpell.ACTIVE_TICKS + 2, () -> {
            ctx.assertFalse(DeflectionSpell.isActive(p), "pane down");
            ArrowEntity arrow = arrowAt(ctx, skeleton, p, 4, 0);
            ctx.waitAndRun(5, () -> {
                ctx.assertTrue(arrow.getOwner() == skeleton, "not turned back");
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_own")
    public void yourOwnFireballGoesStraightThrough(TestContext ctx) {
        ServerPlayerEntity p = player(ctx, new BlockPos(0, 1, 0));
        face(p, p.getEyePos().add(5, 0, 5), 0f);
        cast(p, SpellId.DEFLECTION);
        cast(p, SpellId.FIREBALLS);
        ctx.waitAndRun(6, () -> {
            var balls = ctx.getWorld().getEntitiesByClass(SmallFireballEntity.class, p.getBoundingBox().expand(20), e -> true);
            ctx.assertFalse(balls.isEmpty(), "the fireball is out");
            ctx.assertTrue(balls.get(0).getVelocity().dotProduct(p.getRotationVector()) > 0, "still flying away from you");
            balls.forEach(b -> b.discard());
            p.discard();
            ctx.complete();
        });
    }

    // ---------------------------------------------------------------------
    // Another player's spells, back at them
    // ---------------------------------------------------------------------

    /**
     * Two players at opposite corners of the test area (about 10 blocks apart; it's walled in), facing each other: after they've settled in, the caster casts {@code spell}
     * at the deflector, who puts up a pane {@code paneDelay} ticks later. The caster should end up hurt.
     */
    private static void duel(TestContext ctx, SpellId spell, int paneDelay, int wait) {
        duel(ctx, spell, paneDelay, wait, false);
    }

    /** With {@code summonFirst}, the caster casts once beforehand to call up their swords or arrows, then fires. */
    private static void duel(TestContext ctx, SpellId spell, int paneDelay, int wait, boolean summonFirst) {
        ctx.getWorld().getServer().setPvpEnabled(true);
        ServerPlayerEntity caster = player(ctx, new BlockPos(0, 1, 0));
        ServerPlayerEntity deflector = player(ctx, new BlockPos(7, 1, 7));
        if (summonFirst) {
            ctx.waitAndRun(SETTLE - 20, () -> {
                face(caster, deflector.getBoundingBox().getCenter(), 0f);
                cast(caster, spell);
            });
        }
        ctx.waitAndRun(SETTLE, () -> {
            caster.setHealth(caster.getMaxHealth());
            face(caster, deflector.getBoundingBox().getCenter(), 0f);
            face(deflector, caster.getBoundingBox().getCenter(), 0f);
            cast(caster, spell);
            ctx.waitAndRun(paneDelay, () -> {
                face(deflector, caster.getBoundingBox().getCenter(), 0f);
                cast(deflector, SpellId.DEFLECTION);
            });
            ctx.waitAndRun(wait, () -> {
                ctx.assertTrue(caster.getHealth() < caster.getMaxHealth(),
                        spell + " came back and hurt its caster (health " + caster.getHealth() + ")");
                caster.discard();
                deflector.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_fireball", tickLimit = 200)
    public void aFireballGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.FIREBALLS, 9, 50);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_ice", tickLimit = 200)
    public void anIceShardGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.ICE_SHARDS, 1, 40);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_ball", tickLimit = 250)
    public void ballLightningGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.BALL_LIGHTNING, 5, 60);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_ricochet", tickLimit = 250)
    public void aRicochetArrowGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.RICOCHET, 28, 50);
    }

    /**
     * Boulders never hurt creative players, and mock players always count as creative, so a husk stands beside the
     * caster to take the hit instead.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_boulder", tickLimit = 250)
    public void aBoulderGoesBackAtItsCaster(TestContext ctx) {
        ctx.getWorld().getServer().setPvpEnabled(true);
        ServerPlayerEntity caster = player(ctx, new BlockPos(0, 1, 0));
        ServerPlayerEntity deflector = player(ctx, new BlockPos(7, 1, 7));
        var husk = ctx.spawnEntity(EntityType.HUSK, new BlockPos(1, 1, 0));
        husk.setAiDisabled(true);
        ctx.waitAndRun(SETTLE, () -> {
            face(caster, deflector.getBoundingBox().getCenter(), 0f);
            cast(caster, SpellId.BOULDERS);
            ctx.waitAndRun(6, () -> {
                face(deflector, caster.getBoundingBox().getCenter(), 0f);
                cast(deflector, SpellId.DEFLECTION);
            });
            ctx.waitAndRun(60, () -> {
                ctx.assertTrue(husk.getHealth() < husk.getMaxHealth(), "the boulder came back and landed on the caster's side");
                ctx.assertTrue(deflector.getHealth() == deflector.getMaxHealth(), "and missed the deflector");
                caster.discard();
                deflector.discard();
                ctx.complete();
            });
        });
    }

    // ---------------------------------------------------------------------
    // Spells that fly without being entities (DeflectableShots)
    // ---------------------------------------------------------------------

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_leaf", tickLimit = 200)
    public void aSharpLeafGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.SHARP_LEAVES, 1, 30);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_air_cut", tickLimit = 200)
    public void anAirCutGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.AIR_CUT, 1, 30);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_cannon", tickLimit = 250)
    public void aStoneCannonGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.STONE_CANNON, 28, 50);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_quiver", tickLimit = 250)
    public void aQuiverArrowGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.QUIVER, 1, 30, true);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_swords", tickLimit = 250)
    public void aSwordGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.SWORDS, 1, 40, true);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_spraying", tickLimit = 250)
    public void sprayedArrowsGoBackAtTheirCaster(TestContext ctx) {
        duel(ctx, SpellId.SPRAYING, 10, 40);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "deflect_booming", tickLimit = 250)
    public void aBoomingOrbGoesBackAtItsCaster(TestContext ctx) {
        duel(ctx, SpellId.BOOMING, 14, 50);
    }

    // ---------------------------------------------------------------------
    // ...and with nobody deflecting, each still does what it always did
    // ---------------------------------------------------------------------

    /** The caster fires {@code spell} at a husk across the test area (after a first cast to summon, if needed); it should get hurt. */
    private static void stillHits(TestContext ctx, SpellId spell, boolean summonFirst, int wait) {
        ServerPlayerEntity caster = player(ctx, new BlockPos(0, 1, 0));
        var husk = ctx.spawnEntity(EntityType.HUSK, new BlockPos(7, 1, 7));
        husk.setAiDisabled(true);
        face(caster, husk.getBoundingBox().getCenter(), 0f);
        if (summonFirst) cast(caster, spell);
        ctx.waitAndRun(summonFirst ? 20 : 1, () -> {
            face(caster, husk.getBoundingBox().getCenter(), 0f);
            cast(caster, spell);
            ctx.waitAndRun(wait, () -> {
                ctx.assertTrue(husk.getHealth() < husk.getMaxHealth(), spell + " still hits what it's aimed at");
                ctx.assertTrue(caster.getHealth() == caster.getMaxHealth(), spell + " still leaves its caster alone");
                caster.discard();
                husk.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_leaf", tickLimit = 200)
    public void sharpLeavesStillHit(TestContext ctx) {
        stillHits(ctx, SpellId.SHARP_LEAVES, false, 20);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_air_cut", tickLimit = 200)
    public void airCutStillHits(TestContext ctx) {
        stillHits(ctx, SpellId.AIR_CUT, false, 20);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_cannon", tickLimit = 200)
    public void stoneCannonStillHits(TestContext ctx) {
        stillHits(ctx, SpellId.STONE_CANNON, false, 45);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_quiver", tickLimit = 200)
    public void quiverStillHits(TestContext ctx) {
        stillHits(ctx, SpellId.QUIVER, true, 20);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_swords", tickLimit = 200)
    public void swordsStillHit(TestContext ctx) {
        stillHits(ctx, SpellId.SWORDS, true, 25);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_spraying", tickLimit = 200)
    public void sprayingStillHits(TestContext ctx) {
        stillHits(ctx, SpellId.SPRAYING, false, 30);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "still_booming", tickLimit = 200)
    public void boomingStillHits(TestContext ctx) {
        stillHits(ctx, SpellId.BOOMING, false, 35);
    }
}
