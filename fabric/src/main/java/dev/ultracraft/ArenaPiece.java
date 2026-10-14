package dev.ultracraft;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * An arena, built block by block from its layer's palette (Arenas.Layer): a round floor with its pattern (a cross and a
 * ring, a checkerboard for Fraud, a moat for Wrath), lights set into it, a raised dais in the middle where the boss
 * comes, a wall all round with four gateways (windows where the layer has them), eight pillars with lights on top (soul
 * fire for Heresy, end rods for the Prime Sanctum), and a foundation down to the ground. Everything depends only on
 * where a block is, so the chunks it spans build it the same.
 */
final class ArenaPiece extends StructurePiece {
	/** The arena's radius (blocks). */
	static final int R = 14;
	private static final int CLEAR = 14;

	private final String layer;
	private final BlockPos center;

	ArenaPiece(String layer, BlockPos center) {
		super(Arenas.PIECE, 0, new BoundingBox(center.getX() - R - 1, center.getY() - 16, center.getZ() - R - 1, center.getX() + R + 1, center.getY() + CLEAR + 2,
			center.getZ() + R + 1));
		this.layer = layer;
		this.center = center;
	}

	ArenaPiece(CompoundTag tag) {
		super(Arenas.PIECE, tag);
		this.layer = tag.getStringOr("layer", "limbo");
		this.center = new BlockPos(tag.getIntOr("cx", 0), tag.getIntOr("cy", 64), tag.getIntOr("cz", 0));
	}

	String layer() {
		return layer;
	}

	BlockPos center() {
		return center;
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag tag) {
		tag.putString("layer", layer);
		tag.putInt("cx", center.getX());
		tag.putInt("cy", center.getY());
		tag.putInt("cz", center.getZ());
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos chunk, BlockPos pivot) {
		Arenas.Layer L = Arenas.layer(layer);
		if (L == null) return;
		int cx = center.getX(), cy = center.getY(), cz = center.getZ();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = Math.max(box.minX(), cx - R - 1); x <= Math.min(box.maxX(), cx + R + 1); x++) {
			for (int z = Math.max(box.minZ(), cz - R - 1); z <= Math.min(box.maxZ(), cz + R + 1); z++) {
				int dx = x - cx, dz = z - cz;
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > R + 0.5) continue;
				double angle = Math.toDegrees(Math.atan2(dz, dx));
				// a clear space above, a foundation below
				for (int y = cy + 1; y <= cy + CLEAR; y++) set(level, box, x, y, z, air);
				foundation(level, box, x, cy - 1, z, L.base().defaultBlockState());
				// the wall, its gateways and windows
				if (d > R - 0.5) {
					boolean gate = Math.abs(dx) <= 1 && Math.abs(dz) > R - 2 || Math.abs(dz) <= 1 && Math.abs(dx) > R - 2;
					set(level, box, x, cy, z, L.wall().defaultBlockState());
					for (int y = cy + 1; y <= cy + 4; y++) {
						BlockState s = L.wall().defaultBlockState();
						if (gate && y <= cy + 3) s = air;
						else if (gate) s = L.top().defaultBlockState();
						else if (y == cy + 3 && L.window() != null && Math.floorMod((int) Math.round(angle), 24) < 8) s = L.window().defaultBlockState();
						set(level, box, x, y, z, s);
					}
					set(level, box, x, cy + 5, z, L.top().defaultBlockState());
					continue;
				}
				set(level, box, x, cy, z, floor(L, x, z, dx, dz, d, angle));
				// the dais
				if (d <= 1.5) set(level, box, x, cy + 1, z, L.dais().defaultBlockState());
			}
		}
		// eight pillars inside the wall, between the gateways
		for (int k = 0; k < 8; k++) {
			double th = Math.toRadians(22.5 + 45 * k);
			int px = cx + (int) Math.round((R - 2.5) * Math.cos(th)), pz = cz + (int) Math.round((R - 2.5) * Math.sin(th));
			for (int y = cy + 1; y <= cy + 7; y++) set(level, box, px, y, pz, L.pillar().defaultBlockState());
			switch (L.key()) {
				case "heresy" -> {
					set(level, box, px, cy + 8, pz, Blocks.SOUL_SOIL.defaultBlockState());
					set(level, box, px, cy + 9, pz, Blocks.SOUL_FIRE.defaultBlockState());
				}
				case "prime" -> {
					set(level, box, px, cy + 8, pz, L.light().defaultBlockState());
					set(level, box, px, cy + 9, pz, Blocks.END_ROD.defaultBlockState());
				}
				case "greed" -> {
					set(level, box, px, cy + 8, pz, Blocks.GOLD_BLOCK.defaultBlockState());
					set(level, box, px, cy + 9, pz, L.light().defaultBlockState());
				}
				default -> set(level, box, px, cy + 8, pz, L.light().defaultBlockState());
			}
		}
	}

	/** The floor's block here: the dais, the pattern, the lights, Wrath's moat. */
	private static BlockState floor(Arenas.Layer L, int x, int z, int dx, int dz, double d, double angle) {
		if (d <= 2.5) return L.dais().defaultBlockState();
		// lights in a ring, eight of them
		if (d >= 7.5 && d < 8.5 && (Math.floorMod((int) Math.round(angle), 45) <= 3 || Math.floorMod((int) Math.round(angle), 45) >= 42)) return L.light().defaultBlockState();
		if (L.key().equals("wrath") && d >= 10.5 && d < 11.5) return Blocks.WATER.defaultBlockState();
		if (L.key().equals("fraud")) return ((x + z) & 1) == 0 ? L.accent().defaultBlockState() : L.floor().defaultBlockState();
		boolean cross = (dx == 0 || dz == 0) && d > 3 && d < R - 1;
		boolean ring = d >= R - 3.5 && d < R - 2.5;
		return cross || ring ? L.accent().defaultBlockState() : L.floor().defaultBlockState();
	}

	/** The foundation: down from the floor until the ground (16 blocks at most). */
	private static void foundation(WorldGenLevel level, BoundingBox box, int x, int fromY, int z, BlockState base) {
		for (int y = fromY; y > fromY - 16; y--) {
			BlockPos p = new BlockPos(x, y, z);
			if (!box.isInside(p)) return;
			BlockState s = level.getBlockState(p);
			if (!s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()) return;
			level.setBlock(p, base, 2);
		}
	}

	private static void set(WorldGenLevel level, BoundingBox box, int x, int y, int z, BlockState s) {
		BlockPos p = new BlockPos(x, y, z);
		if (box.isInside(p)) level.setBlock(p, s, 2);
	}
}
