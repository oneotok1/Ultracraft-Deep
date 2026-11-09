package dev.ultracraft;

import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Bosses' and arenas' own ULTRAKILL songs (the Boss Themes setting), and Minecraft's music hushed while ULTRAKILL's
 * fight music plays. The server says when a theme starts (C:THEME boss:v2, arena:limbo) and ends (C:THEME -); this
 * picks the song and tells ULTRAKILL (THEME key, or -). ULTRAKILL says when fights start and end (FIGHT 1/0).
 */
public final class UcThemes {
	/**
	 * Each boss's song in ULTRAKILL (song keys of its soundtrack): its own theme, or what plays in the level where V1
	 * fights it (0-3, 1-3, 2-3, 4-2, 5-2).
	 */
	private static final Map<String, String> BOSSES = Map.ofEntries(
		Map.entry("swordsmachine", "Levels/Act 1/Prelude/Into the Fire.asset"),
		Map.entry("cerberus", "Levels/Act 1/Prelude/Cerberus.asset"),
		Map.entry("hideousmass", "Levels/Act 1/Limbo/Castle Vein.asset"),
		Map.entry("v2", "Levels/Act 1/Limbo/Versus.asset"),
		Map.entry("mindflayer", "Levels/Act 1/Lust/Panic Betrayer.asset"),
		Map.entry("gabriel", "Levels/Act 1/Gluttony/Divine Intervention.asset"),
		Map.entry("insurrectionist", "Levels/Act 2/Greed/Sands of Tide.asset"),
		Map.entry("v2green", "Levels/Act 2/Greed/Duel (Versus Reprise).asset"),
		Map.entry("ferryman", "Levels/Act 2/Wrath/He Is The Light In My Darkness.asset"),
		Map.entry("gabriel2", "Levels/Act 2/Heresy/The Death of Gods Will.asset"),
		Map.entry("minosprime", "Prime Sanctums/Order.asset"),
		Map.entry("sisyphusprime", "Prime Sanctums/War.asset"));

	/** A boss's own song, as ULTRAKILL has it (null: none). */
	static String defaultSong(String boss) {
		return BOSSES.get(boss);
	}

	/** Each layer's song, for its arenas. */
	private static final Map<String, String> LAYERS = Map.ofEntries(
		Map.entry("prelude", "Levels/Act 1/Prelude/Into the Fire.asset"),
		Map.entry("limbo", "Levels/Act 1/Limbo/Castle Vein.asset"),
		Map.entry("lust", "Levels/Act 1/Lust/Cold Winds.asset"),
		Map.entry("gluttony", "Levels/Act 1/Gluttony/Guts.asset"),
		Map.entry("greed", "Levels/Act 2/Greed/Dune Eternal.asset"),
		Map.entry("wrath", "Levels/Act 2/Wrath/Deep Blue.asset"),
		Map.entry("heresy", "Levels/Act 2/Heresy/Altars of Apostasy.asset"),
		Map.entry("violence", "Levels/Act 3/Violence/Bull of Hell.asset"),
		Map.entry("fraud", "Levels/Act 3/Fraud/Mirror Rim.asset"),
		Map.entry("prime", "Prime Sanctums/Chaos.asset"));

	/** ULTRAKILL's fight music is playing (a fight is on and a song is chosen). */
	static volatile boolean fightMusic;

	/** The last theme the server asked for ("boss:v2 arena:limbo", or "-"), and the last fight state: what the
	 * settings below have to re-apply when they change mid-fight. */
	private static String lastTheme;
	private static boolean lastFight;
	/** A theme of its own is really sounding (not taken away by Boss Themes, nor by the boss's own Off pick). */
	private static boolean themePlaying;

	private UcThemes() {}

	/** C:THEME [boss:key] [arena:layer] | -: the boss's own song first, else its arena's layer's. */
	static void theme(String what) {
		lastTheme = what;
		String song = null;
		for (String w : what.split(" ")) {
			if (song == null && w.startsWith("boss:")) {
				// the player's pick for it (Boss Music), else its own
				String key = w.substring(5), pick = UltracraftConfig.bossSongs.getOrDefault(key, "default");
				if (pick.equals("off")) {
					UkLink.send("THEME -");
					themePlaying = false;
					return;
				}
				song = pick.equals("default") ? BOSSES.get(key) : pick;
			}
			if (song == null && w.startsWith("arena:")) song = LAYERS.get(w.substring(6));
		}
		if (song == null || !UltracraftConfig.bossThemes) {
			// no theme of its own: the fight music as chosen
			UkLink.send("THEME -");
			themePlaying = false;
			return;
		}
		UkLink.send("THEME " + UcMusicScreen.wire(song));
		themePlaying = true;
		// a theme plays even with fight music off
		fightMusic = true;
	}

	/** FIGHT 1/0 from ULTRAKILL. */
	static void fight(Minecraft mc, boolean on) {
		lastFight = on;
		// a boss's or an arena's own song counts as fight music here: it plays with the fight music off, and
		// Minecraft's must still give way to it
		fightMusic = on && (themePlaying || !UltracraftConfig.fightMusic.equals("off"));
		if (fightMusic && UltracraftConfig.hushMcMusic) mc.getMusicManager().stopPlaying();
	}

	/** Boss Themes changed: the theme of the fight in progress is applied again, or taken away, now. */
	static void retune() {
		if (!lastFight || lastTheme == null || lastTheme.equals("-")) return;
		// re-picks with the new setting: THEME song, or THEME - when it is off
		theme(lastTheme);
		fightMusic = themePlaying || !UltracraftConfig.fightMusic.equals("off");
	}

	/** Hush Minecraft's Music changed: the fight in progress starts hushing now, instead of at the next one. */
	static void refight() {
		if (!lastFight) return;
		fightMusic = themePlaying || !UltracraftConfig.fightMusic.equals("off");
		if (!fightMusic || !UltracraftConfig.hushMcMusic) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) mc.getMusicManager().stopPlaying();
	}

	/** Minecraft's music waits while ULTRAKILL's plays (MusicManagerMixin). */
	public static boolean hushing() {
		return fightMusic && UltracraftConfig.hushMcMusic && (Ultracraft.active || Ultracraft.steveView);
	}
}
