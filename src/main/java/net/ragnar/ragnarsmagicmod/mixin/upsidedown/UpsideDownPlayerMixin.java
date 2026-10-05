package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Upside Down: the flip flag, synced to the player's own client and everyone who can see them. */
@Mixin(PlayerEntity.class)
public abstract class UpsideDownPlayerMixin extends LivingEntity implements UpsideDown.Flippable {
    @Unique
    private static final TrackedData<Boolean> RAGNARSMAGICMOD_UPSIDE_DOWN =
            DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    protected UpsideDownPlayerMixin(EntityType<? extends LivingEntity> type, World world) {
        super(type, world);
    }

    @Inject(method = "initDataTracker", at = @At("TAIL"))
    private void ragnarsmagicmod$trackUpsideDown(DataTracker.Builder builder, CallbackInfo ci) {
        builder.add(RAGNARSMAGICMOD_UPSIDE_DOWN, false);
    }

    @Override
    public boolean ragnarsmagicmod$isUpsideDown() {
        // Asked during construction, before the tracker exists: not yet
        return this.dataTracker != null && this.dataTracker.get(RAGNARSMAGICMOD_UPSIDE_DOWN);
    }

    @Override
    public void ragnarsmagicmod$setUpsideDown(boolean upsideDown) {
        this.dataTracker.set(RAGNARSMAGICMOD_UPSIDE_DOWN, upsideDown);
    }
}
