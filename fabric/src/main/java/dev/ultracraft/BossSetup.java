package dev.ultracraft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Boss Setup (Cheats -> Boss Setup...): each boss as the player wants it, saved as bossSet.&lt;boss&gt;.&lt;option&gt;.
 * <ul>
 * <li>In rotation: whether it comes on its own (the schedule never picks it when off; it can still be called);</li>
 * <li>Encounter (V2, Gabriel): both of ULTRAKILL's fights, only the first (1-4 V2, 3-2 Gabriel), or only the rematch
 * (4-4 V2 with the green arm, 6-2 Gabriel);</li>
 * <li>Traits: random (the Trait Chance setting), none, or the ones picked;</li>
 * <li>Health, speed and damage multipliers (ULTRAKILL's own modifiers);</li>
 * <li>Second phase from the start (Gabriel's "Enough!", the Primes' "WEAK"/enraged half);</li>
 * <li>Practice: no reward and it doesn't count as beaten;</li>
 * <li>Its own: V2 retreats at a third of its health as in 1-4 (and that counts as a win); Cerberus wakes one at a time as
 * in 0-5 (the second when the first is down to half); the Ferryman takes a coin thrown at him and lets you pass, as in
 * 5-2; the Primes as the Cyber Grind has them (no speeches, no death scene, a quick death).</li>
 * </ul>
 */
final class BossSetup {
	private BossSetup() {}

	static String get(String boss, String opt, String def) {
		return UltracraftConfig.bossSets.getOrDefault(boss + "." + opt, def);
	}

	static void set(String boss, String opt, String value, String def) {
		if (value == null || value.equals(def)) UltracraftConfig.bossSets.remove(boss + "." + opt);
		else UltracraftConfig.bossSets.put(boss + "." + opt, value);
		UltracraftConfig.save();
	}

	static boolean flag(String boss, String opt) {
		return get(boss, opt, "0").equals("1");
	}

	static void flag(String boss, String opt, boolean on) {
		set(boss, opt, on ? "1" : "0", "0");
	}

	/** Health, speed or damage: tenths (10 = 1x). */
	static int tenths(String boss, String opt) {
		try {
			return Integer.parseInt(get(boss, opt, "10"));
		} catch (NumberFormatException e) {
			return 10;
		}
	}

	static boolean inRotation(String boss) {
		return !flag(boss, "notInRotation");
	}

	/** The pair a boss belongs to (V2 and its rematch, Gabriel and his), by its first fight's key; null if none. */
	static String family(String boss) {
		return switch (boss) {
			case "v2", "v2green" -> "v2";
			case "gabriel", "gabriel2" -> "gabriel";
			default -> null;
		};
	}

	static boolean isRematch(String boss) {
		return boss.equals("v2green") || boss.equals("gabriel2");
	}

	/** both, first or rematch. */
	static String encounter(String boss) {
		String f = family(boss);
		return f == null ? "both" : get(f, "encounter", "both");
	}

	/** Whether the schedule (and an arena) may bring this one, by its pair's Encounter. */
	static boolean encounterAllows(String boss) {
		String e = encounter(boss);
		return e.equals("both") || (e.equals("first") ? !isRematch(boss) : isRematch(boss));
	}

	/** The fight to bring when this one is asked for (an arena's, the schedule's): the other of its pair if its
	 * Encounter rules this one out. */
	static String resolve(String boss) {
		if (encounterAllows(boss) || family(boss) == null) return boss;
		return switch (boss) {
			case "v2" -> "v2green";
			case "v2green" -> "v2";
			case "gabriel" -> "gabriel2";
			case "gabriel2" -> "gabriel";
			default -> boss;
		};
	}

	/** random, none or pick. */
	static String traits(String boss) {
		return get(boss, "traits", "random");
	}

	static Set<String> picked(String boss) {
		Set<String> out = new LinkedHashSet<>();
		for (String k : get(boss, "traitList", "").split(",")) if (!k.isBlank()) out.add(k.trim());
		return out;
	}

	static void pick(String boss, String trait, boolean on) {
		Set<String> s = picked(boss);
		if (on) s.add(trait);
		else s.remove(trait);
		set(boss, "traitList", String.join(",", s), "");
	}

	static boolean hasPhases(String boss) {
		return switch (boss) {
			case "gabriel", "gabriel2", "minosprime", "sisyphusprime" -> true;
			default -> false;
		};
	}

	static boolean isPrime(String boss) {
		return boss.equals("minosprime") || boss.equals("sisyphusprime");
	}

	/** The Primes as the Cyber Grind has them: no speeches, no death scene. */
	static boolean grindVersion(String boss) {
		return isPrime(boss) && flag(boss, "grind");
	}

	static boolean practice(String boss) {
		return flag(boss, "practice");
	}

	/** What ULTRAKILL needs to know, for the BOSS message's flags: hp=1.5,spd=1.2,dmg=2,phase2,stagger,bribe,grind,retreat. */
	static List<String> flags(String boss) {
		List<String> out = new ArrayList<>();
		int hp = tenths(boss, "health"), spd = tenths(boss, "speed"), dmg = tenths(boss, "damage");
		if (hp != 10) out.add(String.format(Locale.ROOT, "hp=%.1f", hp / 10f));
		if (spd != 10) out.add(String.format(Locale.ROOT, "spd=%.1f", spd / 10f));
		if (dmg != 10) out.add(String.format(Locale.ROOT, "dmg=%.1f", dmg / 10f));
		if (hasPhases(boss) && flag(boss, "phase2")) out.add("phase2");
		if (boss.equals("cerberus") && flag(boss, "stagger")) out.add("stagger");
		if (boss.equals("ferryman") && flag(boss, "bribe")) out.add("bribe");
		if (grindVersion(boss)) out.add("grind");
		if (boss.equals("v2") && flag(boss, "retreat")) out.add("retreat");
		return out;
	}
}
