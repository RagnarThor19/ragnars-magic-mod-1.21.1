package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.InvisibilitySpell;
import net.ragnar.ragnarsmagicmod.item.spell.SizeSpell;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility;

/** In-world tests for the Tomes of Shrinking, Growing and Invisibility. */
public class SizeAndInvisibilityGameTests implements FabricGameTest {
    private static final BlockPos STAND = new BlockPos(3, 1, 3);
    private static final int SETTLE = 20; // the size eases in over a few ticks

    private static PlayerEntity player(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.BEDROCK);
        PlayerEntity p = ctx.createMockPlayer(GameMode.SURVIVAL);
        GameMode.SURVIVAL.setAbilities(p.getAbilities());
        p.setPosition(Vec3d.ofBottomCenter(ctx.getAbsolutePos(STAND)));
        return p;
    }

    private static Spell spell(SpellId id) {
        return Spells.get(id);
    }

    private static boolean cast(TestContext ctx, PlayerEntity p, SpellId id) {
        return spell(id).cast(ctx.getWorld(), p, ItemStack.EMPTY);
    }

    private static float height(PlayerEntity p) {
        return p.getDimensions(p.getPose()).height();
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    private static boolean fitsAt(TestContext ctx, PlayerEntity p, BlockPos rel) {
        Box box = p.getDimensions(p.getPose()).getBoxAt(Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel)));
        return ctx.getWorld().isSpaceEmpty(p, box.contract(1.0E-6));
    }

    private static void topSlab(TestContext ctx, BlockPos rel) {
        ctx.setBlockState(rel, Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
    }

    // ------------------------------------------------------------------ registration

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomesAreBeginnerWithTheRightCosts(TestContext ctx) {
        for (TomeItem tome : new TomeItem[]{ModItems.TOME_OF_SHRINKING, ModItems.TOME_OF_GROWING, (TomeItem) ModItems.TOME_INVISIBILITY}) {
            ctx.assertEquals(TomeTier.BEGINNER, tome.getTier(), tome + " tier");
            ctx.assertEquals(10, tome.getXpCost(), tome + " xp");
            ctx.assertEquals(300, tome.getCooldown(), tome + " cooldown");
            ctx.assertTrue(ModItems.getTomeFor(tome.getSpell(), TomeTier.BEGINNER) == tome, tome + " lookup");
        }
        ctx.assertTrue(spell(SpellId.SHRINKING) instanceof SizeSpell, "shrinking registered");
        ctx.assertTrue(spell(SpellId.GROWING) instanceof SizeSpell, "growing registered");
        ctx.complete();
    }

    // ------------------------------------------------------------------ shrinking

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void shrinkingSlipsUnderAHalfSlab(TestContext ctx) {
        PlayerEntity p = player(ctx);
        // A slab in the top half of the block in front: a half-block gap at the floor
        BlockPos under = STAND.add(1, 0, 0);
        topSlab(ctx, under);
        ctx.assertFalse(fitsAt(ctx, p, under), "a normal player shouldn't fit under the slab");
        float maxHealth = p.getMaxHealth();
        ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "shrink");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertTrue(near(p.getScale(), SizeSpell.SMALL_SCALE), "scale " + p.getScale());
            ctx.assertTrue(height(p) < 0.5f && height(p) > 0.4f, "should be just under half a block, is " + height(p));
            ctx.assertTrue(fitsAt(ctx, p, under), "tiny player should fit under the slab");
            ctx.assertEquals(maxHealth, p.getMaxHealth(), "hearts unchanged");
            ctx.assertTrue(Math.abs(p.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) - 0.1) < 1e-6, "speed unchanged");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void castingAgainGoesBackAndStartsTheCooldown(TestContext ctx) {
        PlayerEntity p = player(ctx);
        Spell s = spell(SpellId.SHRINKING);
        ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "shrink");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(0, s.xpCost(p, 10), "turning back is free");
            ctx.assertTrue(s.ignoresCooldown(p), "turning back works during a cooldown");
            ctx.assertFalse(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_SHRINKING), "no cooldown while small");
            ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "cast again");
            ctx.waitAndRun(SETTLE, () -> {
                ctx.assertTrue(near(height(p), 1.8f), "back to normal, is " + height(p));
                ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_SHRINKING), "cooldown after");
                ctx.assertEquals(10, s.xpCost(p, 10), "costs XP again");
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tinyWaitsForRoomBeforeGrowingBack(TestContext ctx) {
        PlayerEntity p = player(ctx);
        ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "shrink");
        ctx.waitAndRun(SETTLE, () -> {
            topSlab(ctx, STAND); // now hiding under a slab
            SizeSpell.setTicksLeft(p, 1);
            ctx.waitAndRun(SETTLE, () -> {
                ctx.assertTrue(height(p) < 0.5f, "grew back into the slab: " + height(p));
                ctx.assertFalse(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_SHRINKING), "not over yet");
                ctx.setBlockState(STAND, Blocks.AIR); // crawled out
                ctx.waitAndRun(SETTLE, () -> {
                    ctx.assertTrue(near(height(p), 1.8f), "back to normal once there's room, is " + height(p));
                    ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_SHRINKING), "cooldown after");
                    ctx.complete();
                });
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void askingToGrowBackUnderASlabWaitsThenGrows(TestContext ctx) {
        PlayerEntity p = player(ctx);
        ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "shrink");
        ctx.waitAndRun(SETTLE, () -> {
            topSlab(ctx, STAND);
            ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "ask to grow back under the slab");
            ctx.waitAndRun(SETTLE, () -> {
                ctx.assertTrue(height(p) < 0.5f, "grew into the slab: " + height(p));
                ctx.setBlockState(STAND, Blocks.AIR); // crawled out, no second click
                ctx.waitAndRun(SETTLE, () -> {
                    ctx.assertTrue(near(height(p), 1.8f), "should grow back by itself once there's room, is " + height(p));
                    ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_SHRINKING), "cooldown after");
                    ctx.complete();
                });
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void sizeIsNeverSavedWithThePlayer(TestContext ctx) {
        PlayerEntity p = player(ctx);
        ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "shrink");
        ctx.waitAndRun(SETTLE, () -> {
            NbtCompound saved = p.writeNbt(new NbtCompound());
            ctx.assertFalse(saved.toString().contains("size_spell"), "the shrink would survive logging out");
            cast(ctx, p, SpellId.SHRINKING);
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ growing

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void growingIsSixBlocksTallWithStrength(TestContext ctx) {
        PlayerEntity p = player(ctx);
        float maxHealth = p.getMaxHealth();
        ctx.assertTrue(cast(ctx, p, SpellId.GROWING), "grow");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertTrue(near(height(p), 6.0f), "should be 6 blocks tall, is " + height(p));
            ctx.assertEquals(maxHealth, p.getMaxHealth(), "hearts unchanged");
            StatusEffectInstance strength = p.getStatusEffect(StatusEffects.STRENGTH);
            ctx.assertTrue(strength != null && strength.getAmplifier() == 0, "Strength I while big");
            p.removeStatusEffect(StatusEffects.STRENGTH); // drank milk
            ctx.waitAndRun(2, () -> {
                ctx.assertTrue(p.hasStatusEffect(StatusEffects.STRENGTH), "strength comes back while still big");
                SizeSpell.setTicksLeft(p, 1); // time runs out
                ctx.waitAndRun(SETTLE, () -> {
                    ctx.assertTrue(near(height(p), 1.8f), "back to normal, is " + height(p));
                    ctx.assertFalse(p.hasStatusEffect(StatusEffects.STRENGTH), "strength should end with the size");
                    ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_GROWING), "cooldown after");
                    ctx.complete();
                });
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void growingNeverTouchesAStrengthPotion(TestContext ctx) {
        PlayerEntity p = player(ctx);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 2000, 1)); // Strength II potion
        ctx.assertTrue(cast(ctx, p, SpellId.GROWING), "grow");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(1, p.getStatusEffect(StatusEffects.STRENGTH).getAmplifier(), "potion replaced");
            ctx.assertTrue(cast(ctx, p, SpellId.GROWING), "shrink back");
            ctx.waitAndRun(SETTLE, () -> {
                StatusEffectInstance strength = p.getStatusEffect(StatusEffects.STRENGTH);
                ctx.assertTrue(strength != null && strength.getAmplifier() == 1, "potion removed with the size");
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void cantGrowUnderALowCeiling(TestContext ctx) {
        PlayerEntity p = player(ctx);
        ctx.setBlockState(STAND.up(3), Blocks.STONE);
        ctx.assertFalse(cast(ctx, p, SpellId.GROWING), "grew into the ceiling");
        ctx.waitAndRun(5, () -> {
            ctx.assertTrue(near(height(p), 1.8f), "size changed anyway");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void onlyOneSizeAtATime(TestContext ctx) {
        PlayerEntity p = player(ctx);
        ctx.assertTrue(cast(ctx, p, SpellId.SHRINKING), "shrink");
        ctx.assertFalse(cast(ctx, p, SpellId.GROWING), "grew while shrunk");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertTrue(height(p) < 0.5f, "still small");
            cast(ctx, p, SpellId.SHRINKING);
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ invisibility

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void invisibilityLastsThirtySecondsAndCanBeEndedEarly(TestContext ctx) {
        PlayerEntity p = player(ctx);
        Spell s = spell(SpellId.INVISIBILITY);
        ctx.assertTrue(cast(ctx, p, SpellId.INVISIBILITY), "vanish");
        StatusEffectInstance effect = p.getStatusEffect(StatusEffects.INVISIBILITY);
        ctx.assertTrue(effect != null && effect.getDuration() == 600, "30 second invisibility");
        ctx.assertFalse(effect.shouldShowParticles(), "no particles giving you away");
        ctx.assertTrue(AbsoluteInvisibility.isHidden(p), "completely hidden");
        ctx.assertEquals(0, s.xpCost(p, 10), "showing yourself is free");
        ctx.assertTrue(s.ignoresCooldown(p), "showing yourself works during a cooldown");
        ctx.waitAndRun(5, () -> {
            ctx.assertTrue(cast(ctx, p, SpellId.INVISIBILITY), "cast again");
            ctx.assertFalse(p.hasStatusEffect(StatusEffects.INVISIBILITY), "still invisible");
            ctx.assertFalse(AbsoluteInvisibility.isHidden(p), "still hidden");
            ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_INVISIBILITY), "cooldown after");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void invisibilityEndsWhenTimeRunsOutOrMilkClearsIt(TestContext ctx) {
        PlayerEntity a = player(ctx), b = player(ctx);
        cast(ctx, a, SpellId.INVISIBILITY);
        cast(ctx, b, SpellId.INVISIBILITY);
        InvisibilitySpell.setTicksLeft(a, 1);
        b.removeStatusEffect(StatusEffects.INVISIBILITY); // drank milk
        ctx.waitAndRun(5, () -> {
            ctx.assertFalse(AbsoluteInvisibility.isHidden(a) || a.hasStatusEffect(StatusEffects.INVISIBILITY), "timer didn't end it");
            ctx.assertFalse(AbsoluteInvisibility.isHidden(b), "milk didn't end it");
            ctx.assertTrue(a.getItemCooldownManager().isCoolingDown(ModItems.TOME_INVISIBILITY), "cooldown after timer");
            ctx.assertTrue(b.getItemCooldownManager().isCoolingDown(ModItems.TOME_INVISIBILITY), "cooldown after milk");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void armorDoesntGiveYouAwayToMobs(TestContext ctx) {
        PlayerEntity tome = player(ctx), potion = player(ctx);
        for (PlayerEntity p : new PlayerEntity[]{tome, potion}) {
            p.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            p.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        }
        ZombieEntity zombie = ctx.spawnMob(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        cast(ctx, tome, SpellId.INVISIBILITY);
        potion.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 600));
        double withTome = tome.getAttackDistanceScalingFactor(zombie);
        double withPotion = potion.getAttackDistanceScalingFactor(zombie);
        ctx.assertTrue(withTome <= 0.07 + 1e-9, "mobs still see the armor: " + withTome);
        // A normal invisibility potion still behaves like vanilla
        ctx.assertTrue(withPotion > 0.3, "vanilla potion changed: " + withPotion);
        cast(ctx, tome, SpellId.INVISIBILITY);
        ctx.complete();
    }
}
