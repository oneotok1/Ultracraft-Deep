package dev.ultracraft.mixin;

import dev.ultracraft.MobRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With "Minecraft Mobs" off, a new monster never enters the world (every spawn goes through here). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void ultracraft$noMonsters(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (MobRules.blocked(entity)) cir.setReturnValue(false);
	}
}
