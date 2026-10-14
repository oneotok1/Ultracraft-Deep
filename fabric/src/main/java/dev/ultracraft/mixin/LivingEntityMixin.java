package dev.ultracraft.mixin;

import dev.ultracraft.UcNet;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.V1Damage;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft healing the player (regeneration from a full stomach, Regeneration and Instant Health, golden apples,
 * beacons) heals V1 in ULTRAKILL too; V1 doesn't make Steve's hurt sound (ULTRAKILL plays its own); and Minecraft's
 * knockback (a mob's hit, an arrow, a blocked swing's pushback) shoves V1 in ULTRAKILL as it would shove Steve.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "heal", at = @At("HEAD"))
	private void ultracraft$healV1(float amount, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer sp && UcNet.isV1(sp) && amount > 0f) UcNet.send(sp, "HEAL " + amount);
	}

	/** As V1 a dead mob goes without Minecraft's white puff of smoke: ULTRAKILL's blood has burst out of it instead. */
	@Inject(method = "makePoofParticles", at = @At("HEAD"), cancellable = true)
	private void ultracraft$bloodNotSmoke(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() && self.isDeadOrDying() && (Ultracraft.active || Ultracraft.steveDrawn)) ci.cancel();
	}

	@Inject(method = "playHurtSound", at = @At("HEAD"), cancellable = true)
	private void ultracraft$quietV1(DamageSource source, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer sp && UcNet.isV1(sp)) ci.cancel();
	}

	/**
	 * Minecraft's own knockback sum (strength less knockback resistance, away from the hit, a hop when on the ground),
	 * applied by ULTRAKILL to V1's speed instead. A mob's swing goes along with its MELEE, so a parry cancels both.
	 */
	@Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
	private void ultracraft$knockV1(double strength, double x, double z, CallbackInfo ci) {
		if (!((Object) this instanceof ServerPlayer sp) || !UcNet.isV1(sp)) return;
		ci.cancel();
		double d = strength * (1.0 - sp.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
		if (d <= 0.0) return;
		while (x * x + z * z < 1.0E-5) {
			x = (Math.random() - Math.random()) * 0.01;
			z = (Math.random() - Math.random()) * 0.01;
		}
		Vec3 push = new Vec3(x, 0.0, z).normalize().scale(d);
		double up = sp.onGround() ? Math.min(0.4, d) : -1.0;
		UcNet.send(sp, String.format(Locale.ROOT, "KNOCK %.4f %.4f %.4f %d", -push.x, -push.z, up, V1Damage.meleeThisTick(sp)));
	}
}
