package dev.ultracraft.mixin;

import com.mojang.serialization.Lifecycle;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ultracraft's own dimension (the Cyber Grind's arenas, data/ultracraft/dimension) makes Minecraft call every world
 * "experimental" and ask about a backup each time it's opened. Nothing in it is experimental: the world is stable.
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin {
	@Inject(method = "worldGenSettingsLifecycle", at = @At("HEAD"), cancellable = true)
	private void ultracraft$stable(CallbackInfoReturnable<Lifecycle> cir) {
		cir.setReturnValue(Lifecycle.stable());
	}
}
