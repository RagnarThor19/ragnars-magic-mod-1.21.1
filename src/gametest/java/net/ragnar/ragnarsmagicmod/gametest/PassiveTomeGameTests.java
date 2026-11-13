package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.ragnar.ragnarsmagicmod.climbing.Climbing;
import net.ragnar.ragnarsmagicmod.gliding.Gliding;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpeedSpell;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;
import net.ragnar.ragnarsmagicmod.leaping.Leaping;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;
import net.ragnar.ragnarsmagicmod.nightvision.NightVision;
import net.ragnar.ragnarsmagicmod.util.ModLootTableModifiers;

import java.util.List;
import java.util.Set;

/** Passive tomes: their own class, never on a staff, working from the inventory; old staffs that held one still do. */
public class PassiveTomeGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void speedJumpingAndLungingAreThePassiveTomes(TestContext ctx) {
        Set<TomeItem> passive = Set.copyOf(PassiveTomes.all());
        ctx.assertEquals(Set.of(ModItems.TOME_OF_SPEED, Jumping.TOME_OF_JUMPING, Lunging.TOME_OF_LUNGING,
                NightVision.TOME_OF_NIGHT_VISION, Leaping.TOME_OF_LEAPING, Climbing.TOME_OF_CLIMBING, Gliding.TOME_OF_GLIDING,
                net.ragnar.ragnarsmagicmod.swimming.Swimming.TOME_OF_SWIMMING, net.ragnar.ragnarsmagicmod.fireresistance.FireResistance.TOME_OF_FIRE_RESISTANCE,
                net.ragnar.ragnarsmagicmod.grace.Grace.TOME_OF_GRACE, net.ragnar.ragnarsmagicmod.lifesteal.Lifesteal.TOME_OF_LIFESTEAL,
                net.ragnar.ragnarsmagicmod.gourmet.Gourmet.TOME_OF_THE_GOURMET, net.ragnar.ragnarsmagicmod.knockback.Knockback.TOME_OF_KNOCKBACK,
                net.ragnar.ragnarsmagicmod.groundpound.GroundPound.TOME_OF_GROUND_POUND, net.ragnar.ragnarsmagicmod.sidestep.Sidestep.TOME_OF_SIDESTEP,
                net.ragnar.ragnarsmagicmod.desperation.Desperation.TOME_OF_DESPERATION, net.ragnar.ragnarsmagicmod.reflection.Reflection.TOME_OF_REFLECTION,
                net.ragnar.ragnarsmagicmod.haste.Haste.TOME_OF_HASTE, net.ragnar.ragnarsmagicmod.living.Living.TOME_OF_LIVING),
                passive, "the passive tomes");
        for (TomeItem t : passive) ctx.assertEquals(TomeTier.PASSIVE, t.getTier(), "tier of " + t);
        ctx.assertTrue(ModLootTableModifiers.PASSIVE_SHARE > 0f && ModLootTableModifiers.PASSIVE_SHARE < 1f, "rarer than advanced");

