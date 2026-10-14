package dev.ultracraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * An ULTRAKILL arena (Arenas): one per structure_set cell where its layer's biomes are, on ground flat enough to
 * stand it on (on the shore for Wrath; on a cavern floor with room above in the Nether; never over the End's void).
 */
final class ArenaStructure extends Structure {
	static final MapCodec<ArenaStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		settingsCodec(i),
		Codec.STRING.fieldOf("layer").forGetter(s -> s.layer)
	).apply(i, ArenaStructure::new));

	private final String layer;

	ArenaStructure(Structure.StructureSettings settings, String layer) {
		super(settings);
		this.layer = layer;
	}

	@Override
	protected Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext ctx) {
		if (!UltracraftConfig.arenas || Arenas.layer(layer) == null) return Optional.empty();
		ChunkPos cp = ctx.chunkPos();
		int x = cp.getMiddleBlockX(), z = cp.getMiddleBlockZ();
		Integer floor = layer.equals("heresy") ? cavernFloor(ctx, x, z) : groundFloor(ctx, x, z);
		if (floor == null) return Optional.empty();
		BlockPos center = new BlockPos(x, floor, z);
		return Optional.of(new Structure.GenerationStub(center, builder -> builder.addPiece(new ArenaPiece(layer, center))));
	}

	/** The ground under the arena: five spots across it, not too steep, not under water (except Wrath's shore). */
	private Integer groundFloor(Structure.GenerationContext ctx, int x, int z) {
		int r = ArenaPiece.R - 4;
		int[][] spots = {{0, 0}, {r, 0}, {-r, 0}, {0, r}, {0, -r}};
		int[] h = new int[spots.length];
		int min = ctx.heightAccessor().getMinY();
		for (int i = 0; i < spots.length; i++) {
			int px = x + spots[i][0], pz = z + spots[i][1];
			int surface = ctx.chunkGenerator().getFirstOccupiedHeight(px, pz, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
			int ground = ctx.chunkGenerator().getFirstOccupiedHeight(px, pz, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
			// over the void (the End's edges)
			if (ground <= min + 2) return null;
			if (surface - ground > 2 && !layer.equals("wrath")) return null;
			h[i] = Math.max(surface, ground);
		}
		int[] sorted = h.clone();
		Arrays.sort(sorted);
		if (sorted[sorted.length - 1] - sorted[0] > 14) return null;
		return sorted[sorted.length / 2];
	}

	/** In the Nether: a floor with room above for the arena (the first from the middle heights down). */
	private Integer cavernFloor(Structure.GenerationContext ctx, int x, int z) {
		NoiseColumn col = ctx.chunkGenerator().getBaseColumn(x, z, ctx.heightAccessor(), ctx.randomState());
		for (int y = 96; y > 34; y--) {
			BlockState s = col.getBlock(y);
			if (s.isAir() || !s.getFluidState().isEmpty()) continue;
			boolean room = true;
			for (int k = 1; k <= 14 && room; k++) if (!col.getBlock(y + k).isAir()) room = false;
			if (room) return y;
		}
		return null;
	}

	@Override
	public StructureType<?> type() {
		return Arenas.TYPE;
	}
}
