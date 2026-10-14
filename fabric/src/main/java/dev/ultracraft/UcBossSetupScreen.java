package dev.ultracraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Cheats -> Boss Setup: every boss, each with its own page (BossSetup has what each option does). */
final class UcBossSetupScreen extends OptionsSubScreen {
	UcBossSetupScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.literal("Boss Setup"));
	}

	/** "In rotation, x1.5 health, practice" for its button. */
	static String summary(String key) {
		List<String> bits = new ArrayList<>();
		if (!BossSetup.inRotation(key)) bits.add("not in rotation");
		if (BossSetup.hasPhases(key) && BossSetup.flag(key, "phase2")) bits.add("phase 2");
		for (String[] m : new String[][] {{"health", "HP"}, {"speed", "speed"}, {"damage", "dmg"}}) {
			int t = BossSetup.tenths(key, m[0]);
			if (t != 10) bits.add(String.format(Locale.ROOT, "x%.1f %s", t / 10f, m[1]));
		}
		if (!BossSetup.traits(key).equals("random")) bits.add(BossSetup.traits(key).equals("none") ? "no traits" : "picked traits");
		if (BossSetup.practice(key)) bits.add("practice");
		return bits.isEmpty() ? "" : " (" + String.join(", ", bits) + ")";
	}

	@Override
	protected void addOptions() {
		List<AbstractWidget> row = new ArrayList<>();
		for (UkBosses.Boss b : UkBosses.ROSTER) {
			String s = summary(b.key());
			Button btn = Button.builder(Component.literal(b.name() + (s.isEmpty() ? "" : " *")), x -> minecraft.setScreen(new Page(this, b)))
				.tooltip(Tooltip.create(Component.literal(s.isEmpty() ? "As ULTRAKILL has it." : s.substring(2, s.length() - 1)))).build();
			row.add(btn);
		}
		for (int i = 0; i < row.size(); i += 2) list.addSmall(row.get(i), i + 1 < row.size() ? row.get(i + 1) : null);
	}

	/** One boss's page. */
	static final class Page extends OptionsSubScreen {
		private final UkBosses.Boss boss;

		Page(Screen last, UkBosses.Boss boss) {
			super(last, Minecraft.getInstance().options, Component.literal(boss.name()));
			this.boss = boss;
		}

		private OptionInstance<Boolean> bool(String caption, String tooltip, String opt) {
			String k = boss.key();
			return OptionInstance.createBoolean(caption, OptionInstance.cachedConstantTooltip(Component.literal(tooltip)), BossSetup.flag(k, opt),
				v -> BossSetup.flag(k, opt, v));
		}

		private OptionInstance<Integer> times(String caption, String tooltip, String opt, int min, int max) {
			String k = boss.key();
			return UcSettingsScreen.slider(caption, tooltip, min, max, BossSetup.tenths(k, opt), v -> String.format(Locale.ROOT, "x%.1f", v / 10f),
				v -> BossSetup.set(k, opt, Integer.toString(v), "10"));
		}

		@Override
		protected void addOptions() {
			String k = boss.key();
			// ---- whether and which
			list.addHeader(Component.literal("When it comes"));
			OptionInstance<Boolean> rotation = OptionInstance.createBoolean("In Rotation",
				OptionInstance.cachedConstantTooltip(Component.literal("It comes on its own, now and then. Off: only when you call it (Fight Now, /uc boss call).")),
				BossSetup.inRotation(k), v -> BossSetup.flag(k, "notInRotation", !v));
			OptionInstance<Boolean> practice = bool("Practice",
				"Beating it pays nothing and doesn't count as beaten (no P, loot or experience; your boss count and difficulty stay as they are).", "practice");
			list.addSmall(rotation, practice);
			// ---- how it fights
			list.addHeader(Component.literal("How it fights"));
			list.addSmall(times("Health", "How much damage it takes to beat (ULTRAKILL's own health modifier).", "health", 3, 40),
				times("Speed", "How fast it moves and attacks (ULTRAKILL's own speed modifier).", "speed", 5, 20));
			OptionInstance<?> phase = BossSetup.hasPhases(k) ? bool("Second Phase From the Start",
				k.startsWith("gabriel") ? "He starts in his second phase (\"Enough!\"), as ULTRAKILL has him past half health." : "He starts enraged, his second half (\"WEAK\"), flames and all.", "phase2") : null;
			OptionInstance<?> damage = times("Damage", "How hard it hits (ULTRAKILL's own damage modifier).", "damage", 3, 40);
			if (phase != null) list.addSmall(damage, phase);
			else list.addSmall(damage);
			// ---- its own
			List<OptionInstance<?>> own = new ArrayList<>();
			switch (k) {
				case "v2" -> own.add(bool("Retreats (1-4)", "As in 1-4: at a third of its health it's knocked down and flees through a flash of light. That's a win.", "retreat"));
				case "cerberus" -> own.add(bool("Wakes One at a Time (0-5)", "As in 0-5: one statue wakes; the second only once the first is down to half its health.", "stagger"));
				case "ferryman" -> own.add(bool("Takes a Coin (5-2)", "As in 5-2: throw a Marksman coin at him and he takes it and lets you pass. The fight is over, without its reward.", "bribe"));
				case "minosprime", "sisyphusprime" -> own.add(bool("Cyber Grind Version", "As the Cyber Grind has him: no entrance speech, no death scene, a quick death.", "grind"));
				default -> {
				}
			}
			if (!own.isEmpty()) {
				list.addHeader(Component.literal("As in its level"));
				list.addSmall(own.toArray(new OptionInstance<?>[0]));
			}
			// ---- traits
			list.addHeader(Component.literal("Traits"));
			String[] modes = {"random", "none", "pick"};
			String[] modeNames = {"Random", "None", "Picked"};
			int mode = java.util.Arrays.asList(modes).indexOf(BossSetup.traits(k));
			list.addSmall(UcSettingsScreen.slider("Traits", "Random: by the Trait Chance setting. None: never any. Picked: always the ones switched on below.",
				0, 2, Math.max(0, mode), v -> modeNames[v], v -> {
					BossSetup.set(k, "traits", modes[v], "random");
					minecraft.setScreen(new Page(lastScreen, boss));
				}));
			if (BossSetup.traits(k).equals("pick")) {
				List<OptionInstance<?>> ts = new ArrayList<>();
				for (UkBosses.Mod m : UkBosses.MODS) {
					String title = m.title().charAt(0) + m.title().substring(1).toLowerCase(Locale.ROOT);
					ts.add(OptionInstance.createBoolean(title, OptionInstance.noTooltip(), BossSetup.picked(k).contains(m.key()), v -> BossSetup.pick(k, m.key(), v)));
				}
				list.addSmall(ts.toArray(new OptionInstance<?>[0]));
			}
			// ---- its music and entrance, and a fight now
			list.addHeader(Component.literal(""));
			Button music = Button.builder(Component.literal("Music & Entrance..."), b -> minecraft.setScreen(new UcBossMusicScreen.Pick(this, boss)))
				.tooltip(Tooltip.create(Component.literal("Its song, and which entrance it makes."))).build();
			Button now = Button.builder(Component.literal("Fight Now"), b -> {
				minecraft.setScreen(null);
				if (minecraft.player != null) minecraft.player.connection.sendCommand("uc boss call " + k + " 3");
			}).tooltip(Tooltip.create(Component.literal("It comes for you in 3 seconds, set up as on this page (as V1, with no other boss around)."))).build();
			list.addSmall(music, now);
		}
	}
}
