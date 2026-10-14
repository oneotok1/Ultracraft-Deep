package dev.ultracraft.mixin;

import dev.ultracraft.Movement;
import dev.ultracraft.UkLink;
import dev.ultracraft.Ultracraft;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server moving the player: a teleport (ender pearl, chorus fruit, /tp, waking up, getting off a mount) puts V1
 * there in ULTRAKILL; a blast's knockback (creeper, TNT, wind charge) throws V1. Both run on the client thread (the
 * network thread's first call throws before reaching the tail).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void ultracraft$teleportV1(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		var p = Minecraft.getInstance().player;
		if (p != null) Movement.serverMoved(p);
	}

	/**
	 * As V1, what's picked up isn't drawn flying into the player: Minecraft's player is where ULTRAKILL's camera is, so
	 * an experience orb or item on its way in filled the whole view. It's still picked up, with its sound.
	 */
	@Redirect(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;add(Lnet/minecraft/client/particle/Particle;)V"))
	private void ultracraft$noPickupFlight(ParticleEngine engine, Particle particle) {
		if (!Ultracraft.active) engine.add(particle);
	}

	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void ultracraft$blastV1(ClientboundExplodePacket packet, CallbackInfo ci) {
		if (!Ultracraft.active || !UkLink.connected) return;
		packet.playerKnockback().ifPresent(v -> {
			if (v.lengthSqr() > 1e-6) UkLink.send(String.format(Locale.ROOT, "PUSH %.4f %.4f %.4f", v.x, v.y, v.z));
		});
	}
}
