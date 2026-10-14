package dev.ultracraft.mixin;

import dev.ultracraft.UcNet;
import dev.ultracraft.Ultracraft;
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
}
