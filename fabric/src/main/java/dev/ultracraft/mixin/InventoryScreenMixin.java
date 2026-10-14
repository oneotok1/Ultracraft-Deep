package dev.ultracraft.mixin;

import dev.ultracraft.UkDoll;
import dev.ultracraft.Ultracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The inventory's player window shows V1 (the real one, from ULTRAKILL) instead of Steve, and this world's P under it. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
	@Inject(method = "renderEntityInInventoryFollowsMouse", at = @At("HEAD"), cancellable = true)
	private static void ultracraft$v1Doll(GuiGraphics g, int x1, int y1, int x2, int y2, int scale, float yOffset, float mouseX, float mouseY, LivingEntity entity, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!Ultracraft.active || entity != mc.player) return;
		boolean doll = UkDoll.draw(g, x1, y1, x2, y2, mouseX, mouseY);
		String p = Ultracraft.moneyText();
		g.drawString(mc.font, p, x2 - 2 - mc.font.width(p), y2 - 10, 0xFFFF4343);
		if (doll) ci.cancel();
	}
}
