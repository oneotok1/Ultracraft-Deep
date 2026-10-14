package dev.ultracraft;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * The Cheats page: ULTRAKILL's own Sandbox cheats (noclip, flight, invincibility, no weapon cooldown...) and a few of
 * Ultracraft's (infinite P, one-hit kills, slow motion, super speed, infinite stamina, never hungry, all gear). They're
 * Ultracraft's settings (config/ultracraft.properties), never ULTRAKILL's: it would save its cheats in its own files.
 * In your own world (singleplayer, or one you host) they're always there; on someone else's server only for operators.
 */
final class UcCheats {
	/** A cheat: its id (ULTRAKILL's own, or ultracraft.*), its name and what it does; server: Minecraft's side acts on it. */
	record Cheat(String id, String name, String tooltip, boolean server) {}

	static final List<Cheat> ULTRAKILL = List.of(
		new Cheat("ultrakill.noclip", "Noclip", "V1 flies through walls and blocks (ULTRAKILL's cheat).", false),
		new Cheat("ultrakill.flight", "Flight", "V1 flies: jump to rise, slide to sink (ULTRAKILL's cheat).", false),
		new Cheat("ultrakill.infinite-wall-jumps", "Infinite Wall Jumps", "Wall jump as often as you like.", false),
		new Cheat("ultrakill.invincibility", "Invincibility", "Nothing hurts V1.", false),
		new Cheat("ultrakill.no-weapon-cooldown", "No Weapon Cooldown", "Weapons fire and recharge without waiting.", false),
		new Cheat("ultrakill.infinite-power-ups", "Infinite Power-Ups", "Power-ups never run out.", false),
		new Cheat("ultrakill.blind-enemies", "Blind Enemies", "ULTRAKILL's enemies can't see V1.", false),
		new Cheat("ultrakill.enemy-ignore-player", "Enemies Ignore Player", "ULTRAKILL's enemies leave V1 alone.", false),
		new Cheat("ultrakill.enemy-hate-enemy", "Enemy Infighting", "ULTRAKILL's enemies fight each other (and Minecraft's monsters).", false),
		new Cheat("ultrakill.invincible-enemies", "Invincible Enemies", "ULTRAKILL's enemies can't be killed.", false),
		new Cheat("ultrakill.hide-weapons", "Hide Weapons", "V1's guns and arms aren't drawn.", false),
		new Cheat("ultrakill.hide-ui", "Hide ULTRAKILL's HUD", "ULTRAKILL's HUD (health, style, weapon) isn't drawn.", false),
		new Cheat("ultrakill.ghost-drone-mode", "Drone Haunting", "Ghostly drones haunt V1 (ULTRAKILL's cheat).", false),
		new Cheat("ultrakill.spawner-arm", "Spawner Arm", "The Sandbox's Spawner Arm in weapon slot 6: spawn ULTRAKILL's enemies and props anywhere.", false));

	static final List<Cheat> ULTRACRAFT = List.of(
		new Cheat("ultracraft.infinite-p", "Infinite P", "The shop sells everything for free.", true),
		new Cheat("ultracraft.all-gear", "All Weapons & Arms", "Every weapon, variant and arm without buying it.", true),
		new Cheat("ultracraft.one-hit-kills", "One-Hit Kills", "Everything V1 hits dies (bosses too).", false),
		new Cheat("ultracraft.slow-motion", "Slow Motion", "ULTRAKILL runs at half speed.", false),
		new Cheat("ultracraft.super-speed", "Super Speed", "V1 runs 60% faster.", false),
		new Cheat("ultracraft.infinite-stamina", "Infinite Stamina", "Dash and slide-jump without running out.", false),
		new Cheat("ultracraft.never-hungry", "Never Hungry", "Steve's (and V1's) hunger stays full.", true));

	private UcCheats() {}

