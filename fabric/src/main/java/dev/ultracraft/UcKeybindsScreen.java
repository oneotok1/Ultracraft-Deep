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
		// one key per weapon (unbound until set): "Gun/<slot>/<colour>" straight to that variant, which ULTRAKILL
		// knows by its colour (blue, green, red); the Alternate versions share their colour
		gun(1, 0, "Revolver: Piercer"), gun(1, 1, "Revolver: Marksman"), gun(1, 2, "Revolver: Sharpshooter"),
		gun(2, 0, "Shotgun: Core Eject"), gun(2, 1, "Shotgun: Pump Charge"), gun(2, 2, "Shotgun: Sawed-On"),
		gun(3, 0, "Nailgun: Attractor"), gun(3, 1, "Nailgun: Overheat"), gun(3, 2, "Nailgun: JumpStart"),
		gun(4, 0, "Railcannon: Electric"), gun(4, 1, "Railcannon: Screwdriver"), gun(4, 2, "Railcannon: Malicious"),
		gun(5, 0, "Rockets: Freezeframe"), gun(5, 1, "Rockets: S.R.S. Cannon"), gun(5, 2, "Rockets: Firestarter"));

	/** No key. */
	static final int UNBOUND = Integer.MIN_VALUE;

	private static Bind gun(int slot, int colour, String label) {
		return new Bind("Gun/" + slot + "/" + colour, label, UNBOUND);
	}

	/** The per-weapon keys aren't ULTRAKILL's own controls: Minecraft watches them and sends GUN (pollGunKeys). */
	static boolean isGun(String action) {
		return action.startsWith("Gun/");
	}

	private static final boolean[] gunDown = new boolean[16];

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
				UkLink.send("GUN " + a[1] + " " + a[2]);
			}
			gunDown[g++] = down;
		}
	}

	private final List<Button> buttons = new ArrayList<>();
	/** The action waiting for a key, or -1. */
	private int waiting = -1;

	public UcKeybindsScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.literal("ULTRAKILL Controls"));
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

	static Component keyName(int code) {
		if (code == UNBOUND) return Component.literal("-").withStyle(ChatFormatting.DARK_GRAY);
		if (code < 0) return Component.literal(switch (-1 - code) {
			case 0 -> "Left Click";
			case 1 -> "Right Click";
			case 2 -> "Middle Click";
			default -> "Mouse " + (-code);
		});
		return InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName();
	}

	@Override
	protected void addOptions() {
		buttons.clear();
		List<AbstractWidget> row = new ArrayList<>();
		for (int i = 0; i < BINDS.size(); i++) {
			int index = i;
			Button b = Button.builder(label(i), btn -> {
				waiting = index;
				refresh();
			}).width(150).build();
			buttons.add(b);
			row.add(b);
		}
		row.add(Button.builder(Component.literal("Reset All"), btn -> {
			UltracraftConfig.ukBinds.clear();
			for (Bind bind : BINDS) if (!isGun(bind.action)) UkLink.send("UKBIND " + bind.action + " -");
			waiting = -1;
			refresh();
		}).width(150).build());
		list.addSmall(row);
	}

	private Component label(int i) {
		Bind b = BINDS.get(i);
		if (i == waiting) return Component.literal(b.label + ": > press a key <").withStyle(ChatFormatting.YELLOW);
		int k = key(b);
		var c = Component.literal(b.label + ": ").append(keyName(k));
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
				// Esc: cancels, or on a weapon's key, unbinds it
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
		if (waiting >= 0 && e.button() <= 2) {
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
