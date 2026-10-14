package dev.ultracraft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's enemies turning up in Minecraft's world the way its monsters do: in the dark (Minecraft's own light
 * rule), never on Peaceful, only with mob spawning on, a few at a time by difficulty, somewhere around V1 out of
 * arm's reach. Which ones depends on where, each place with the feel of one of ULTRAKILL's layers: husks on the plains,
 * Greed's soldiers and idols in the deserts, Gluttony's flesh in the swamps, Violence's machines in the jungles and
 * deep caves; in the Nether its demons (souls in the soul sand valleys, flesh in the crimson forests, industry in the
 * basalt deltas); in the End its angels. Never the bosses: they come only as bosses. An enemy this ULTRAKILL can't make
 * (NOSPAWN) isn't asked for again. Killed, they drop experience like Minecraft's monsters, more the higher V1's style
 * rank (and the Style Rewards), and sometimes a bit of what they're made of. Runs on the server thread.
 */
final class UkSpawns {
	/** One of ULTRAKILL's enemy types (EnemyType names): whether it spawns in the air, its experience. */
	private record Kind(boolean flies, int xp) {}

	private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

	static {
		kind("Filth", false, 3);
		kind("Stray", false, 5);
		kind("Schism", false, 7);
		kind("Soldier", false, 7);
		kind("Drone", true, 5);
		kind("Mannequin", false, 10);
		kind("Streetcleaner", false, 12);
		kind("MaliciousFace", true, 25);
		kind("Stalker", false, 20);
		kind("Gutterman", false, 30);
		kind("Guttertank", false, 40);
		kind("Turret", false, 15);
		kind("Virtue", true, 25);
		kind("Idol", false, 15);
		kind("Power", false, 50);
		kind("Providence", true, 40);
		kind("MirrorReaper", false, 40);
		kind("Deathcatcher", false, 30);
		kind("CancerousRodent", false, 1);
	}

	private static void kind(String type, boolean flies, int xp) {
		KINDS.put(type, new Kind(flies, xp));
	}

