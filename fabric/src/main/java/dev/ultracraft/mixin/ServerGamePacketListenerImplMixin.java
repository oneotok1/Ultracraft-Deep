package dev.ultracraft.mixin;

import dev.ultracraft.Ultracraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * V1 moves by ULTRAKILL's physics (a slide under a one-block gap, a capsule a little slimmer than Steve's box), which
 * Minecraft's "moved wrongly" check would answer with a teleport back. Now that teleports move V1, those corrections
 * would yank V1 around: V1's moves are taken as they come, as a spectator's are.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Redirect(method = "handleMovePlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerPlayer;noPhysics:Z", opcode = Opcodes.GETFIELD))
	private boolean ultracraft$acceptV1(ServerPlayer player) {
		return player.noPhysics || Ultracraft.isV1(player);
	}
}
