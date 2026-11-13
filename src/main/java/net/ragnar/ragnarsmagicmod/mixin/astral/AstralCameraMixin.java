package net.ragnar.ragnarsmagicmod.mixin.astral;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.ragnar.ragnarsmagicmod.astral.client.AstralClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tome of Astral Projection: the view leaves your head and goes wherever your spirit drifts, still turning with your
 * mouse. Marked third person so your own body is drawn standing there for you to look back at. Runs before the other
 * camera mixins (priority) so screen shakes still land on top of it.
 */
@Mixin(value = Camera.class, priority = 900)
public abstract class AstralCameraMixin {
    @Shadow private boolean thirdPerson;

    @Shadow protected abstract void setPos(Vec3d pos);

    @Inject(method = "update", at = @At("TAIL"))
    private void ragnarsmagicmod$spiritView(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                            float tickDelta, CallbackInfo ci) {
        if (focusedEntity == null || focusedEntity != MinecraftClient.getInstance().player) return;
        Vec3d at = AstralClient.cameraPos(tickDelta);
        if (at == null) return;
        setPos(at);
        if (AstralClient.showBody(at, tickDelta)) this.thirdPerson = true;
    }
}
