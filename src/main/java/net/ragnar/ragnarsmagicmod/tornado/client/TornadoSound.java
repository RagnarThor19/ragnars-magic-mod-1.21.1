package net.ragnar.ragnarsmagicmod.tornado.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.ragnar.ragnarsmagicmod.tornado.TornadoEntity;

/**
 * The tornado's roar: a looping rush of wind that follows it about, swells as it spins up, dies away as it blows out,
 * and stops the moment it's gone.
 */
final class TornadoSound extends MovingSoundInstance {
    private final TornadoEntity tornado;

    TornadoSound(TornadoEntity tornado) {
        super(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS, SoundInstance.createRandom());
        this.tornado = tornado;
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.01f;
        this.pitch = 0.6f;
        follow();
    }

    private void follow() {
        x = tornado.getX();
        y = tornado.getY() + 2;
        z = tornado.getZ();
    }

    @Override
    public boolean shouldAlwaysPlay() {
        return true;
    }

    @Override
    public void tick() {
        if (tornado.isRemoved()) {
            setDone();
            return;
        }
        follow();
        float s = tornado.shownScale;
        volume = Math.max(0.01f, 0.7f * s);
        pitch = 0.55f + 0.1f * s + 0.03f * (float) Math.sin(tornado.age * 0.15);
    }
}
