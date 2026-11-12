package net.ragnar.ragnarsmagicmod.blackhole.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoleEntity;

/**
 * One layer of the black hole's drone: a looping sound that follows it about, swells as it forms, rises in pitch as
 * it collapses and cuts out the moment it bursts.
 */
final class BlackHoleSound extends MovingSoundInstance {
    private final BlackHoleEntity hole;
    private final float loudness, basePitch;

    BlackHoleSound(BlackHoleEntity hole, SoundEvent sound, float loudness, float basePitch) {
        super(sound, SoundCategory.PLAYERS, SoundInstance.createRandom());
        this.hole = hole;
        this.loudness = loudness;
        this.basePitch = basePitch;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        this.pitch = basePitch;
        follow();
    }

    private void follow() {
        x = hole.getX();
        y = hole.getY() + hole.getHeight() / 2;
        z = hole.getZ();
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }

    @Override
    public void tick() {
        if (hole.isRemoved() || hole.stage() == BlackHoleEntity.BURST) {
            setDone();
            return;
        }
        follow();
        float f = hole.formed(0f);
        float collapse = hole.stage() == BlackHoleEntity.COLLAPSING
                ? Math.min(1f, hole.stageTime(0f) / BlackHoleEntity.COLLAPSE_TICKS) : 0f;
        volume = Math.max(0.01f, loudness * (0.25f + 0.75f * f) * (1f + 0.4f * collapse));
        pitch = basePitch * (0.8f + 0.2f * f) + 0.9f * collapse * collapse + 0.02f * (float) Math.sin(hole.age * 0.2);
    }
}
