package dev.ultracraft;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * ULTRAKILL's controls, rebound from Minecraft: click an action, press a key (or a mouse button). ULTRAKILL takes
 * the new key as a runtime override of its own binding (UKBIND), never written into its own settings.
 */
public final class UcKeybindsScreen extends OptionsSubScreen {
	/** An ULTRAKILL action ("Map/Action" or "Map/Action/part" for WASD), its name here, and its default key. */
	record Bind(String action, String label, int def) {}

	/** Mouse buttons are stored as -1 - button (left -1, right -2, middle -3). */
	static final List<Bind> BINDS = List.of(
		new Bind("Movement/Move/up", "Forward", GLFW.GLFW_KEY_W),
		new Bind("Movement/Move/down", "Back", GLFW.GLFW_KEY_S),
		new Bind("Movement/Move/left", "Left", GLFW.GLFW_KEY_A),
		new Bind("Movement/Move/right", "Right", GLFW.GLFW_KEY_D),
		new Bind("Movement/Jump", "Jump", GLFW.GLFW_KEY_SPACE),
		new Bind("Movement/Dodge", "Dash", GLFW.GLFW_KEY_LEFT_SHIFT),
		new Bind("Movement/Slide", "Slide / Slam", GLFW.GLFW_KEY_LEFT_CONTROL),
		new Bind("Weapon/PrimaryFire", "Fire", -1),
		new Bind("Weapon/SecondaryFire", "Alt Fire", -2),
		new Bind("Fist/Punch", "Punch", GLFW.GLFW_KEY_F),
		new Bind("Fist/ChangeFist", "Change Arm", GLFW.GLFW_KEY_G),
		new Bind("Fist/Hook", "Whiplash", GLFW.GLFW_KEY_R),
		new Bind("Weapon/NextVariation", "Next Variation", GLFW.GLFW_KEY_E),
		new Bind("Weapon/LastUsedWeapon", "Last Weapon", GLFW.GLFW_KEY_Q),
		new Bind("Weapon/Revolver", "Revolver", GLFW.GLFW_KEY_1),
		new Bind("Weapon/Shotgun", "Shotgun", GLFW.GLFW_KEY_2),
		new Bind("Weapon/Nailgun", "Nailgun", GLFW.GLFW_KEY_3),
		new Bind("Weapon/Railcannon", "Railcannon", GLFW.GLFW_KEY_4),
		new Bind("Weapon/RocketLauncher", "Rocket Launcher", GLFW.GLFW_KEY_5),
		// one key per weapon (unbound until set): "Gun/<slot>/<colour>[/<version>]" straight to that weapon, which
		// ULTRAKILL knows by its colour (blue, green, red). The three weapons the shop sells an alternate of get a key
		// for each version (0 standard, 1 alternate), so a key always hands V1 that exact weapon whatever the shop has
		// equipped; the railcannon and rocket launcher have no alternate and keep one key each.
		gun(1, 0, "Revolver: Piercer"), gun(1, 1, "Revolver: Marksman"), gun(1, 2, "Revolver: Sharpshooter"),
		alt(1, 0, "Revolver: Piercer (Alt)"), alt(1, 1, "Revolver: Marksman (Alt)"), alt(1, 2, "Revolver: Sharpshooter (Alt)"),
		gun(2, 0, "Shotgun: Core Eject"), gun(2, 1, "Shotgun: Pump Charge"), gun(2, 2, "Shotgun: Sawed-On"),
		alt(2, 0, "Shotgun: Core Eject (Alt)"), alt(2, 1, "Shotgun: Pump Charge (Alt)"), alt(2, 2, "Shotgun: Sawed-On (Alt)"),
		gun(3, 0, "Nailgun: Attractor"), gun(3, 1, "Nailgun: Overheat"), gun(3, 2, "Nailgun: JumpStart"),
		alt(3, 0, "Nailgun: Attractor (Alt)"), alt(3, 1, "Nailgun: Overheat (Alt)"), alt(3, 2, "Nailgun: JumpStart (Alt)"),
		gun(4, 0, "Railcannon: Electric"), gun(4, 1, "Railcannon: Screwdriver"), gun(4, 2, "Railcannon: Malicious"),
		gun(5, 0, "Rockets: Freezeframe"), gun(5, 1, "Rockets: S.R.S. Cannon"), gun(5, 2, "Rockets: Firestarter"));

