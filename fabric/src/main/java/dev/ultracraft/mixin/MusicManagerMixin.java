package dev.ultracraft.mixin;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft's music doesn't start while ULTRAKILL's fight music plays (the Hush Minecraft's Music setting). */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void ultracraft$hush(CallbackInfo ci) {
		if (dev.ultracraft.UcThemes.hushing()) ci.cancel();
	}
}
