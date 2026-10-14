package dev.ultracraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ultracraft.UkFrame;
import dev.ultracraft.UkLink;
import dev.ultracraft.Ultracraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** Same vertical field of view as ULTRAKILL's camera (for the frame shown) so the two images line up. */
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void ultracraft$v1Fov(Camera camera, float partial, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
		if (!Ultracraft.active && Ultracraft.steveView && useFovSetting) {
			// Steve's own field of view goes to ULTRAKILL; the frame shown was drawn with the one before
			Ultracraft.steveFov = cir.getReturnValue();
			float[] f = UkFrame.framePose;
			if (Ultracraft.steveDrawn && f != null) cir.setReturnValue(f[6]);
			return;
		}
		if (!Ultracraft.active || !useFovSetting) return;
		float[] f = UkFrame.framePose;
		if (f != null) {
			cir.setReturnValue(f[6]);
			return;
		}
		UkLink.Pose p = UkLink.pose;
		if (p != null) cir.setReturnValue(p.fov);
	}

	/** Minecraft's hurt tilt would knock its world out of line with ULTRAKILL's layer; ULTRAKILL shakes its own camera. */
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noHurtTilt(PoseStack poseStack, float partial, CallbackInfo ci) {
		if (Ultracraft.active || Ultracraft.steveDrawn) ci.cancel();
	}

	/** Walking as Steve, the view's bob would shake Minecraft's world out of line with ULTRAKILL's enemies. */
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noBobUnderV1Layer(PoseStack poseStack, float partial, CallbackInfo ci) {
		if (!Ultracraft.active && Ultracraft.steveDrawn) ci.cancel();
	}

	/** The outline on the block in reach only shows with Minecraft hands out, when V1 can mine or use it. */
	@Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void ultracraft$outlineWithHands(CallbackInfoReturnable<Boolean> cir) {
		if (Ultracraft.active && !Ultracraft.hands) cir.setReturnValue(false);
	}
}