	/** No key. */
	static final int UNBOUND = Integer.MIN_VALUE;

	/** The standard version of the weapon in that slot of that colour. A key always hands V1 exactly the weapon it
	 * names: the shop's own choice stays as it was, and only decides what V1 carries without a key pressed. */
	private static Bind gun(int slot, int colour, String label) {
		return new Bind("Gun/" + slot + "/" + colour + (slot <= 3 ? "/0" : ""), label, UNBOUND);
	}

	/** The alternate of it. Only the three weapons the shop sells an alternate of have one. */
	private static Bind alt(int slot, int colour, String label) {
		return new Bind("Gun/" + slot + "/" + colour + "/1", label, UNBOUND);
	}

	/** The per-weapon keys aren't ULTRAKILL's own controls: Minecraft watches them and sends GUN (pollGunKeys). */
	static boolean isGun(String action) {
		return action.startsWith("Gun/");
	}


	/** One cell per gun key, in BINDS order. */
	private static final boolean[] gunDown = new boolean[24];

	/** Guns out, nothing open: a weapon's key switches straight to it (ULTRAKILL: GUN slot colour). */
	static void pollGunKeys(Minecraft mc, boolean ingame) {
		long win = mc.getWindow().handle();
		int g = 0;
		for (Bind b : BINDS) {
			if (!isGun(b.action)) continue;
			int k = key(b);
			boolean down = ingame && k != UNBOUND && (k < 0 ? GLFW.glfwGetMouseButton(win, -1 - k) == GLFW.GLFW_PRESS : GLFW.glfwGetKey(win, k) == GLFW.GLFW_PRESS);
			if (down && !gunDown[g]) {
				String[] a = b.action.split("/");
				Ultracraft.gunKey(mc);
				// GUN slot colour [version 1 standard, 2 alternate]: a binding made before the version existed has
				// none, and those three weapons' keys mean the standard one
				int version = a.length > 3 ? (a[3].equals("0") ? 1 : 2) : (Integer.parseInt(a[1]) <= 3 ? 1 : -1);
				UkLink.send("GUN " + a[1] + " " + a[2] + " " + version);
			}
			gunDown[g++] = down;
		}
	}

	private static boolean sneakDown;

	/**
	 * Sneak is held, not toggled: ULTRAKILL slows the walk and holds V1 at the ledge while it is down. The key is
	 * Minecraft's own (see Ultracraft.sneakKey), so it is rebound in Minecraft's controls like any other.
	 */
	static void pollSneak(KeyMapping sneak, boolean ingame) {
		if (sneak == null) return;
		boolean down = ingame && sneak.isDown();
		if (down == sneakDown) return;
		sneakDown = down;
		UkLink.send("SNEAK " + (down ? 1 : 0));
	}

	/** The heading a key is listed under: what it does, not which of ULTRAKILL's maps it lives in. */
	static String group(String action) {
		if (isGun(action)) return "Gun Keys";
		if (action.startsWith("Movement/")) return "Movement";
		if (action.startsWith("Fist/")) return "Arms";
		return "Weapons";
	}

	private final List<Button> buttons = new ArrayList<>();
	/** The action waiting for a key, or -1. */
	private int waiting = -1;

