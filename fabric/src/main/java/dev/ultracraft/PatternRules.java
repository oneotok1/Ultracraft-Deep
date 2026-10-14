package dev.ultracraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ultracraft's own Cyber Grind patterns, in ULTRAKILL's way: built from a rule each (rings, steps, trenches, islands,
 * a pyramid...) rather than drawn, with ULTRAKILL's markers put where ULTRAKILL's own patterns put them: melee
 * enemies (n) on the open floor, ranged ones (p) up high, stairs (s) where a step up is one or two levels, a jump pad
 * (J) at the foot of something tall. Heights are ULTRAKILL's levels (2.5 units, 1.5 blocks here); -15 is a pit.
 */
final class PatternRules {
	private static final int N = GrindArenas.N, PIT = -15;

	private PatternRules() {}

	interface Rule {
		int height(int i, int j, Random r);
	}

	/** ULTRAKILL's first wave: everything level. */
	static GrindArenas.Pattern flat() {
		return make("THE GRID", (i, j, r) -> 0, 0);
	}

	static List<GrindArenas.Pattern> all() {
		List<GrindArenas.Pattern> l = new ArrayList<>();
		l.add(make("PYRAMID", (i, j, r) -> Math.max(0, 4 - Math.max(dist(i), dist(j)) / 2), 1));
		l.add(make("CRATER", (i, j, r) -> Math.min(4, Math.max(dist(i), dist(j)) / 2), 2));
		l.add(make("RINGS", (i, j, r) -> (Math.max(dist(i), dist(j)) / 2) % 2 == 0 ? 2 : 0, 3));
		l.add(make("TRENCHES", (i, j, r) -> i % 4 == 1 ? -3 : i % 4 == 3 ? 2 : 0, 4));
		l.add(make("CROSSROADS", (i, j, r) -> Math.abs(i - 7.5) < 2 || Math.abs(j - 7.5) < 2 ? 0 : 3, 5));
		l.add(make("CHECKERBOARD", (i, j, r) -> ((i / 2) + (j / 2)) % 2 == 0 ? 2 : 0, 6));
		l.add(make("STAIRWAY", (i, j, r) -> Math.min(6, (i + j) / 5), 7));
		l.add(make("ISLANDS", (i, j, r) -> {
			boolean island = (i % 5 < 3 && j % 5 < 3) || (Math.abs(i - 7.5) < 2 && Math.abs(j - 7.5) < 2);
			return island ? 1 + ((i / 5 + j / 5) % 3) : PIT;
		}, 8));
		l.add(make("PILLARS", (i, j, r) -> i % 4 == 2 && j % 4 == 2 ? 6 : 0, 9));
		l.add(make("THE PIT", (i, j, r) -> Math.max(dist(i), dist(j)) <= 3 ? PIT : Math.max(dist(i), dist(j)) == 4 ? 0 : 1, 10));
		l.add(make("WAVES", (i, j, r) -> (int) Math.round(2 + 2 * Math.sin(i * 0.8) * Math.cos(j * 0.5)), 11));
		l.add(make("SCATTER", (i, j, r) -> r.nextInt(10) < 6 ? 0 : r.nextInt(10) < 6 ? 1 : r.nextInt(4) == 0 ? -3 : 3, 12));
		l.add(make("BRIDGES", (i, j, r) -> i == 7 || i == 8 || j == 7 || j == 8 || (i % 15 == 0 || j % 15 == 0) ? 1 : PIT, 13));
		l.add(make("TERRACES", (i, j, r) -> Math.min(5, j / 3), 14));
		l.add(make("FORTRESS", (i, j, r) -> {
			int d = Math.max(dist(i), dist(j));
			if (d == 5) return (i + j) % 3 == 0 ? 2 : 4;
			return d < 5 ? 1 : 0;
		}, 15));
		l.add(make("SPIRAL", (i, j, r) -> {
			double a = Math.atan2(j - 7.5, i - 7.5), d = Math.hypot(i - 7.5, j - 7.5);
			return ((int) Math.floor((a + Math.PI) / (Math.PI * 2) * 6 + d / 2.5)) % 3;
		}, 16));
		// a raised ring round the edge, ramps up to it from the middle of each side, a sunken square in the middle
		l.add(make("COLOSSEUM", (i, j, r) -> {
			int edge = Math.min(Math.min(i, j), Math.min(15 - i, 15 - j));
			if (edge <= 1) return 4;
			boolean ramp = (Math.abs(i - 7.5) < 1 && (j <= 5 || j >= 10)) || (Math.abs(j - 7.5) < 1 && (i <= 5 || i >= 10));
			if (ramp) return Math.max(0, 6 - edge);
			return Math.max(dist(i), dist(j)) <= 1 ? -2 : 0;
		}, 17));
		l.add(make("ZIGGURAT", (i, j, r) -> Math.max(0, 6 - Math.max(dist(i), dist(j))), 18));
		l.add(make("TWIN TOWERS", (i, j, r) -> {
			if ((i >= 2 && i <= 4 && j >= 2 && j <= 4) || (i >= 11 && i <= 13 && j >= 11 && j <= 13)) return 8;
			return (i + j) % 7 == 0 && i % 3 == 1 ? 1 : 0;
		}, 19));
		l.add(maze(20));
		l.add(make("CANYON", (i, j, r) -> {
			if (Math.abs(j - 7.5) < 2.5) return (i == 4 || i == 11) ? 2 : -3;
			return 2;
		}, 21));
		l.add(make("PLATEAU", (i, j, r) -> {
			boolean plus = Math.abs(i - 7.5) < 3 || Math.abs(j - 7.5) < 3;
			int edge = Math.min(Math.min(i, j), Math.min(15 - i, 15 - j));
			if (plus) return 3;
			return edge >= 2 ? PIT : 0;
		}, 22));
		l.add(make("STEPPING STONES", (i, j, r) -> {
			if (Math.abs(i - 7.5) < 2 && Math.abs(j - 7.5) < 2) return 1;
			boolean stone = (i / 2 + j / 2) % 3 == 0 && i % 2 == 0 && j % 2 == 0 || (i % 4 < 2 && j % 4 < 2 && (i / 4 + j / 4) % 2 == 0);
			return stone ? (i + j) % 4 : PIT;
		}, 23));
		l.add(make("AMPHITHEATER", (i, j, r) -> {
			double d = Math.hypot(i - 7.5, j - 12);
			if (j >= 12) return d < 4 ? 1 : 0;
			return Math.min(5, (int) (d / 2.4));
		}, 24));
		l.add(make("BUNKERS", (i, j, r) -> {
			int ci = i % 8, cj = j % 8;
			boolean wall = (ci == 1 || ci == 6 || cj == 1 || cj == 6) && ci >= 1 && ci <= 6 && cj >= 1 && cj <= 6;
			boolean door = (ci == 3 || ci == 4) && (cj == 6 || cj == 1);
			return wall && !door ? 3 : 0;
		}, 25));
		l.add(make("THE HOLE", (i, j, r) -> {
			double d = Math.hypot(i - 7.5, j - 7.5);
			if (d < 4.5) return PIT;
			return d > 7.2 ? 2 : 0;
		}, 26));
		l.add(make("RIDGES", (i, j, r) -> Math.floorMod(i + j, 6) < 2 ? 3 : 0, 27));
		l.add(make("CASTLE", (i, j, r) -> {
			int edge = Math.min(Math.min(i, j), Math.min(15 - i, 15 - j));
			boolean corner = (i <= 1 || i >= 14) && (j <= 1 || j >= 14);
			if (corner) return 6;
			if (edge == 0) return (j == 15 && Math.abs(i - 7.5) < 2) ? 0 : 3;
			if (Math.max(dist(i), dist(j)) <= 2) return 4;
			return 0;
		}, 28));
		l.add(make("PINWHEEL", (i, j, r) -> {
			int q = (i < 8 ? 0 : 1) + (j < 8 ? 0 : 2);
			int a = i % 8, b = j % 8;
			return switch (q) {
				case 0 -> a / 2;
				case 1 -> b / 2;
				case 2 -> (7 - b) / 2;
				default -> (7 - a) / 2;
			};
		}, 29));
		l.add(make("VOLCANO", (i, j, r) -> {
			double d = Math.hypot(i - 7.5, j - 7.5);
			if (d < 2.2) return -3;
			if (d < 3.5) return 6;
			return Math.max(0, (int) Math.round(6 - (d - 3.5) * 1.3));
		}, 30));
		l.add(make("LANES", (i, j, r) -> {
			if (i % 5 == 4) return (j == 3 || j == 12) ? 0 : -3;
			return i % 5 == 2 ? 2 : 0;
		}, 31));
		l.add(make("CHECKER PITS", (i, j, r) -> ((i / 2) + (j / 2)) % 2 == 0 ? 1 : (Math.abs(i - 7.5) < 2 || Math.abs(j - 7.5) < 2 ? 0 : PIT), 32));
		return l;
	}

