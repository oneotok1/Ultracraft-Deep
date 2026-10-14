package dev.ultracraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.V1Arm;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With ULTRAKILL's guns out, V1's arm and guns come from ULTRAKILL and Minecraft draws no hands. With Minecraft hands
 * out (Steve mode) the held item shows in V1's own right arm, which swings, eats and draws a bow the way Steve's
 * would; an empty hand is V1's fist. Steve's arm never shows.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Shadow
	private void applyItemArmTransform(PoseStack poseStack, HumanoidArm arm, float equip) {
		throw new AssertionError();
	}

	@Shadow
	private void swingArm(float swing, PoseStack poseStack, int side, HumanoidArm arm) {
		throw new AssertionError();
	}

	/** With V1's hand on them, held items come up into view (Minecraft holds them from just below the screen's edge). */
	@Inject(method = "applyItemArmTransform", at = @At("TAIL"))
	private void ultracraft$liftIntoView(PoseStack poseStack, HumanoidArm arm, float equip, CallbackInfo ci) {
		if (Ultracraft.active && Ultracraft.hands) poseStack.translate((arm == HumanoidArm.RIGHT ? 1 : -1) * V1Arm.LIFT_X, V1Arm.LIFT_Y, 0f);
	}

	@Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void ultracraft$hideHand(float f, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
		// at a shop's screen, ULTRAKILL's own arm points at it instead
		if (Ultracraft.active && (!Ultracraft.hands || Ultracraft.shopTouch)) ci.cancel();
	}

	/** How much of Minecraft's swing V1's arm itself takes: the hand goes with the item, the arm behind it only a little. */
	private static final float ARM_SWING = 0.2f;

	/**
	 * An empty main hand: V1's fist where Steve's arm would be. Mining or punching, it jabs forward and back, a short
	 * punch, instead of Minecraft's item swing (which, with V1's long arm behind the hand, swept the whole arm up across
	 * the view).
	 */
	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
	private void ultracraft$v1Fist(PoseStack poseStack, SubmitNodeCollector collector, int light, float equip, float swing, HumanoidArm arm, CallbackInfo ci) {
		if (!Ultracraft.active) return;
		ci.cancel();
		if (!Ultracraft.hands) return;
		int side = arm == HumanoidArm.RIGHT ? 1 : -1;
		float jab = Mth.sin(Mth.sqrt(swing) * Mth.PI);
		poseStack.translate(side * -0.12f * jab, 0.06f * jab, -0.32f * Mth.sin(swing * Mth.PI));
		applyItemArmTransform(poseStack, arm, equip);
		partSwing(poseStack, swing, side, ARM_SWING);
		V1Arm.render(poseStack, collector, light, arm, true);
	}

	/**
	 * Right before Minecraft draws a held item: V1's hand around it, moved by the same transforms, except for most of
	 * the swing's turn (undone here, a little of it kept): a pickaxe still swings, but the arm holding it doesn't sweep
	 * across the view.
	 */
	@Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
	private void ultracraft$v1Hand(AbstractClientPlayer player, float partial, float pitch, InteractionHand hand, float swing, ItemStack stack, float equip,
		PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (!Ultracraft.active || !Ultracraft.hands || hand != InteractionHand.MAIN_HAND) return;
		HumanoidArm arm = player.getMainArm();
		if (swing > 0f && !player.isUsingItem() && !player.isAutoSpinAttack() && stack.getSwingAnimation().type() == SwingAnimationType.WHACK) {
			poseStack.pushPose();
			int side = arm == HumanoidArm.RIGHT ? 1 : -1;
			undoSwing(poseStack, swing, side);
			partSwing(poseStack, swing, side, ARM_SWING);
			V1Arm.render(poseStack, collector, light, arm, false);
			poseStack.popPose();
			return;
		}
		V1Arm.render(poseStack, collector, light, arm, false);
	}

	/** Minecraft's swingArm turns (at rest they cancel out), scaled by keep. */
	private static void partSwing(PoseStack poseStack, float swing, int side, float keep) {
		float g = Mth.sin(swing * swing * Mth.PI), h = Mth.sin(Mth.sqrt(swing) * Mth.PI);
		poseStack.mulPose(Axis.YP.rotationDegrees(side * (45f - 20f * g * keep)));
		poseStack.mulPose(Axis.ZP.rotationDegrees(side * h * -20f * keep));
		poseStack.mulPose(Axis.XP.rotationDegrees(h * -80f * keep));
		poseStack.mulPose(Axis.YP.rotationDegrees(side * -45f));
	}

	/** Undo swingArm's turns (the inverse, in reverse order). */
	private static void undoSwing(PoseStack poseStack, float swing, int side) {
		float g = Mth.sin(swing * swing * Mth.PI), h = Mth.sin(Mth.sqrt(swing) * Mth.PI);
		poseStack.mulPose(Axis.YP.rotationDegrees(side * 45f));
		poseStack.mulPose(Axis.XP.rotationDegrees(h * 80f));
		poseStack.mulPose(Axis.ZP.rotationDegrees(side * h * 20f));
		poseStack.mulPose(Axis.YP.rotationDegrees(-side * (45f - 20f * g)));
	}
}
