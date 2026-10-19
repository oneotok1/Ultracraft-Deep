package dev.ultracraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Cyber Grind's arena, as ULTRAKILL has it: a 16 x 16 grid of square columns floating in a void dimension of its
 * own (ultracraft:cybergrind), each column raised or sunk by the wave's pattern, and between waves every column slides
 * to the next pattern's height. Patterns are ULTRAKILL's own .cgp format: the ones in ULTRAKILL's Cybergrind/Patterns
 * folder (its examples and any the player made or downloaded), and Ultracraft's own, built from rules (PatternRules).
 * A pattern's markers are used as ULTRAKILL uses them: melee enemies come in on n tiles, ranged ones on p, J is a jump
 * pad (ULTRAKILL's real one, placed by UltraBridge), s a staircase up to the next column.
 *
 * Every few waves the grid takes on one of ULTRAKILL's layers (its blocks and sky). A ledge off the south side carries
 * a temporary shop: its screen is the way out. Server thread.
 *
 * Scale: ULTRAKILL's tiles are 5 units wide and step 2.5 units a level; here a tile is 3 blocks and a level 1.5 blocks
 * (a slab on top for the half), so steps keep ULTRAKILL's proportions: too tall for an enemy to walk up (they take the
 * stairs), one jump for V1.
 */