	public UcKeybindsScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.translatable("ULTRAKILL Controls"));
		migrateBinds();
	}

	/** Minecraft's names ("key.keyboard.t", "key.mouse.left") of the keys rebound to ULTRAKILL. */
	public static java.util.Set<String> reboundKeys() {
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (int code : UltracraftConfig.ukBinds.values()) {
			if (code == UNBOUND) continue;
			keys.add(code < 0 ? InputConstants.Type.MOUSE.getOrCreate(-1 - code).getName() : InputConstants.Type.KEYSYM.getOrCreate(code).getName());
		}
		return keys;
	}

	static int key(Bind b) {
		return UltracraftConfig.ukBinds.getOrDefault(b.action, b.def);
	}

	/** The name a gun key had before the keys carried a version ("Gun/1/0"), or null. A binding under it meant the
	 * standard version then and means it now, so it moves across once (see migrateBinds) instead of being read as a
	 * fallback - a fallback would keep a cleared key alive. */
	private static String legacyName(String action) {
		if (!action.startsWith("Gun/") || !action.endsWith("/0")) return null;
		String old = action.substring(0, action.length() - 2);
		// only the names that existed then: the weapons with no alternate had no version suffix
		return old.equals("Gun/1/0") || old.equals("Gun/1/1") || old.equals("Gun/1/2")
			|| old.equals("Gun/2/0") || old.equals("Gun/2/1") || old.equals("Gun/2/2")
			|| old.equals("Gun/3/0") || old.equals("Gun/3/1") || old.equals("Gun/3/2") ? old : null;
	}

	/** Gun keys bound before the version existed move to their versioned name, so nothing reads the old one again. */
	private static void migrateBinds() {
		for (Bind b : BINDS) {
			String old = legacyName(b.action);
			if (old == null) continue;
			Integer code = UltracraftConfig.ukBinds.remove(old);
			if (code != null && !UltracraftConfig.ukBinds.containsKey(b.action)) UltracraftConfig.ukBinds.put(b.action, code);
		}
	}

	static Component keyName(int code) {
		if (code == UNBOUND) return Component.literal("-").withStyle(ChatFormatting.DARK_GRAY);
		if (code < 0) return Component.literal(switch (-1 - code) {
			case 0 -> "Left Click";
			case 1 -> "Right Click";
			case 2 -> "Middle Click";
			default -> (-1 - code) == 3 ? "Mouse 4 (back)" : (-1 - code) == 4 ? "Mouse 5 (forward)" : "Mouse " + (-code);
		});
		return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName();
	}

	@Override
	protected void addOptions() {
		buttons.clear();
		// grouped by what the key does: a heading per group, then a few keys to a row (one row of them all runs
		// off the screen, and the ones past the edge can't be reached)
		String open = null;
		List<AbstractWidget> row = new ArrayList<>();
		for (int i = 0; i < BINDS.size(); i++) {
			Bind bind = BINDS.get(i);
			String group = group(bind.action);
			if (!group.equals(open)) {
				if (!row.isEmpty()) list.addSmall(row);
				row = new ArrayList<>();
				list.addHeader(Component.translatable(group));
				open = group;
			}
			int index = i;
			Button b = Button.builder(label(i), btn -> {
				waiting = index;
				refresh();
			}).width(150).build();
			buttons.add(b);
			row.add(b);
			if (row.size() == PER_ROW) {
				list.addSmall(row);
				row = new ArrayList<>();
			}
		}
		row.add(Button.builder(Component.translatable("Reset All"), btn -> {
			UltracraftConfig.ukBinds.clear();
			for (Bind bind : BINDS) if (!isGun(bind.action)) UkLink.send("UKBIND " + bind.action + " -");
			waiting = -1;
			refresh();
		}).width(150).build());
		list.addSmall(row);
	}

	/** How many keys a row of the page holds. */
	private static final int PER_ROW = 3;

	private Component label(int i) {
		Bind b = BINDS.get(i);
		if (i == waiting) return Component.translatable(b.label).copy().append(": > press a key <").withStyle(ChatFormatting.YELLOW);
		int k = key(b);
		var c = Component.translatable(b.label).copy().append(": ").append(keyName(k));
		return k != b.def ? c.withStyle(ChatFormatting.GOLD) : c;
	}

	private void refresh() {
		for (int i = 0; i < buttons.size(); i++) buttons.get(i).setMessage(label(i));
	}

	private void set(int code) {
		Bind b = BINDS.get(waiting);
		if (code == b.def) UltracraftConfig.ukBinds.remove(b.action);
		else UltracraftConfig.ukBinds.put(b.action, code);
		if (!isGun(b.action)) UkLink.send("UKBIND " + b.action + " " + code);
		waiting = -1;
		refresh();
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (waiting >= 0) {
			// Esc cancels
			if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
				// Esc: cancels, or on a key Minecraft owns, unbinds it
				if (isGun(BINDS.get(waiting).action)) UltracraftConfig.ukBinds.remove(BINDS.get(waiting).action);
				waiting = -1;
				refresh();
			} else {
				set(e.key());
			}
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (waiting >= 0 && e.button() <= 4) {
			set(-1 - e.button());
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public void removed() {
		super.removed();
		UltracraftConfig.save();
	}
}
