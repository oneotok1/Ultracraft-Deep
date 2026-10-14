package dev.ultracraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft's terrain as ULTRAKILL collision far beyond the box colliders kept around V1: one mesh per
 * 16x16x16 chunk section (greedy-merged exposed faces of full blocks, plus the boxes of partial shapes), so
 * beams, coins, rockets and nails hit walls in the distance. Sections are sent nearest first within a time
 * budget, resent when a block in them changes, and dropped when they fall out of range.
 */
public final class WorldMesh {
	private static final int RH = 8; // sections around the player horizontally (128 blocks) at most
	/** Sections around the player horizontally now (Performance → Terrain Range). */
	private static int rh() {
		return Math.max(4, Math.min(RH, UltracraftConfig.terrainRange / 16));
	}
	private static final int RV = 4; // sections above and below
	// and around each of ULTRAKILL's enemies (a boss V1 ran from, one up on a cliff): the ground under it stays, or it
	// would fall through the world
	private static final int AH = 1, A_BELOW = 2, A_ABOVE = 1, MAX_ANCHORS = 24;
	private static final long BUDGET_NANOS = 3_000_000L;
	private static final int[][] OFFSETS;

	/** Sections ULTRAKILL knows about, and whether they had any faces. */
	private static final Map<Long, Boolean> sent = new HashMap<>();
	private static final Set<Long> dirty = new HashSet<>();
	private static ClientLevel lastLevel;
	private static int ticks;

	static {
		List<int[]> l = new ArrayList<>();
		for (int dx = -RH; dx <= RH; dx++) {
			for (int dy = -RV; dy <= RV; dy++) {
				for (int dz = -RH; dz <= RH; dz++) l.add(new int[] {dx, dy, dz});
			}
		}
		l.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
		OFFSETS = l.toArray(new int[0][]);
	}

	private WorldMesh() {}

	/** ULTRAKILL lost (or never had) our sections: start over. */
	public static void reset() {
		sent.clear();
		dirty.clear();
		UkLink.send("SECCLR");
	}

