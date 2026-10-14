package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse look goes to ULTRAKILL's CameraController (its own sensitivity), not Minecraft's player. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;
	@Shadow @Final private Minecraft minecraft;

	/** While an ULTRAKILL menu is open the cursor stays free (clicking would otherwise grab it again). */
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void ultracraft$keepCursorForMenus(CallbackInfo ci) {
		if (Ultracraft.active && Ultracraft.uiMode) ci.cancel();
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void ultracraft$lookToV1(double time, CallbackInfo ci) {
		if (!Ultracraft.active) return;
		Ultracraft.mouseDx += accumulatedDX;
		Ultracraft.mouseDy += accumulatedDY;
		ci.cancel();
	}

	/**
	 * The wheel is ULTRAKILL's (weapon switching, scrolling the spawn menu) unless V1 has Minecraft hands out, when it
	 * picks the hotbar slot as usual. Minecraft's own screens (inventory, chat) keep it either way.
	 */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void ultracraft$wheelToV1(long window, double dx, double dy, CallbackInfo ci) {
		if (!Ultracraft.active || minecraft.screen != null) return;
		if (Ultracraft.hands && !Ultracraft.uiMode) return;
		Ultracraft.wheel += dy;
		ci.cancel();
	}
}
