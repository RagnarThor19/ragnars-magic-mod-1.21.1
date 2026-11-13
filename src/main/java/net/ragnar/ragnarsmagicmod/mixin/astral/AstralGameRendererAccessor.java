package net.ragnar.ragnarsmagicmod.mixin.astral;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Tome of Astral Projection: lets AstralClient wash the world pale violet with its own post shader. */
@Mixin(GameRenderer.class)
public interface AstralGameRendererAccessor {
    @Invoker("loadPostProcessor")
    void ragnarsmagicmod$loadPostProcessor(Identifier id);
}
