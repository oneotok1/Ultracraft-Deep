package dev.ultracraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Marks a placed shop (its lower left block), so Minecraft can draw it and tell ULTRAKILL where shops stand. */
public final class UkShopBlockEntity extends BlockEntity {
	public UkShopBlockEntity(BlockPos pos, BlockState state) {
		super(UltracraftCommon.UK_SHOP_ENTITY, pos, state);
	}
}
