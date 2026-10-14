package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import dev.ultracraft.WorldMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks broken or placed (by explosions, by anyone) change ULTRAKILL's far terrain too. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("TAIL"))
	private void ultracraft$terrainChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		if (oldState != newState) WorldMesh.blockChanged(pos);
	}

	/**
	 * A potion's swirls rise from around the drinker's body, and as V1 the camera is inside that body: each one came up
	 * right against the lens, a big square of colour (night vision's lime). Not V1's own; everyone else still sees them.
	 */
	@Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noOwnEffectSwirls(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz, CallbackInfo ci) {
		if (!Ultracraft.active || options.getType() != ParticleTypes.ENTITY_EFFECT) return;
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		double dx = x - p.getX(), dz = z - p.getZ();
		if (dx * dx + dz * dz < 1.0 && y >= p.getY() - 0.5 && y <= p.getY() + p.getBbHeight() + 0.5) ci.cancel();
	}
}
