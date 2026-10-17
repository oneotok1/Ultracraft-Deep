package dev.ultracraft.mixin;

import dev.ultracraft.ServerOps;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Slime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A reeling mob must not land a touch.
 * <p>
 * A slime hurts whatever bumps into it from playerTouch, which it declares itself - cancelling Entity.playerTouch
 * does nothing to it, and the touch does not go through a goal either, so neither the goal router nor the AI step
 * stops it. Its own isDealsDamage/hurtServer path is cut here instead, at the one call that actually deals the damage.
 */
@Mixin(Slime.class)
public abstract class SlimeStunMixin {
	@Inject(method = "dealDamage", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noTouchWhileReeled(LivingEntity target, CallbackInfo ci) {
		if (ServerOps.isWhipped((Slime) (Object) this)) ci.cancel();
	}
}