final class GrindArenas {
	static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("ultracraft", "cybergrind"));

	/** Where the grid's level 0 is (the top of a level-0 column), and where it stands. */
	private static final int Y = 100, OX = 1024, OZ = 0;
	/** 16 x 16 tiles of 3 x 3 blocks; heights in half blocks, three to a pattern level. */
	static final int N = 16, TILE = 3, SIZE = N * TILE, HALF = SIZE / 2, PER_LEVEL = 3;
	/** The lowest and highest a column goes (half blocks from Y), and the columns' foot. */
	private static final int MIN_H = -42, MAX_H = 66, BASE = Y - 24;

	/**
	 * A layer's blocks: floor (column tops), accent (the grid's dots), wall, top (trim), pillar (the grid's lines),
	 * light, base (column bodies), slab (half-level tops).
	 */
	record Palette(Block floor, Block accent, Block wall, Block top, Block pillar, Block light, Block base, Block slab) {}

	static final Palette CYBER = new Palette(Blocks.BLACK_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.CYAN_CONCRETE,
		Blocks.GRAY_CONCRETE, Blocks.SEA_LANTERN, Blocks.BLACK_CONCRETE, Blocks.POLISHED_BLACKSTONE_SLAB);
	static final Palette LIMBO = new Palette(Blocks.STONE_BRICKS, Blocks.SEA_LANTERN, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
		Blocks.QUARTZ_PILLAR, Blocks.SEA_LANTERN, Blocks.STONE, Blocks.STONE_BRICK_SLAB);
	static final Palette LUST = new Palette(Blocks.RED_NETHER_BRICKS, Blocks.PEARLESCENT_FROGLIGHT, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
		Blocks.PURPUR_PILLAR, Blocks.PEARLESCENT_FROGLIGHT, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICK_SLAB);
	static final Palette GLUTTONY = new Palette(Blocks.NETHER_WART_BLOCK, Blocks.SHROOMLIGHT, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
		Blocks.BONE_BLOCK, Blocks.SHROOMLIGHT, Blocks.NETHER_WART_BLOCK, Blocks.RED_NETHER_BRICK_SLAB);
	static final Palette GREED = new Palette(Blocks.CUT_SANDSTONE, Blocks.OCHRE_FROGLIGHT, Blocks.SMOOTH_SANDSTONE, Blocks.CHISELED_SANDSTONE,
		Blocks.ORANGE_TERRACOTTA, Blocks.OCHRE_FROGLIGHT, Blocks.SANDSTONE, Blocks.CUT_SANDSTONE_SLAB);
	static final Palette WRATH = new Palette(Blocks.PRISMARINE_BRICKS, Blocks.SEA_LANTERN, Blocks.PRISMARINE, Blocks.DARK_PRISMARINE,
		Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICK_SLAB);
	static final Palette HERESY = new Palette(Blocks.POLISHED_BLACKSTONE, Blocks.CRYING_OBSIDIAN, Blocks.POLISHED_BLACKSTONE_BRICKS,
		Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.GILDED_BLACKSTONE, Blocks.CRYING_OBSIDIAN, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_SLAB);
	static final Palette VIOLENCE = new Palette(Blocks.POLISHED_DEEPSLATE, Blocks.OCHRE_FROGLIGHT, Blocks.DEEPSLATE_TILES, Blocks.WAXED_CUT_COPPER,
		Blocks.WAXED_CUT_COPPER, Blocks.OCHRE_FROGLIGHT, Blocks.COBBLED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_SLAB);
	static final Palette FRAUD = new Palette(Blocks.WHITE_CONCRETE, Blocks.BLACK_CONCRETE, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_QUARTZ,
		Blocks.BLACK_CONCRETE, Blocks.SEA_LANTERN, Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_SLAB);
	static final Palette TREACHERY = new Palette(Blocks.PACKED_ICE, Blocks.VERDANT_FROGLIGHT, Blocks.DEEPSLATE_BRICKS, Blocks.CHISELED_DEEPSLATE,
		Blocks.BLUE_ICE, Blocks.VERDANT_FROGLIGHT, Blocks.DEEPSLATE, Blocks.DEEPSLATE_TILE_SLAB);
	static final Palette PRELUDE = new Palette(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.MAGMA_BLOCK, Blocks.BLACKSTONE, Blocks.CHISELED_POLISHED_BLACKSTONE,
		Blocks.POLISHED_BASALT, Blocks.SHROOMLIGHT, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
	static final Palette PRIME = new Palette(Blocks.OBSIDIAN, Blocks.GOLD_BLOCK, Blocks.OBSIDIAN, Blocks.GOLD_BLOCK,
		Blocks.CRYING_OBSIDIAN, Blocks.PEARLESCENT_FROGLIGHT, Blocks.OBSIDIAN, Blocks.END_STONE_BRICK_SLAB);
	static final Palette HEAVEN = new Palette(Blocks.SMOOTH_QUARTZ, Blocks.GOLD_BLOCK, Blocks.QUARTZ_BRICKS, Blocks.GOLD_BLOCK,
		Blocks.QUARTZ_PILLAR, Blocks.GLOWSTONE, Blocks.CALCITE, Blocks.SMOOTH_QUARTZ_SLAB);
	static final Palette FLESH = new Palette(Blocks.RED_CONCRETE, Blocks.BONE_BLOCK, Blocks.RED_TERRACOTTA, Blocks.BONE_BLOCK,
		Blocks.BONE_BLOCK, Blocks.SHROOMLIGHT, Blocks.CRIMSON_HYPHAE, Blocks.RED_NETHER_BRICK_SLAB);

	/** The grid's colour in the Cyber Grind's own look: it shifts every wave, as ULTRAKILL's does. */
	private static final Block[] CYBER_ACCENTS = {Blocks.LIGHT_BLUE_CONCRETE, Blocks.CYAN_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.LIME_CONCRETE,
		Blocks.ORANGE_CONCRETE, Blocks.RED_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.PINK_CONCRETE, Blocks.WHITE_CONCRETE};

	/** A look the grid takes on for a few waves: its title (and the layer it's after) and blocks. */
	record Def(String title, String layer, Palette p) {}

	static final List<Def> ARENAS = List.of(
		new Def("THE CYBER GRIND", "CYBER GRIND", CYBER),
		new Def("0: PRELUDE", "PRELUDE", PRELUDE),
		new Def("1: LIMBO", "LIMBO", LIMBO),
		new Def("2: LUST", "LUST", LUST),
		new Def("3: GLUTTONY", "GLUTTONY", GLUTTONY),
		new Def("3: IN THE FLESH", "GLUTTONY", FLESH),
		new Def("4: GREED", "GREED", GREED),
		new Def("5: WRATH", "WRATH", WRATH),
		new Def("6: HERESY", "HERESY", HERESY),
		new Def("7: VIOLENCE", "VIOLENCE", VIOLENCE),
		new Def("8: FRAUD", "FRAUD", FRAUD),
		new Def("9: TREACHERY", "TREACHERY", TREACHERY),
		new Def("P: PRIME SANCTUM", "PRIME SANCTUM", PRIME),
		new Def("HEAVEN", "HEAVEN", HEAVEN));

	/** ULTRAKILL's own skies (its Addressables' materials), by the layer a look is after. */
	private static final java.util.Map<String, String[]> SKIES = java.util.Map.ofEntries(
		java.util.Map.entry("CYBER GRIND", new String[] {"EndlessSky"}),
		java.util.Map.entry("PRELUDE", new String[] {"RedSky", "RedSky3", "DuskSky"}),
		java.util.Map.entry("LIMBO", new String[] {"SkyBlue", "DaySky", "DaySky 2", "EveningSky"}),
		java.util.Map.entry("LUST", new String[] {"../LustSky", "../LustSky 1", "../LustSky 2"}),
		java.util.Map.entry("GLUTTONY", new String[] {"RedSky2", "RedSky"}),
		java.util.Map.entry("GREED", new String[] {"GreedSky", "GreedSky2", "GreedSky3", "GreedSky4", "GreedSky5"}),
		java.util.Map.entry("WRATH", new String[] {"../Environment/Layer 5/OvercastSky", "FogCoveredSky", "NightSky2"}),
		java.util.Map.entry("HERESY", new String[] {"NightSky1", "NightSky3"}),
		java.util.Map.entry("VIOLENCE", new String[] {"ViolenceSky", "ViolenceSky3", "ViolenceSky4"}),
		java.util.Map.entry("FRAUD", new String[] {"FraudCity_SkyMat_Night", "FraudCity_SkyMat"}),
		java.util.Map.entry("TREACHERY", new String[] {"SpaceSky", "NightSky4"}),
		java.util.Map.entry("PRIME SANCTUM", new String[] {"NightSky5", "SpaceSky"}),
		java.util.Map.entry("HEAVEN", new String[] {"DawnSky", "EveningSky 3"}));

	/** A sky for look index: the address of one of its layer's ULTRAKILL skybox materials, picked at random. */
	static String sky(int index, RandomSource random) {
		return skyFor(def(index).layer, random);
	}

	/** One of a layer's ULTRAKILL skies (its material's address), picked at random. */
	static String skyFor(String layer, RandomSource random) {
		String[] all = SKIES.getOrDefault(layer, new String[] {"DuskSky"});
		String name = all[random.nextInt(all.length)];
		return name.startsWith("../") ? "Assets/Materials/" + name.substring(3) + ".mat" : "Assets/Materials/Skyboxes/" + name + ".mat";
	}

	/** The arena as built: its look, its middle, its half-width, where V1 lands, and its shop. */
	record Built(Def def, Vec3 center, int r, Vec3 spawn, BlockPos shop, Direction shopFacing) {}

	// ------------------------------------------------------------------ patterns

	/** One of ULTRAKILL's arena patterns: heights in levels, and a marker per tile (0 n p J s H). */
	record Pattern(String name, int[][] h, char[][] marks) {}

	private static List<Pattern> patterns;
	private static final List<Integer> patternOrder = new ArrayList<>();
	private static int patternPos;

	/** ULTRAKILL's .cgp: 16 rows of heights (a digit each, or (-15) in brackets), a blank line, 16 rows of markers. */
	static Pattern parse(String name, String text) {
		String[] lines = text.replace("\r", "").split("\n");
		int[][] h = new int[N][N];
		char[][] m = new char[N][N];
		int row = 0, i = 0;
		for (; i < lines.length && row < N; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) continue;
			int col = 0;
			for (int c = 0; c < line.length() && col < N; c++) {
				char ch = line.charAt(c);
				if (ch == '(') {
					int end = line.indexOf(')', c);
					if (end < 0) return null;
					try {
						h[row][col++] = Integer.parseInt(line.substring(c + 1, end).trim());
					} catch (NumberFormatException e) {
						return null;
					}
					c = end;
				} else if (Character.isDigit(ch)) {
					h[row][col++] = ch - '0';
				} else {
					return null;
				}
			}
			if (col != N) return null;
			row++;
		}
		if (row != N) return null;
		row = 0;
		for (; i < lines.length && row < N; i++) {
			String line = lines[i].trim();
			if (line.isEmpty()) continue;
			if (line.length() != N) break;
			for (int c = 0; c < N; c++) m[row][c] = line.charAt(c);
			row++;
		}
		// no (or broken) marker rows: none
		if (row != N) for (char[] r : m) java.util.Arrays.fill(r, '0');
		return new Pattern(name, h, m);
	}

	/** ULTRAKILL's own patterns (CGPATTERNS, from UltraBridge): its numbered ones (its order), then the rest it ships. */
	private static volatile List<Pattern> fromUltrakill = List.of();
	/** How many of the patterns at the start of patterns() go in order (ULTRAKILL's 01, 02...) before the shuffle. */
	private static int inOrder;

	/** CGPATTERNS name|heights|prefabs;... (rows joined with '/'). */
	static synchronized void ultrakillPatterns(String data) {
		List<Pattern> list = new ArrayList<>();
		for (String rec : data.split(";")) {
			String[] a = rec.split("\\|", -1);
			if (a.length < 3) continue;
			Pattern p = parse(prettyName(a[0]), a[1].replace('/', '\n') + "\n\n" + a[2].replace('/', '\n'));
			if (p != null) list.add(p);
		}
		list.sort(java.util.Comparator.comparing(Pattern::name));
		fromUltrakill = list;
		// (not mid-run: the run's order stays)
		if (!built) patterns = null;
		LOG.info("Cyber Grind: {} of ULTRAKILL's own patterns", list.size());
	}

	/**
	 * ULTRAKILL's own patterns (its numbered ones first, in order), those in its Cybergrind/Patterns folder (where it
	 * keeps the player's own too), then Ultracraft's.
	 */
	static synchronized List<Pattern> patterns() {
		if (patterns != null) return patterns;
		List<Pattern> all = new ArrayList<>();
		inOrder = 0;
		for (Pattern p : fromUltrakill) {
			if (p.name.matches("PATTERN \\d+.*")) inOrder++;
		}
		// numbered first (sorted), then the community's
		for (Pattern p : fromUltrakill) if (p.name.matches("PATTERN \\d+.*")) all.add(p);
		for (Pattern p : fromUltrakill) if (!p.name.matches("PATTERN \\d+.*")) all.add(p);
		try {
			Path game = UkInstaller.game();
			Path dir = game != null ? game.resolve("Cybergrind").resolve("Patterns") : null;
			if (dir != null && Files.isDirectory(dir)) {
				List<Path> files;
				try (var walk = Files.walk(dir, 3)) {
					files = walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".cgp")).sorted().toList();
				}
				for (Path f : files) {
					String name = f.getFileName().toString();
					Pattern p = parse(prettyName(name.substring(0, name.length() - 4)), Files.readString(f, StandardCharsets.UTF_8));
					if (p != null) all.add(p);
					else LOG.warn("Cyber Grind pattern {} couldn't be read: skipped", f);
				}
			}
		} catch (Throwable t) {
			LOG.warn("reading ULTRAKILL's Cyber Grind patterns: {}", t.toString());
		}
		int fromUk = all.size();
		all.addAll(PatternRules.all());
		LOG.info("Cyber Grind: {} patterns ({} from ULTRAKILL's Cybergrind/Patterns folder)", all.size(), fromUk);
		patterns = all;
		return all;
	}

	/** "CG_BigBridge" as ULTRAKILL announces it: BIG BRIDGE; "dood_brokenBridge": BROKEN BRIDGE; "04b": PATTERN 04B. */
	private static String prettyName(String file) {
		if (file.matches("\\d+[a-zA-Z]?")) return "PATTERN " + file.toUpperCase(Locale.ROOT);
		String s = file.replaceAll("(?i)^cg_", "").replaceAll("^[a-z0-9]+_(?=[a-z])", "").replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
		return s.trim().replaceAll(" +", " ").toUpperCase(Locale.ROOT);
	}

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ultracraft");

	// ------------------------------------------------------------------ the grid as it stands

	/** Each block column's height now and where it's going (half blocks over Y), columns 0..47 each way. */
	private static final int[][] cur = new int[SIZE][SIZE], tgt = new int[SIZE][SIZE];
	private static boolean built, moving;
	private static ServerLevel gridLevel;
	private static Def look = ARENAS.get(0);
	private static Block accent = CYBER.accent;
	private static Pattern pattern;
	/** The pattern's tiles by marker: {tile x, tile z, height}. */
	private static final List<int[]> melee = new ArrayList<>(), ranged = new ArrayList<>(), pads = new ArrayList<>(), any = new ArrayList<>();
	private static Built standing;

	private GrindArenas() {}

	static ServerLevel level(MinecraftServer server) {
		return server.getLevel(DIMENSION);
	}

	static Def def(int index) {
		return ARENAS.get(Math.floorMod(index, ARENAS.size()));
	}

	/** Columns are still sliding to the wave's pattern. */
	static boolean moving() {
		return moving;
	}

	/**
	 * The grid, built fresh (any damage from a last run swept away) in look index, flat as ULTRAKILL starts, with its
	 * shop on the ledge. The seed rolls the order the patterns come in.
	 */
	static Built build(ServerLevel level, int index, long seed) {
		RandomSource rnd = RandomSource.create(seed);
		look = def(index);
		accent = look.p.accent;
		gridLevel = level;
		clear(level);
		patternOrder.clear();
		List<Pattern> all = patterns();
		// ULTRAKILL's own run first, in its order, then everything else shuffled
		List<Integer> rest = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			if (i < inOrder) patternOrder.add(i);
			else rest.add(i);
		}
		Collections.shuffle(rest, new java.util.Random(rnd.nextLong()));
		patternOrder.addAll(rest);
		patternPos = -1;
		pattern = PatternRules.flat();
		setTargets(pattern);
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				cur[x][z] = tgt[x][z];
				column(level, x, z, BASE, top(MAX_H) + 1);
			}
		}
		moving = false;
		built = true;
		BlockPos shop = ledge(level);
		standing = new Built(look, new Vec3(OX + 0.5, Y, OZ + 0.5), HALF, landing(), shop, Direction.NORTH);
		return standing;
	}

	/** The grid takes on look index: its blocks, everywhere (the caller sends its sky). */
	static Built retheme(int index) {
		if (!built || gridLevel == null) return standing;
		look = def(index);
		accent = look.p.accent;
		for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) column(gridLevel, x, z, BASE, top(cur[x][z]) + 1);
		BlockPos shop = ledge(gridLevel);
		standing = new Built(look, standing.center(), standing.r(), standing.spawn(), shop, standing.shopFacing());
		return standing;
	}

	/**
	 * The next wave's pattern: every column starts sliding to it, and (in the Cyber Grind's own look) the grid's colour
	 * shifts. Returns the pattern's name, as ULTRAKILL announces it.
	 */
	static String nextPattern(int wave) {
		if (!built) return null;
		List<Pattern> all = patterns();
		if (all.isEmpty()) return null;
		patternPos = (patternPos + 1) % patternOrder.size();
		pattern = all.get(patternOrder.get(patternPos));
		setTargets(pattern);
		if (look.p == CYBER) {
			accent = CYBER_ACCENTS[Math.floorMod(wave, CYBER_ACCENTS.length)];
			// the dots of columns that won't move take the new colour now (the moving ones as they go)
			for (int x = 0; x < SIZE; x += TILE) for (int z = 0; z < SIZE; z += TILE) if (cur[x][z] == tgt[x][z]) refreshTop(gridLevel, x, z);
		}
		moving = true;
		standing = new Built(look, standing.center(), standing.r(), landing(), standing.shop(), standing.shopFacing());
		return pattern.name;
	}

	/** Every server tick: each column that isn't at its height yet moves half a block towards it (ten blocks a second). */
	static void tick(MinecraftServer server) {
		if (!built || !moving || gridLevel == null) return;
		boolean still = false;
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				int c = cur[x][z], t = tgt[x][z];
				if (c == t) continue;
				int n = c + Integer.signum(t - c);
				cur[x][z] = n;
				column(gridLevel, x, z, Math.min(top(c), top(n)) - 2, Math.max(top(c), top(n)) + 1);
				if (n != t) still = true;
			}
		}
		unstick(server);
		moving = still;
	}

	/**
	 * The columns close over nobody: a V1 whose feet have a column's block swing up into them goes back on top of that
	 * column. Only a block that's really there counts, so standing on a low column beside a tall one is left alone.
	 */
	private static void unstick(MinecraftServer server) {
		ServerLevel level = server.getLevel(DIMENSION);
		if (level == null) return;
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			if (o.level() != level || !o.isAlive()) continue;
			int bx = Mth.floor(o.getX()) - (OX - HALF), bz = Mth.floor(o.getZ()) - (OZ - HALF);
			if (bx < 0 || bz < 0 || bx >= SIZE || bz >= SIZE) continue;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(OX - HALF + bx, Mth.floor(o.getY()), OZ - HALF + bz);
			if (level.getBlockState(m).isAir() && level.getBlockState(m.above()).isAir()) continue;
			double surface = Y + cur[bx][bz] / 2.0;
			if (o.getY() >= surface - 0.02) continue;
			o.teleportTo(level, o.getX(), surface, o.getZ(), Set.of(), o.getYRot(), o.getXRot(), true);
			o.fallDistance = 0;
		}
	}

	/** The pattern's heights (and staircases) as each block column's target, and its markers sorted. */
	private static void setTargets(Pattern p) {
		melee.clear();
		ranged.clear();
		pads.clear();
		any.clear();
		int[][] lv = new int[N][N];
		for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) lv[i][j] = Math.max(MIN_H, Math.min(MAX_H, p.h[i][j] * PER_LEVEL));
		for (int i = 0; i < N; i++) {
			for (int j = 0; j < N; j++) {
				int H = lv[i][j];
				char m = p.marks[i][j];
				// ULTRAKILL's rows run along x, its columns along z; Minecraft's z is mirrored from Unity's
				int tx = i, tz = N - 1 - j;
				for (int a = 0; a < TILE; a++) for (int b = 0; b < TILE; b++) tgt[tx * TILE + a][tz * TILE + b] = H;
				if (m == 's') stairs(lv, i, j, tx, tz);
				int[] spot = {tx, tz, H};
				if (m == 'n') melee.add(spot);
				else if (m == 'p') ranged.add(spot);
				else if (m == 'J') pads.add(spot);
				if (m != 'J' && m != 's' && H > MIN_H + 6) any.add(spot);
			}
		}
	}

	/** An s tile: three steps up towards the neighbour one or two levels above it (as far as ULTRAKILL's stairs reach). */
	private static void stairs(int[][] lv, int i, int j, int tx, int tz) {
		int H = lv[i][j], best = -1, bestUp = Integer.MAX_VALUE;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int d = 0; d < 4; d++) {
			int ni = i + dirs[d][0], nj = j + dirs[d][1];
			if (ni < 0 || nj < 0 || ni >= N || nj >= N) continue;
			int up = lv[ni][nj] - H;
			if (up > 0 && up <= 2 * PER_LEVEL && up < bestUp) {
				best = d;
				bestUp = up;
			}
		}
		if (best < 0) return;
		// which way is up, in this tile's blocks (i runs along x; j along z, mirrored)
		int sx = dirs[best][0], sz = -dirs[best][1];
		for (int k = 0; k < TILE; k++) {
			int rise = Math.round(bestUp * (k + 1) / (float) TILE);
			for (int w = 0; w < TILE; w++) {
				int bx = sx != 0 ? (sx > 0 ? k : TILE - 1 - k) : w;
				int bz = sz != 0 ? (sz > 0 ? k : TILE - 1 - k) : w;
				tgt[tx * TILE + bx][tz * TILE + bz] = H + rise;
			}
		}
	}

	// ------------------------------------------------------------------ blocks

	/** The y of the slab spot (or the air above the top block) of a column h half blocks high. */
	private static int top(int h) {
		return Y + Math.floorDiv(h, 2);
	}

	/**
	 * Column x z (block columns 0..47) set from y lo to hi for its height now: its body, a top block (its corner
	 * column's is the grid's coloured dot, and its body the grid's line), and a slab for a half level.
	 */
	private static void column(ServerLevel level, int x, int z, int lo, int hi) {
		int h = cur[x][z], full = top(h);
		boolean slab = Math.floorMod(h, 2) == 1;
		Palette p = look.p;
		boolean line = x % TILE == 0 && z % TILE == 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int y = Math.max(BASE, lo); y <= hi; y++) {
			BlockState s;
			if (y < full - 1) s = (line ? p.pillar : p.base).defaultBlockState();
			else if (y == full - 1) s = (line ? accent : p.floor).defaultBlockState();
			else if (y == full && slab) s = p.slab.defaultBlockState();
			else s = Blocks.AIR.defaultBlockState();
			m.set(OX - HALF + x, y, OZ - HALF + z);
			if (level.getBlockState(m) != s) level.setBlock(m, s, 2 | 16);
		}
	}

	private static void refreshTop(ServerLevel level, int x, int z) {
		int t = top(cur[x][z]);
		column(level, x, z, t - 1, t);
	}

	/** The ledge off the south side, level with the grid's floor, with the temporary shop on it facing the grid. */
	private static BlockPos ledge(ServerLevel level) {
		int z0 = OZ + HALF + 2, z1 = z0 + 5;
		for (int x = OX - 4; x <= OX + 4; x++) {
			for (int z = z0; z <= z1; z++) {
				for (int y = Y - 3; y < Y; y++) set(level, x, y, z, y == Y - 1 ? (x == OX - 4 || x == OX + 4 || z == z1 ? look.p.top : look.p.floor) : look.p.base);
			}
		}
		// a bridge onto it from the grid's edge
		for (int x = OX - 1; x <= OX; x++) for (int z = OZ + HALF; z < z0; z++) set(level, x, Y - 1, z, look.p.top);
		BlockPos at = new BlockPos(OX - 1, Y, z1 - 2);
		removeShop(level, at, Direction.NORTH);
		placeShop(level, at, Direction.NORTH, look);
		return at;
	}

	// ------------------------------------------------------------------ where things go

	/** Where V1 lands: the tile nearest the middle that's no pit, on top of it. */
	private static Vec3 landing() {
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int[] s : any) {
			double d = Math.hypot(s[0] - 7.5, s[1] - 7.5) + (s[2] < -PER_LEVEL ? 20 : 0);
			if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		if (best == null) return new Vec3(OX + 0.5, Y + 0.5, OZ + 0.5);
		return tileTop(best[0], best[1]);
	}

	/** The middle of a tile's top, at the height it's going to. */
	private static Vec3 tileTop(int tx, int tz) {
		int x = tx * TILE + TILE / 2, z = tz * TILE + TILE / 2;
		return new Vec3(OX - HALF + x + 0.5, Y + tgt[x][z] / 2.0, OZ - HALF + z + 0.5);
	}

	/**
	 * Where an enemy of the wave comes in, as ULTRAKILL places them: a ranged one on a p tile, a melee one on an n
	 * tile, either on the other's when the pattern has none of its kind, any tile when it has neither; not on top of V1.
	 */
	static Vec3 enemySpot(RandomSource random, boolean rangedKind, Vec3 avoid) {
		List<int[]> from = rangedKind ? ranged : melee;
		if (from.isEmpty()) from = rangedKind ? melee : ranged;
		if (from.isEmpty()) from = any;
		if (from.isEmpty()) return landing();
		Vec3 at = null;
		for (int attempt = 0; attempt < 8; attempt++) {
			int[] s = from.get(random.nextInt(from.size()));
			at = tileTop(s[0], s[1]);
			if (avoid == null || at.distanceToSqr(avoid) > 36) break;
		}
		return at;
	}

	/** PADS x,y,z;...: the wave's jump pads (Minecraft coordinates, on the tile tops) for UltraBridge to place. */
	static String padsMessage() {
		StringBuilder sb = new StringBuilder("PADS ");
		if (built && !moving) {
			for (int[] s : pads) {
				Vec3 v = tileTop(s[0], s[1]);
				sb.append(String.format(Locale.ROOT, "%.1f,%.2f,%.1f;", v.x, v.y, v.z));
			}
		}
		return sb.toString().trim();
	}

	/** Below this, someone has fallen off the grid (its deepest pits are above it). */
	static double voidY() {
		return Y + MIN_H / 2.0 - 3;
	}

	/** The run left the grid: nothing moves any more. */
	static void stopped() {
		moving = false;
		built = false;
		gridLevel = null;
	}

	/** Kept for older callers: in the grid enemies are put on its tiles (enemySpot), so anywhere passes. */
	static boolean standable(Built b, int x, int z, int groundY) {
		return true;
	}

	/** The temporary shop goes when the run leaves its arena. */
	static void removeShop(ServerLevel level, BlockPos anchor, Direction facing) {
		if (level == null || anchor == null) return;
		for (int y = 4; y <= 16; y += 2) level.setBlock(anchor.above(y), Blocks.AIR.defaultBlockState(), 2 | 16);
		for (int part = 0; part < UkShopBlock.PARTS; part++) {
			BlockPos at = anchor.offset(UkShopBlock.offset(facing, part));
			if (level.getBlockState(at).is(UltracraftCommon.UK_SHOP)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
		}
	}

	private static void placeShop(ServerLevel level, BlockPos anchor, Direction facing, Def d) {
		Direction right = UkShopBlock.right(facing);
		for (int a = -1; a <= 2; a++) {
			for (int f = 0; f <= 2; f++) {
				for (int y = 0; y < 4; y++) {
					BlockPos at = anchor.relative(right, a).relative(facing, f).above(y);
					if (!level.getBlockState(at).isAir()) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
				}
			}
		}
		BlockState state = UltracraftCommon.UK_SHOP.defaultBlockState().setValue(UkShopBlock.FACING, facing);
		for (int part = 0; part < UkShopBlock.PARTS; part++) {
			level.setBlock(anchor.offset(UkShopBlock.offset(facing, part)), state.setValue(UkShopBlock.PART, part), 3);
		}
		// its marker: a column of lights rising over it
		for (int y = 4; y <= 16; y += 2) level.setBlock(anchor.above(y), d.p.light.defaultBlockState(), 2 | 16);
	}

	/** Which way the shop is from the middle, in words. */
	static String direction(Built b) {
		if (b.shop() == null) return "nearby";
		double dx = b.shop().getX() + 0.5 - b.center().x, dz = b.shop().getZ() + 0.5 - b.center().z;
		String ns = dz < -2 ? "north" : dz > 2 ? "south" : "", ew = dx > 2 ? "east" : dx < -2 ? "west" : "";
		return ns.isEmpty() ? (ew.isEmpty() ? "in the middle" : ew) : ew.isEmpty() ? ns : ns + "-" + ew;
	}

	/** The grid's whole box goes (what's already air is left alone). */
	private static void clear(ServerLevel level) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = OX - HALF - 4; x <= OX + HALF + 4; x++) {
			for (int z = OZ - HALF - 4; z <= OZ + HALF + 12; z++) {
				for (int y = BASE - 2; y <= Y + MAX_H / 2 + 20; y++) {
					m.set(x, y, z);
					if (!level.getBlockState(m).isAir()) level.setBlock(m, air, 2 | 16);
				}
			}
		}
	}

	private static void set(ServerLevel level, int x, int y, int z, Block b) {
		level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 2 | 16);
	}
}