	/** A maze of walls two levels high, its corridors one tile wide, the same every time (drawn from its seed). */
	private static GrindArenas.Pattern maze(long seed) {
		Random r = new Random(seed);
		boolean[][] open = new boolean[N][N];
		int cells = N / 2;
		boolean[][] seen = new boolean[cells][cells];
		java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
		stack.push(new int[] {0, 0});
		seen[0][0] = true;
		open[0][0] = true;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		while (!stack.isEmpty()) {
			int[] c = stack.peek();
			List<int[]> next = new ArrayList<>();
			for (int[] d : dirs) {
				int ni = c[0] + d[0], nj = c[1] + d[1];
				if (ni >= 0 && nj >= 0 && ni < cells && nj < cells && !seen[ni][nj]) next.add(new int[] {ni, nj});
			}
			if (next.isEmpty()) {
				stack.pop();
				continue;
			}
			int[] n = next.get(r.nextInt(next.size()));
			seen[n[0]][n[1]] = true;
			open[n[0] * 2][n[1] * 2] = true;
			open[c[0] * 2 + (n[0] - c[0])][c[1] * 2 + (n[1] - c[1])] = true;
			stack.push(n);
		}
		// a clearing in the middle, so a fight has room
		for (int i = 6; i <= 9; i++) for (int j = 6; j <= 9; j++) open[i][j] = true;
		return make("MAZE", (i, j, rr) -> open[i][j] ? 0 : 2, seed);
	}

