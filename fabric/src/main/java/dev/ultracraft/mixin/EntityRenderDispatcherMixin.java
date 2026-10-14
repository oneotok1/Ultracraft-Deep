package dev.ultracraft.mixin;

import dev.ultracraft.UcNet;
import dev.ultracraft.Ultracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Another player who is V1 isn't drawn as Steve while our ULTRAKILL draws V1's body where they are (we're V1, or
 * Steve with ULTRAKILL's layer over the view): with Steve on top it would show twice. Without it, Minecraft draws them. Experience orbs within five
 * blocks of the camera (on their way into V1, where Minecraft has the player) aren't drawn either.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void ultracraft$hideOtherV1(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		// (as Steve too, while our ULTRAKILL draws its enemies and other V1s over Minecraft's view)
		if ((Ultracraft.active || Ultracraft.steveView) && entity instanceof Player p && p != Minecraft.getInstance().player && UcNet.isV1(p)) cir.setReturnValue(false);
		// ourselves in third person (F5): ULTRAKILL draws V1's body there, not Steve
		if (Ultracraft.active && entity == Minecraft.getInstance().player) cir.setReturnValue(false);
		// experience flying into V1 gathers where Minecraft has the player, right at ULTRAKILL's camera: drawn there it
		// was a giant lime orb over the whole view
		if (Ultracraft.active && entity instanceof ExperienceOrb && entity.distanceToSqr(x, y, z) < 25.0) cir.setReturnValue(false);
	}
}
