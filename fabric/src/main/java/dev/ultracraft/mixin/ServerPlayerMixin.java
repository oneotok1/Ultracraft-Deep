package dev.ultracraft.mixin;

import dev.ultracraft.UcNet;
import dev.ultracraft.Ultracraft;
import dev.ultracraft.V1Damage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * V1's health lives in ULTRAKILL. Everything else Minecraft does to the player goes through its own rules first
 * (PlayerMixin hands what's left to V1); here only what V1 is spared or can't survive: falls (V1 lands on anything),
 * being inside or flying into blocks, and the void below the world.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void ultracraft$hurtV1(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (!UcNet.isV1(self)) return;
		// creative and spectator: Minecraft would turn every one of these away itself, and V1's health lives on the
		// other side where nothing asks it, so nothing here may reach him either
		if (self.isCreative() || self.isSpectator()) return;
		// a swing Minecraft is about to resolve over: armour, absorption hearts or a raised shield can eat the whole
		// thing, and the tail below reports it to ULTRAKILL as the flash-with-no-damage it still was
		V1Damage.arm(self, source);
		// a raised shield stops a mob's swing outright, so nothing reaches V1Damage.take: the swing still gets
		// ULTRAKILL's parry flash, with no damage behind it
		if (self.isBlocking()) V1Damage.blocked(self, source);
		if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
			// below the world: V1 dies in ULTRAKILL (like falling into a pit there), and Minecraft follows
			UcNet.send(self, "HURT 9999 void");
			cir.setReturnValue(false);
		} else if (source.is(DamageTypes.FALL) || source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.FLY_INTO_WALL) || source.is(DamageTypes.CRAMMING)) {
			// V1's body is ULTRAKILL's, which never sinks into blocks: Steve's taller box poking into a cave ceiling or
			// a shop (whose terminal is a little smaller than its blocks) mustn't suffocate it, nor a fast dash into a wall
			cir.setReturnValue(false);
		}
	}

	/**
	 * The swing is resolved. Nothing reached {@link V1Damage#take} if Minecraft's own hearts swallowed all of it, and
	 * that branch never calls setHealth, so the damage hook stays silent: report the swing here instead.
	 */
	@Inject(method = "hurtServer", at = @At("TAIL"))
	private void ultracraft$swingSettled(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		V1Damage.settle((ServerPlayer) (Object) this, source);
	}
}
