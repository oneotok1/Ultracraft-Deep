package dev.ultracraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's bosses coming for V1 in Minecraft's world. After a while of play (about {@code bossMinutes} of V1
 * actually playing: never while idle, asleep, riding or in the Cyber Grind) one is announced: a warning, its name,
 * a raid horn, and a red pillar where it will land. Then V1 has {@code bossWarnSeconds} to get ready (the countdown
 * waits while V1 shops, and never runs out while V1 is away from the keyboard). It arrives with a lightning strike and
 * ULTRAKILL's boss bar. Beaten, it pays P, experience and loot; if V1 dies, runs, or turns back into Steve, it leaves.
 * Bosses come in tiers: the more V1 has beaten, the worse they get, and the harder (difficulty: EASY, MEDIUM, HARD,
 * NIGHTMARE, V1 MUST DIE, which is ULTRAKILL's own difficulty for that boss plus more health, speed and damage). A
 * boss may also bring modifiers (40% one, 15% two; bridge BossMods.cs), each paying a quarter more P. Stormcaller and
 * Eclipse are Minecraft's to do. Runs on the server thread.
 */
final class UkBosses {
	/** A boss: the bridge's key, its name, its tier, the P, experience it pays, and where it likes to turn up. */
	record Boss(String key, String name, int tier, int reward, int xp, String home) {}

	/** The boss's name in the player's language (boss.ultracraft.<key>); ULTRAKILL's own HUD keeps the plain one. */
	static net.minecraft.network.chat.MutableComponent bossName(Boss b) {
		return net.minecraft.network.chat.Component.translatable("boss.ultracraft." + b.key);
	}

	static final List<Boss> ROSTER = List.of(
		new Boss("swordsmachine", "SWORDSMACHINE", 1, 5000, 60, ""),
		new Boss("cerberus", "CERBERUS", 1, 6000, 70, "nether"),
		new Boss("hideousmass", "HIDEOUS MASS", 1, 8000, 80, "nether"),
		new Boss("mindflayer", "MINDFLAYER", 2, 10000, 100, ""),
		new Boss("ferryman", "FERRYMAN", 2, 12000, 120, "nether"),
		new Boss("insurrectionist", "SISYPHEAN INSURRECTIONIST", 2, 12000, 120, "end"),
		new Boss("v2", "V2", 2, 15000, 150, ""),
		new Boss("gabriel", "GABRIEL, JUDGE OF HELL", 3, 25000, 250, "nether"),
		new Boss("v2green", "V2 (GREEN ARM)", 3, 25000, 250, ""),
		new Boss("gabriel2", "GABRIEL, APOSTATE OF HATE", 4, 35000, 350, "end"),
		new Boss("minosprime", "MINOS PRIME", 4, 50000, 500, "nether"),
		new Boss("sisyphusprime", "SISYPHUS PRIME", 5, 75000, 750, "end"));

	/** How many bosses V1 must have beaten before each tier comes. */
	private static final int[] TIER_AT = {0, 0, 2, 4, 6, 8};

	/** A modifier: the key ULTRAKILL knows it by, its title, and its colour. */
	record Mod(String key, String title, ChatFormatting color) {}

	static final List<Mod> MODS = List.of(
		new Mod("radiant", "RADIANT", ChatFormatting.GOLD),
		new Mod("secondwind", "UNDYING", ChatFormatting.YELLOW),
		new Mod("regen", "REGENERATING", ChatFormatting.GREEN),
		new Mod("enraged", "ENRAGED", ChatFormatting.RED),
		new Mod("escorted", "ESCORTED", ChatFormatting.GRAY),
		new Mod("twin", "TWIN", ChatFormatting.LIGHT_PURPLE),
		new Mod("blinker", "BLINKING", ChatFormatting.DARK_PURPLE),
		new Mod("demolisher", "DEMOLISHER", ChatFormatting.GOLD),
		new Mod("stormcaller", "STORMCALLER", ChatFormatting.AQUA),
		new Mod("eclipse", "ECLIPSE", ChatFormatting.BLUE),
		new Mod("volatile", "VOLATILE", ChatFormatting.GOLD),
		new Mod("sanded", "SANDED", ChatFormatting.YELLOW),
		new Mod("glass", "GLASS CANNON", ChatFormatting.WHITE),
		new Mod("vampiric", "VAMPIRIC", ChatFormatting.DARK_RED),
		new Mod("giant", "GIANT", ChatFormatting.RED),
		new Mod("tiny", "TINY", ChatFormatting.GREEN));

	/** Boss difficulty: names, colours, the bosses beaten each needs, and the P it pays (times). */
	static final String[] DIFFICULTY = {"EASY", "MEDIUM", "HARD", "NIGHTMARE", "V1 MUST DIE"};
	private static final ChatFormatting[] DIFFICULTY_COLOR = {ChatFormatting.GREEN, ChatFormatting.YELLOW, ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_PURPLE};
	private static final int[] DIFFICULTY_AT = {0, 3, 6, 10, 15};
	private static final float[] DIFFICULTY_PAY = {1f, 1.25f, 1.6f, 2.2f, 3f};

	/** This boss's difficulty and modifiers. */
	private static int difficulty;
	private static final List<Mod> mods = new ArrayList<>();
	/** Eclipse: the time of day it took (to give back, plus the fight's length), and where. */
	private static long eclipseFrom = -1;
	private static ServerLevel eclipseLevel;
	/** Stormcaller: the next strike, and the spot sparking before it. */
	private static int stormIn;
	private static Vec3 strikeAt;

	private enum Phase { IDLE, WARN, FIGHT }

	private static Phase phase = Phase.IDLE;
	private static Boss boss;
	private static Vec3 spot = Vec3.ZERO;
	/** Ticks of warning left. */
	private static int warnLeft;
	private static int fightId, nextId = 1, fightTicks, farTicks;
	/** In its arena (Boss Arenas): ticks until it comes, once the party is in and the arena around them loaded. */
	private static int arenaDelay;
	private static Vec3 bossAt;
	private static int saveTicks;
	/** Who hears the boss's theme (its own ULTRAKILL song) until the fight is over. */
	private static ServerPlayer themeFor;
	/** An arena's fight (Arenas): its layer, and what happens when the boss is beaten. */
	private static String arenaLayer;
	private static Runnable arenaWin;
	/** The player it's coming for (in multiplayer the others can join in: they see it, and share the prize). */
	private static java.util.UUID target;
	/**
	 * Its health for everyone else: the boss lives in its target's ULTRAKILL, and the others' copies of it (puppets)
	 * have no health bar of their own, so they get Minecraft's boss bar (as does the target while down and watching).
	 */
	private static net.minecraft.server.level.ServerBossEvent bar;
	private static float barMax;

	/** The player a boss is coming for, or null. */
	static ServerPlayer target(net.minecraft.server.MinecraftServer server) {
		return target == null || phase == Phase.IDLE ? null : server.getPlayerList().getPlayer(target);
	}

	private static boolean isTarget(ServerPlayer sp) {
		return target != null && target.equals(sp.getUUID());
	}

	private UkBosses() {}

	/** A boss is on its way or here: no Cyber Grind and no spawning in the dark meanwhile. */
	static boolean busy() {
		return phase != Phase.IDLE;
	}

	/** A boss is here, fighting. */
	static boolean fighting() {
		return phase == Phase.FIGHT;
	}

	/** End the fight whoever it was for (BossParty: everyone is down). */
	static void forceEnd(net.minecraft.server.MinecraftServer server, String why) {
		ServerPlayer t = target(server);
		if (t != null) stop(t, why);
		else reset();
	}

	/**
	 * Every 10 ticks while V1 is active. playing: the player is at the keyboard (moved, looked or pressed something
	 * in the last minute) and in the game, not a menu.
	 */
	static void tick(ServerPlayer sp, boolean playing) {
		// a boss on its way or here is the business of the player it came for
		if (phase != Phase.IDLE && !isTarget(sp)) return;
		switch (phase) {
			case IDLE -> idle(sp, playing);
			case WARN -> warn(sp, playing);
			case FIGHT -> fight(sp);
		}
	}

	private static boolean calm(ServerPlayer sp) {
		return sp.isAlive() && !sp.isSleeping() && !sp.isPassenger() && !sp.isFallFlying() && !CyberGrind.running && !Duels.inDuel(sp);
	}

	private static void idle(ServerPlayer sp, boolean playing) {
		if (!UltracraftConfig.bosses || sp.level().getDifficulty() == Difficulty.PEACEFUL) return;
		if (!playing || !calm(sp)) return;
		UkProgress p = UkProgress.get(sp);
		if (p.nextBoss <= 0) {
			p.nextBoss = p.bossClock + interval(sp.getRandom());
			p.setDirty();
		}
		p.bossClock += 10;
		if (++saveTicks % 60 == 0) p.setDirty();
		if (p.bossClock >= p.nextBoss) {
			Boss b = pick(sp, p);
			// every boss V1 can face is out of rotation: none comes, and the clock starts over
			if (b == null) p.nextBoss = p.bossClock + interval(sp.getRandom());
			else announce(sp, b, UltracraftConfig.bossWarnSeconds);
		}
	}

	/** Ticks of play until the next boss: bossMinutes, give or take a quarter. */
	private static long interval(RandomSource random) {
		double minutes = Math.max(1, UltracraftConfig.bossMinutes) * (0.75 + random.nextDouble() * 0.5);
		return (long) (minutes * 60 * 20);
	}

	/** Which boss: one of the worst tiers V1 has earned, one not beaten yet if there is one, the place's own likelier. */
	private static Boss pick(ServerPlayer sp, UkProgress p) {
		int beaten = p.bossesBeaten(), tier = 1;
		for (int t = 1; t < TIER_AT.length; t++) if (beaten >= TIER_AT[t]) tier = t;
		String home = sp.level().dimension() == Level.NETHER ? "nether" : sp.level().dimension() == Level.END ? "end" : "";
		RandomSource random = sp.getRandom();
		List<Boss> can = new ArrayList<>();
		List<Integer> weight = new ArrayList<>();
		int total = 0;
		for (Boss b : ROSTER) {
			// Boss Setup: out of rotation
			if (b.tier > tier || !BossSetup.inRotation(b.key)) continue;
			// the newest tier the most, a fresh face more, a boss on its home ground more
			int w = 2 + b.tier * 2 + (b.tier == tier ? 6 : 0);
			if (!p.beaten.containsKey(b.key)) w += 6;
			if (!home.isEmpty() && b.home.equals(home)) w += 8;
			can.add(b);
			weight.add(w);
			total += w;
		}
		if (can.isEmpty()) return null;
		int r = random.nextInt(total);
		for (int i = 0; i < can.size(); i++) {
			r -= weight.get(i);
			if (r < 0) return can.get(i);
		}
		return can.get(0);
	}

	static Boss byKey(String key) {
		for (Boss b : ROSTER) if (b.key.equalsIgnoreCase(key)) return b;
		return null;
	}

	/** Debug: call a boss now ("next" picks as the schedule would), with that many seconds of warning. */
	static void force(ServerPlayer sp, String key, int seconds) {
		force(sp, key, seconds, null);
	}

	/**
	 * The same, with its modifiers and difficulty chosen ("radiant,volatile,nightmare"; "none" for no modifiers; a
	 * difficulty alone keeps the random roll).
	 */
	static void force(ServerPlayer sp, String key, int seconds, String pickMods) {
		if (phase != Phase.IDLE) return;
		Boss b = key.equals("next") ? pick(sp, UkProgress.get(sp)) : byKey(key);
		if (b == null && key.equals("next")) {
			sp.sendSystemMessage(Component.translatable("[Ultracraft] Every boss you can face is out of rotation (Cheats -> Boss Setup).").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (b == null) return;
		announce(sp, b, seconds);
		if (phase != Phase.WARN || pickMods == null) return;
		List<Mod> chosen = new ArrayList<>();
		boolean anyMod = false;
		for (String w : pickMods.toLowerCase(Locale.ROOT).split(",")) {
			w = w.trim();
			if (w.equals("none")) anyMod = true;
			for (int i = 0; i < DIFFICULTY.length; i++) {
				if (w.equals(DIFFICULTY[i].toLowerCase(Locale.ROOT).replace(" ", "")) || w.equals("d" + i)) difficulty = i;
			}
			if (w.equals("ukmd")) difficulty = 4;
			for (Mod m : MODS) {
				if (m.key.equals(w)) {
					chosen.add(m);
					anyMod = true;
				}
			}
		}
		if (anyMod) {
			mods.clear();
			mods.addAll(chosen);
		}
		sp.sendSystemMessage(Component.literal("[Ultracraft] (set to ").withStyle(ChatFormatting.GRAY).append(describeColored()).append(Component.literal(")").withStyle(ChatFormatting.GRAY)));
	}

	/** An arena's boss: it was waiting there, so there's no warning; it comes onto the dais at once. */
	static boolean arena(ServerPlayer sp, String key, Vec3 at, String layer, Runnable onBeaten) {
		if (phase != Phase.IDLE) return false;
		Boss b = byKey(key);
		if (b == null) return false;
		boss = b;
		target = sp.getUUID();
		difficulty = difficultyFor(UkProgress.get(sp));
		rollMods(sp);
		spot = at;
		arrive(sp, false);
		// (after arrive: it ends any earlier fight's arena)
		arenaLayer = layer;
		arenaWin = onBeaten;
		UcNet.send(sp, "C:THEME boss:" + boss.key + " arena:" + layer);
		return true;
	}

	/** Its difficulty by how many bosses this player has beaten. */
	private static int difficultyFor(UkProgress p) {
		// chosen in the settings
		if (UltracraftConfig.bossDifficulty > 0) return Math.min(DIFFICULTY.length, UltracraftConfig.bossDifficulty) - 1;
		int beaten = p.bossesBeaten(), d = 0;
		for (int i = 0; i < DIFFICULTY_AT.length; i++) if (beaten >= DIFFICULTY_AT[i]) d = i;
		return d;
	}

	/**
	 * Traits at the settings' chance (55% by default: 40% one, 15% two); never Giant and Tiny together, Eclipse only
	 * under a sky.
	 */
	private static void rollMods(ServerPlayer sp) {
		mods.clear();
		// Boss Setup: none, or the ones picked
		String how = boss != null ? BossSetup.traits(boss.key) : "random";
		if (how.equals("none")) return;
		if (how.equals("pick")) {
			for (Mod m : MODS) if (BossSetup.picked(boss.key).contains(m.key)) mods.add(m);
			return;
		}
		RandomSource r = sp.getRandom();
		double roll = r.nextDouble(), chance = UltracraftConfig.traitChance / 100.0;
		int n = roll < chance * 15 / 55 ? 2 : roll < chance ? 1 : 0;
		List<Mod> can = new ArrayList<>();
		for (Mod m : MODS) {
			if (m.key.equals("eclipse") && !sp.level().dimensionType().hasSkyLight()) continue;
			can.add(m);
		}
		while (mods.size() < n && !can.isEmpty()) {
			Mod m = can.remove(r.nextInt(can.size()));
			mods.add(m);
			if (m.key.equals("giant")) can.removeIf(o -> o.key.equals("tiny"));
			if (m.key.equals("tiny")) can.removeIf(o -> o.key.equals("giant"));
		}
	}

	/** The difficulty and traits in the player's language (difficulty.ultracraft.<name>, trait.ultracraft.<key>). */
	private static Component describeColored() {
		var c = Component.translatable("Difficulty: ").withStyle(ChatFormatting.GRAY)
			.append(Component.translatable(difficultyKey(difficulty)).withStyle(DIFFICULTY_COLOR[difficulty], ChatFormatting.BOLD));
		// no modifiers rolled: the traits part stays away whole, label and all
		if (mods.isEmpty()) return c;
		var traits = Component.translatable("Traits: ").withStyle(ChatFormatting.GRAY);
		for (Mod m : mods) {
			if (m != mods.getFirst()) traits.append(Component.literal("  "));
			traits.append(Component.translatable("trait.ultracraft." + m.key).withStyle(m.color));
		}
		return c.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)).append(traits);
	}

	/** The same as plain text, in English: the bridge's HUD and the debug line have no language to look up. */
	private static String describe() {
		StringBuilder sb = new StringBuilder(DIFFICULTY[difficulty]);
		for (Mod m : mods) sb.append(", ").append(m.title);
		return sb.toString();
	}

	/** difficulty.ultracraft.<lowercase name> for a tier index, and for the Auto setting (0). */
	static String difficultyKey(int tier) {
		if (tier < 0 || tier >= DIFFICULTY.length) return "difficulty.ultracraft.auto";
		return "difficulty.ultracraft." + DIFFICULTY[tier].toLowerCase(Locale.ROOT).replace(" ", "");
	}

	private static boolean has(String key) {
		for (Mod m : mods) if (m.key.equals(key)) return true;
		return false;
	}

	/** What it pays: its P, times its difficulty's, plus a quarter for each modifier. */
	private static int reward(Boss b) {
		return Math.round(b.reward * DIFFICULTY_PAY[difficulty] * (1f + 0.25f * mods.size()));
	}

	static int fightId() {
		return phase == Phase.FIGHT ? fightId : 0;
	}

	/** The warning: what's coming, where, and how long V1 has. */
	static void announce(ServerPlayer sp, Boss b, int seconds) {
		Vec3 at = findSpot(sp);
		if (at == null) {
			// nowhere for it to stand around here: try again in a little while
			UkProgress p = UkProgress.get(sp);
			p.nextBoss = p.bossClock + 20 * 30;
			return;
		}
		boss = b;
		spot = at;
		target = sp.getUUID();
		warnLeft = Math.max(3, seconds) * 20;
		phase = Phase.WARN;
		difficulty = difficultyFor(UkProgress.get(sp));
		rollMods(sp);
		title(sp, Component.translatable("WARNING").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
			Component.translatable("%s IS COMING", bossName(b)).withStyle(ChatFormatting.RED), 10, 70, 20);
		sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.RAID_HORN, SoundSource.HOSTILE, 64f, 1f);
		sp.sendSystemMessage(Component.translatable("[Ultracraft] WARNING: %s is coming in %s seconds, where the red pillar stands. Get ready!",
			bossName(b), warnLeft / 20).withStyle(ChatFormatting.RED));
		sp.sendSystemMessage(Component.translatable("[Ultracraft] ").withStyle(ChatFormatting.RED).append(describeColored()));
		UcNet.send(sp, "HUD <color=red>WARNING</color>: " + b.name + " is coming. " + warnLeft / 20 + " seconds to get ready.");
		// the other players hear it too
		for (ServerPlayer o : sp.level().getServer().getPlayerList().getPlayers()) {
			if (o != sp) o.sendSystemMessage(Component.translatable("[Ultracraft] %s is coming for %s.", bossName(b), sp.getName().getString()).withStyle(ChatFormatting.RED));
		}
	}

	private static void warn(ServerPlayer sp, boolean playing) {
		if (!sp.isAlive()) {
			cancel(sp, "the warning passes");
			return;
		}
		ServerLevel level = sp.level();
		// V1 wandered far off: it comes to where V1 is now
		if (sp.position().distanceTo(spot) > 48.0) {
			Vec3 at = findSpot(sp);
			if (at != null) spot = at;
		}
		marker(level);
		// it waits while V1 shops; it never arrives on a player who isn't there, or one riding, flying or asleep
		boolean shopping = ServerOps.shopping(sp);
		int floor = !playing ? 200 : !calm(sp) ? 60 : 0;
		if (!shopping && warnLeft > floor) warnLeft = Math.max(floor, warnLeft - 10);
		int secs = (warnLeft + 19) / 20;
		if (warnLeft % 20 == 0 || shopping) {
			net.minecraft.network.chat.MutableComponent bar = shopping
				? Component.translatable("%s waits while you shop", bossName(boss))
				: !playing ? Component.translatable("%s is coming: it waits for you to come back", bossName(boss))
				: Component.translatable("%s arrives in %s s - get ready", bossName(boss), secs);
			sp.displayClientMessage(bar.withStyle(ChatFormatting.RED), true);
		}
		if (shopping || warnLeft % 20 != 0) return;
		if (secs == 10) UcNet.send(sp, "HUD " + boss.name + " arrives in <color=red>10 seconds</color>.");
		if (secs <= 5 && secs > 0 && playing) {
			title(sp, Component.literal(Integer.toString(secs)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal(boss.name).withStyle(ChatFormatting.RED), 0, 20, 5);
			level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.HOSTILE, 2f, 0.6f);
		}
		if (warnLeft <= 0) arrive(sp, true);
	}

	/** A red pillar where the boss will land, seen from far off. */
	private static void marker(ServerLevel level) {
		DustParticleOptions red = new DustParticleOptions(0xFF2020, 2.0f);
		for (int i = 0; i < 6; i++) {
			level.sendParticles(red, true, true, spot.x, spot.y + i * 1.5, spot.z, 2, 0.25, 0.5, 0.25, 0.0);
		}
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, true, true, spot.x, spot.y + 0.2, spot.z, 4, 0.6, 0.05, 0.6, 0.02);
	}

	/**
	 * It's here. canArena: it may take V1 to its own arena first (Boss Arenas; not for one already waiting in an
	 * arena of the world's).
	 */
	private static void arrive(ServerPlayer sp, boolean canArena) {
		ServerLevel level = sp.level();
		Vec3 at = standable(level, spot) ? spot : findSpot(sp);
		if (at == null) at = spot;
		fightId = nextId++;
		fightTicks = 0;
		farTicks = 0;
		arenaDelay = 0;
		phase = Phase.FIGHT;
		String arenaTheme = null;
		if (canArena && UltracraftConfig.bossArenas && !CyberGrind.running && BossArenas.has(boss.key)) {
			BossArenas.Built b = BossArenas.enter(sp, boss.key);
			if (b != null) {
				at = b.boss();
				arenaTheme = layerKey(b.layer());
				// it comes once the party is in and ULTRAKILL has the arena's blocks around them
				arenaDelay = 80;
			}
		}
		spot = at;
		bossAt = at;
		themeFor = sp;
		if (arenaDelay == 0) spawn(sp);
		// its own song, where ULTRAKILL has one (the player's Boss Themes setting decides)
		UcNet.send(sp, "C:THEME boss:" + boss.key + (arenaTheme != null ? " arena:" + arenaTheme : ""));
		// ECLIPSE: night falls for the fight
		if (has("eclipse") && level.dimensionType().hasSkyLight()) {
			eclipseLevel = level;
			eclipseFrom = level.getDayTime();
			long day = eclipseFrom - Math.floorMod(eclipseFrom, 24000L);
			level.setDayTime(day + 18000L);
			level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1f, 0.5f);
		}
		stormIn = 60;
		strikeAt = null;
		if (bar != null) bar.removeAllPlayers();
		bar = new net.minecraft.server.level.ServerBossEvent(bossName(boss).withStyle(ChatFormatting.RED),
			net.minecraft.world.BossEvent.BossBarColor.RED, net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10);
		barMax = 0f;
	}

	/** The boss itself, sent to the target's ULTRAKILL: it lands with a lightning strike (and its entrance, if on). */
	private static void spawn(ServerPlayer sp) {
		ServerLevel level = sp.level();
		Vec3 at = spot;
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt != null) {
			bolt.snapTo(at.x, at.y, at.z);
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		// with its entrance on, ULTRAKILL shows its own title card for it
		if (UltracraftConfig.bossIntros == 0) title(sp, bossName(boss).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), describeColored(), 5, 60, 15);
		else sp.sendSystemMessage(Component.translatable("[Ultracraft] %s: ", bossName(boss)).withStyle(ChatFormatting.RED).append(describeColored()));
		StringBuilder keys = new StringBuilder();
		for (Mod m : mods) keys.append(keys.length() > 0 ? "," : "").append(m.key);
		// its entrance (which of them: Boss Music & Intros), and its death scene
		StringBuilder flags = new StringBuilder();
		boolean firstMeeting = !UkProgress.get(sp).beaten.containsKey(boss.key);
		String alt = UltracraftConfig.bossIntroAlt(boss.key, firstMeeting);
		boolean grind = BossSetup.grindVersion(boss.key);
		if (alt != null && !alt.isEmpty()) flags.append("alt=").append(alt);
		if (UltracraftConfig.bossDeaths && !grind) flags.append(flags.length() > 0 ? "," : "").append("outro");
		for (String f : BossSetup.flags(boss.key)) flags.append(flags.length() > 0 ? "," : "").append(f);
		// never beaten yet: their first meeting (Gabriel speaks to V1 before he fights it, as in 3-2)
		if (firstMeeting) flags.append(flags.length() > 0 ? "," : "").append("first");
		UcNet.send(sp, String.format(Locale.ROOT, "BOSS %d %s %.2f %.2f %.2f %d %s %s %s", fightId, boss.key, at.x, at.y, at.z, difficulty, keys.length() > 0 ? keys : "-",
			grind ? "nointro" : UltracraftConfig.bossIntros == 2 ? "introfull" : UltracraftConfig.bossIntros == 1 ? "intro" : "nointro", flags.length() > 0 ? flags : "-"));
	}

	/** The arena-key of a layer's name ("PRIME SANCTUM" -> prime), as Arenas knows it. */
	private static String layerKey(String layer) {
		String k = layer.toLowerCase(Locale.ROOT);
		if (k.startsWith("prime")) return "prime";
		return Arenas.layer(k) != null ? k : null;
	}

	/** Who sees the boss bar: every other player near the fight, and its target while down. */
	private static void updateBar(ServerPlayer sp) {
		if (bar == null || bossAt == null) return;
		for (ServerPlayer o : sp.level().getServer().getPlayerList().getPlayers()) {
			boolean see = (!isTarget(o) || BossParty.isDown(o)) && o.level() == sp.level() && o.position().distanceTo(bossAt) < 128.0;
			if (see && !bar.getPlayers().contains(o)) bar.addPlayer(o);
			else if (!see && bar.getPlayers().contains(o)) bar.removePlayer(o);
		}
	}

	/** The fight is over (won, lost, left): the sun comes back. */
	private static void endEffects() {
		// whoever went down in it gets back up
		BossParty.reviveAll();
		// out of its arena, back where they were
		BossArenas.leaveAll();
		arenaDelay = 0;
		if (bar != null) bar.removeAllPlayers();
		bar = null;
		if (themeFor != null) UcNet.send(themeFor, "C:THEME -");
		themeFor = null;
		arenaLayer = null;
		arenaWin = null;
		if (eclipseLevel != null && eclipseFrom >= 0) eclipseLevel.setDayTime(eclipseFrom + fightTicks);
		eclipseLevel = null;
		eclipseFrom = -1;
		strikeAt = null;
	}

	/** STORMCALLER: a spot near V1 sparks for a second, then lightning strikes it. */
	private static void storm(ServerPlayer sp) {
		ServerLevel level = sp.level();
		if (strikeAt != null) {
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, true, strikeAt.x, strikeAt.y + 0.2, strikeAt.z, 12, 0.6, 0.1, 0.6, 0.05);
		}
		stormIn -= 10;
		if (stormIn > 0) return;
		if (strikeAt == null) {
			// pick the spot: about where V1 will be in a moment
			Vec3 lead = sp.position().add(sp.getDeltaMovement().scale(15));
			double angle = sp.getRandom().nextDouble() * Math.PI * 2, dist = sp.getRandom().nextDouble() * 4.0;
			int x = (int) Math.floor(lead.x + Math.cos(angle) * dist), z = (int) Math.floor(lead.z + Math.sin(angle) * dist);
			int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
			strikeAt = new Vec3(x + 0.5, y, z + 0.5);
			level.playSound(null, strikeAt.x, strikeAt.y, strikeAt.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2f, 2f);
			stormIn = 20;
			return;
		}
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt != null) {
			bolt.snapTo(strikeAt.x, strikeAt.y, strikeAt.z);
			level.addFreshEntity(bolt);
		}
		strikeAt = null;
		stormIn = 60 + sp.getRandom().nextInt(60);
	}

	private static void fight(ServerPlayer sp) {
		if (arenaDelay > 0) {
			arenaDelay -= 10;
			if (arenaDelay <= 0) spawn(sp);
			return;
		}
		fightTicks += 10;
		updateBar(sp);
		if (has("stormcaller")) storm(sp);
		if (bossAt != null && sp.position().distanceTo(bossAt) > 96.0) farTicks += 10;
		else farTicks = 0;
		if (farTicks > 20 * 30) leave(sp, boss.name + " lost track of you.");
		else if (fightTicks > 20 * 60 * 20) leave(sp, boss.name + " got bored and left.");
	}

	/** BOSSPOS id x y z health, from the target's ULTRAKILL. */
	static void moved(ServerPlayer sp, int id, Vec3 at, float health) {
		if (phase != Phase.FIGHT || id != fightId || !isTarget(sp)) return;
		bossAt = at;
		if (bar != null && health >= 0f) {
			// (its most yet: an Undying or Regenerating boss coming back above it fills the bar again)
			barMax = Math.max(barMax, health);
			bar.setProgress(barMax > 0f ? Math.min(1f, health / barMax) : 1f);
		}
	}

	/** BOSSDEAD id x y z: beaten. P, experience, loot, and the next one later. */
	static void beaten(ServerPlayer sp, int id, Vec3 at) {
		if (phase != Phase.FIGHT || id != fightId || !isTarget(sp)) return;
		Boss b = boss;
		Runnable arenaCleared = arenaWin;
		ServerLevel level = sp.level();
		UkProgress p = UkProgress.get(sp);
		if (BossSetup.practice(b.key)) {
			// Boss Setup: practice, no prize and not counted
			BossArenas.leaveSoon();
			endEffects();
			phase = Phase.IDLE;
			boss = null;
			target = null;
			title(sp, bossName(b).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.translatable("DEFEATED   (PRACTICE)").withStyle(ChatFormatting.GRAY), 5, 80, 20);
			sp.sendSystemMessage(Component.translatable("[Ultracraft] %s defeated (practice: no reward, not counted).", bossName(b)).withStyle(ChatFormatting.GRAY));
			if (arenaCleared != null) arenaCleared.run();
			return;
		}
		p.beaten.merge(b.key, 1, Integer::sum);
		p.bossClock = 0;
		p.nextBoss = interval(sp.getRandom());
		int pay = reward(b);
		p.award(pay);
		// in its arena: everyone stays a while to pick up the drops, then goes back
		BossArenas.leaveSoon();
		endEffects();
		phase = Phase.IDLE;
		boss = null;
		// the prize falls where it died, unless that's far off or somewhere V1 can't get to
		Vec3 drop = at != null && at.distanceTo(sp.position()) < 32.0 && level.getFluidState(BlockPos.containing(at)).isEmpty() ? at : sp.position();
		ExperienceOrb.award(level, drop, b.xp);
		for (ItemStack stack : loot(b.tier, level.getRandom())) {
			ItemEntity item = new ItemEntity(level, drop.x, drop.y + 0.5, drop.z, stack);
			item.setUnlimitedLifetime();
			item.setGlowingTag(true);
			level.addFreshEntity(item);
		}
		String paid = String.format(Locale.ROOT, "+%,dP", pay);
		title(sp, bossName(b).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.translatable("DEFEATED   %s", paid).withStyle(ChatFormatting.YELLOW), 5, 80, 20);
		level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
		sp.sendSystemMessage(Component.translatable("[Ultracraft] %s defeated: %s, and its loot dropped. Bosses beaten: %d.",
			bossName(b), paid, p.bossesBeaten()).withStyle(ChatFormatting.GOLD));
		UcNet.send(sp, "HUD <color=#FF4343>" + b.name + "</color> DEFEATED. " + paid);
		// every other V1 who was there takes the same prize
		for (ServerPlayer o : level.getServer().getPlayerList().getPlayers()) {
			if (o == sp || !UcNet.isV1(o) || o.level() != level || o.position().distanceTo(drop) > 96.0) continue;
			UkProgress op = UkProgress.get(o);
			op.beaten.merge(b.key, 1, Integer::sum);
			op.award(pay);
			title(o, bossName(b).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.translatable("DEFEATED   %s", paid).withStyle(ChatFormatting.YELLOW), 5, 80, 20);
			UcNet.send(o, "HUD <color=#FF4343>" + b.name + "</color> DEFEATED. " + paid);
		}
		target = null;
		if (arenaCleared != null) arenaCleared.run();
	}

	/** BOSSGONE id why: ULTRAKILL couldn't bring it in, or it dropped out of the world. */
	static void gone(ServerPlayer sp, int id, String why) {
		if (phase != Phase.FIGHT || id != fightId || !isTarget(sp)) return;
		String name = boss.name;
		endEffects();
		phase = Phase.IDLE;
		boss = null;
		UkProgress p = UkProgress.get(sp);
		if (why.equals("bribed")) {
			p.bossClock = 0;
			p.nextBoss = interval(sp.getRandom());
			p.setDirty();
			title(sp, Component.literal(name).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), Component.translatable("TOOK YOUR COIN").withStyle(ChatFormatting.GRAY), 5, 70, 20);
			sp.sendSystemMessage(Component.translatable("[Ultracraft] The Ferryman took your coin and let you pass.").withStyle(ChatFormatting.AQUA));
			return;
		}
		// it never came: soon again
		p.nextBoss = p.bossClock + 20 * 60 * 2;
		p.setDirty();
		sp.sendSystemMessage(Component.translatable("[Ultracraft] %s didn't make it (%s).", name, why).withStyle(ChatFormatting.GRAY));
	}

	private static void leave(ServerPlayer sp, String why) {
		UcNet.send(sp, "BOSSCLEAR " + fightId);
		endEffects();
		phase = Phase.IDLE;
		boss = null;
		UkProgress p = UkProgress.get(sp);
		// it'll be back, sooner than a fresh one
		p.bossClock = 0;
		p.nextBoss = interval(sp.getRandom()) / 2;
		p.setDirty();
		sp.sendSystemMessage(Component.literal("[Ultracraft] " + why).withStyle(ChatFormatting.GRAY));
		sp.displayClientMessage(Component.literal(why).withStyle(ChatFormatting.GRAY), true);
	}

	private static void cancel(ServerPlayer sp, String why) {
		phase = Phase.IDLE;
		boss = null;
		UkProgress p = UkProgress.get(sp);
		p.nextBoss = p.bossClock + 20 * 60 * 3;
		p.setDirty();
		sp.sendSystemMessage(Component.literal("[Ultracraft] " + why + ".").withStyle(ChatFormatting.GRAY));
		sp.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
	}

	/** V1 died, became Steve, or ULTRAKILL went away: a warning lapses, a boss leaves. */
	static void stop(ServerPlayer sp, String why) {
		if (phase == Phase.IDLE || !isTarget(sp)) return;
		if (phase == Phase.WARN) {
			cancel(sp, boss.name + " turns back (" + why + ")");
			return;
		}
		leave(sp, boss.name + " leaves (" + why + ").");
	}

	/** Forget a fight without a player to tell (the world closed). */
	static void reset() {
		BossParty.reset();
		BossArenas.reset();
		endEffects();
		phase = Phase.IDLE;
		boss = null;
		target = null;
	}

	static String state() {
		return phase + (boss != null ? " " + boss.key + " [" + describe() + "]" : "") + (phase == Phase.WARN ? " " + warnLeft / 20 + "s" : "") + (phase == Phase.FIGHT ? " id " + fightId + " " + fightTicks / 20 + "s" : "");
	}

	private static List<ItemStack> loot(int tier, RandomSource r) {
		List<ItemStack> drops = new ArrayList<>();
		switch (tier) {
			case 1 -> {
				drops.add(new ItemStack(Items.IRON_INGOT, 4 + r.nextInt(5)));
				drops.add(new ItemStack(Items.DIAMOND, 1 + r.nextInt(2)));
				drops.add(new ItemStack(Items.GOLDEN_APPLE));
			}
			case 2 -> {
				drops.add(new ItemStack(Items.DIAMOND, 2 + r.nextInt(3)));
				drops.add(new ItemStack(Items.GOLDEN_APPLE, 1 + r.nextInt(2)));
				drops.add(new ItemStack(Items.EMERALD, 4 + r.nextInt(5)));
			}
			case 3 -> {
				drops.add(new ItemStack(Items.DIAMOND, 4 + r.nextInt(3)));
				drops.add(new ItemStack(Items.NETHERITE_SCRAP));
				drops.add(r.nextInt(4) == 0 ? new ItemStack(Items.ENCHANTED_GOLDEN_APPLE) : new ItemStack(Items.GOLDEN_APPLE, 2));
			}
			case 4 -> {
				drops.add(new ItemStack(Items.DIAMOND, 6));
				drops.add(new ItemStack(Items.NETHERITE_SCRAP, 2));
				drops.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
			}
			default -> {
				drops.add(new ItemStack(Items.NETHERITE_INGOT));
				drops.add(new ItemStack(Items.DIAMOND, 8));
				drops.add(new ItemStack(Items.TOTEM_OF_UNDYING));
				drops.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
			}
		}
		return drops;
	}

	private static void title(ServerPlayer sp, Component title, Component sub, int in, int stay, int out) {
		sp.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
		sp.connection.send(new ClientboundSetSubtitleTextPacket(sub));
		sp.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	/** Open ground 14 to 24 blocks from V1, roughly where it's looking, with room for a big body (3x3, 4 high). */
	private static Vec3 findSpot(ServerPlayer sp) {
		ServerLevel level = sp.level();
		RandomSource random = sp.getRandom();
		double yaw = Math.toRadians(sp.getYRot());
		for (int attempt = 0; attempt < 24; attempt++) {
			// in front first, then anywhere around
			double angle = attempt < 12 ? yaw + (random.nextDouble() - 0.5) * Math.PI * 0.8 : random.nextDouble() * Math.PI * 2.0;
			double dist = 14.0 + random.nextDouble() * 10.0;
			int x = (int) Math.floor(sp.getX() - Math.sin(angle) * dist), z = (int) Math.floor(sp.getZ() + Math.cos(angle) * dist);
			if (!level.hasChunkAt(new BlockPos(x, (int) sp.getY(), z))) continue;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, (int) Math.floor(sp.getY()) + 10, z);
			for (int y = m.getY(); y > sp.getY() - 14 && y > level.getMinY(); y--) {
				m.setY(y);
				if (!level.getBlockState(m).isFaceSturdy(level, m, Direction.UP)) continue;
				Vec3 at = new Vec3(x + 0.5, y + 1, z + 0.5);
				if (standable(level, at)) return at;
				break;
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, Vec3 at) {
		BlockPos feet = BlockPos.containing(at);
		return level.noCollision(new AABB(at.x - 1.4, at.y, at.z - 1.4, at.x + 1.4, at.y + 4.0, at.z + 1.4))
			&& level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty()
			&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
	}
}