	/** A block changed: its section (and the neighbour across a section border) needs resending. */
	public static void blockChanged(BlockPos pos) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		mark(x >> 4, y >> 4, z >> 4);
		if ((x & 15) == 0) mark((x >> 4) - 1, y >> 4, z >> 4);
		if ((x & 15) == 15) mark((x >> 4) + 1, y >> 4, z >> 4);
		if ((y & 15) == 0) mark(x >> 4, (y >> 4) - 1, z >> 4);
		if ((y & 15) == 15) mark(x >> 4, (y >> 4) + 1, z >> 4);
		if ((z & 15) == 0) mark(x >> 4, y >> 4, (z >> 4) - 1);
		if ((z & 15) == 15) mark(x >> 4, y >> 4, (z >> 4) + 1);
	}

	/** A chunk (re)loaded: it and its neighbours' borders may have changed. */
	public static void chunkLoaded(int cx, int cz) {
		for (long key : sent.keySet()) {
			if (Math.abs(SectionPos.x(key) - cx) <= 1 && Math.abs(SectionPos.z(key) - cz) <= 1) dirty.add(key);
		}
	}

	private static void mark(int sx, int sy, int sz) {
		long key = SectionPos.asLong(sx, sy, sz);
		if (sent.containsKey(key)) dirty.add(key);
	}

	public static void tick(ClientLevel level, BlockPos center) {
		if (level != lastLevel) {
			lastLevel = level;
			reset();
		}
		int cx = center.getX() >> 4, cy = center.getY() >> 4, cz = center.getZ() >> 4;
		long start = System.nanoTime();
		int rh = rh();
		boolean done = true;
		for (int[] o : OFFSETS) {
			if (Math.abs(o[0]) > rh || Math.abs(o[2]) > rh) continue;
			if (!send(level, cx + o[0], cy + o[1], cz + o[2])) continue;
			if (System.nanoTime() - start > BUDGET_NANOS) {
				done = false;
				break;
			}
		}
		List<int[]> anchors = anchors(cx, cy, cz, rh);
		if (done) {
			outer:
			for (int[] a : anchors) {
				for (int dy = -A_BELOW; dy <= A_ABOVE; dy++) {
					for (int dx = -AH; dx <= AH; dx++) {
						for (int dz = -AH; dz <= AH; dz++) {
							if (!send(level, a[0] + dx, a[1] + dy, a[2] + dz)) continue;
							if (System.nanoTime() - start > BUDGET_NANOS) break outer;
						}
					}
				}
			}
		}
		if (++ticks % 20 == 0) {
			Iterator<Map.Entry<Long, Boolean>> it = sent.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Long, Boolean> e = it.next();
				long key = e.getKey();
				int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
				if (Math.abs(x - cx) > rh + 1 || Math.abs(y - cy) > RV + 1 || Math.abs(z - cz) > rh + 1) {
					if (anchored(anchors, x, y, z)) continue;
					if (e.getValue()) UkLink.send("SECX " + x + " " + y + " " + z);
					dirty.remove(key);
					it.remove();
				}
			}
		}
	}

	/** Builds and sends one section if ULTRAKILL needs it (new or changed); false if there was nothing to do. */
	private static boolean send(ClientLevel level, int sx, int sy, int sz) {
		if (sy < level.getMinSectionY() || sy > level.getMaxSectionY()) return false;
		long key = SectionPos.asLong(sx, sy, sz);
		if (sent.containsKey(key) && !dirty.contains(key)) return false;
		if (!level.hasChunk(sx, sz)) return false;
		dirty.remove(key);
		build(level, sx, sy, sz, key);
		return true;
	}

	/** The sections of ULTRAKILL's enemies outside the range around V1, nearest first. */
	private static List<int[]> anchors(int cx, int cy, int cz, int rh) {
		List<int[]> l = new ArrayList<>();
		for (Lighting.Spot s : Lighting.enemySpots()) {
			int x = (int) Math.floor(s.x()) >> 4, y = (int) Math.floor(s.y()) >> 4, z = (int) Math.floor(s.z()) >> 4;
			if (Math.abs(x - cx) + AH <= rh && Math.abs(z - cz) + AH <= rh && y - A_BELOW >= cy - RV && y + A_ABOVE <= cy + RV) continue;
			l.add(new int[] {x, y, z});
		}
		l.sort(Comparator.comparingInt(a -> (a[0] - cx) * (a[0] - cx) + (a[1] - cy) * (a[1] - cy) + (a[2] - cz) * (a[2] - cz)));
		return l.size() > MAX_ANCHORS ? l.subList(0, MAX_ANCHORS) : l;
	}

	private static boolean anchored(List<int[]> anchors, int x, int y, int z) {
		for (int[] a : anchors) {
			if (Math.abs(x - a[0]) <= AH + 1 && Math.abs(z - a[2]) <= AH + 1 && y >= a[1] - A_BELOW - 1 && y <= a[1] + A_ABOVE + 1) return true;
		}
		return false;
	}

	/** Debug: sections ULTRAKILL has now, and how many are kept for its enemies. */
	static String info(BlockPos center) {
		int cx = center.getX() >> 4, cy = center.getY() >> 4, cz = center.getZ() >> 4;
		return "sections=" + sent.size() + " anchors=" + anchors(cx, cy, cz, rh()).size() + " enemies=" + Lighting.enemySpots().size();
	}

	private static int idx(int x, int y, int z) {
		return ((x + 1) * 18 + (y + 1)) * 18 + (z + 1);
	}

	private static void build(ClientLevel level, int sx, int sy, int sz, long key) {
		int bx = sx << 4, by = sy << 4, bz = sz << 4;
		LevelChunkSection sec = level.getChunk(sx, sz).getSection(level.getSectionIndexFromSectionY(sy));
		StringBuilder sb = new StringBuilder(4096);
		int faces = 0;
		if (!sec.hasOnlyAir()) {
			// full collision cubes in this section plus a one-block border; see-through ones (glass, leaves) collide
			// but don't hide ULTRAKILL's effects behind them, except where their texture is solid
			boolean[] full = new boolean[18 * 18 * 18];
			// 0 solid, 1 see-through, 2 + texture id: drawn with holes
			byte[] look = new byte[18 * 18 * 18];
			// glass hides its faces against more of the same glass (leaves don't: Minecraft draws leaf against leaf)
			boolean[] cullSame = new boolean[18 * 18 * 18];
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int x = -1; x <= 16; x++) {
				for (int y = -1; y <= 16; y++) {
					for (int z = -1; z <= 16; z++) {
						boolean inside = x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16;
						m.set(bx + x, by + y, bz + z);
						BlockState s = inside ? sec.getBlockState(x, y, z) : level.getBlockState(m);
						if (s.isAir()) continue;
						if (s.isCollisionShapeFullBlock(level, m)) {
							full[idx(x, y, z)] = true;
							CutoutTextures.Kind kind = CutoutTextures.kind(s);
							if (kind == CutoutTextures.Kind.SEE_THROUGH) look[idx(x, y, z)] = 1;
							else if (kind == CutoutTextures.Kind.TEXTURED) {
								int tex = CutoutTextures.id(s);
								look[idx(x, y, z)] = (byte) (tex >= 0 && tex < 120 ? 2 + tex : 1);
								cullSame[idx(x, y, z)] = !s.is(BlockTags.LEAVES);
							}
						} else if (inside) {
							// grass, flowers, crops, vines, torches, doors...: their model's own quads, drawn with holes
							faces += plant(sb, level, s, m, bx, by, bz);
							// fences, walls, stairs, panes, lanterns...: their model hides what's behind it
							faces += solid(sb, s, m, bx, by, bz);
							VoxelShape shape = s.getCollisionShape(level, m);
							if (!shape.isEmpty()) {
								int off = s.canOcclude() ? 0 : 6;
								for (AABB b : shape.toAabbs()) faces += box(sb, off, bx + x + b.minX, by + y + b.minY, bz + z + b.minZ, bx + x + b.maxX, by + y + b.maxY, bz + z + b.maxZ);
							}
						}
					}
				}
			}
			faces += greedy(sb, full, look, cullSame, bx, by, bz);
		}
		boolean had = sent.getOrDefault(key, false);
		if (faces == 0) {
			if (had) UkLink.send("SECX " + sx + " " + sy + " " + sz);
			sent.put(key, false);
			return;
		}
		UkLink.send("SEC " + sx + " " + sy + " " + sz + " " + sb);
		sent.put(key, true);
	}

	/**
	 * Exposed faces of full cubes, merged into rectangles per slice; face record = d,plane,a0,b0,a1,b1 with d + 6 for
	 * see-through blocks (merged separately), and a 7th field, the texture, for those drawn with holes. Those show
	 * every face Minecraft draws for them, the ones between two leaf blocks too: through one leaf's holes Minecraft
	 * shows the next leaf's face.
	 */
	private static int greedy(StringBuilder sb, boolean[] full, byte[] look, boolean[] cullSame, int bx, int by, int bz) {
		int faces = 0;
		byte[] mask = new byte[256];
		int[] base = {bx, by, bz};
		int[] c = new int[3];
		for (int d = 0; d < 6; d++) {
			int axis = d >> 1;
			int step = (d & 1) == 0 ? 1 : -1;
			// (u, v) are the other two axes in x, y, z order
			int ua = axis == 0 ? 1 : 0, va = axis == 2 ? 1 : 2;
			for (int i = 0; i < 16; i++) {
				boolean any = false;
				for (int v = 0; v < 16; v++) {
					for (int u = 0; u < 16; u++) {
						c[axis] = i;
						c[ua] = u;
						c[va] = v;
						int cell = idx(c[0], c[1], c[2]);
						byte kind = 0;
						if (full[cell]) {
							c[axis] = i + step;
							int n = idx(c[0], c[1], c[2]);
							// 1 = solid face, 2 = face of a see-through block, 3 + texture = face drawn with holes
							if (look[cell] >= 2) {
								if (!full[n] || (look[n] != 0 && !(cullSame[cell] && look[n] == look[cell]))) kind = (byte) (look[cell] + 1);
							} else if (!full[n]) kind = (byte) (look[cell] == 1 ? 2 : 1);
						}
						mask[u + v * 16] = kind;
						any |= kind != 0;
					}
				}
				if (!any) continue;
				int plane = base[axis] + i + (step > 0 ? 1 : 0);
				for (int v = 0; v < 16; v++) {
					for (int u = 0; u < 16; u++) {
						byte kind = mask[u + v * 16];
						if (kind == 0) continue;
						int w = 1;
						while (u + w < 16 && mask[u + w + v * 16] == kind) w++;
						int h = 1;
						grow:
						while (v + h < 16) {
							for (int k = 0; k < w; k++) if (mask[u + k + (v + h) * 16] != kind) break grow;
							h++;
						}
						for (int dv = 0; dv < h; dv++) for (int du = 0; du < w; du++) mask[u + du + (v + dv) * 16] = 0;
						int a0 = base[ua] + u, b0 = base[va] + v;
						sb.append(kind >= 2 ? d + 6 : d).append(',').append(plane).append(',').append(a0).append(',').append(b0).append(',')
							.append(a0 + w).append(',').append(b0 + h);
						if (kind >= 3) sb.append(',').append(kind - 3);
						sb.append(';');
						faces++;
					}
				}
			}
		}
		return faces;
	}

	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<BlockModelPart> PARTS = new ArrayList<>();
	private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	/**
	 * Blocks Minecraft draws with holes that aren't full cubes (grass, flowers, ferns, crops, saplings, vines, torches,
	 * doors, ladders, bars): every quad of their model as Minecraft draws it (random offsets and variants included),
	 * with its texture, so ULTRAKILL's enemies and effects hide behind the leaves and blades and show between them.
	 * Record: 12,tex,x,y,z,u,v (four corners, x y z from the section's corner, u v across the texture from its top left).
	 * Faces seen from both sides (a cross of grass is two quads per plane) go once: ULTRAKILL draws them double-sided.
	 */
	/** Plant quads sent so far, and plant blocks looked at (debug "mesh"). */
	static int plantQuads, plantBlocks;

	private static int plant(StringBuilder sb, ClientLevel level, BlockState s, BlockPos pos, int bx, int by, int bz) {
		if (s.getRenderShape() != RenderShape.MODEL || ItemBlockRenderTypes.getChunkRenderType(s) != ChunkSectionLayer.CUTOUT) return 0;
		plantBlocks++;
		var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(s);
		RANDOM.setSeed(s.getSeed(pos));
		PARTS.clear();
		model.collectParts(RANDOM, PARTS);
		Vec3 off = s.getOffset(pos);
		double ox = pos.getX() - bx + off.x, oy = pos.getY() - by + off.y, oz = pos.getZ() - bz + off.z;
		Set<String> seen = new HashSet<>();
		int n = 0;
		for (BlockModelPart part : PARTS) {
			for (Direction d : SIDES) {
				for (BakedQuad q : part.getQuads(d)) {
					TextureAtlasSprite sp = q.sprite();
					// the same four corners, whichever way round: once
					String key = corners(q);
					if (!seen.add(key)) continue;
					int tex = CutoutTextures.spriteId(sp);
					if (tex < 0) continue;
					float du = sp.getU1() - sp.getU0(), dv = sp.getV1() - sp.getV0();
					if (du == 0f || dv == 0f) continue;
					sb.append("12,").append(tex);
					for (int i = 0; i < 4; i++) {
						Vector3fc v = q.position(i);
						long uv = q.packedUV(i);
						sb.append(',');
						num(sb, ox + v.x()).append(',');
						num(sb, oy + v.y()).append(',');
						num(sb, oz + v.z()).append(',');
						num3(sb, (UVPair.unpackU(uv) - sp.getU0()) / du).append(',');
						num3(sb, (UVPair.unpackV(uv) - sp.getV0()) / dv);
					}
					sb.append(';');
					n++;
					plantQuads++;
				}
			}
		}
		return n;
	}

	/**
	 * Solid blocks that aren't full cubes (fences, walls, stairs, slabs, anvils, lanterns...): every quad of their
	 * model, so ULTRAKILL's enemies, the shop and effects hide behind a fence post or a wall's top exactly where
	 * Minecraft draws one (their collision boxes alone are often thinner, or don't hide anything at all).
	 * Record: 13,x,y,z (four corners, from the section's corner); depth only, drawn double-sided.
	 */
	private static int solid(StringBuilder sb, BlockState s, BlockPos pos, int bx, int by, int bz) {
		if (s.getRenderShape() != RenderShape.MODEL || ItemBlockRenderTypes.getChunkRenderType(s) != ChunkSectionLayer.SOLID) return 0;
		var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(s);
		RANDOM.setSeed(s.getSeed(pos));
		PARTS.clear();
		model.collectParts(RANDOM, PARTS);
		Vec3 off = s.getOffset(pos);
		double ox = pos.getX() - bx + off.x, oy = pos.getY() - by + off.y, oz = pos.getZ() - bz + off.z;
		Set<String> seen = new HashSet<>();
		int n = 0;
		for (BlockModelPart part : PARTS) {
			for (Direction d : SIDES) {
				for (BakedQuad q : part.getQuads(d)) {
					if (!seen.add(corners(q))) continue;
					sb.append("13");
					for (int i = 0; i < 4; i++) {
						Vector3fc v = q.position(i);
						sb.append(',');
						num(sb, ox + v.x()).append(',');
						num(sb, oy + v.y()).append(',');
						num(sb, oz + v.z());
					}
					sb.append(';');
					n++;
				}
			}
		}
		return n;
	}

	private static String corners(BakedQuad q) {
		String[] c = new String[4];
		for (int i = 0; i < 4; i++) {
			Vector3fc v = q.position(i);
			c[i] = Math.round(v.x() * 1000) + ":" + Math.round(v.y() * 1000) + ":" + Math.round(v.z() * 1000);
		}
		java.util.Arrays.sort(c);
		return String.join("|", c);
	}

	private static StringBuilder num3(StringBuilder sb, double v) {
		return sb.append(Math.round(v * 1000.0) / 1000.0);
	}

	/** All six faces of a partial collision box (slabs, stairs, fences...); off = 6 for see-through blocks. */
	private static int box(StringBuilder sb, int off, double x0, double y0, double z0, double x1, double y1, double z1) {
		face(sb, off, x1, y0, z0, y1, z1);
		face(sb, off + 1, x0, y0, z0, y1, z1);
		face(sb, off + 2, y1, x0, z0, x1, z1);
		face(sb, off + 3, y0, x0, z0, x1, z1);
		face(sb, off + 4, z1, x0, y0, x1, y1);
		face(sb, off + 5, z0, x0, y0, x1, y1);
		return 6;
	}

	private static void face(StringBuilder sb, int d, double p, double a0, double b0, double a1, double b1) {
		sb.append(d).append(',');
		num(sb, p).append(',');
		num(sb, a0).append(',');
		num(sb, b0).append(',');
		num(sb, a1).append(',');
		num(sb, b1).append(';');
	}

	private static StringBuilder num(StringBuilder sb, double v) {
		long r = Math.round(v);
		if (Math.abs(v - r) < 1e-6) return sb.append(r);
		return sb.append(Math.round(v * 10000.0) / 10000.0);
	}
}
