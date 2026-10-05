package dev.brights0ng.enginesandempires.geophone.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/**
 * The third-person arm pose for holding the portable record display up in both hands, while its map is open.
 *
 * <p>Arm poses are an enum ({@link HumanoidModel.ArmPose}), and a mod adds to one through NeoForge's enum extensions:
 * {@code META-INF/enumextensions.json} names the new constant and points at {@link #RAISED} here for its constructor's
 * arguments (two-handed, and the transformer that poses the arms). The game builds the constant when it first loads the enum,
 * and {@code RAISED.getValue()} returns it afterwards.
 *
 * <p>This class is loaded while that enum is being built, so it must stay tiny: nothing here may reach into the rest of the
 * mod's client code, or the game.
 */
public final class PrdArmPose {

    /** Both arms forward and in, the forearms angled down to the display held in front of the chest. */
    public static final EnumProxy<HumanoidModel.ArmPose> RAISED = new EnumProxy<>(HumanoidModel.ArmPose.class, true,
            (IArmPoseTransformer) PrdArmPose::pose);

    /** How far the arms are raised (radians; 0 hangs straight down), and turned in towards each other. */
    static final float RAISE = -0.95F;
    static final float INWARD = 0.4F;

    private static void pose(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
        // A little of the head's tilt, so looking down at the display lowers it.
        float look = model.head.xRot * 0.3F;
        model.rightArm.xRot = RAISE + look;
        model.rightArm.yRot = -INWARD;
        model.rightArm.zRot = 0;
        model.leftArm.xRot = RAISE + look;
        model.leftArm.yRot = INWARD;
        model.leftArm.zRot = 0;
    }

    private PrdArmPose() {
    }
}
