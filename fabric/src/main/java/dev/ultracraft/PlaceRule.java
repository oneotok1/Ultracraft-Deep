package dev.ultracraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where V1 may put a block.
 *
 * <p>Bridging works by hanging out over an edge and putting the next block level with the floor he walks on. That
 * block's top sits at his feet, so the two are "level" - and it must stay allowed, or bridging off an edge stops
 * working altogether.
 *
 * <p>The block stacked on top of the one under his feet is the opposite case: it rises a full block above his feet,
 * and because he is leaning out over the edge his body is no longer inside that column, so Minecraft's own obstruction
 * test finds nothing and lets it through. That is the block that lifts him onto a wall of his own making and then
 * drops him off it.
 */
public final class PlaceRule {
	private PlaceRule() {
	}

	/** How far above his feet a block may reach and still count as level with the floor. The client and the server do
	 * not agree on V1's feet to the digit (the pose reaches them a tick apart, measured 0.4 of a block), so a bridge
	 * block level with the floor can look slightly above his feet on the other side; half a block covers that and
	 * still leaves a stacked block (a full block up) on the wrong side of the line. */
	private static final double FootSlack = 0.5;

	/** Whether the block's top is level with the floor he walks on - the only shape a bridge block has. */
	private static boolean levelWithFloor(AABB box, Player player) {
		return box.maxY <= player.getY() + FootSlack;
	}

	/** The floor he stands on: the whole-number height his feet rest at, give or take the tick the pose is late by. */
	private static int floorY(Player player) {
		return (int) Math.round(player.getY());
	}

	/**
	 * Whether a block's square is one his feet are over, with a margin.
	 *
	 * <p>V1 is not a Minecraft body that walks: the pose arrives from ULTRAKILL and the player is teleported onto it
	 * every tick, on the client at once and on the server a packet later, so the two sides disagree about where his
	 * feet are by a few centimetres - and right at the edge of a block that is enough to put him on one side of the
	 * line on one attempt and the other side on the next. A test that asks for an exact overlap therefore answers
	 * differently for the same block, which is what let the block stacked under his feet through half the time. The
	 * margin is wider than that drift and still narrower than a block, so a block one square away is never caught.
	 */
	private static boolean feetOver(AABB box, Player player, double margin) {
		double x = player.getX(), z = player.getZ();
		return x + margin > box.minX && x - margin < box.maxX && z + margin > box.minZ && z - margin < box.maxZ;
	}

	/** Whether the block would reach into V1's body at all. */
	private static boolean touchesBody(AABB box, Player player) {
		AABB body = player.getBoundingBox();
		return box.maxX > body.minX && box.minX < body.maxX
				&& box.maxY > body.minY && box.minY < body.maxY
				&& box.maxZ > body.minZ && box.minZ < body.maxZ;
	}

	/**
	 * Minecraft turned the placement down. That happens while he hangs over an edge: his body is inside the square the
	 * bridge block is going into. Let it through if the block is level with the floor - and nothing else.
	 */
	public static boolean mayPlaceThroughV1(Level level, BlockState state, BlockPos pos, Player player) {
		if (player == null) return false;
		if (!UcNet.isV1(player)) return false;
		CollisionContext ctx = CollisionContext.placementContext(player);
		VoxelShape shape = state.getCollisionShape(level, pos, ctx);
		// an empty shape cannot have obstructed anything, so the place failed for some other reason
		if (shape.isEmpty()) return false;
		VoxelShape placed = shape.move(pos);
		AABB box = placed.bounds();
		// anything reaching above the floor is not a bridge block: it is one that would end up inside him
		if (!levelWithFloor(box, player))
			return false;
		if (!state.canSurvive(level, pos)) return false;
		for (Entity e : level.getEntities((Entity) null, box, e -> e != null && !e.isRemoved() && e.blocksBuilding)) {
			if (e.isPassengerOfSameVehicle(player)) continue;
			if (!Shapes.joinIsNotEmpty(placed, Shapes.create(e.getBoundingBox()), BooleanOp.AND)) continue;
			// someone else's body is in that square: their block, not V1's bridge
			if (!UcNet.isV1(e)) return false;
		}
		return true;
	}

	/**
	 * Minecraft would have allowed the placement on its own. Refuse the two shapes that end up putting V1 on top of
	 * his own block: the one stacked on the block under his feet, and any other block that rises above his feet and
	 * reaches into his body.
	 */
	public static boolean refuseOverV1(Level level, BlockState state, BlockPos pos, Player player) {
		if (player == null || !UcNet.isV1(player)) return false;
		CollisionContext ctx = CollisionContext.placementContext(player);
		VoxelShape shape = state.getCollisionShape(level, pos, ctx);
		if (shape.isEmpty()) return false;
		AABB box = shape.move(pos).bounds();
		// level with the floor: the bridge block, always allowed
		if (levelWithFloor(box, player)) return false;
		// A bridge block hangs below the floor he walks on - its top is the floor, so its bottom is a block under it.
		// Anything whose bottom is at the floor or above would be standing on that floor, and if it is also where his
		// feet are it is the block that lifts him onto his own wall. Asking for the bottom settles that without
		// depending on which square he is nominally in, and the margin is wide enough to survive the drift between
		// the client's pose and the server's.
		if (box.minY >= floorY(player) - 0.05 && feetOver(box, player, 0.4)) return true;
		return touchesBody(box, player);
	}
}
