package net.ragnar.ragnarsmagicmod.sound;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ModSoundEvents {
    public static final SoundEvent ZAP_CAST   = register("zap_cast");
    public static final SoundEvent ZAP_IMPACT = register("zap_impact");

    private static SoundEvent register(String path) {
        Identifier id = Identifier.of("ragnarsmagicmod", path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    public static void init() { /* load class */ }
}
