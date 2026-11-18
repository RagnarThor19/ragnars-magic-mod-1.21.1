package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.blocktome.BlockTome;
import net.ragnar.ragnarsmagicmod.blocktome.BlockTrait;
import net.ragnar.ragnarsmagicmod.blocktome.BlocksSpell;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/**
 * Tome of Blocks: ten blocks copied from around you, 12 XP to summon and free to fire, 8 damage a block, a held
 * barrage, block traits, and the 25 second cooldown only once the ring is empty.
 */
public class BlockTomeGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeAndTraits(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, BlockTome.TOME_OF_BLOCKS.getTier(), "tier");
        ctx.assertEquals(12, BlockTome.TOME_OF_BLOCKS.getXpCost(), "xp");
        ctx.assertEquals(500, BlockTome.TOME_OF_BLOCKS.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.BLOCKS) instanceof BlocksSpell, "spell registered");

        ctx.assertEquals(BlockTrait.FROST, BlockTrait.of(Blocks.PACKED_ICE.getDefaultState()), "ice");
        ctx.assertEquals(BlockTrait.FLAME, BlockTrait.of(Blocks.NETHERRACK.getDefaultState()), "netherrack");
        ctx.assertEquals(BlockTrait.BOUNCY, BlockTrait.of(Blocks.SLIME_BLOCK.getDefaultState()), "slime");
        ctx.assertEquals(BlockTrait.EXPLOSIVE, BlockTrait.of(Blocks.TNT.getDefaultState()), "tnt");
        ctx.assertEquals(BlockTrait.CONDUCTIVE, BlockTrait.of(Blocks.OXIDIZED_CUT_COPPER.getDefaultState()), "copper");
        ctx.assertEquals(BlockTrait.GLASS, BlockTrait.of(Blocks.RED_STAINED_GLASS.getDefaultState()), "glass");
        ctx.assertEquals(BlockTrait.HEAVY, BlockTrait.of(Blocks.OBSIDIAN.getDefaultState()), "obsidian");
        ctx.assertEquals(BlockTrait.PLAIN, BlockTrait.of(Blocks.STONE.getDefaultState()), "stone");

        // A few of the hits, straight onto fresh zombies
        Vec3d dir = new Vec3d(0, 0, 1);
        ZombieEntity burnt = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        BlockTrait.FLAME.onHit(ctx.getWorld(), null, burnt, burnt.getPos(), dir);
        ctx.assertTrue(burnt.isOnFire(), "netherrack burns");
        ZombieEntity frozen = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(3, 1, 1));
        BlockTrait.FROST.onHit(ctx.getWorld(), null, frozen, frozen.getPos(), dir);
        ctx.assertTrue(frozen.getFrozenTicks() >= frozen.getMinFreezeDamageTicks(), "ice freezes solid");
        ctx.assertTrue(frozen.getStatusEffect(StatusEffects.SLOWNESS) != null, "and slows");
        ZombieEntity floated = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(5, 1, 1));
        BlockTrait.FLOATY.onHit(ctx.getWorld(), null, floated, floated.getPos(), dir);
        ctx.assertTrue(floated.getStatusEffect(StatusEffects.LEVITATION) != null, "purpur floats");
        ZombieEntity a = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(1, 1, 5));
        ZombieEntity b = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(3, 1, 5));
        float bMax = b.getHealth();
        BlockTrait.CONDUCTIVE.onHit(ctx.getWorld(), null, a, a.getPos(), dir);
        ctx.assertTrue(b.getHealth() < bMax, "copper arcs to the next one");
        for (var z : new ZombieEntity[]{burnt, frozen, floated, a, b}) z.discard();
        ctx.complete();
    }

    private record Setup(ServerPlayerEntity player, StaffItem item, ItemStack staff, IronGolemEntity golem) {}

    /** A floor of ice and netherrack, a golem ahead, and a survival caster with plenty of XP, facing it. */
    private static Setup setup(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), z < 3 ? Blocks.PACKED_ICE : Blocks.NETHERRACK);
        }
        IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(3, 1, 6));
        golem.setAiDisabled(true);

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.setExperienceLevel(30);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 10f); // facing +z, at the golem
        StaffItem item = (StaffItem) ModItems.DIAMOND_STAFF;
        ItemStack staff = new ItemStack(item);
        item.insertTome(staff, BlockTome.TOME_OF_BLOCKS);
        p.setStackInHand(Hand.MAIN_HAND, staff);
        return new Setup(p, item, staff, golem);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 160)
    public void summonTapAndCooldown(TestContext ctx) {
        Setup s = setup(ctx);
        ServerPlayerEntity p = s.player();
        BlocksSpell spell = (BlocksSpell) Spells.get(SpellId.BLOCKS);

        ctx.assertEquals(12, spell.xpCost(p, 12), "summoning costs the tome's XP");
        s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
        p.stopUsingItem();
        ctx.assertEquals(BlockTome.COUNT, BlocksSpell.count(p), "ten blocks in the ring");
        ctx.assertEquals(0, spell.xpCost(p, 12), "shots are free");
        ctx.assertTrue(!p.getItemCooldownManager().isCoolingDown(BlockTome.TOME_OF_BLOCKS), "no cooldown while the ring's up");

        float max = s.golem().getMaxHealth();
        // Let them all rise into the ring, then tap once
        ctx.waitAndRun(40, () -> {
            s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
            p.stopUsingItem();
            ctx.assertEquals(BlockTome.COUNT - 1, BlocksSpell.count(p), "a tap fires one");
        });
        // It's slow: about five blocks to the golem, a handful of ticks
        ctx.waitAndRun(55, () -> {
            float lost = max - s.golem().getHealth();
            ctx.assertTrue(lost >= BlockTome.DAMAGE - 0.01f, "one block hit for at least 8, lost " + lost);
            // Hold: the rest go in a barrage, and the ring's empty in well under a second
            s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
            for (int i = 0; i < 30 && p.isUsingItem(); i++) s.item().usageTick(ctx.getWorld(), p, s.staff(), 72000 - i);
            ctx.assertEquals(0, BlocksSpell.count(p), "the barrage emptied the ring");
            ctx.assertTrue(!p.isUsingItem(), "the hold ends with the last block");
            ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(BlockTome.TOME_OF_BLOCKS), "cooldown once the ring's empty");
        });
        ctx.waitAndRun(90, () -> {
            ctx.assertTrue(s.golem().getHealth() < max - 40 || s.golem().isDead(), "the barrage landed, health " + s.golem().getHealth());
            // The ring always has the ice and the netherrack from the floor in it: both went off
            ctx.assertTrue(s.golem().getStatusEffect(StatusEffects.SLOWNESS) != null, "the ice slowed it");
            p.discard();
            s.golem().discard();
            ctx.complete();
        });
    }
}
