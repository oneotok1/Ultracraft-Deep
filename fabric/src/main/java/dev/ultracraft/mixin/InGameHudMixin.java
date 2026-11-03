package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xaero's minimap draws after the whole HUD (up in GameRenderer), and vanilla's title and the rest draw inside
 * Gui.render: so when it's in, V1's overlay skips its own HUD slot (Ultracraft) and draws at Gui.render's head
 * instead — first, as the backdrop, with every HUD element (title, hotbar, the map) landing on top of it.
 */
@Mixin(value = Gui.class)
public abstract class InGameHudMixin {
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
	private void ultracraft$drawUnderMinimap(GuiGraphics ctx, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!Ultracraft.xaeroPresent) return;
		Ultracraft.renderOverlay(ctx, deltaTracker.getGameTimeDeltaPartialTick(false));
	}
}
