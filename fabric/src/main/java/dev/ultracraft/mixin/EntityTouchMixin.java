package dev.ultracraft.mixin;

import dev.ultracraft.ServerOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Contact damage does not go through a mob's goals.
 * <p>
 * A slime hurts whatever walks into it from playerTouch - an Entity collision callback - so cancelling
 * Mob.serverAiStep leaves that path wide open and a mob on the hook can still bite. playerTouch is declared here on
 * Entity, which is why this mixin targets Entity rather than Mob; non-living entities simply never match.
 */
@Mixin(Entity.class)
public abstract class EntityTouchMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noTouchWhileReeled(Player player, CallbackInfo ci) {
		if ((Object) this instanceof LivingEntity living && ServerOps.isWhipped(living)) ci.cancel();
	}
}
