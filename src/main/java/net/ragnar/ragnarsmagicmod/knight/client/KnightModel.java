package net.ragnar.ragnarsmagicmod.knight.client;

import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.render.entity.model.SinglePartEntityModel;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.knight.KnightEntity;

/**
 * A blocky Knight of Hyrule, 48 pixels (three blocks) tall: skull-faced crested helm, bronze plate gone green with
 * moss, a curved blade in its right hand and a round shield on its left. Texture is 128x128, one texel per pixel.
 */
public class KnightModel extends SinglePartEntityModel<KnightEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of(RagnarsMagicMod.MOD_ID, "knight"), "main");

    private static final float SWORD_REST = 2.2f;   // ~126 degrees: blade forward and down, tip off the ground
    private static final float SHIELD_YAW = -0.3f;  // outer edge swung back a little

    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;
    private final ModelPart tabard;
    private final ModelPart backCloth;

    public KnightModel(ModelPart root) {
        this.root = root;
        ModelPart knight = root.getChild("knight");
        this.body = knight.getChild("body");
        this.head = body.getChild("head");
        this.rightArm = body.getChild("right_arm");
        this.leftArm = body.getChild("left_arm");
        this.tabard = body.getChild("tabard");
        this.backCloth = body.getChild("back_cloth");
        this.rightLeg = knight.getChild("right_leg");
        this.leftLeg = knight.getChild("left_leg");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        // Everything hangs off "knight" so the rise and dismiss animations can sink the whole body into the ground
        ModelPartData knight = data.getRoot().addChild("knight", ModelPartBuilder.create(), ModelTransform.NONE);

        // Legs: grey thigh under bronze knee guard and a heavy greave
        knight.addChild("right_leg", ModelPartBuilder.create()
                .uv(0, 61).cuboid(-3, 0, -3, 6, 12, 6)
                .uv(24, 61).cuboid(-3.5f, 11, -4.5f, 7, 7, 8)
                .uv(54, 61).cuboid(-2.5f, 7, -4, 5, 4, 2), ModelTransform.pivot(-4, 6, 0));
        knight.addChild("left_leg", ModelPartBuilder.create().mirrored()
                .uv(0, 61).cuboid(-3, 0, -3, 6, 12, 6)
                .uv(24, 61).cuboid(-3.5f, 11, -4.5f, 7, 7, 8)
                .uv(54, 61).cuboid(-2.5f, 7, -4, 5, 4, 2), ModelTransform.pivot(4, 6, 0));

        // Body pivots at the waist, so swings twist and lean from the hips
        ModelPartData body = knight.addChild("body", ModelPartBuilder.create()
                .uv(16, 22).cuboid(-6, -8, -3.5f, 12, 8, 7)        // mail-covered belly
                .uv(0, 0).cuboid(-8, -20, -4.5f, 16, 12, 9)        // breastplate
                .uv(54, 22).cuboid(-6.5f, -2, -4, 13, 3, 8)        // belt
                .uv(104, 27).cuboid(-2, -15, -5.5f, 4, 5, 1),      // bird-skull crest
                ModelTransform.pivot(0, 6, 0));

        body.addChild("head", ModelPartBuilder.create()
                .uv(50, 0).cuboid(-4.5f, -10, -4.5f, 9, 10, 9)     // helm with the skull face
                .uv(120, 0).cuboid(-1, -17, -1, 2, 7, 2)           // crest spike
                .uv(96, 22).cuboid(-7, -6, -1, 14, 2, 2)           // side bar
                .uv(96, 27).cuboid(-7, -9, -1, 2, 3, 2)            // horn tips
                .mirrored().uv(96, 27).cuboid(5, -9, -1, 2, 3, 2),
                ModelTransform.pivot(0, -20, 0));

        ModelPartData rightArm = body.addChild("right_arm", ModelPartBuilder.create()
                .uv(16, 38).cuboid(-2.5f, -2, -2.5f, 5, 17, 5)
                .uv(86, 0).cuboid(-5, -4.5f, -4.5f, 8, 6, 9)        // pauldron
                .uv(36, 38).cuboid(-3, 8, -3, 6, 7, 6),             // bracer
                ModelTransform.pivot(-10.5f, -17, 0));
        // The blade runs up local -y from the grip; its flat faces sideways, the curve sweeps toward +z
        rightArm.addChild("sword", ModelPartBuilder.create()
                .uv(114, 27).cuboid(-1, -3, -1, 2, 6, 2)            // grip
                .uv(104, 36).cuboid(-1.5f, -4, -3.5f, 3, 1, 7)      // guard
                .uv(94, 45).cuboid(-1.5f, 3, -1.5f, 3, 2, 3)        // pommel
                .uv(0, 22).cuboid(-0.5f, -28, -3, 1, 24, 7),        // curved blade (cut out of the texture)
                ModelTransform.of(0, 13.5f, -0.5f, SWORD_REST, 0, 0));

        ModelPartData leftArm = body.addChild("left_arm", ModelPartBuilder.create().mirrored()
                .uv(16, 38).cuboid(-2.5f, -2, -2.5f, 5, 17, 5)
                .uv(86, 0).cuboid(-3, -4.5f, -4.5f, 8, 6, 9)
                .uv(36, 38).cuboid(-3, 8, -3, 6, 7, 6),
                ModelTransform.pivot(10.5f, -17, 0));
        leftArm.addChild("shield", ModelPartBuilder.create()
                .uv(60, 34).cuboid(-8, -8, -1, 16, 16, 1)           // round shield (cut out of a square)
                .uv(94, 36).cuboid(-2, -2, -2, 4, 4, 1)             // boss
                .uv(0, 80).cuboid(-5, 7, -1.3f, 10, 7, 0),          // vines hanging off the rim
                ModelTransform.of(2, 9, -3.5f, 0, SHIELD_YAW, 0));

        body.addChild("tabard", ModelPartBuilder.create().uv(68, 61).cuboid(-3.5f, 0, -0.5f, 7, 13, 1),
                ModelTransform.pivot(0, 1, -4.6f));
        body.addChild("back_cloth", ModelPartBuilder.create().uv(84, 61).cuboid(-5.5f, 0, -0.5f, 11, 10, 1),
                ModelTransform.pivot(0, 1, 4.1f));

        return TexturedModelData.of(data, 128, 128);
    }

    @Override
    public ModelPart getPart() {
        return root;
    }

    @Override
    public void setAngles(KnightEntity knight, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        root.traverse().forEach(ModelPart::resetTransform);

        head.yaw = MathHelper.clamp(headYaw, -50f, 50f) * MathHelper.RADIANS_PER_DEGREE;
        head.pitch = headPitch * MathHelper.RADIANS_PER_DEGREE;

        // Heavy, slow stride
        float stride = limbAngle * 0.55f;
        float amount = Math.min(limbDistance, 1f);
        float step = MathHelper.cos(stride);
        rightLeg.pitch = step * 1.1f * amount;
        leftLeg.pitch = -step * 1.1f * amount;
        rightArm.pitch = -step * 0.5f * amount - 0.15f;
        leftArm.pitch = step * 0.25f * amount - 0.1f;
        body.pivotY += Math.abs(step) * 1.0f * amount;           // sinks onto each footfall
        body.yaw = -step * 0.08f * amount;
        body.roll = MathHelper.sin(stride) * 0.04f * amount;

        // Breathing and a slight sway when standing about
        float breathe = MathHelper.sin(age * 0.07f);
        body.pitch += breathe * 0.015f;
        head.pitch += breathe * 0.02f;
        rightArm.roll += 0.08f + breathe * 0.02f;
        leftArm.roll -= 0.1f + breathe * 0.02f;

        // Cloth gets pushed by whichever leg swings into it, and trails a little when moving
        float flutter = MathHelper.sin(age * 0.12f) * 0.03f;
        tabard.pitch = Math.min(0f, Math.min(rightLeg.pitch, leftLeg.pitch)) + flutter;
        backCloth.pitch = Math.max(0f, Math.max(rightLeg.pitch, leftLeg.pitch)) + amount * 0.25f - flutter;

        updateAnimation(knight.riseAnim, KnightAnimations.RISE, age);
        updateAnimation(knight.cleaveAnim, KnightAnimations.CLEAVE, age);
        updateAnimation(knight.sweepAnim, KnightAnimations.SWEEP, age);
        updateAnimation(knight.uppercutAnim, KnightAnimations.UPPERCUT, age);
        updateAnimation(knight.dashWindupAnim, KnightAnimations.DASH_WINDUP, age);
        updateAnimation(knight.dashStrikeAnim, KnightAnimations.DASH_STRIKE, age);
        updateAnimation(knight.dismissAnim, KnightAnimations.DISMISS, age);
    }
}
