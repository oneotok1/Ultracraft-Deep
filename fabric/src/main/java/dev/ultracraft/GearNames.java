package dev.ultracraft;

import java.util.Map;

/** ULTRAKILL's names for its gear codes (what the shop sells: GEARADD). */
final class GearNames {
	private static final Map<String, String> NAMES = Map.ofEntries(
		Map.entry("rev0", "Piercer Revolver"), Map.entry("rev1", "Marksman Revolver"), Map.entry("rev2", "Sharpshooter Revolver"),
		Map.entry("revalt", "Slab Revolver (alternate)"),
		Map.entry("sho0", "Core Eject Shotgun"), Map.entry("sho1", "Pump Charge Shotgun"), Map.entry("sho2", "Sawed-On Shotgun"),
		Map.entry("shoalt", "Jackhammer (alternate shotgun)"),
		Map.entry("nai0", "Attractor Nailgun"), Map.entry("nai1", "Overheat Nailgun"), Map.entry("nai2", "JumpStart Nailgun"),
		Map.entry("naialt", "Sawblade Launcher (alternate nailgun)"),
		Map.entry("rai0", "Electric Railcannon"), Map.entry("rai1", "Screwdriver"), Map.entry("rai2", "Malicious Railcannon"),
		Map.entry("rock0", "Freezeframe Rocket Launcher"), Map.entry("rock1", "S.R.S. Cannon"), Map.entry("rock2", "Firestarter Rocket Launcher"),
		Map.entry("arm1", "Knuckleblaster"), Map.entry("arm2", "Whiplash"),
		Map.entry("color0", "custom revolver colours"), Map.entry("color1", "custom shotgun colours"), Map.entry("color2", "custom nailgun colours"),
		Map.entry("color3", "custom railcannon colours"), Map.entry("color4", "custom rocket launcher colours"));

	private GearNames() {}

	static String of(String gear) {
		return NAMES.getOrDefault(gear, gear);
	}
}
