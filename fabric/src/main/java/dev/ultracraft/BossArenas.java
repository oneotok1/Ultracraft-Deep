package dev.ultracraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Boss Arenas (Ultracraft... -> Enemies & Bosses): a boss that comes for V1 takes V1 (and the V1s with them) to its
 * own arena first, built after the room ULTRAKILL fights it in, and back again when the fight is over:
 * <ul>
 * <li>Swordsmachine: 0-3's First Encounter, a square room with raised corners, a statue on each, and the fan shaft in
 * the middle it drops down;</li>
 * <li>Cerberus: 0-5's square chamber before the gates of Hell, a statue to either side of them, lava pouring down the
 * walls;</li>
 * <li>Hideous Mass: 1-3's domed courtyard;</li>
 * <li>Mindflayer: 2-3's last room, a pool in the middle, a waterfall at the back, a split staircase up to a walkway;</li>
 * <li>Ferryman: 5-2's ship's bow in the open sea, with the roof of its cabin above;</li>
 * <li>Sisyphean Insurrectionist: 4-2's open-air desert arena with its four sandstone platforms and the skull pedestals;</li>
 * <li>V2: 1-4's moonlit hall, a pool of holographic water, four pillars, a skylight and the stained glass window;</li>
 * <li>V2 (green arm): 4-4's hall with the throne in the middle and four pillars, the pyramid's side through the wall;</li>
 * <li>Gabriel, Judge of Hell: 3-2's round platform over the black pit, ringed with teeth, an eye in its middle, the
 * great heart beating beside it;</li>
 * <li>Gabriel, Apostate of Hate: 6-2's hall of bones and skulls on stakes, the organ on its raised platform;</li>
 * <li>Minos Prime: P-1's octagonal flesh chamber, spines up its eight corners, its ceiling rising to the middle;</li>
 * <li>Sisyphus Prime: P-2's eight-sided court lined with empty cells, open to the sky.</li>
 * </ul>
 * They stand in the Cyber Grind's void dimension (unbreakable, no spawns), far from its grid. Server thread.
 */
final class BossArenas {
	/** Where the arenas stand: each its own spot, out along -x from the Grind's grid. */
	private static final int Y = 100, X0 = -2048, SPACING = 512;

	/** An arena: the layer it's after (its ULTRAKILL sky and boss theme), where V1 comes in, and where the boss lands. */
	record Built(String layer, Vec3 entrance, float yaw, Vec3 boss) {}

	private record Return(ResourceKey<Level> level, Vec3 at, float yaw, float pitch) {}

	private static final List<String> ORDER = List.of("swordsmachine", "cerberus", "hideousmass", "mindflayer", "ferryman", "insurrectionist",
		"v2", "v2green", "gabriel", "gabriel2", "minosprime", "sisyphusprime");

	/** The arena now in use: its middle (blocks) and where V1 comes in, for anyone who falls out of it. */
	private static Vec3 arenaMiddle, arenaEntrance;
	private static float arenaYaw;

	/** Everyone taken into the arena, and where they came from. */
	private static final Map<UUID, Return> party = new HashMap<>();
	private static MinecraftServer server;

	private BossArenas() {}

	/** This player was taken into the arena (rejoining mid-fight, they stay there). */
	static boolean inParty(UUID id) {
		return party.containsKey(id);
	}

	static boolean has(String key) {
		return ORDER.contains(key);
	}

	/** In an arena fight now. */
	static boolean active() {
		return !party.isEmpty();
	}

