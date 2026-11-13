package net.ragnar.ragnarsmagicmod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.ReloadableRegistries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.fishing.Fishing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Fishing (see Fishing): luck, quicker bites, double catches and fishing in lava. */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberMixin extends ProjectileEntity {
    @Shadow @Final @Mutable private int luckBonus;
    @Shadow private int waitCountdown;
    @Shadow private int fishTravelCountdown;
    @Shadow private int hookCountdown;

    @Shadow public abstract PlayerEntity getPlayerOwner();

    /** Set while a catch is being reeled out of lava, so its items can be kept from burning. */
    @Unique private boolean ragnarsmagicmod$lavaCatch;

    protected FishingBobberMixin(EntityType<? extends ProjectileEntity> type, World world) {
        super(type, world);
    }

    @Unique
    private boolean ragnarsmagicmod$tome() {
        return Fishing.has(getPlayerOwner());
    }

    /** In lava, and allowed to fish there. */
    @Unique
    private boolean ragnarsmagicmod$inLava() {
        return getWorld().getFluidState(getBlockPos()).isIn(FluidTags.LAVA) && ragnarsmagicmod$tome();
    }

    // --- Luck ---

    @Inject(method = "<init>(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/world/World;II)V", at = @At("RETURN"))
    private void ragnarsmagicmod$luck(PlayerEntity thrower, World world, int luck, int lure, CallbackInfo ci) {
        if (Fishing.has(thrower)) luckBonus += Fishing.LUCK;
    }

    // --- Floating on lava ---

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/fluid/FluidState;isIn(Lnet/minecraft/registry/tag/TagKey;)Z"))
    private boolean ragnarsmagicmod$lavaIsWater(FluidState fluid, TagKey<net.minecraft.fluid.Fluid> tag, Operation<Boolean> original) {
        if (original.call(fluid, tag)) return true;
        return tag == FluidTags.WATER && fluid.isIn(FluidTags.LAVA) && ragnarsmagicmod$tome();
    }

    /** A bobber fishing in lava never catches fire. */
    @Override
    public boolean isFireImmune() {
        return super.isFireImmune() || ragnarsmagicmod$tome();
    }

    // --- Quicker bites, and the lava bubbling now and then ---

    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$quickerBites(CallbackInfo ci) {
        if (getWorld().isClient || isRemoved() || !ragnarsmagicmod$tome()) return;
        // Never down to 0 from here: the wait has to run out in vanilla's own countdown for a fish to come
        if (hookCountdown <= 0 && fishTravelCountdown <= 0 && waitCountdown > 1) waitCountdown--;
        if (ragnarsmagicmod$inLava() && random.nextInt(40) == 0) {
            ((net.minecraft.server.world.ServerWorld) getWorld()).spawnParticles(ParticleTypes.LAVA, getX(), getY() + 0.1, getZ(), 1, 0.2, 0, 0.2, 0);
        }
    }

    // --- Bites in lava look and sound like lava ---

    @WrapOperation(method = "tickFishingLogic", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;isOf(Lnet/minecraft/block/Block;)Z"))
    private boolean ragnarsmagicmod$lavaTrail(BlockState state, Block block, Operation<Boolean> original) {
        if (original.call(state, block)) return true;
        return block == Blocks.WATER && state.isOf(Blocks.LAVA) && ragnarsmagicmod$tome();
    }

    @ModifyArg(method = "tickFishingLogic", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;spawnParticles(Lnet/minecraft/particle/ParticleEffect;DDDIDDDD)I"), index = 0)
    private ParticleEffect ragnarsmagicmod$lavaParticles(ParticleEffect effect) {
        if (!ragnarsmagicmod$inLava()) return effect;
        if (effect == ParticleTypes.BUBBLE) return ParticleTypes.SMOKE;
        if (effect == ParticleTypes.FISHING || effect == ParticleTypes.SPLASH) return ParticleTypes.LAVA;
        return effect;
    }

    @ModifyArg(method = "tickFishingLogic", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/projectile/FishingBobberEntity;playSound(Lnet/minecraft/sound/SoundEvent;FF)V"), index = 0)
    private SoundEvent ragnarsmagicmod$lavaBite(SoundEvent sound) {
        return ragnarsmagicmod$inLava() ? SoundEvents.BLOCK_LAVA_POP : sound;
    }

    // --- Reeling in: the lava's own loot, double catches, catches that don't burn ---

    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/registry/ReloadableRegistries$Lookup;getLootTable(Lnet/minecraft/registry/RegistryKey;)Lnet/minecraft/loot/LootTable;"))
    private LootTable ragnarsmagicmod$lavaLoot(ReloadableRegistries.Lookup lookup, RegistryKey<LootTable> key, Operation<LootTable> original) {
        ragnarsmagicmod$lavaCatch = ragnarsmagicmod$inLava();
        return original.call(lookup, ragnarsmagicmod$lavaCatch ? Fishing.LAVA_FISHING : key);
    }

    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/loot/LootTable;generateLoot(Lnet/minecraft/loot/context/LootContextParameterSet;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<ItemStack> ragnarsmagicmod$multiCatch(LootTable table, LootContextParameterSet params, Operation<ObjectArrayList<ItemStack>> original) {
        if (!ragnarsmagicmod$tome()) return original.call(table, params);
        PlayerEntity angler = getPlayerOwner();
        int pulls = Fishing.pulls(random);
        ObjectArrayList<ItemStack> catches = new ObjectArrayList<>();
        for (int i = 0; i < pulls; i++) {
            // Out of lava, now and then something alive comes up instead
            EntityType<?> creature = ragnarsmagicmod$lavaCatch ? Fishing.lavaCreature(random) : null;
            if (creature != null) {
                Fishing.pullCreature((net.minecraft.server.world.ServerWorld) getWorld(), getPos(), angler, creature);
                continue;
            }
            catches.addAll(original.call(table, params));
        }
        for (int i = 1; i < pulls; i++) { // one more reel sound for each extra catch, each a little higher
            getWorld().playSound(null, angler.getX(), angler.getY(), angler.getZ(), SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE,
                    SoundCategory.NEUTRAL, 1.0f, 1.3f + 0.2f * i);
        }
        return catches;
    }

    @WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;spawnEntity(Lnet/minecraft/entity/Entity;)Z"))
    private boolean ragnarsmagicmod$fireproofCatch(World world, Entity entity, Operation<Boolean> original) {
        if (ragnarsmagicmod$lavaCatch && entity instanceof ItemEntity item) {
            item.setPosition(item.getX(), item.getY() + 0.5, item.getZ()); // up off the lava's surface
            Fishing.protect(item);
        }
        return original.call(world, entity);
    }
}
