package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpeedSpell;

/** Tome of Speed: a free passive. Speed I while it's on a staff you carry, gone when it isn't, and potions left alone. */
public class SpeedGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void speedWhileCarried(TestContext ctx) {
        ctx.assertEquals(0, ModItems.TOME_OF_SPEED.getXpCost(), "no XP cost");
        ctx.assertEquals(0, ModItems.TOME_OF_SPEED.getCooldown(), "no cooldown");

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        ItemStack staff = new ItemStack(ModItems.GOLDEN_STAFF);
        ((StaffItem) ModItems.GOLDEN_STAFF).insertTome(staff, ModItems.TOME_OF_SPEED);
        p.getInventory().setStack(5, staff); // just carried, not held

        SpeedSpell.update(p);
        StatusEffectInstance speed = p.getStatusEffect(StatusEffects.SPEED);
        ctx.assertTrue(speed != null && speed.getAmplifier() == 0, "Speed I while carried");

        p.getInventory().setStack(5, ItemStack.EMPTY);
        SpeedSpell.update(p);
        ctx.assertTrue(p.getStatusEffect(StatusEffects.SPEED) == null, "gone once it's not carried");

        // A potion's Speed is never taken away
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 3600, 0));
        SpeedSpell.update(p);
        ctx.assertTrue(p.getStatusEffect(StatusEffects.SPEED) != null, "a potion's Speed stays");
        p.discard();
        ctx.complete();
    }
}
