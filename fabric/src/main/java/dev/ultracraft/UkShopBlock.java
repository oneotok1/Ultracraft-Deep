package dev.ultracraft;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * ULTRAKILL's shop terminal as a Minecraft block: two blocks wide, three high, one deep (the real terminal's size), its
 * screen facing whoever placed it. As V1, walking up to it opens the real ULTRAKILL shop (SmileOS 2.0: weapons and
 * their variations and colours, the enemies, the Cyber Grind, the Sandbox, tip of the day); otherwise Minecraft shows
 * the same terminal, its screen dark. The block placed is the lower left one seen from the front (part 0), which
 * carries the drawing and drops the shop; the other five only fill the space.
 */
public final class UkShopBlock extends HorizontalDirectionalBlock implements EntityBlock {
	public static final MapCodec<UkShopBlock> CODEC = simpleCodec(UkShopBlock::new);
	/** Left (even) or right (odd) column, rows 0-1, 2-3, 4-5 from the bottom, seen from in front of the screen. */
	public static final IntegerProperty PART = IntegerProperty.create("part", 0, 5);
	static final int PARTS = 6;

	public UkShopBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, PART);
	}

	/** The way to the right of the screen, as someone standing in front of it sees it. */
	static Direction right(Direction facing) {
		return facing.getCounterClockWise();
	}

	/** Where part p sits from part 0. */
	static BlockPos offset(Direction facing, int part) {
		BlockPos o = BlockPos.ZERO;
		if ((part & 1) != 0) o = o.relative(right(facing));
		return o.above(part >> 1);
	}

	static BlockPos anchor(BlockPos pos, BlockState state) {
		return pos.subtract(offset(state.getValue(FACING), state.getValue(PART)));
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
		Direction facing = ctx.getHorizontalDirection().getOpposite();
		BlockPos pos = ctx.getClickedPos();
		Level level = ctx.getLevel();
		if (pos.getY() + 2 > level.getMaxY()) return null;
		for (int part = 1; part < PARTS; part++) {
			if (!level.getBlockState(pos.offset(offset(facing, part))).canBeReplaced(ctx)) return null;
		}
		return defaultBlockState().setValue(FACING, facing).setValue(PART, 0);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide()) return;
		Direction facing = state.getValue(FACING);
		for (int part = 1; part < PARTS; part++) level.setBlock(pos.offset(offset(facing, part)), state.setValue(PART, part), 3);
	}

	/** One part gone: the whole shop goes (part 0 drops it, so it comes back as one item). */
	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir, BlockPos neighbourPos,
		BlockState neighbour, RandomSource random) {
		Direction facing = state.getValue(FACING);
		BlockPos base = anchor(pos, state);
		for (int part = 0; part < PARTS; part++) {
			if (!base.offset(offset(facing, part)).equals(neighbourPos)) continue;
			boolean ours = neighbour.is(this) && neighbour.getValue(FACING) == facing && neighbour.getValue(PART) == part;
			return ours ? state : Blocks.AIR.defaultBlockState();
		}
		return super.updateShape(state, level, ticks, pos, dir, neighbourPos, neighbour, random);
	}

	/** In creative, breaking any part leaves nothing behind (part 0 would otherwise drop the shop as it goes). */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && player.preventsBlockDrops() && state.getValue(PART) != 0) {
			BlockPos base = anchor(pos, state);
			BlockState b = level.getBlockState(base);
			if (b.is(this) && b.getValue(PART) == 0) {
				level.setBlock(base, Blocks.AIR.defaultBlockState(), 35);
				level.levelEvent(player, 2001, base, Block.getId(b));
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide() && !Ultracraft.active) {
			player.displayClientMessage(Component.literal(UkLink.connected ? "The shop is ULTRAKILL's: become V1 (F8) and walk up to the screen"
				: "The shop is ULTRAKILL's: start ULTRAKILL and become V1 to use it"), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		// drawn by UkShopRenderer (or by ULTRAKILL itself, for V1)
		return RenderShape.INVISIBLE;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return false;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0f;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return state.getValue(PART) == 0 ? new UkShopBlockEntity(pos, state) : null;
	}
}
