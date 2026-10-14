package dev.ultracraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

/**
 * Minecraft's water and lava around V1 for ULTRAKILL's own Water: FLUID water|lava rrggbb x0,y0,z0,x1,y1,z1;...
 * Each column's stretch of fluid is a box as high as the fluid stands (a full block under more fluid, 8/9 for a
 * still surface, lower where it flows), and columns alike are merged into rectangles, so a lake is a few boxes.
 */
final class Fluids {
	private static final int R = 20, DOWN = 12, UP = 12;
	private static String lastWater, lastLava;
	private static long lastAt;

	private Fluids() {}

	static void export(ClientLevel level, BlockPos c) {
		if (level == null) return;
		String water = String.format("%06x", BiomeColors.getAverageWaterColor(level, c) & 0xFFFFFF) + " " + boxes(level, c, true);
		String lava = "ff5a00 " + boxes(level, c, false);
		// unchanged: don't make ULTRAKILL redo it, but say it again now and then in case it restarted
		long now = System.currentTimeMillis();
		boolean again = now - lastAt > 5000;
		if (again) lastAt = now;
		if (again || !water.equals(lastWater)) UkLink.send("FLUID water " + water);
		if (again || !lava.equals(lastLava)) UkLink.send("FLUID lava " + lava);
		lastWater = water;
		lastLava = lava;
	}

	static void reset() {
		lastWater = null;
		lastLava = null;
	}

	private static String boxes(ClientLevel level, BlockPos c, boolean water) {
		int n = 2 * R + 1;
		int x0 = c.getX() - R, z0 = c.getZ() - R;
		// every column's stretches of fluid, keyed by (bottom, top): columns with the same stretch merge
		Map<String, boolean[][]> columns = new HashMap<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < n; dx++) {
			for (int dz = 0; dz < n; dz++) {
				int runStart = Integer.MIN_VALUE;
				for (int y = c.getY() - DOWN; y <= c.getY() + UP; y++) {
					m.set(x0 + dx, y, z0 + dz);
					FluidState fs = level.getFluidState(m);
					boolean in = !fs.isEmpty() && fs.is(water ? FluidTags.WATER : FluidTags.LAVA);
					if (in && runStart == Integer.MIN_VALUE) runStart = y;
					boolean last = y == c.getY() + UP;
					if (runStart != Integer.MIN_VALUE && (!in || last)) {
						// the stretch's top: the last fluid block's own height (1 with more of it above)
						int topY = in ? y : y - 1;
						m.set(x0 + dx, topY, z0 + dz);
						float top = topY + level.getFluidState(m).getHeight(level, m);
						String key = runStart + "," + String.format(Locale.ROOT, "%.3f", top);
						columns.computeIfAbsent(key, k -> new boolean[n][n])[dx][dz] = true;
						runStart = Integer.MIN_VALUE;
					}
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		List<String> keys = new ArrayList<>(columns.keySet());
		keys.sort(null);
		for (String key : keys) {
			boolean[][] g = columns.get(key);
			String[] yy = key.split(",");
			// greedy rectangles: as far as it goes along x, then as many rows along z as match
			for (int dz = 0; dz < n; dz++) {
				for (int dx = 0; dx < n; dx++) {
					if (!g[dx][dz]) continue;
					int ex = dx;
					while (ex + 1 < n && g[ex + 1][dz]) ex++;
					int ez = dz;
					rows:
					while (ez + 1 < n) {
						for (int i = dx; i <= ex; i++) if (!g[i][ez + 1]) break rows;
						ez++;
					}
					for (int i = dx; i <= ex; i++) for (int j = dz; j <= ez; j++) g[i][j] = false;
					sb.append(x0 + dx).append(',').append(yy[0]).append(',').append(z0 + dz).append(',')
						.append(x0 + ex + 1).append(',').append(yy[1]).append(',').append(z0 + ez + 1).append(';');
				}
			}
		}
		return sb.toString();
	}
}
