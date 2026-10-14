package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Projectiles fly through V1's Minecraft stand-in: ULTRAKILL decides whether they hit V1 (its body, dodge
 * invincibility, parries) and tells us, and then the hit is applied through {@link Ultracraft.HitApplier}.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin implements Ultracraft.HitApplier {
	@Shadow
	protected abstract void onHit(HitResult hitResult);

	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void ultracraft$passThroughV1(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (Ultracraft.isV1(entity)) cir.setReturnValue(false);
	}

	@Inject(method = "onHit", at = @At("TAIL"))
	private void ultracraft$parried(HitResult hit, CallbackInfo ci) {
		if (!((Projectile) (Object) this).level().isClientSide()) dev.ultracraft.ServerOps.parriedHit((Projectile) (Object) this, hit);
	}

	@Override
	public void ultracraft$applyHit(HitResult hit) {
		onHit(hit);
	}
}
