package dev.ultracraft.mixin;

import dev.ultracraft.ServerOps;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A mob the hook is reeling in has lost the use of its body, the way ULTRAKILL's reel leaves its enemies helpless.
 * <p>
 * serverAiStep is where every decision is made - the senses, both goal selectors, the mob's own custom step, the
 * move/look/jump controls and the path navigation. Cancelling it leaves the mob with no way to choose an attack, so
 * the swing that would have landed on V1 is never begun, rather than being started and then quietly refunded.
 * <p>
 * Physics is untouched on purpose: the reel's speed is applied by ServerOps and integrated by LivingEntity.aiStep
 * through travel(), which is a separate method. Suppressing the AI this way cannot stop the mob from moving.
 */
@Mixin(Mob.class)
public abstract class MobAiMixin {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noAiWhileReeled(CallbackInfo ci) {
		if (ServerOps.isWhipped((LivingEntity) (Object) this)) ci.cancel();
	}
}
