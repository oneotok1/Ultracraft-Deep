package dev.ultracraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the shop's Upgrades page sells. Every weapon and each arm has its own Power (how hard it hits enemies,
 * mobs and blocks: 40% at first, 300% fully upgraded) and upgrades of its own: two for each weapon, six for each arm.
 * With the OP Shop on (settings), Power goes on to 1500% and every blast size upgrade to 1500%. ULTRAKILL's side knows
 * what each level does (bridge Upgrades.cs); here is what each costs and how far it goes, and the one effect Minecraft
 * applies itself (parried projectiles).
 */
final class UkUpgrades {
	enum Kind { POWER, SPECIAL, BLAST }

	record Track(String key, String group, String name, Kind kind) {
		/** How many levels it has (more with the OP Shop on). */
		int max() {
			return switch (kind) {
				case POWER -> 1 + POWER_COST.length + (UltracraftConfig.opShop ? OP_POWER_LEVELS : 0);
				case BLAST -> 1 + SPECIAL_COST.length + (UltracraftConfig.opShop ? OP_BLAST_LEVELS : 0);
				case SPECIAL -> 1 + SPECIAL_COST.length;
			};
		}

		/** What reaching level (2 to max) costs. */
		int costTo(int level) {
			if (level < 2 || level > max()) return 0;
			int[] base = kind == Kind.POWER ? POWER_COST : SPECIAL_COST;
			if (level - 2 < base.length) return base[level - 2];
			// the OP Shop's levels past the usual top
			return kind == Kind.POWER ? OP_POWER_COST : OP_BLAST_COST;
		}
	}

	/** Weapons and arms, as the page lists them. */
	static final Map<String, String> GROUPS = new LinkedHashMap<>();
	static final Map<String, Track> TRACKS = new LinkedHashMap<>();

	private static final int[] POWER_COST = {2500, 5000, 7500, 10000, 15000, 20000, 30000, 40000, 55000, 75000};
	private static final int[] SPECIAL_COST = {5000, 15000, 35000, 70000};
	/** OP Shop: Power 400% to 1500% (one level per 100%), blasts 300% to 1500%. */
	private static final int OP_POWER_LEVELS = 12, OP_BLAST_LEVELS = 8;
	private static final int OP_POWER_COST = 25000, OP_BLAST_COST = 20000;

	static {
		group("rev", "Revolver", "Hair Trigger", "rate", "Capacitor", "charge");
		group("sho", "Shotgun", "Payload", "payload", "Capacitor", "charge");
		group("nai", "Nailgun", "Overclock", "rate", "Heatsink", "charge");
		group("rai", "Railcannon", "Capacitor", "charge", "Payload", "payload");
		group("rock", "Rocket Launcher", "Payload", "payload", "Autoloader", "rate");
		group("arm0", "Feedbacker", "Reflex", "reflex", "Return to Sender", "sender", "Bloodfist", "bloodfist", "Arc", "arc",
			"Counterblast", "counter", "Adrenaline", "adrenaline");
		group("arm1", "Knuckleblaster", "Shockwave", "shockwave", "Demolition", "demolition", "Detonator", "detonator", "Seismic", "seismic",
			"Ironclad", "ironclad", "Overcharge", "overcharge");
		group("arm2", "Whiplash", "Reel", "reel", "Barbs", "barbs", "Grapple", "grapple", "Live Wire", "livewire",
			"Slingshot", "slingshot", "Ripcord", "ripcord");
	}

	/** A weapon or arm: its name, then each upgrade of its own as name, key. */
	private static void group(String g, String name, String... specials) {
		GROUPS.put(g, name);
		add(new Track(g + ".power", g, "Power", Kind.POWER));
		for (int i = 0; i + 1 < specials.length; i += 2) {
			String t = specials[i + 1];
			// the upgrades that size a blast go on to 1500% in the OP Shop
			Kind kind = t.equals("payload") || t.equals("shockwave") ? Kind.BLAST : Kind.SPECIAL;
			add(new Track(g + "." + t, g, specials[i], kind));
		}
	}

	private static void add(Track t) {
		TRACKS.put(t.key, t);
	}

	private UkUpgrades() {}

	static List<String> keys() {
		return new ArrayList<>(TRACKS.keySet());
	}

	/** "Feedbacker Return to Sender". */
	static String name(String key) {
		Track t = TRACKS.get(key);
		return t == null ? key : GROUPS.get(t.group) + " " + t.name;
	}

	/** Return to Sender: how much harder (and faster) a parried projectile comes back, x1 to x3. */
	static float senderMult(UkProgress p) {
		return new float[] {1f, 1.5f, 2f, 2.5f, 3f}[p.level("arm0.sender") - 1];
	}

	/** Worlds from before the upgrades split (rev=3, arm=2...): each weapon's old level is its Power now. */
	static void migrate(Map<String, Integer> upgrades) {
		for (String old : new String[] {"rev", "sho", "nai", "rai", "rock", "arm"}) {
			Integer lv = upgrades.remove(old);
			if (lv == null) continue;
			if (old.equals("arm")) {
				for (String a : new String[] {"arm0", "arm1", "arm2"}) upgrades.merge(a + ".power", lv, Math::max);
			} else {
				upgrades.merge(old + ".power", lv, Math::max);
			}
		}
	}
}
