package net.ragnar.ragnarsmagicmod.knight.client;

import net.minecraft.client.render.entity.animation.Animation;
import net.minecraft.client.render.entity.animation.AnimationHelper;
import net.minecraft.client.render.entity.animation.Keyframe;
import net.minecraft.client.render.entity.animation.Transformation;

/**
 * The knight's keyframed moves. Values are degrees (and pixels, +y up) added on top of the resting pose from
 * KnightModel.setAngles, so every animation starts and ends at 0. Negative arm pitch raises the arm forward;
 * the sword rests at 126 degrees to the arm, so +54 lines the blade up with it.
 * Strikes use LINEAR keyframes so the blow snaps; everything else eases (CUBIC).
 */
final class KnightAnimations {
    private KnightAnimations() {}

    /** Overhead cleave: rears back, then brings the blade straight down into the ground. Hit lands at 0.4s. */
    static final Animation CLEAVE = Animation.Builder.create(0.9f)
            .addBoneAnimation("right_arm", rot(r(0, 0, 0, 0), r(0.3f, -165, 12, -8), l(0.4f, -35, -6, 0), r(0.55f, -32, -6, 0), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("sword", rot(r(0, 0, 0, 0), r(0.3f, -106, 0, 0), l(0.4f, 22, 0, 0), r(0.55f, 22, 0, 0), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("body", rot(r(0, 0, 0, 0), r(0.3f, -12, 16, 0), l(0.4f, 18, -10, 0), r(0.55f, 16, -8, 0), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("body", move(m(0, 0, 0, 0), m(0.3f, 0, 0.5f, 0), ml(0.4f, 0, -1.5f, 0), m(0.55f, 0, -1.5f, 0), m(0.9f, 0, 0, 0)))
            .addBoneAnimation("head", rot(r(0, 0, 0, 0), r(0.3f, -16, -10, 0), l(0.4f, -12, 6, 0), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("left_arm", rot(r(0, 0, 0, 0), r(0.3f, -25, 0, -10), l(0.4f, 8, 0, -14), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("right_leg", rot(r(0, 0, 0, 0), r(0.3f, 4, 0, 0), l(0.4f, -18, 0, 0), r(0.55f, -18, 0, 0), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 0, 0, 0), r(0.3f, -4, 0, 0), l(0.4f, 14, 0, 0), r(0.55f, 14, 0, 0), r(0.9f, 0, 0, 0)))
            .addBoneAnimation("back_cloth", rot(r(0, 0, 0, 0), l(0.4f, 20, 0, 0), r(0.9f, 0, 0, 0)))
            .build();

    /** Wide sweep: winds the blade out to its right, then carves a flat arc across the front. Hit lands at 0.35s. */
    static final Animation SWEEP = Animation.Builder.create(0.85f)
            .addBoneAnimation("right_arm", rot(r(0, 0, 0, 0), r(0.25f, -80, 70, 0), l(0.35f, -82, -55, 0), r(0.5f, -75, -70, 0), r(0.85f, 0, 0, 0)))
            .addBoneAnimation("sword", rot(r(0, 0, 0, 0), r(0.25f, 52, 0, 0), l(0.35f, 52, 0, 0), r(0.5f, 45, 0, 0), r(0.85f, 0, 0, 0)))
            .addBoneAnimation("body", rot(r(0, 0, 0, 0), r(0.25f, 0, 28, 0), l(0.35f, 6, -30, 0), r(0.5f, 6, -34, 0), r(0.85f, 0, 0, 0)))
            .addBoneAnimation("head", rot(r(0, 0, 0, 0), r(0.25f, 0, -25, 0), l(0.35f, 0, 25, 0), r(0.5f, 0, 30, 0), r(0.85f, 0, 0, 0)))
            .addBoneAnimation("left_arm", rot(r(0, 0, 0, 0), r(0.25f, -30, 0, -10), l(0.35f, -10, 0, -30), r(0.5f, -10, 0, -28), r(0.85f, 0, 0, 0)))
            .addBoneAnimation("right_leg", rot(r(0, 0, 0, 0), l(0.35f, -12, 0, 0), r(0.85f, 0, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 0, 0, 0), l(0.35f, 10, 0, 0), r(0.85f, 0, 0, 0)))
            .build();

    /** Rising uppercut: drags the blade low behind it, then rips it up overhead. Hit lands at 0.3s. */
    static final Animation UPPERCUT = Animation.Builder.create(0.8f)
            .addBoneAnimation("right_arm", rot(r(0, 0, 0, 0), r(0.2f, 40, 20, 10), l(0.3f, -150, -10, 0), r(0.45f, -158, -10, 0), r(0.8f, 0, 0, 0)))
            .addBoneAnimation("sword", rot(r(0, 0, 0, 0), r(0.2f, 0, 0, 0), l(0.3f, 60, 0, 0), r(0.45f, 60, 0, 0), r(0.8f, 0, 0, 0)))
            .addBoneAnimation("body", rot(r(0, 0, 0, 0), r(0.2f, 16, 20, 0), l(0.3f, -14, -14, 0), r(0.45f, -16, -14, 0), r(0.8f, 0, 0, 0)))
            .addBoneAnimation("body", move(m(0, 0, 0, 0), m(0.2f, 0, -1.5f, 0), ml(0.3f, 0, 0.5f, 0), m(0.8f, 0, 0, 0)))
            .addBoneAnimation("head", rot(r(0, 0, 0, 0), r(0.2f, -14, -18, 0), l(0.3f, 10, 12, 0), r(0.8f, 0, 0, 0)))
            .addBoneAnimation("left_arm", rot(r(0, 0, 0, 0), r(0.2f, -35, 0, -8), l(0.3f, 10, 0, -22), r(0.8f, 0, 0, 0)))
            .addBoneAnimation("right_leg", rot(r(0, 0, 0, 0), r(0.2f, -16, 0, 0), l(0.3f, -6, 0, 0), r(0.8f, 0, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 0, 0, 0), r(0.2f, 12, 0, 0), l(0.3f, 4, 0, 0), r(0.8f, 0, 0, 0)))
            .build();

    /**
     * Dash wind-up: crouches behind its shield with the blade trailing back. Not looping, so the last keyframe
     * (the charge pose) holds for as long as the charge lasts.
     */
    static final Animation DASH_WINDUP = Animation.Builder.create(0.6f)
            .addBoneAnimation("body", rot(r(0, 0, 0, 0), r(0.45f, 32, -12, 0), r(0.6f, 26, -10, 0)))
            .addBoneAnimation("body", move(m(0, 0, 0, 0), m(0.45f, 0, -2.5f, 0), m(0.6f, 0, -1.5f, 0)))
            .addBoneAnimation("head", rot(r(0, 0, 0, 0), r(0.45f, -28, 10, 0), r(0.6f, -22, 8, 0)))
            .addBoneAnimation("left_arm", rot(r(0, 0, 0, 0), r(0.45f, -80, -25, 0), r(0.6f, -78, -22, 0)))
            .addBoneAnimation("right_arm", rot(r(0, 0, 0, 0), r(0.45f, 45, 10, 12), r(0.6f, 40, 8, 10)))
            .addBoneAnimation("sword", rot(r(0, 0, 0, 0), r(0.45f, 78, 0, 0), r(0.6f, 78, 0, 0)))
            .addBoneAnimation("right_leg", rot(r(0, 0, 0, 0), r(0.45f, -28, 0, 0), r(0.6f, -24, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 0, 0, 0), r(0.45f, 24, 0, 0), r(0.6f, 20, 0, 0)))
            .addBoneAnimation("back_cloth", rot(r(0, 0, 0, 0), r(0.6f, 25, 0, 0)))
            .build();

    /** End of the charge: from the charge pose straight into a heavy horizontal slash. */
    static final Animation DASH_STRIKE = Animation.Builder.create(0.7f)
            .addBoneAnimation("body", rot(r(0, 26, -10, 0), r(0.1f, 10, 25, 0), l(0.2f, 14, -30, 0), r(0.35f, 12, -28, 0), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("body", move(m(0, 0, -1.5f, 0), m(0.2f, 0, -1.5f, 0), m(0.7f, 0, 0, 0)))
            .addBoneAnimation("head", rot(r(0, -22, 8, 0), r(0.1f, -10, -20, 0), l(0.2f, -12, 25, 0), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("right_arm", rot(r(0, 40, 8, 10), r(0.1f, -95, 70, 0), l(0.2f, -85, -60, 0), r(0.35f, -80, -70, 0), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("sword", rot(r(0, 78, 0, 0), r(0.1f, 52, 0, 0), l(0.2f, 52, 0, 0), r(0.35f, 45, 0, 0), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("left_arm", rot(r(0, -78, -22, 0), r(0.1f, -40, 0, -10), l(0.2f, -10, 0, -30), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("right_leg", rot(r(0, -24, 0, 0), r(0.2f, -14, 0, 0), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 20, 0, 0), r(0.2f, 12, 0, 0), r(0.7f, 0, 0, 0)))
            .addBoneAnimation("back_cloth", rot(r(0, 25, 0, 0), r(0.7f, 0, 0, 0)))
            .build();

    /** Summoning: claws its way up out of the ground, looks up, and raises its sword in salute. */
    static final Animation RISE = Animation.Builder.create(2.0f)
            .addBoneAnimation("knight", move(m(0, 0, -34, 0), m(0.3f, 0, -26, 0), m(1.1f, 0, -2, 0), m(1.3f, 0, 0, 0)))
            .addBoneAnimation("body", rot(r(0, 35, 0, 0), r(1.1f, 20, 0, 0), r(1.4f, -6, 0, 0), r(1.6f, 0, 0, 0)))
            .addBoneAnimation("head", rot(r(0, 40, 0, 0), r(1.1f, 30, 0, 0), r(1.35f, -15, 0, 0), r(2.0f, 0, 0, 0)))
            .addBoneAnimation("right_arm", rot(r(0, -20, 0, 10), r(1.1f, -25, 0, 10), r(1.5f, -60, 0, 0), r(1.75f, -60, 0, 0), r(2.0f, 0, 0, 0)))
            .addBoneAnimation("sword", rot(r(0, 60, 0, 0), r(1.1f, 40, 0, 0), r(1.5f, -66, 0, 0), r(1.75f, -66, 0, 0), r(2.0f, 0, 0, 0)))
            .addBoneAnimation("left_arm", rot(r(0, -30, 0, -10), r(1.1f, -20, 0, -10), r(1.5f, -35, -20, 0), r(2.0f, 0, 0, 0)))
            .addBoneAnimation("right_leg", rot(r(0, -50, 0, 0), r(1.1f, -15, 0, 0), r(1.3f, 0, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 30, 0, 0), r(1.1f, 10, 0, 0), r(1.3f, 0, 0, 0)))
            .build();

    /** Dismissal: kneels, drives its sword into the ground, bows its head and sinks back into the earth. */
    static final Animation DISMISS = Animation.Builder.create(1.5f)
            .addBoneAnimation("knight", move(m(0, 0, 0, 0), m(0.5f, 0, -5, 0), m(0.7f, 0, -6, 0), ml(1.5f, 0, -40, 0)))
            .addBoneAnimation("body", rot(r(0, 0, 0, 0), r(0.5f, 28, 0, 0), r(1.5f, 32, 0, 0)))
            .addBoneAnimation("head", rot(r(0, 0, 0, 0), r(0.5f, 35, 0, 0), r(1.5f, 40, 0, 0)))
            .addBoneAnimation("right_arm", rot(r(0, 0, 0, 0), r(0.4f, -40, 0, 0), r(1.5f, -40, 0, 0)))
            .addBoneAnimation("sword", rot(r(0, 0, 0, 0), r(0.4f, 102, 0, 0), r(1.5f, 102, 0, 0)))
            .addBoneAnimation("left_arm", rot(r(0, 0, 0, 0), r(0.5f, -30, 0, -15), r(1.5f, -30, 0, -15)))
            .addBoneAnimation("right_leg", rot(r(0, 0, 0, 0), r(0.5f, -70, 0, 0), r(1.5f, -70, 0, 0)))
            .addBoneAnimation("left_leg", rot(r(0, 0, 0, 0), r(0.5f, 25, 0, 0), r(1.5f, 25, 0, 0)))
            .build();

    private static Transformation rot(Keyframe... keyframes) {
        return new Transformation(Transformation.Targets.ROTATE, keyframes);
    }

    private static Transformation move(Keyframe... keyframes) {
        return new Transformation(Transformation.Targets.TRANSLATE, keyframes);
    }

    /** Eased rotation keyframe, in degrees. */
    private static Keyframe r(float t, float x, float y, float z) {
        return new Keyframe(t, AnimationHelper.createRotationalVector(x, y, z), Transformation.Interpolations.CUBIC);
    }

    /** Snapping (linear) rotation keyframe - for the blow itself. */
    private static Keyframe l(float t, float x, float y, float z) {
        return new Keyframe(t, AnimationHelper.createRotationalVector(x, y, z), Transformation.Interpolations.LINEAR);
    }

    /** Eased translation keyframe, in pixels (+y up). */
    private static Keyframe m(float t, float x, float y, float z) {
        return new Keyframe(t, AnimationHelper.createTranslationalVector(x, y, z), Transformation.Interpolations.CUBIC);
    }

    private static Keyframe ml(float t, float x, float y, float z) {
        return new Keyframe(t, AnimationHelper.createTranslationalVector(x, y, z), Transformation.Interpolations.LINEAR);
    }
}