        // Their own section at the very end of the tomes in the Tools tab
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        List<TomeItem> tomes = Registries.ITEM_GROUP.get(ItemGroups.TOOLS).getDisplayStacks().stream()
                .filter(s -> s.getItem() instanceof TomeItem).map(s -> (TomeItem) s.getItem()).distinct().toList();
        List<TomeItem> last = tomes.subList(tomes.size() - passive.size(), tomes.size());
        ctx.assertTrue(last.stream().allMatch(t -> t.getTier() == TomeTier.PASSIVE), "passive tomes come last, after the master tomes");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void workFromTheInventoryWithNoStaff(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getInventory().clear();
        p.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
        p.getInventory().selectedSlot = 0;
        p.addExperience(100);
        ctx.assertFalse(Lunging.triggers(p, Hand.MAIN_HAND), "no lunging without the tome");

        p.getInventory().setStack(20, new ItemStack(ModItems.TOME_OF_SPEED));
        p.getInventory().setStack(30, new ItemStack(Jumping.TOME_OF_JUMPING));
        p.getInventory().offHand.set(0, new ItemStack(Lunging.TOME_OF_LUNGING));
        SpeedSpell.update(p);
        ctx.assertTrue(p.hasStatusEffect(StatusEffects.SPEED), "speed from the backpack");
        ctx.assertTrue(Jumping.canAirJump(p), "air jumps from the backpack");
        ctx.assertTrue(Lunging.triggers(p, Hand.MAIN_HAND), "lunging from the off hand");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void anyStaffTakesThemAndTheyWorkFromIt(TestContext ctx) {
        // Even the golden staff (beginner tomes only) takes passive tomes
        StaffItem golden = (StaffItem) ModItems.GOLDEN_STAFF;
        ItemStack staff = new ItemStack(golden);
        ctx.assertEquals(StaffItem.InsertResult.OK, golden.insertTome(staff, NightVision.TOME_OF_NIGHT_VISION), "golden staff takes night vision");
        ctx.assertEquals(StaffItem.InsertResult.OK, golden.insertTome(staff, Leaping.TOME_OF_LEAPING), "and leaping");
        ServerPlayerEntity q = ctx.createMockCreativeServerPlayerInWorld();
        q.getInventory().clear();
        q.getInventory().setStack(7, staff);
        NightVision.update(q);
        ctx.assertTrue(q.hasStatusEffect(StatusEffects.NIGHT_VISION), "night vision from the staff");
        ctx.assertTrue(PassiveTomes.has(q, Leaping.TOME_OF_LEAPING), "leaping from the staff");
        q.discard();
        StaffItem netherite = (StaffItem) ModItems.NETHERITE_STAFF;

        // A staff from before: Speed saved as a beginner tome and Jumping as an advanced one
        NbtList list = new NbtList();
        for (String[] e : new String[][]{{"SPEED", "BEGINNER"}, {"JUMPING", "ADVANCED"}, {"LUNGING", "BEGINNER"}}) {
            NbtCompound c = new NbtCompound();
            c.putString("id", e[0]);
            c.putString("tier", e[1]);
            list.add(c);
        }
        NbtCompound nbt = new NbtCompound();
        nbt.put("rmm_tomes", list);
        ItemStack old = new ItemStack(netherite);
        old.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        ctx.assertEquals(List.of(ModItems.TOME_OF_SPEED, Jumping.TOME_OF_JUMPING, Lunging.TOME_OF_LUNGING), StaffItem.getTomes(old),
                "old staff still holds its tomes");

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getInventory().clear();
        p.getInventory().setStack(5, old);
        SpeedSpell.update(p);
        ctx.assertTrue(p.hasStatusEffect(StatusEffects.SPEED), "and they still work from it");
        ItemStack back = StaffItem.removeSelectedTome(old);
        ctx.assertTrue(back.getItem() instanceof TomeItem t && t.getTier() == TomeTier.PASSIVE, "and it hands them back");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nightVisionWhileCarriedWithoutFlicker(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getInventory().clear();
        NightVision.update(p);
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.NIGHT_VISION), "nothing without the tome");
        p.getInventory().setStack(12, new ItemStack(NightVision.TOME_OF_NIGHT_VISION));
        NightVision.update(p);
        var nv = p.getStatusEffect(StatusEffects.NIGHT_VISION);
        ctx.assertTrue(nv != null && nv.getDuration() > 200, "night vision, well clear of the flicker at 10 seconds left");
        p.getInventory().setStack(12, ItemStack.EMPTY);
        NightVision.update(p);
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.NIGHT_VISION), "gone with the tome");
        p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(StatusEffects.NIGHT_VISION, 3600, 0));
        NightVision.update(p);
        ctx.assertTrue(p.hasStatusEffect(StatusEffects.NIGHT_VISION), "a potion's is left alone");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void leapingNumbers(TestContext ctx) {
        ctx.assertEquals(40, Leaping.FULL_CHARGE_TICKS, "2 seconds for the full leap");
        ctx.assertEquals(15f, Leaping.heightFor(Leaping.FULL_CHARGE_TICKS), "15 blocks at full");
        ctx.assertEquals(15f, Leaping.heightFor(500), "and never more");
        ctx.assertTrue(Leaping.heightFor(Leaping.MIN_CHARGE_TICKS) > 2.5f, "even the least wind-up beats a normal jump");
        double rise = Leaping.riseFrom(Leaping.velocityFor(15));
        ctx.assertTrue(Math.abs(rise - 15) < 0.05, "the full leap rises 15 blocks, " + rise);
        ctx.assertTrue(Math.abs(Leaping.riseFrom(0.42) - 1.25) < 0.05, "physics match vanilla's jump");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void aFifteenBlockLeapLandsSafelyAndFurtherHurts(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        p.getInventory().clear();
        var safe = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        double normal = safe.getValue();

        Leaping.leap(p, 15f);
        ctx.assertEquals(normal, safe.getValue(), "no tome, no protection");

        p.getInventory().setStack(9, new ItemStack(Leaping.TOME_OF_LEAPING));
        Leaping.leap(p, 15f);
        float before = p.getHealth();
        p.handleFallDamage(15f, 1f, p.getDamageSources().fall());
        ctx.assertEquals(before, p.getHealth(), "landing a full 15 block leap doesn't hurt");
        p.timeUntilRegen = 0;
        p.handleFallDamage(19f, 1f, p.getDamageSources().fall());
        ctx.assertTrue(p.getHealth() < before && p.getHealth() >= before - 4.01f, "4 blocks further hurts a little, " + p.getHealth());

        Leaping.leap(p, 999f);
        ctx.assertTrue(safe.getValue() <= 15.5 + 1e-6, "a client can't ask for more than 15");
        Leaping.clear(p);
        ctx.assertEquals(normal, safe.getValue(), "gone once they've landed");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "climbing")
    public void climbingTakesTheFallOnlyAgainstAWall(TestContext ctx) {
        for (int y = 1; y <= 4; y++) ctx.setBlockState(new net.minecraft.util.math.BlockPos(2, y, 1), net.minecraft.block.Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getInventory().clear();
        // Pressed up against the east face of the wall, looking at it, a couple of blocks up
        net.minecraft.util.math.Vec3d at = ctx.getAbsolute(new net.minecraft.util.math.Vec3d(1.7, 2.5, 1.5));
        p.refreshPositionAndAngles(at.x, at.y, at.z, -90f, 0f);
        ctx.assertTrue(Climbing.wallAt(p) != null, "the wall is found");

        p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        Climbing.set(p, true);
        ctx.assertFalse(Climbing.isClimbing(p), "no tome, no climbing");

        p.getInventory().setStack(25, new ItemStack(Climbing.TOME_OF_CLIMBING));
        Climbing.set(p, true);
        ctx.assertTrue(Climbing.isClimbing(p), "on the wall");
        // A long fall caught on the wall, then a slide all the way down: none of it hurts, however the moves arrive
        float before = p.getHealth();
        p.handleFallDamage(30f, 1f, p.getDamageSources().fall());
        ctx.assertEquals(before, p.getHealth(), "a fall caught on the wall doesn't hurt");

        Climbing.set(p, false);
        ctx.assertFalse(Climbing.isClimbing(p), "let go");
        ctx.assertEquals(0f, p.fallDistance, "what they fell on the wall is forgiven");
        p.timeUntilRegen = 0;
        p.handleFallDamage(8f, 1f, p.getDamageSources().fall());
        ctx.assertTrue(p.getHealth() < before, "but a fall after letting go counts");

        // A client claiming to climb thin air gets nothing
        net.minecraft.util.math.Vec3d away = ctx.getAbsolute(new net.minecraft.util.math.Vec3d(0.5, 2.5, 3.5));
        p.refreshPositionAndAngles(away.x, away.y, away.z, -90f, 0f);
        ctx.assertTrue(Climbing.wallAt(p) == null, "nothing beside them");
        Climbing.set(p, true);
        ctx.assertFalse(Climbing.isClimbing(p), "no wall, no grab");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gliding")
    public void glidingNeedsTheTome(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getInventory().clear();
        p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        Gliding.set(p, true);
        ctx.assertFalse(Gliding.isGliding(p), "no tome, no gliding");

        p.getInventory().offHand.set(0, new ItemStack(Gliding.TOME_OF_GLIDING));
        Gliding.set(p, true);
        ctx.assertTrue(Gliding.isGliding(p), "gliding");
        // Even if a laggy connection delivers the whole glide down in one go, landing it doesn't hurt
        float before = p.getHealth();
        p.handleFallDamage(40f, 1f, p.getDamageSources().fall());
        ctx.assertEquals(before, p.getHealth(), "landing a glide doesn't hurt");
        Gliding.set(p, false);
        ctx.assertFalse(Gliding.isGliding(p), "stopped");
        p.timeUntilRegen = 0;
        p.handleFallDamage(8f, 1f, p.getDamageSources().fall());
        ctx.assertTrue(p.getHealth() < before, "and a fall after letting go of jump counts");

        ctx.assertTrue(Gliding.START_DROP > Leaping.riseFrom(0.42), "an ordinary jump never drops far enough to glide");
        p.discard();
        ctx.complete();
    }
}
