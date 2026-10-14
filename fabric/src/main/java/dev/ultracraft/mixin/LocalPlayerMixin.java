package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ctrl is V1's slide, not Steve's sprint: V1 moves by ULTRAKILL's rules, and a Minecraft sprint flag would only burn
 * hunger at V1's speeds.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noSprint(CallbackInfoReturnable<Boolean> cir) {
		if (Ultracraft.active) cir.setReturnValue(false);
	}
}
