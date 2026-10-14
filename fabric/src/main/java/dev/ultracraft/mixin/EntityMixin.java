package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While ULTRAKILL draws the view (as V1, or as Steve with its layer over Minecraft's), something burning burns with
 * ULTRAKILL's own fire (bridge Fire.cs, from ENTS), not Minecraft's flat flames: those were drawn over everything on
 * fire, ULTRAKILL's enemies' invisible stand-ins included. Our own burning view (the flames at the bottom of the screen)
 * stays.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "displayFireAnimation", at = @At("HEAD"), cancellable = true)
	private void ultracraft$ukFire(CallbackInfoReturnable<Boolean> cir) {
		if ((Ultracraft.active || Ultracraft.steveView) && (Object) this != Minecraft.getInstance().player) cir.setReturnValue(false);
	}
}