	/**
	 * The boss's arena, built fresh, with V1 and every V1 within 32 blocks of them taken into it (their way back
	 * remembered). Returns where the boss lands, or null if there's no arena dimension.
	 */
	static Built enter(ServerPlayer sp, String key) {
		server = sp.level().getServer();
		// a new fight while the last one's party was still picking up its loot: they stay for this one
		leaveIn = 0;
		ServerLevel level = GrindArenas.level(server);
		if (level == null || !has(key)) return null;
		int i = ORDER.indexOf(key);
		int ox = X0 - i * SPACING, oz = 0;
		Built b = build(level, key, ox, oz);
		arenaMiddle = new Vec3(ox + 0.5, Y, oz + 0.5);
		arenaEntrance = b.entrance;
		arenaYaw = b.yaw;
		RandomSource random = server.overworld().getRandom();
		String sky = "SKY " + GrindArenas.skyFor(b.layer, random);
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			if (o != sp && !(UcNet.isV1(o) && o.isAlive() && o.level() == sp.level() && o.position().distanceTo(sp.position()) < 32.0)) continue;
			party.putIfAbsent(o.getUUID(), new Return(o.level().dimension(), o.position(), o.getYRot(), o.getXRot()));
			// side by side at the way in
			int n = party.size() - 1;
			Vec3 at = b.entrance.add((n % 2 == 0 ? 1 : -1) * (n + 1) / 2 * 1.5, 0, 0);
			o.teleportTo(level, at.x, at.y, at.z, Set.of(), b.yaw, 0f, true);
			o.fallDistance = 0;
			UcNet.send(o, sky);
		}
		org.slf4j.LoggerFactory.getLogger("ultracraft").info("boss arena for {} at {} ({} taken there)", key, b.boss, party.size());
		return b;
	}

	/** How long the party stays after a win, to pick up the boss's drops (ticks). */
	static final int STAY_AFTER_WIN = 20 * 20;
	/** Ticks until a won arena's party goes back (0: not waiting). */
	private static int leaveIn;

	/** The boss is beaten: the party stays a while (its loot, its experience), then goes back (tick). */
	static void leaveSoon() {
		if (party.isEmpty() || server == null) return;
		leaveIn = STAY_AFTER_WIN;
		for (UUID id : party.keySet()) {
			ServerPlayer o = server.getPlayerList().getPlayer(id);
			if (o != null) o.sendSystemMessage(Component.literal("[Ultracraft] Back in " + STAY_AFTER_WIN / 20 + " seconds: grab the loot.").withStyle(ChatFormatting.GOLD));
		}
	}

	/** Server tick: anyone fallen out of the arena back at its way in; the countdown after a win, then everyone back. */
	static void tick(MinecraftServer s) {
		outOfBounds();
		if (leaveIn <= 0) return;
		leaveIn--;
		if (leaveIn % 20 == 0 && leaveIn > 0 && server != null) {
			for (UUID id : party.keySet()) {
				ServerPlayer o = server.getPlayerList().getPlayer(id);
				if (o != null) o.displayClientMessage(Component.literal("Leaving the arena in " + leaveIn / 20 + "...").withStyle(leaveIn <= 100 ? ChatFormatting.RED : ChatFormatting.GOLD), true);
			}
		}
		if (leaveIn == 0) leaveAll();
	}

	/**
	 * Out of bounds (into the pit, or off the edge of the world): as ULTRAKILL does, back where the arena is entered, and
	 * no fall damage. Below the arena's floor by 8, or more than 46 blocks out from its middle.
	 */
	private static void outOfBounds() {
		if (party.isEmpty() || server == null || arenaMiddle == null) return;
		for (UUID id : party.keySet()) {
			ServerPlayer o = server.getPlayerList().getPlayer(id);
			if (o == null || !o.isAlive() || o.isSpectator() || o.level().dimension() != GrindArenas.DIMENSION) continue;
			double dx = o.getX() - arenaMiddle.x, dz = o.getZ() - arenaMiddle.z;
			if (o.getY() >= Y - 8 && dx * dx + dz * dz <= 46 * 46) continue;
			o.teleportTo((ServerLevel) o.level(), arenaEntrance.x, arenaEntrance.y, arenaEntrance.z, Set.of(), arenaYaw, 0f, true);
			o.setDeltaMovement(Vec3.ZERO);
			o.fallDistance = 0;
			o.hurtMarked = true;
			o.displayClientMessage(Component.literal("OUT OF BOUNDS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), true);
		}
	}

	/** The fight is over: everyone taken into the arena goes back where they came from (the dead respawn as usual).
	 * After a win this waits for the countdown (leaveSoon). */
	static void leaveAll() {
		if (leaveIn > 0) return;
		if (party.isEmpty() || server == null) {
			party.clear();
			return;
		}
		for (Map.Entry<UUID, Return> e : party.entrySet()) {
			ServerPlayer o = server.getPlayerList().getPlayer(e.getKey());
			if (o == null) continue;
			UcNet.send(o, "SKY -");
			if (!o.isAlive() || o.level().dimension() != GrindArenas.DIMENSION) continue;
			Return r = e.getValue();
			ServerLevel back = server.getLevel(r.level());
			if (back == null) back = server.overworld();
			o.teleportTo(back, r.at().x, r.at().y, r.at().z, Set.of(), r.yaw(), r.pitch(), true);
			o.fallDistance = 0;
		}
		party.clear();
	}

	static void reset() {
		party.clear();
		leaveIn = 0;
		arenaMiddle = null;
		server = null;
	}

	// ------------------------------------------------------------------ the arenas

	private static Built build(ServerLevel l, String key, int ox, int oz) {
		clear(l, ox, oz, 48);
		return switch (key) {
			case "swordsmachine" -> firstEncounter(l, ox, oz);
			case "cerberus" -> gatesOfHell(l, ox, oz);
			case "hideousmass" -> domedCourtyard(l, ox, oz);
			case "mindflayer" -> sheerHeartAttack(l, ox, oz);
			case "ferryman" -> ferry(l, ox, oz);
			case "insurrectionist" -> godDamnTheSun(l, ox, oz);
			case "v2" -> clairDeLune(l, ox, oz);
			case "v2green" -> clairDeSoleil(l, ox, oz);
			case "gabriel" -> inTheFlesh(l, ox, oz);
			case "gabriel2" -> aestheticsOfHate(l, ox, oz);
			case "minosprime" -> soulSurvivor(l, ox, oz);
			default -> waitOfTheWorld(l, ox, oz);
		};
	}

	/** 0-3: a square room with raised corners, a Cerberus statue on each, the fan shaft in the middle. */
	private static Built firstEncounter(ServerLevel l, int ox, int oz) {
		int r = 16, h = 14;
		room(l, ox, oz, r, h, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.SHROOMLIGHT);
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				int cx = ox + sx * (r - 3), cz = oz + sz * (r - 3);
				fill(l, cx - 3, Y, cz - 3, cx + 3, Y + 1, cz + 3, Blocks.POLISHED_BLACKSTONE);
				// a statue: plinth, body and its glowing orb
				fill(l, cx, Y + 2, cz, cx, Y + 5, cz, Blocks.CHISELED_POLISHED_BLACKSTONE);
				set(l, cx, Y + 6, cz, Blocks.SHROOMLIGHT);
			}
		}
		// the fan shaft: a grate over a deep shaft, and the hole in the ceiling it comes down
		fill(l, ox - 2, Y - 12, oz - 2, ox + 2, Y - 1, oz + 2, Blocks.AIR);
		ringBox(l, ox, oz, 3, Y - 12, Y - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(l, ox - 2, Y - 12, oz - 2, ox + 2, Y - 12, oz + 2, Blocks.MAGMA_BLOCK);
		fill(l, ox - 2, Y - 1, oz - 2, ox + 2, Y - 1, oz + 2, Blocks.IRON_BARS);
		fill(l, ox - 2, Y + h, oz - 2, ox + 2, Y + h, oz + 2, Blocks.AIR);
		// the doorway behind V1, shut
		fill(l, ox - 2, Y, oz + r, ox + 2, Y + 4, oz + r, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
		return new Built("PRELUDE", new Vec3(ox + 0.5, Y, oz + r - 3.5), 180f, new Vec3(ox + 0.5, Y, oz - 4.5));
	}

	/** 0-5: the square chamber before the gates of Hell, a statue either side of them, lava pouring down the walls. */
	private static Built gatesOfHell(ServerLevel l, int ox, int oz) {
		int r = 20, h = 18;
		room(l, ox, oz, r, h, Blocks.NETHER_BRICKS, Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.SHROOMLIGHT);
		// the gate: a great arch in the north wall, shut with bars
		fill(l, ox - 5, Y, oz - r, ox + 5, Y + 12, oz - r, Blocks.GILDED_BLACKSTONE);
		fill(l, ox - 4, Y, oz - r, ox + 4, Y + 10, oz - r, Blocks.IRON_BARS);
		// the statues' plinths either side of it
		for (int s = -1; s <= 1; s += 2) {
			fill(l, ox + s * 9 - 2, Y, oz - r + 3, ox + s * 9 + 2, Y, oz - r + 7, Blocks.POLISHED_BLACKSTONE);
		}
		// lava down the side walls, behind glass
		for (int s = -1; s <= 1; s += 2) {
			for (int z = oz - r + 4; z <= oz + r - 4; z += 6) {
				int x = ox + s * r;
				fill(l, x, Y, z, x, Y + h - 2, z + 1, Blocks.MAGMA_BLOCK);
				fill(l, x - s, Y, z, x - s, Y + h - 2, z + 1, Blocks.ORANGE_STAINED_GLASS);
			}
		}
		return new Built("PRELUDE", new Vec3(ox + 0.5, Y, oz + r - 3.5), 180f, new Vec3(ox + 0.5, Y, oz - r + 6.5));
	}

	/** 1-3: a domed courtyard. */
	private static Built domedCourtyard(ServerLevel l, int ox, int oz) {
		int r = 22;
		disc(l, ox, oz, Y - 1, r, Blocks.STONE_BRICKS);
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
			double d = Math.hypot(dx, dz);
			if (d <= r && (Math.floorMod(dx * 7 + dz * 3, 11) == 0)) set(l, ox + dx, Y - 1, oz + dz, Blocks.MOSSY_STONE_BRICKS);
			if (Math.abs(d - r * 0.55) < 0.6) set(l, ox + dx, Y - 1, oz + dz, Blocks.CHISELED_STONE_BRICKS);
		}
		// the wall and the dome over it, with an open eye at its top
		for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
			double d = Math.hypot(dx, dz);
			if (d < r || d > r + 1) continue;
			fill(l, ox + dx, Y, oz + dz, ox + dx, Y + 8, oz + dz, Math.floorMod(dx + dz, 8) == 0 ? Blocks.QUARTZ_PILLAR : Blocks.STONE_BRICKS);
		}
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
			double d = Math.hypot(dx, dz);
			if (d > r) continue;
			int y = Y + 9 + (int) Math.round(Math.sqrt(Math.max(0, r * r - d * d)) * 0.6);
			set(l, ox + dx, y, oz + dz, d < 4 ? Blocks.GLASS : Math.floorMod((int) (Math.atan2(dz, dx) * 8 / Math.PI), 2) == 0 ? Blocks.STONE_BRICKS : Blocks.SEA_LANTERN);
		}
		return new Built("LIMBO", new Vec3(ox + 0.5, Y, oz + r - 3.5), 180f, new Vec3(ox + 0.5, Y, oz + 0.5));
	}

	/** 2-3: the last room: a pool in the middle, a waterfall at the back, a split staircase up to a walkway. */
	private static Built sheerHeartAttack(ServerLevel l, int ox, int oz) {
		int r = 18, h = 16;
		room(l, ox, oz, r, h, Blocks.RED_NETHER_BRICKS, Blocks.NETHER_BRICKS, Blocks.PURPUR_BLOCK, Blocks.PEARLESCENT_FROGLIGHT);
		// the pool
		fill(l, ox - 6, Y - 3, oz - 5, ox + 6, Y - 1, oz + 3, Blocks.PRISMARINE_BRICKS);
		fill(l, ox - 5, Y - 2, oz - 4, ox + 5, Y - 1, oz + 2, Blocks.WATER);
		// the waterfall down the back wall, into a basin
		fill(l, ox - 3, Y + h - 3, oz - r + 1, ox + 3, Y + h - 3, oz - r + 1, Blocks.WATER);
		fill(l, ox - 4, Y - 1, oz - r + 1, ox + 4, Y - 1, oz - r + 3, Blocks.PRISMARINE_BRICKS);
		fill(l, ox - 3, Y - 1, oz - r + 1, ox + 3, Y - 1, oz - r + 2, Blocks.WATER);
		// the walkway over the way in, with a staircase up to it either side
		fill(l, ox - r + 1, Y + 6, oz + r - 4, ox + r - 1, Y + 6, oz + r - 1, Blocks.PURPUR_BLOCK);
		for (int s = -1; s <= 1; s += 2) {
			for (int k = 0; k < 6; k++) {
				int x = ox + s * (r - 2), z = oz + r - 6 - k;
				fill(l, x - 1, Y, z, x + 1, Y + 5 - k, z, Blocks.PURPUR_BLOCK);
			}
		}
		return new Built("LUST", new Vec3(ox + 0.5, Y, oz + r - 6.5), 180f, new Vec3(ox + 0.5, Y, oz - 8.5));
	}

	/** 5-2: the bow of the ferry in the open sea, its cabin's roof above. */
	private static Built ferry(ServerLevel l, int ox, int oz) {
		// the sea
		fill(l, ox - 46, Y - 6, oz - 46, ox + 46, Y - 6, oz + 46, Blocks.DARK_PRISMARINE);
		fill(l, ox - 46, Y - 5, oz - 46, ox + 46, Y - 4, oz + 46, Blocks.WATER);
		// the hull and deck: wide astern, coming to a point at the bow (north)
		for (int z = -22; z <= 14; z++) {
			int half = z < -6 ? Math.max(1, 11 + (z + 6) * 11 / 16) : 11;
			for (int x = -half; x <= half; x++) {
				fill(l, ox + x, Y - 5, oz + z, ox + x, Y - 2, oz + z, Blocks.DARK_OAK_PLANKS);
				set(l, ox + x, Y - 1, oz + z, Blocks.SPRUCE_PLANKS);
			}
			// the rail
			set(l, ox - half, Y, oz + z, Blocks.DARK_OAK_FENCE);
			set(l, ox + half, Y, oz + z, Blocks.DARK_OAK_FENCE);
		}
		// the cabin astern, with its roof a second arena
		fill(l, ox - 8, Y, oz + 6, ox + 8, Y + 6, oz + 14, Blocks.DARK_OAK_PLANKS);
		fill(l, ox - 7, Y, oz + 7, ox + 7, Y + 5, oz + 13, Blocks.AIR);
		fill(l, ox - 8, Y + 7, oz + 6, ox + 8, Y + 7, oz + 14, Blocks.SPRUCE_PLANKS);
		for (int k = 0; k < 7; k++) set(l, ox - 10, Y + k, oz + 6 + k / 2, Blocks.DARK_OAK_PLANKS);
		// lanterns along the rail, and the mast
		for (int z = -18; z <= 4; z += 6) {
			set(l, ox - 10, Y + 1, oz + z, Blocks.SEA_LANTERN);
			set(l, ox + 10, Y + 1, oz + z, Blocks.SEA_LANTERN);
		}
		fill(l, ox, Y, oz - 2, ox, Y + 18, oz - 2, Blocks.STRIPPED_DARK_OAK_LOG);
		set(l, ox, Y + 19, oz - 2, Blocks.LIGHTNING_ROD);
		return new Built("WRATH", new Vec3(ox + 0.5, Y, oz + 3.5), 180f, new Vec3(ox + 0.5, Y, oz - 12.5));
	}

	/** 4-2: open air, gold sand, four sandstone platforms, the skull pedestals, the siren it falls on. */
	private static Built godDamnTheSun(ServerLevel l, int ox, int oz) {
		int r = 30;
		disc(l, ox, oz, Y - 2, r + 6, Blocks.SANDSTONE);
		disc(l, ox, oz, Y - 1, r + 6, Blocks.SAND);
		// dunes round the edge
		for (int dx = -r - 6; dx <= r + 6; dx++) for (int dz = -r - 6; dz <= r + 6; dz++) {
			double d = Math.hypot(dx, dz);
			if (d < r || d > r + 6) continue;
			int hgt = (int) Math.round((d - r) * 1.2 + 2 * Math.sin(Math.atan2(dz, dx) * 5));
			fill(l, ox + dx, Y, oz + dz, ox + dx, Y + Math.max(0, hgt), oz + dz, Blocks.SAND);
		}
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 4 + i * Math.PI / 2;
			int cx = ox + (int) Math.round(Math.cos(a) * 16), cz = oz + (int) Math.round(Math.sin(a) * 16);
			fill(l, cx - 4, Y, cz - 4, cx + 4, Y + 2, cz + 4, Blocks.CUT_SANDSTONE);
			fill(l, cx - 4, Y + 3, cz - 4, cx + 4, Y + 3, cz + 4, Blocks.SMOOTH_SANDSTONE);
		}
		// the skull pedestals
		for (int s = -1; s <= 1; s += 2) {
			fill(l, ox + s * 6, Y, oz + 14, ox + s * 6, Y + 1, oz + 14, Blocks.CHISELED_SANDSTONE);
			set(l, ox + s * 6, Y + 2, oz + 14, s < 0 ? Blocks.BLUE_CONCRETE : Blocks.RED_CONCRETE);
		}
		// the siren
		fill(l, ox, Y, oz - 6, ox, Y + 5, oz - 6, Blocks.IRON_BARS);
		set(l, ox, Y + 6, oz - 6, Blocks.REDSTONE_LAMP);
		return new Built("GREED", new Vec3(ox + 0.5, Y, oz + 20.5), 180f, new Vec3(ox + 0.5, Y, oz - 5.5));
	}

	/** 1-4: the moonlit hall: holographic water, four pillars, the skylight, the stained glass window. */
	private static Built clairDeLune(ServerLevel l, int ox, int oz) {
		int r = 18, h = 18;
		room(l, ox, oz, r, h, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.SEA_LANTERN);
		// the pool of holographic water
		fill(l, ox - 6, Y - 2, oz - 6, ox + 6, Y - 2, oz + 6, Blocks.SEA_LANTERN);
		fill(l, ox - 6, Y - 1, oz - 6, ox + 6, Y - 1, oz + 6, Blocks.LIGHT_BLUE_STAINED_GLASS);
		// four pillars
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2)
			fill(l, ox + sx * 10, Y, oz + sz * 10, ox + sx * 10 + 1, Y + h - 1, oz + sz * 10 + 1, Blocks.QUARTZ_PILLAR);
		// the skylight
		fill(l, ox - 6, Y + h, oz - 6, ox + 6, Y + h, oz + 6, Blocks.GLASS);
		// the stained glass window over the skull door
		for (int x = -5; x <= 5; x++) for (int y = 6; y <= 14; y++) {
			Block g = (x * x + (y - 10) * (y - 10)) % 3 == 0 ? Blocks.BLUE_STAINED_GLASS : Blocks.WHITE_STAINED_GLASS;
			set(l, ox + x, Y + y, oz - r, g);
		}
		for (int i = -1; i <= 1; i++) set(l, ox + i * 2, Y, oz - r + 1, Blocks.CHISELED_STONE_BRICKS);
		return new Built("LIMBO", new Vec3(ox + 0.5, Y, oz + r - 3.5), 180f, new Vec3(ox + 0.5, Y, oz - 9.5));
	}

	/** 4-4: the hall with a throne in the middle, four pillars, and the pyramid's side through the broken wall. */
	private static Built clairDeSoleil(ServerLevel l, int ox, int oz) {
		int r = 20, h = 18;
		room(l, ox, oz, r, h, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.OCHRE_FROGLIGHT);
		// the throne
		fill(l, ox - 2, Y, oz - 3, ox + 2, Y, oz + 1, Blocks.CHISELED_SANDSTONE);
		fill(l, ox - 1, Y + 1, oz - 1, ox + 1, Y + 1, oz, Blocks.GOLD_BLOCK);
		fill(l, ox - 1, Y + 2, oz - 2, ox + 1, Y + 5, oz - 2, Blocks.GOLD_BLOCK);
		set(l, ox - 2, Y + 2, oz - 1, Blocks.CHISELED_SANDSTONE);
		set(l, ox + 2, Y + 2, oz - 1, Blocks.CHISELED_SANDSTONE);
		// four pillars, risen for the fight
		for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2)
			fill(l, ox + sx * 11, Y, oz + sz * 11, ox + sx * 11 + 1, Y + h - 1, oz + sz * 11 + 1, Blocks.SMOOTH_SANDSTONE);
		// the west wall broken through: the pyramid's side sloping away outside
		fill(l, ox - r, Y, oz - 4, ox - r, Y + 8, oz + 4, Blocks.AIR);
		for (int k = 0; k < 20; k++) fill(l, ox - r - 1 - k, Y - 1 - k, oz - 6, ox - r - 1 - k, Y - 1 - k, oz + 6, Blocks.SANDSTONE);
		return new Built("GREED", new Vec3(ox + 0.5, Y, oz + r - 3.5), 180f, new Vec3(ox + 0.5, Y, oz + 4.5));
	}

	/**
	 * 3-2's Heart Chamber, as its level has it (measured out of ULTRAKILL's scene, ~1.94 of its units a block): a round
	 * flesh platform 11 blocks across the middle on a thick column down into a lit pit, teeth round its rim like a jaw,
	 * the great eye sunk in its floor off to one side; vertebrae rising out of the pit around it some 20 blocks out and
	 * curling in overhead like ribs; the colossal heart 33 blocks beyond, veins running from it under the platform; dark
	 * flesh walls 31 blocks to either side; and the way in, a walkway from a bone arch flanked by two vertebrae.
	 */
	private static Built inTheFlesh(ServerLevel l, int ox, int oz) {
		int r = 11, wall = 31, top = Y + 30, pitBottom = Y - 28;
		// ---- the chamber: flesh walls, a ceiling, and the pit's glowing floor far below
		for (int x = -wall; x <= wall; x++) {
			for (int z = -46; z <= wall; z++) {
				boolean side = Math.abs(x) == wall || z == -46 || z == wall;
				Block flesh = flesh(x, 0, z);
				if (side) fill(l, ox + x, pitBottom, oz + z, ox + x, top, oz + z, flesh);
				set(l, ox + x, top, oz + z, flesh);
				set(l, ox + x, pitBottom, oz + z, Math.floorMod(x * 7 + z * 3, 11) == 0 ? Blocks.SHROOMLIGHT : Blocks.MAGMA_BLOCK);
			}
		}
		// ---- the platform and its column, narrowing as it goes down
		for (int y = Y - 1; y >= pitBottom + 1; y--) {
			int depth = Y - 1 - y;
			int rr = depth < 3 ? r : Math.max(6, r - (depth - 2) / 4);
			for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) {
				if (dx * dx + dz * dz > rr * rr + rr) continue;
				set(l, ox + dx, y, oz + dz, y == Y - 1 ? (Math.floorMod(dx * 5 + dz * 3, 9) == 0 ? Blocks.NETHER_WART_BLOCK : Blocks.RED_TERRACOTTA) : flesh(dx, y, dz));
			}
		}
		// ---- teeth round the rim: a jaw, their tips just over the floor's edge
		for (int k = 0; k < 44; k++) {
			double a = k * Math.PI * 2 / 44;
			int x = ox + (int) Math.round(Math.cos(a) * (r + 1)), z = oz + (int) Math.round(Math.sin(a) * (r + 1));
			int tip = k % 4 == 0 ? Y + 1 : k % 2 == 0 ? Y : Y - 1;
			fill(l, x, Y - 6, z, x, tip, z, Blocks.BONE_BLOCK);
			set(l, x, Y - 7, z, Blocks.WHITE_CONCRETE);
		}
		// ---- the eye in the floor, a little off the middle (as in the level, toward one side)
		int ex = ox - 6, ez = oz;
		disc(l, ex, ez, Y - 1, 4, Blocks.NETHER_WART_BLOCK);
		disc(l, ex, ez, Y - 1, 3, Blocks.AIR);
		disc(l, ex, ez, Y - 2, 3, Blocks.WHITE_CONCRETE);
		disc(l, ex, ez, Y - 2, 2, Blocks.RED_CONCRETE);
		disc(l, ex, ez, Y - 2, 1, Blocks.BLACK_CONCRETE);
		// ---- the walkway in, from the bone arch
		fill(l, ox - 2, Y - 2, oz + r, ox + 2, Y - 1, oz + 22, Blocks.RED_TERRACOTTA);
		fill(l, ox - 2, Y - 1, oz + r, ox + 2, Y - 1, oz + 22, Blocks.NETHER_WART_BLOCK);
		for (int k = 0; k <= 12; k++) {
			double a = Math.PI * k / 12;
			int x = ox + (int) Math.round(Math.cos(a) * 4), y = Y + (int) Math.round(Math.sin(a) * 6);
			fill(l, x, y, oz + 21, x, y, oz + 22, Blocks.BONE_BLOCK);
		}
		fill(l, ox - 4, Y, oz + 21, ox - 4, Y + 1, oz + 22, Blocks.BONE_BLOCK);
		fill(l, ox + 4, Y, oz + 21, ox + 4, Y + 1, oz + 22, Blocks.BONE_BLOCK);
		// the corridor behind it, to the wall
		fill(l, ox - 3, Y - 1, oz + 23, ox + 3, Y - 1, oz + wall - 1, Blocks.RED_TERRACOTTA);
		for (int z = oz + 23; z < oz + wall; z++) {
			fill(l, ox - 4, Y, z, ox - 4, Y + 7, z, flesh(-4, 0, z - oz));
			fill(l, ox + 4, Y, z, ox + 4, Y + 7, z, flesh(4, 0, z - oz));
			fill(l, ox - 3, Y + 8, z, ox + 3, Y + 8, z, flesh(0, 8, z - oz));
		}
		// ---- vertebrae out of the pit, where the level has them (its spines, mirrored to face this way in), curling in
		int[][] spines = {{-7, 19}, {-20, 4}, {-19, -7}, {-4, -20}, {20, -4}, {7, -19}, {19, 7}, {-4, 21}, {4, 21}};
		for (int[] sp : spines) {
			double len = Math.hypot(sp[0], sp[1]);
			double ix = -sp[0] / len, iz = -sp[1] / len;
			boolean gate = sp[1] == 21;
			int rise = gate ? 10 : 34;
			for (int t = 0; t <= rise; t++) {
				// up out of the pit, then leaning in over the arena
				double lean = gate ? 0 : Math.max(0, t - 18) * Math.max(0, t - 18) / 30.0;
				int x = ox + (int) Math.round(sp[0] + ix * lean), z = oz + (int) Math.round(sp[1] + iz * lean);
				int y = (gate ? Y - 1 : Y - 22) + t;
				set(l, x, y, z, Blocks.BONE_BLOCK);
				if (t % 3 == 0) {
					// a vertebra's wings
					set(l, x + 1, y, z, Blocks.BONE_BLOCK);
					set(l, x - 1, y, z, Blocks.BONE_BLOCK);
					set(l, x, y, z + 1, Blocks.BONE_BLOCK);
					set(l, x, y, z - 1, Blocks.BONE_BLOCK);
				}
			}
		}
		// ---- the heart: colossal, beyond the platform, its glow inside; veins from it to under the platform
		int hx = ox, hy = Y + 2, hz = oz - 33;
		for (int dx = -12; dx <= 12; dx++) for (int dy = -26; dy <= 26; dy++) for (int dz = -12; dz <= 12; dz++) {
			double d = dx * dx / 144.0 + dy * dy / 676.0 + dz * dz / 144.0;
			if (d > 1.0) continue;
			if (d > 0.72) set(l, hx + dx, hy + dy, hz + dz, Math.floorMod(dx + dy * 2 + dz, 5) == 0 ? Blocks.NETHER_WART_BLOCK : Blocks.RED_CONCRETE);
			else if (d > 0.6 && Math.floorMod(dx * 3 + dy + dz * 7, 13) == 0) set(l, hx + dx, hy + dy, hz + dz, Blocks.SHROOMLIGHT);
		}
		for (int v = 0; v < 6; v++) {
			double a = (v - 2.5) * 0.35;
			for (int t = 0; t <= 40; t++) {
				double f = t / 40.0;
				int x = (int) Math.round(hx + Math.sin(a) * 12 * (1 - f) + (v - 2.5) * 2 * f);
				int y = (int) Math.round(hy - 6 - v * 2 + (Y - 6 - hy + 6 + v * 2) * f + Math.sin(f * Math.PI) * 4);
				int z = (int) Math.round(hz + 10 + (oz - 6 - hz - 10) * f);
				set(l, x, y, z, Blocks.CRIMSON_STEM);
				set(l, x, y + 1, z, Blocks.CRIMSON_HYPHAE);
			}
		}
		// ---- its light: the level's warm point lights over the platform (invisible here: light blocks)
		for (int[] lp : new int[][] {{0, 0}, {10, 10}, {-10, 10}, {10, -10}, {-10, -10}}) set(l, ox + lp[0], Y + 8, oz + lp[1], Blocks.LIGHT);
		return new Built("GLUTTONY", new Vec3(ox + 0.5, Y, oz + 20.5), 180f, new Vec3(ox + 0.5, Y, oz + 0.5));
	}

	/** Flesh: mostly red, with darker and paler patches. */
	private static Block flesh(int x, int y, int z) {
		int h = Math.floorMod(x * 7 + y * 13 + z * 5, 17);
		return h == 0 ? Blocks.NETHER_WART_BLOCK : h == 3 ? Blocks.RED_MUSHROOM_BLOCK : h < 7 ? Blocks.RED_TERRACOTTA : Blocks.RED_CONCRETE;
	}

	/**
	 * 6-2's Organ Hall, as its level has it (measured out of ULTRAKILL's scene): a long, high gothic hall, 40 blocks wide
	 * and 64 deep, entered from a raised landing two steps above its floor; skeletons on stakes in tidy rows down both
	 * sides, the middle left clear; two giant animal skulls high on the side walls; the golden organ on its raised dais
	 * at the far end, candle piles either side of it, Gabriel above it; and bright, warm light down its length.
	 */
	private static Built aestheticsOfHate(ServerLevel l, int ox, int oz) {
		int hw = 20, front = 31, back = -32, h = 24;
		// ---- floor, walls with pillars, a vaulted ceiling
		for (int x = -hw; x <= hw; x++) {
			for (int z = back; z <= front; z++) {
				boolean border = Math.abs(x) >= hw - 1 || z <= back + 1 || z >= front - 1;
				set(l, ox + x, Y - 2, oz + z, Blocks.STONE_BRICKS);
				set(l, ox + x, Y - 1, oz + z, border ? Blocks.POLISHED_BLACKSTONE : Math.floorMod(x + z, 2) == 0 ? Blocks.POLISHED_ANDESITE : Blocks.SMOOTH_STONE);
				// the vault: higher in the middle
				int roof = Y + h - (int) Math.round((x * x) / 40.0);
				set(l, ox + x, roof, oz + z, Math.floorMod(z, 8) == 0 ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICKS);
				if (Math.abs(x) == hw || z == back || z == front) fill(l, ox + x, Y, oz + z, ox + x, roof, oz + z, Blocks.STONE_BRICKS);
			}
		}
		// a red runner down the middle to the organ
		fill(l, ox - 2, Y - 1, oz + back + 9, ox + 2, Y - 1, oz + front - 6, Blocks.RED_NETHER_BRICKS);
		fill(l, ox - 1, Y - 1, oz + back + 9, ox + 1, Y - 1, oz + front - 6, Blocks.RED_CONCRETE);
		// pillars along the walls, gold-capped, and tall red windows between them
		for (int z = back + 4; z <= front - 4; z += 8) {
			for (int sx = -1; sx <= 1; sx += 2) {
				int x = ox + sx * (hw - 1);
				fill(l, x, Y, oz + z, x, Y + 15, oz + z, Blocks.CHISELED_STONE_BRICKS);
				set(l, x, Y + 16, oz + z, Blocks.GOLD_BLOCK);
				fill(l, ox + sx * hw, Y + 4, oz + z + 2, ox + sx * hw, Y + 14, oz + z + 5, Blocks.RED_STAINED_GLASS);
				// a sconce on each pillar
				set(l, x - sx, Y + 8, oz + z, Blocks.LANTERN);
			}
		}
		// ---- the way in: a raised landing, two steps down to the floor
		fill(l, ox - hw + 1, Y - 1, oz + front - 5, ox + hw - 1, Y + 1, oz + front - 1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(l, ox - hw + 1, Y - 1, oz + front - 6, ox + hw - 1, Y, oz + front - 6, Blocks.POLISHED_BLACKSTONE_BRICKS);
		// ---- the dais and the organ at the far end
		int dz0 = back + 1, dz1 = back + 9;
		fill(l, ox - hw + 1, Y - 1, oz + dz0, ox + hw - 1, Y + 3, oz + dz1, Blocks.POLISHED_BLACKSTONE_BRICKS);
		fill(l, ox - hw + 1, Y + 3, oz + dz0, ox + hw - 1, Y + 3, oz + dz1, Blocks.POLISHED_DEEPSLATE);
		for (int k = 0; k < 4; k++) fill(l, ox - 3, Y - 1, oz + dz1 + 1 + k, ox + 3, Y + 2 - k, oz + dz1 + 1 + k, Blocks.POLISHED_BLACKSTONE_BRICKS);
		// its pipes up the back wall, tallest in the middle, gold and quartz
		for (int x = -12; x <= 12; x++) {
			int ph = 10 + (int) Math.round(9 * Math.cos(x * Math.PI / 26)) - Math.abs(x) % 2;
			fill(l, ox + x, Y + 4, oz + back + 1, ox + x, Y + 4 + ph, oz + back + 1, x % 2 == 0 ? Blocks.GOLD_BLOCK : Blocks.SMOOTH_QUARTZ);
			set(l, ox + x, Y + 5 + ph, oz + back + 1, x % 2 == 0 ? Blocks.CHISELED_QUARTZ_BLOCK : Blocks.GOLD_BLOCK);
		}
		// the console and its keys
		fill(l, ox - 5, Y + 4, oz + back + 3, ox + 5, Y + 5, oz + back + 3, Blocks.DARK_OAK_PLANKS);
		fill(l, ox - 4, Y + 5, oz + back + 4, ox + 4, Y + 5, oz + back + 4, Blocks.QUARTZ_SLAB);
		// candle piles either side of it
		BlockState candles = Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 4)
			.setValue(net.minecraft.world.level.block.CandleBlock.LIT, true);
		for (int sx = -1; sx <= 1; sx += 2)
			for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++)
				if ((dx + dz) % 2 == 0) l.setBlock(new BlockPos(ox + sx * (9 + dx), Y + 4, oz + back + 3 + dz), candles, 2 | 16);
		// ---- skeletons on stakes, in rows down both sides (the middle clear)
		for (int z = back + 14; z <= front - 10; z += 6) {
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int x : new int[] {11, 15}) {
					int px = ox + sx * x, pz = oz + z + (x == 15 ? 3 : 0);
					fill(l, px, Y, pz, px, Y + 1, pz, Blocks.DARK_OAK_FENCE);
					set(l, px, Y + 2, pz, Blocks.SKELETON_SKULL);
				}
			}
		}
		// ---- the two giant skulls high on the side walls
		for (int sx = -1; sx <= 1; sx += 2) {
			int x = ox + sx * (hw - 1), cz = oz - 4, cy = Y + 17;
			for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
				boolean eye = dy == 0 && (dz == -2 || dz == 2), jaw = dy == -2 && Math.abs(dz) == 3;
				if (jaw) continue;
				set(l, x, cy + dy, cz + dz, eye ? Blocks.BLACK_CONCRETE : Blocks.BONE_BLOCK);
			}
			// its horns
			for (int t = 0; t < 4; t++) {
				set(l, x, cy + 3 + t, cz - 4 - t / 2, Blocks.BONE_BLOCK);
				set(l, x, cy + 3 + t, cz + 4 + t / 2, Blocks.BONE_BLOCK);
			}
		}
		// ---- bright: chandeliers down the middle, and the level's warm light everywhere (light blocks)
		for (int z = back + 12; z <= front - 6; z += 10) {
			int roof = Y + h;
			fill(l, ox, roof - 4, oz + z, ox, roof - 1, oz + z, Blocks.IRON_CHAIN);
			set(l, ox, roof - 5, oz + z, Blocks.GOLD_BLOCK);
			set(l, ox, roof - 6, oz + z, Blocks.LANTERN);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(l, ox + d[0], roof - 5, oz + z + d[1], Blocks.LANTERN);
		}
		for (int x = -16; x <= 16; x += 8) for (int z = back + 6; z <= front - 4; z += 8) set(l, ox + x, Y + 7, oz + z, Blocks.LIGHT);
		for (int x = -8; x <= 8; x += 8) set(l, ox + x, Y + 12, oz + back + 6, Blocks.LIGHT);
		return new Built("HERESY", new Vec3(ox + 0.5, Y + 2, oz + front - 3.5), 180f, new Vec3(ox + 0.5, Y + 4, oz + back + 6.5));
	}

	/** P-1: the octagonal flesh chamber, spines up its eight corners, its ceiling rising to the middle. */
	private static Built soulSurvivor(ServerLevel l, int ox, int oz) {
		int r = 24;
		for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
			boolean in = octagon(dx, dz, r), wall = octagon(dx, dz, r + 1) && !in;
			if (!in && !wall) continue;
			Block flesh = Math.floorMod(dx * 3 + dz * 5, 7) == 0 ? Blocks.NETHER_WART_BLOCK : Math.floorMod(dx + dz * 2, 5) == 0 ? Blocks.RED_MUSHROOM_BLOCK : Blocks.RED_CONCRETE;
			set(l, ox + dx, Y - 1, oz + dz, flesh);
			double d = Math.hypot(dx, dz);
			int ceiling = Y + 16 + (int) Math.round(Math.max(0, r - d) * 0.5);
			if (wall) fill(l, ox + dx, Y, oz + dz, ox + dx, ceiling, oz + dz, flesh);
			else set(l, ox + dx, ceiling, oz + dz, flesh);
		}
		// spines up the eight corners
		for (int k = 0; k < 8; k++) {
			double a = Math.PI / 8 + k * Math.PI / 4;
			int x = ox + (int) Math.round(Math.cos(a) * (r - 2)), z = oz + (int) Math.round(Math.sin(a) * (r - 2));
			for (int y = 0; y < 18; y++) {
				set(l, x, Y + y, z, Blocks.BONE_BLOCK);
				if (y % 3 == 0) {
					set(l, x + 1, Y + y, z, Blocks.BONE_BLOCK);
					set(l, x - 1, Y + y, z, Blocks.BONE_BLOCK);
					set(l, x, Y + y, z + 1, Blocks.BONE_BLOCK);
					set(l, x, Y + y, z - 1, Blocks.BONE_BLOCK);
				}
			}
		}
		// its light: blue
		for (int k = 0; k < 8; k++) {
			double a = k * Math.PI / 4;
			set(l, ox + (int) Math.round(Math.cos(a) * 12), Y + 12, oz + (int) Math.round(Math.sin(a) * 12), Blocks.SOUL_LANTERN);
		}
		return new Built("PRIME SANCTUM", new Vec3(ox + 0.5, Y, oz + r - 4.5), 180f, new Vec3(ox + 0.5, Y, oz + 0.5));
	}

	/** P-2: an eight-sided court lined with empty cells, open to the sky. */
	private static Built waitOfTheWorld(ServerLevel l, int ox, int oz) {
		int r = 26, h = 12;
		for (int dx = -r - 4; dx <= r + 4; dx++) for (int dz = -r - 4; dz <= r + 4; dz++) {
			boolean in = octagon(dx, dz, r), shell = octagon(dx, dz, r + 4) && !in;
			if (!in && !shell) continue;
			set(l, ox + dx, Y - 1, oz + dz, in ? (Math.floorMod(dx + dz, 6) == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.POLISHED_ANDESITE) : Blocks.DEEPSLATE_BRICKS);
			if (shell) fill(l, ox + dx, Y, oz + dz, ox + dx, Y + h, oz + dz, Blocks.DEEPSLATE_BRICKS);
		}
		// the cells: alcoves in the wall, barred
		for (int k = 0; k < 32; k++) {
			double a = k * Math.PI * 2 / 32;
			int x = ox + (int) Math.round(Math.cos(a) * (r + 2)), z = oz + (int) Math.round(Math.sin(a) * (r + 2));
			int fx = ox + (int) Math.round(Math.cos(a) * (r + 0.5)), fz = oz + (int) Math.round(Math.sin(a) * (r + 0.5));
			fill(l, x - 1, Y, z - 1, x + 1, Y + 3, z + 1, Blocks.AIR);
			set(l, x, Y + 4, z, Blocks.OCHRE_FROGLIGHT);
			fill(l, fx, Y, fz, fx, Y + 3, fz, Blocks.IRON_BARS);
		}
		return new Built("PRIME SANCTUM", new Vec3(ox + 0.5, Y, oz + r - 5.5), 180f, new Vec3(ox + 0.5, Y, oz + 0.5));
	}

	// ------------------------------------------------------------------ pieces

	/** A square room: floor, walls, a ceiling, and lights set in the walls. */
	private static void room(ServerLevel l, int ox, int oz, int r, int h, Block floor, Block under, Block wall, Block light) {
		fill(l, ox - r, Y - 3, oz - r, ox + r, Y - 2, oz + r, under);
		fill(l, ox - r, Y - 1, oz - r, ox + r, Y - 1, oz + r, floor);
		ringBox(l, ox, oz, r, Y, Y + h, wall);
		fill(l, ox - r, Y + h, oz - r, ox + r, Y + h, oz + r, wall);
		for (int k = -r + 3; k <= r - 3; k += 6) {
			set(l, ox + k, Y + h / 2, oz - r, light);
			set(l, ox + k, Y + h / 2, oz + r, light);
			set(l, ox - r, Y + h / 2, oz + k, light);
			set(l, ox + r, Y + h / 2, oz + k, light);
			for (int j = -r + 3; j <= r - 3; j += 8) set(l, ox + k, Y + h, oz + j, light);
		}
	}

	/** The four walls of a square, r out from the middle, y0 to y1. */
	private static void ringBox(ServerLevel l, int ox, int oz, int r, int y0, int y1, Block b) {
		fill(l, ox - r, y0, oz - r, ox + r, y1, oz - r, b);
		fill(l, ox - r, y0, oz + r, ox + r, y1, oz + r, b);
		fill(l, ox - r, y0, oz - r, ox - r, y1, oz + r, b);
		fill(l, ox + r, y0, oz - r, ox + r, y1, oz + r, b);
	}

	private static boolean octagon(int dx, int dz, int r) {
		return Math.abs(dx) <= r && Math.abs(dz) <= r && Math.abs(dx) + Math.abs(dz) <= r * 1.42;
	}

	private static void disc(ServerLevel l, int ox, int oz, int y, int r, Block b) {
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r + r) set(l, ox + dx, y, oz + dz, b);
	}

	private static void fill(ServerLevel l, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
		BlockState s = b.defaultBlockState();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					m.set(x, y, z);
					if (l.getBlockState(m) != s) l.setBlock(m, s, 2 | 16);
				}
	}

	private static void set(ServerLevel l, int x, int y, int z, Block b) {
		l.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 2 | 16);
	}

	/** The arena's whole box goes (what's already air is left alone). */
	private static void clear(ServerLevel l, int ox, int oz, int r) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = ox - r; x <= ox + r; x++)
			for (int z = oz - r; z <= oz + r; z++)
				for (int y = Y - 30; y <= Y + 40; y++) {
					m.set(x, y, z);
					if (!l.getBlockState(m).isAir()) l.setBlock(m, air, 2 | 16);
				}
	}
}
