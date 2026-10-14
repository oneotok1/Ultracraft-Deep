package dev.ultracraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Boss Music & Intros: each boss's song, and (for the bosses ULTRAKILL gives more than one) which entrance it makes.
 * Its song: (Default) is its own ULTRAKILL theme (V2: Versus, Minos Prime: Order...), Off leaves the
 * Fight Music as chosen, or any song of ULTRAKILL's soundtrack. A boss's row opens the list of songs to pick from.
 */
final class UcBossMusicScreen extends OptionsSubScreen {
	/** A boss's other entrance: its id (UltraBridge's BossIntro.cs has it as key:id), name, and what happens. */
	record Alt(String id, String name, String about) {}

	/** The other entrances there are, by boss (each made of ULTRAKILL's own lines). */
	static final Map<String, List<Alt>> ALTS = Map.of(
		"gabriel2", List.of(new Alt("rematch", "Rematch",
			"6-2, the rematch: \"IS THIS WHAT I LOST TO!? YOU'RE GETTING RUSTY, MACHINE!\" ...then he draws his swords: \"My hands shall RELISH ENDING YOU... HERE AND NOW!\"")),
		"gabriel", List.of(new Alt("choice", "Your Choice Is Made",
			"3-2, after V1 didn't turn back: \"Your choice is made. As the righteous hand of the father, I shall rend you apart, and you will become inanimate once more.\"")),
		"minosprime", List.of(new Alt("prepare", "Prepare Thyself",
			"Quick: the soul shatters, he rises and cries \"PREPARE THYSELF!\"")),
		"sisyphusprime", List.of(new Alt("prison", "This Prison",
			"Quick: he falls out of the sky as he breaks free: \"This prison... To hold ME?\"")));

	UcBossMusicScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.literal("Boss Music & Intros"));
	}

	/** Its entrance's name ("Rematch"), or null for its own. */
	static String introLabel(String boss) {
		String pick = UltracraftConfig.bossIntroAlts.get(boss);
		if (pick == null || pick.equals("-")) return null;
		for (Alt a : ALTS.getOrDefault(boss, List.of())) if (a.id().equals(pick)) return a.name();
		return null;
	}

	/** "Default (Versus)", "Off", "Order". */
	static String label(String boss) {
		String pick = UltracraftConfig.bossSongs.getOrDefault(boss, "default");
		if (pick.equals("default")) {
			String d = UcThemes.defaultSong(boss);
			return "(Default)" + (d != null ? " " + UcMusicScreen.name(d) : "");
		}
		return pick.equals("off") ? "Off (Fight Music)" : UcMusicScreen.name(pick);
	}

	@Override
	protected void addOptions() {
		List<AbstractWidget> row = new ArrayList<>();
		for (UkBosses.Boss b : UkBosses.ROSTER) {
			String intro = introLabel(b.key());
			Button btn = Button.builder(Component.literal(b.name() + ": " + label(b.key()) + (intro != null ? " | " + intro : "")), x -> minecraft.setScreen(new Pick(this, b)))
				.tooltip(Tooltip.create(Component.literal("The song that plays when " + b.name() + " comes. (Default): its own ULTRAKILL theme."
					+ (ALTS.containsKey(b.key()) ? " It has more than one entrance, too." : "")))).build();
			row.add(btn);
		}
		for (int i = 0; i < row.size(); i += 2) list.addSmall(row.get(i), i + 1 < row.size() ? row.get(i + 1) : null);
	}

	/** One boss's song, from (Default), Off, and ULTRAKILL's soundtrack grouped by where it plays. */
	static final class Pick extends OptionsSubScreen {
		private final UkBosses.Boss boss;

		Pick(Screen last, UkBosses.Boss boss) {
			super(last, Minecraft.getInstance().options, Component.literal(boss.name() + ": Music & Intro"));
			this.boss = boss;
		}

		@Override
		protected void addOptions() {
			List<Alt> alts = ALTS.get(boss.key());
			if (alts != null) {
				list.addHeader(Component.literal("Entrance"));
				List<AbstractWidget> row = new ArrayList<>();
				String auto = UltracraftConfig.BOSS_INTRO_DEFAULTS.get(boss.key());
				if (auto != null) {
					String autoName = alts.stream().filter(a -> a.id().equals(auto)).map(Alt::name).findFirst().orElse(auto);
					row.add(intro(null, "(Default) Auto", "Its own entrance the first time you meet it (until you've beaten it), then " + autoName + " in every rematch."));
					row.add(intro("-", "Level Intro", "Always its own entrance, as ULTRAKILL has it (Boss Intros: Short or Full)."));
				} else {
					row.add(intro(null, "(Default)", "Its own entrance, as ULTRAKILL has it (Boss Intros: Short or Full)."));
				}
				for (Alt a : alts) row.add(intro(a.id(), a.name(), a.about()));
				list.addSmall(row);
				list.addHeader(Component.literal("Music"));
			}
			String d = UcThemes.defaultSong(boss.key());
			list.addSmall(choice("default", "(Default)" + (d != null ? " " + UcMusicScreen.name(d) : ""), "Its own ULTRAKILL theme."),
				choice("off", "Off (Fight Music)", "No theme of its own: the Fight Music plays as chosen."));
			list.addSmall(UcMusicScreen.folderButton(), null);
			if (UcMusicScreen.SONGS.isEmpty()) list.addHeader(Component.literal("ULTRAKILL hasn't listed its soundtrack yet: start it (or open a world) and come back."));
			Map<String, List<String>> groups = new LinkedHashMap<>();
			for (String k : UcMusicScreen.all()) groups.computeIfAbsent(UcMusicScreen.group(k), g -> new ArrayList<>()).add(k);
			for (var e : groups.entrySet()) {
				list.addHeader(Component.literal(e.getKey()));
				List<AbstractWidget> row = new ArrayList<>();
				for (String k : e.getValue()) row.add(choice(k, UcMusicScreen.name(k), null));
				list.addSmall(row);
			}
		}

		private Button intro(String id, String text, String tooltip) {
			String now = UltracraftConfig.bossIntroAlts.get(boss.key());
			boolean picked = id == null ? now == null : id.equals(now);
			Button b = Button.builder(Component.literal((picked ? "> " : "") + text + (picked ? " <" : "")), btn -> {
				if (id == null) UltracraftConfig.bossIntroAlts.remove(boss.key());
				else UltracraftConfig.bossIntroAlts.put(boss.key(), id);
				UltracraftConfig.save();
				minecraft.setScreen(new Pick(lastScreen, boss));
			}).build();
			b.setTooltip(Tooltip.create(Component.literal(tooltip)));
			return b;
		}

		private Button choice(String key, String text, String tooltip) {
			boolean picked = UltracraftConfig.bossSongs.getOrDefault(boss.key(), "default").equals(key);
			Button b = Button.builder(Component.literal((picked ? "> " : "") + text + (picked ? " <" : "")), btn -> {
				if (key.equals("default")) UltracraftConfig.bossSongs.remove(boss.key());
				else UltracraftConfig.bossSongs.put(boss.key(), key);
				UltracraftConfig.save();
				minecraft.setScreen(new Pick(lastScreen, boss));
			}).build();
			if (tooltip != null) b.setTooltip(Tooltip.create(Component.literal(tooltip)));
			return b;
		}

		@Override
		public void onClose() {
			// back to where it was opened from: the bosses (their labels up to date), or a boss's Boss Setup page
			if (lastScreen instanceof UcBossMusicScreen s) minecraft.setScreen(new UcBossMusicScreen(s.lastScreen));
			else minecraft.setScreen(lastScreen);
		}
	}
}
