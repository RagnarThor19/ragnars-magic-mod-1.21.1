package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.ProjectileDeflection;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.ragnar.ragnarsmagicmod.reflection.Reflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tome of Reflection: a projectile hitting a player who carries it may bounce back instead. This is where vanilla
 * decides whether a hit is deflected (as a breeze deflects arrows), so a bounced projectile never hits at all.
 */
@Mixin(ProjectileEntity.class)
public abstract class ReflectionProjectileMixin {
    @Inject(method = "hitOrDeflect", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$reflect(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> cir) {
        ProjectileEntity self = (ProjectileEntity) (Object) this;
        if (self.getWorld().isClient || !(hit instanceof EntityHitResult e) || !(e.getEntity() instanceof ServerPlayerEntity player)) return;
        if (Reflection.tryReflect(self, player, false)) cir.setReturnValue(ProjectileDeflection.SIMPLE);
    }
}
