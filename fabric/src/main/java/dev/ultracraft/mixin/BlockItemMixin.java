package dev.ultracraft.mixin;

import dev.ultracraft.PlaceRule;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BlockItem.canPlace turns a placement down when the new block's shape would overlap an entity that blocks building,
 * and the player is one of those. While V1 hangs over an edge his Minecraft body sticks out past the block he stands
 * on, so the block he is trying to bridge into lands inside himself and the placement is refused. PlaceRule re-asks
 * the same question but lets V1's own body through, and only for a block that does not rise above his feet.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Inject(method = "canPlace", at = @At("RETURN"), cancellable = true)
	private void ultracraft$placeUnderV1(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		// the game's own answer is only half of it. Hanging over an edge V1's body leaves the column of the block he
		// stands on, so a block stacked on top of it no longer cuts into him and Minecraft lets it through - the very
		// placement that lifts him onto a wall of his own making
		if (PlaceRule.refuseOverV1(context.getLevel(), state, context.getClickedPos(), context.getPlayer())) {
			cir.setReturnValue(false);
			return;
		}
		if (Boolean.TRUE.equals(cir.getReturnValue())) return;
		if (PlaceRule.mayPlaceThroughV1(context.getLevel(), state, context.getClickedPos(), context.getPlayer())) cir.setReturnValue(true);
	}
}
