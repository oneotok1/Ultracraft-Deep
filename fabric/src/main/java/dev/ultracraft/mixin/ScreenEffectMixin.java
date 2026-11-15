package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The block the camera is inside, which Minecraft paints over the whole screen.
 *
 * <p>Minecraft works this out from where Steve's eyes are. V1 is not walked by Minecraft's physics but teleported
 * onto ULTRAKILL's camera every tick, so pressing up against a block can leave that point a centimetre inside it for
 * a frame while the picture V1 actually sees is drawn by ULTRAKILL from its own camera, outside the block. Minecraft
 * then covers a whole screen that was never blocked. Nothing is lost by turning the pass off: everything it draws
 * (the inside of the block, fire, water) is ULTRAKILL's own picture here.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectMixin {
	@Inject(method = "getViewBlockingState", at = @At("RETURN"), cancellable = true)
	private static void ultracraft$noBlockInTheEyes(Player player, CallbackInfoReturnable<BlockState> cir) {
		if (Ultracraft.active && cir.getReturnValue() != null) cir.setReturnValue(null);
	}
}