	/** Cheats are allowed here: singleplayer, a world you host, or a server where you're an operator. */
	static boolean allowed() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.isLocalServer()) return true;
		return mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	static boolean on(String id) {
		if (id.equals("ultracraft.all-gear")) return UltracraftConfig.allGear;
		return UltracraftConfig.cheats.getOrDefault(id, false) && allowed();
	}

	/** The Cheats page. */
	static void fill(UcSettingsScreen.Category c) {
		if (!allowed()) c.header("Cheats need operator permission on this server.");
		c.header("ULTRAKILL's cheats");
		c.add(options(ULTRAKILL));
		Button kill = Button.builder(Component.literal("Kill All ULTRAKILL Enemies"), b -> UkLink.send("CHEAT ultrakill.kill-all-enemies 1"))
			.tooltip(Tooltip.create(Component.literal("ULTRAKILL's enemies around V1 die (Minecraft's monsters don't)."))).build();
		kill.active = allowed();
		c.add(kill, null);
		c.header("Ultracraft's cheats");
		c.add(options(ULTRACRAFT));
		c.header("Bosses");
		Button setup = Button.builder(Component.literal("Boss Setup..."), b -> Minecraft.getInstance().setScreen(new UcBossSetupScreen(c.self())))
			.tooltip(Tooltip.create(Component.literal("Each boss as you want it: first encounter or rematch, its phase, health, speed, damage and traits, whether it comes on its own, practice fights, and a Fight Now button."))).build();
		c.add(setup, null);
	}

	private static OptionInstance<?>[] options(List<Cheat> cheats) {
		OptionInstance<?>[] out = new OptionInstance<?>[cheats.size()];
		for (int i = 0; i < cheats.size(); i++) {
			Cheat ch = cheats.get(i);
			out[i] = OptionInstance.createBoolean(ch.name, OptionInstance.cachedConstantTooltip(Component.literal(ch.tooltip)), on(ch.id), v -> set(ch, v));
		}
		return out;
	}

	/** Debug: switch a cheat by its id. */
	static void debugSet(String id, boolean v) {
		for (List<Cheat> list : List.of(ULTRAKILL, ULTRACRAFT)) for (Cheat ch : list) if (ch.id.equals(id)) set(ch, v);
		// saved, as the Cheats page saves: one switched off here mustn't come back on from the file next time
		UltracraftConfig.save();
	}

	private static void set(Cheat ch, boolean v) {
		if (!allowed()) return;
		if (ch.id.equals("ultracraft.all-gear")) {
			UltracraftConfig.allGear = v;
			resendGear();
			return;
		}
		UltracraftConfig.cheats.put(ch.id, v);
		send(ch, v);
	}

	private static void send(Cheat ch, boolean v) {
		if (ch.server) UcNet.toServer("CHEATSRV " + ch.id + " " + (v ? 1 : 0));
		// the shop's prices and ULTRAKILL's own cheats are ULTRAKILL's to show and do
		if (!ch.server || ch.id.equals("ultracraft.infinite-p")) UkLink.send("CHEAT " + ch.id + " " + (v ? 1 : 0));
	}

	/** Every cheat as Minecraft has it, to ULTRAKILL and the server (on connecting, and on joining a world). */
	static void sendAll() {
		for (List<Cheat> list : List.of(ULTRAKILL, ULTRACRAFT)) {
			for (Cheat ch : list) {
				if (ch.id.equals("ultracraft.all-gear")) continue;
				boolean v = on(ch.id);
				if (!ch.server || ch.id.equals("ultracraft.infinite-p")) UkLink.send("CHEAT " + ch.id + " " + (v ? 1 : 0));
			}
		}
	}

	/** The same for the server's side (Never Hungry, Infinite P), once the player is in a world. */
	static void sendServer() {
		for (Cheat ch : ULTRACRAFT) if (ch.server && !ch.id.equals("ultracraft.all-gear")) UcNet.toServer("CHEATSRV " + ch.id + " " + (on(ch.id) ? 1 : 0));
	}

	/** All Weapons & Arms changed: every player's ULTRAKILL hears what it owns now. */
	private static void resendGear() {
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) return;
		server.execute(() -> {
			for (var sp : server.getPlayerList().getPlayers()) UkProgress.get(sp).send();
		});
	}
}
