package dev.ultracraft.mixin;

import dev.ultracraft.V1Damage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The last step of Minecraft hurting a player, after shields, armour, enchantments, Resistance and absorption hearts
 * have had their say: what's left goes to V1 in ULTRAKILL instead of off Steve's health.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Redirect(method = "actuallyHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V"))
	private void ultracraft$hurtV1(Player self, float health, ServerLevel level, DamageSource source, float amount) {
		if (!V1Damage.take(self, source, self.getHealth() - health)) self.setHealth(health);
	}
}
