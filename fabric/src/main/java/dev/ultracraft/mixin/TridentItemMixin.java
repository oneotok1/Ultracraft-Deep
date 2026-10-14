package dev.ultracraft.mixin;

import dev.ultracraft.UkLink;
import dev.ultracraft.Ultracraft;
import java.util.Locale;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A Riptide trident thrown in water or rain launches V1 the way it launches Steve. */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
	@Redirect(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"))
	private void ultracraft$riptideV1(Player player, double x, double y, double z) {
		if (player.level().isClientSide() && Ultracraft.isV1(player) && UkLink.connected) {
			// off the ground, Minecraft also lifts the player a block first: a little more up instead
			UkLink.send(String.format(Locale.ROOT, "PUSH %.4f %.4f %.4f", x, y + (player.onGround() ? 0.3 : 0.0), z));
		}
		player.push(x, y, z);
	}
}