	/** How far a row or column is from the middle (0 for the middle two). */
	private static int dist(int i) {
		return (int) Math.floor(Math.abs(i - 7.5));
	}

	/** A pattern from its rule, with ULTRAKILL's markers set where they'd be. */
	private static GrindArenas.Pattern make(String name, Rule rule, long seed) {
		Random r = new Random(seed * 7919L + name.hashCode());
		int[][] h = new int[N][N];
		char[][] m = new char[N][N];
		for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) h[i][j] = Math.max(PIT, Math.min(20, rule.height(i, j, r)));
		int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
		for (int[] row : h) for (int v : row) if (v > PIT) {
			lowest = Math.min(lowest, v);
			highest = Math.max(highest, v);
		}
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				m[i][j] = '0';
				int v = h[i][j];
				if (v <= PIT) continue;
				int up = upStep(h, i, j);
				// a step up next to it: stairs, half the time for one level (ramps stay climbable), now and then for two
				if ((up == 1 && r.nextInt(2) == 0) || (up == 2 && r.nextInt(4) == 0)) {
					m[i][j] = 's';
					continue;
				}
				// at the foot of something three levels or more up: a jump pad, now and then
				if (up >= 3 && r.nextInt(3) == 0) {
					m[i][j] = 'J';
					continue;
				}
				boolean high = highest > lowest && v >= highest - (highest - lowest) / 3;
				int roll = r.nextInt(8);
				if (high && roll < 3) m[i][j] = 'p';
				else if (!high && roll < 2) m[i][j] = 'n';
			}
		}
		// never a flat pattern without anywhere for enemies: some of each, spread out
		if (name.equals("THE GRID")) {
			for (int i = 1; i < N; i += 4) for (int j = 1; j < N; j += 4) m[i][j] = (i + j) % 8 == 2 ? 'p' : 'n';
		}
		return new GrindArenas.Pattern(name, h, m);
	}

	/** How many levels up its highest four-way neighbour is (0 if none is higher). */
	private static int upStep(int[][] h, int i, int j) {
		int up = 0;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : dirs) {
			int ni = i + d[0], nj = j + d[1];
			if (ni < 0 || nj < 0 || ni >= N || nj >= N) continue;
			up = Math.max(up, h[ni][nj] - h[i][j]);
		}
		return up;
	}
}
