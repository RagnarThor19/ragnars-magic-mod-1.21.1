package net.ragnar.ragnarsmagicmod.astral.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.random.Random;

/**
 * The hush of the astral plane: the warped forest's low hum, played softly and a little higher, right in your ears
 * for as long as you're out of your body. Fades in as you leave and out as you're reeled back.
 */
final class AstralAmbience extends MovingSoundInstance {
    private static final float MAX = 0.55f;
    private boolean fading;

    AstralAmbience() {
        super(SoundEvents.AMBIENT_WARPED_FOREST_LOOP.value(), SoundCategory.PLAYERS, Random.create());
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        this.pitch = 1.25f;
        this.relative = true;
        this.attenuationType = SoundInstance.AttenuationType.NONE;
    }

    void fadeOut() {
        fading = true;
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }

    @Override
    public void tick() {
        if (fading) {
            volume -= 0.06f;
            if (volume <= 0.01f) setDone();
        } else if (volume < MAX) {
            volume = Math.min(MAX, volume + 0.03f);
        }
    }
}