	/** A spawn table: enemy type, weight. */
	private static Map<String, Integer> table(Object... pairs) {
		Map<String, Integer> t = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) t.put((String) pairs[i], (Integer) pairs[i + 1]);
		return t;
	}

	// the Overworld's usual crowd, which each kind of place then leans on
	private static final Map<String, Integer> OVERWORLD = table("Filth", 30, "Stray", 20, "Schism", 12, "Soldier", 10, "Drone", 10, "Mannequin", 4,
		"Streetcleaner", 4, "MaliciousFace", 2, "Stalker", 1, "Gutterman", 1, "Virtue", 1);

	// the Nether: ULTRAKILL's demons, by biome
	private static final Map<String, Integer> NETHER_WASTES = table("Filth", 14, "Stray", 12, "MaliciousFace", 10, "Streetcleaner", 8, "Soldier", 6,
		"Idol", 2, "Gutterman", 2);
	private static final Map<String, Integer> SOUL_SAND_VALLEY = table("Stray", 10, "Schism", 10, "Idol", 6, "Virtue", 6, "MaliciousFace", 6, "Filth", 4,
		"Deathcatcher", 2);
	private static final Map<String, Integer> CRIMSON_FOREST = table("Filth", 12, "Mannequin", 8, "MaliciousFace", 8, "Gutterman", 4, "Guttertank", 2, "Stray", 4);
	private static final Map<String, Integer> WARPED_FOREST = table("Drone", 10, "Virtue", 6, "Stalker", 6, "Mannequin", 4, "Schism", 4);
	private static final Map<String, Integer> BASALT_DELTAS = table("Streetcleaner", 10, "Turret", 6, "Soldier", 6, "Gutterman", 6, "Guttertank", 4, "Drone", 4);

	// the End: ULTRAKILL's angels
	private static final Map<String, Integer> END_ISLAND = table("Virtue", 10, "Drone", 8, "Idol", 3, "Power", 2);
	private static final Map<String, Integer> END_OUTER = table("Virtue", 8, "Drone", 6, "Power", 4, "Providence", 3, "Idol", 2, "MirrorReaper", 2);

	/** Enemies this ULTRAKILL doesn't have (it said so: NOSPAWN). */
	private static final Set<String> MISSING = new HashSet<>();

	private UkSpawns() {}

	/** NOSPAWN type: V1's ULTRAKILL can't make one of these. */
	static void missing(String type) {
		if (MISSING.add(type)) org.slf4j.LoggerFactory.getLogger("ultracraft").info("ULTRAKILL can't spawn {}: left out of the spawns", type);
	}

	/** Every couple of seconds while V1 is active. */
	static void tick(ServerPlayer sp) {
		if (!UltracraftConfig.ukSpawns) return;
		ServerLevel level = sp.level();
		// the Cyber Grind's arenas have only its waves
		if (level.dimension() == GrindArenas.DIMENSION) return;
		if (level.getDifficulty() == Difficulty.PEACEFUL) return;
		if (!level.getGameRules().get(GameRules.SPAWN_MOBS) || !level.getGameRules().get(GameRules.SPAWN_MONSTERS)) return;
		int cap = switch (level.getDifficulty()) {
			case EASY -> 3;
			case HARD -> 7;
			default -> 5;
		};
		int near = 0;
		for (UkEnemyEntity e : UltracraftCommon.allUkEnemies()) if (!e.isRemoved() && e.level() == level && e.distanceToSqr(sp) < 64 * 64) near++;
		if (near >= cap) return;
		RandomSource random = level.getRandom();
		// about one try in three: a new enemy every few seconds while there's room, like a dark area filling up
		if (random.nextInt(3) != 0) return;
		for (int attempt = 0; attempt < 8; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2.0;
			double dist = 20.0 + random.nextDouble() * 16.0;
			int x = (int) Math.floor(sp.getX() + Math.cos(angle) * dist);
			int z = (int) Math.floor(sp.getZ() + Math.sin(angle) * dist);
			BlockPos ground = findGround(level, x, (int) Math.floor(sp.getY()) + 8, z);
			if (ground == null) continue;
			String type = pick(level, ground, random);
			if (type == null) continue;
			Kind kind = KINDS.get(type);
			BlockPos feet = ground.above();
			if (!Monster.isDarkEnoughToSpawn(level, feet, random)) continue;
			// a body's room: ULTRAKILL's bigger enemies are three blocks tall
			if (!level.noCollision(new AABB(feet.getX() + 0.1, feet.getY(), feet.getZ() + 0.1, feet.getX() + 0.9, feet.getY() + 3.0, feet.getZ() + 0.9))) continue;
			if (!level.getFluidState(feet).isEmpty()) continue;
			double y = feet.getY() + (kind.flies ? 3.0 + random.nextInt(3) : 0.0);
			UcNet.send(sp, String.format(Locale.ROOT, "SPAWNAT %s %.2f %.2f %.2f 1", type, feet.getX() + 0.5, y, feet.getZ() + 0.5));
			return;
		}
	}

	/** The highest standable block at or below fromY (16 blocks down at most): solid on top, two of air above. */
	private static BlockPos findGround(ServerLevel level, int x, int fromY, int z) {
		if (!level.hasChunkAt(new BlockPos(x, fromY, z))) return null;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, fromY, z);
		for (int y = fromY; y > fromY - 16 && y > level.getMinY(); y--) {
			m.setY(y);
			if (!level.getBlockState(m).isFaceSturdy(level, m, Direction.UP)) continue;
			if (level.getBlockState(m.above()).isAir() && level.getBlockState(m.above(2)).isAir()) return m.immutable();
		}
		return null;
	}

	private static boolean is(Holder<Biome> biome, List<ResourceKey<Biome>> keys) {
		for (ResourceKey<Biome> k : keys) if (biome.is(k)) return true;
		return false;
	}

	/** Which enemy for this spot: its dimension's and biome's table. */
	private static String pick(ServerLevel level, BlockPos pos, RandomSource random) {
		Holder<Biome> biome = level.getBiome(pos);
		Map<String, Integer> table;
		if (level.dimension() == Level.NETHER) {
			if (biome.is(Biomes.SOUL_SAND_VALLEY)) table = SOUL_SAND_VALLEY;
			else if (biome.is(Biomes.CRIMSON_FOREST)) table = CRIMSON_FOREST;
			else if (biome.is(Biomes.WARPED_FOREST)) table = WARPED_FOREST;
			else if (biome.is(Biomes.BASALT_DELTAS)) table = BASALT_DELTAS;
			else table = NETHER_WASTES;
		} else if (level.dimension() == Level.END) {
			table = biome.is(Biomes.THE_END) ? END_ISLAND : END_OUTER;
		} else {
			// no monsters where Minecraft has none
			if (biome.is(Biomes.MUSHROOM_FIELDS) || biome.is(Biomes.DEEP_DARK)) return null;
			table = new HashMap<>(OVERWORLD);
			// each kind of place has its regulars, like one of ULTRAKILL's layers
			if (is(biome, List.of(Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.MEADOW, Biomes.CHERRY_GROVE))) {
				favour(table, "Filth", "Stray", "Schism");
				// once in a long while, something small and very cancerous
				add(table, "CancerousRodent", 1);
			} else if (biome.is(Biomes.DARK_FOREST) || biome.is(Biomes.PALE_GARDEN)) {
				favour(table, "Mannequin", "Stalker", "Schism");
				add(table, "MirrorReaper", 1);
			}
			else if (biome.is(BiomeTags.IS_BADLANDS) || biome.is(Biomes.DESERT)) {
				// Greed: soldiers, idols and sentries in the sand
				favour(table, "Soldier", "Stalker", "Streetcleaner");
				add(table, "Idol", 3);
				add(table, "Turret", 3);
			} else if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) favour(table, "Filth", "Mannequin", "MaliciousFace");
			else if (biome.is(BiomeTags.IS_JUNGLE)) {
				// Violence's garden: mannequins and the Guttermen
				favour(table, "Mannequin", "Gutterman");
				add(table, "Guttertank", 2);
				add(table, "Turret", 2);
			} else if (biome.is(BiomeTags.IS_SAVANNA)) favour(table, "Soldier", "Streetcleaner", "Drone");
			else if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.ICE_SPIKES)) favour(table, "Stray", "Schism", "Streetcleaner");
			else if (biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL)) {
				favour(table, "Drone", "Virtue", "MaliciousFace");
				add(table, "Power", 1);
			} else if (biome.is(BiomeTags.IS_BEACH) || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) favour(table, "Filth", "Drone", "Gutterman");
			else if (biome.is(BiomeTags.IS_FOREST)) favour(table, "Stray", "Schism", "Mannequin");
			// down in the caves, Violence's and Fraud's machines
			if (biome.is(Biomes.LUSH_CAVES)) favour(table, "Mannequin", "Stray");
			else if (biome.is(Biomes.DRIPSTONE_CAVES)) favour(table, "Stalker", "Soldier");
			if (pos.getY() < 40) {
				favour(table, "Soldier", "Stalker", "Gutterman");
				add(table, "Turret", 3);
			}
		}
		int total = 0;
		for (var e : table.entrySet()) if (!MISSING.contains(e.getKey()) && KINDS.containsKey(e.getKey())) total += e.getValue();
		if (total <= 0) return null;
		int r = random.nextInt(total);
		for (var e : table.entrySet()) {
			if (MISSING.contains(e.getKey()) || !KINDS.containsKey(e.getKey())) continue;
			r -= e.getValue();
			if (r < 0) return e.getKey();
		}
		return null;
	}

	private static void favour(Map<String, Integer> table, String... types) {
		for (String t : types) table.computeIfPresent(t, (k, w) -> w * 3 + 4);
	}

	private static void add(Map<String, Integer> table, String type, int weight) {
		table.merge(type, weight, Integer::sum);
	}

	/**
	 * UKDEAD type x y z rank: one of ULTRAKILL's enemies died; its experience (and a little loot) drops there. A kill
	 * by V1 at a top style rank pays more (StyleRewards).
	 */
	static void died(ServerPlayer sp, ServerLevel level, String type, Vec3 at, int rank) {
		Kind k = KINDS.get(type);
		int xp = k != null ? k.xp : 5;
		// style pays: D is plain, every rank up adds a quarter (ULTRAKILL rank: double), and a streak more on top
		xp = Math.round(xp * (1f + 0.25f * Math.max(0, Math.min(rank, 7))) * StyleRewards.kill(sp, at));
		ExperienceOrb.award(level, at, xp);
		RandomSource random = level.getRandom();
		if (random.nextInt(3) != 0) return;
		ItemStack drop = switch (type) {
			// husks: what's left of the damned
			case "Filth", "Stray", "Schism", "Soldier", "Stalker" -> new ItemStack(random.nextBoolean() ? Items.ROTTEN_FLESH : Items.BONE, 1 + random.nextInt(2));
			// machines run on blood, wires and steel
			case "Drone", "Streetcleaner", "Swordsmachine", "Mindflayer", "Gutterman", "Guttertank", "Turret", "V2" ->
				new ItemStack(random.nextBoolean() ? Items.IRON_NUGGET : Items.REDSTONE, 2 + random.nextInt(4));
			// demons
			case "MaliciousFace", "Cerberus", "Idol" -> new ItemStack(random.nextBoolean() ? Items.COBBLESTONE : Items.BLAZE_POWDER, 1 + random.nextInt(2));
			case "HideousMass", "Mannequin" -> new ItemStack(Items.SLIME_BALL, 1 + random.nextInt(3));
			// angels
			case "Virtue", "Power", "Providence" -> new ItemStack(random.nextBoolean() ? Items.GLOWSTONE_DUST : Items.GOLD_NUGGET, 2 + random.nextInt(4));
			default -> ItemStack.EMPTY;
		};
		if (!drop.isEmpty()) level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, drop));
	}
}
