package dev.ultracraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Which of ULTRAKILL's songs plays in fights: off, a random one each fight, or one picked from its soundtrack (the
 * list ULTRAKILL's Cyber Grind jukebox has, sent over as SONGS), grouped by where it plays in ULTRAKILL; or one of the
 * player's own (mp3, ogg or wav files in the ultracraft/music folder: Your Music), which ULTRAKILL plays the same way.
 */
final class UcMusicScreen extends OptionsSubScreen {
	/** ULTRAKILL's soundtrack: song keys ("Levels/Act 1/Limbo/Versus.asset"), as its catalog has them (SONGS). */
	static final List<String> SONGS = new ArrayList<>();

	UcMusicScreen(Screen last) {
		super(last, Minecraft.getInstance().options, Component.translatable("Fight Music"));
	}

	/** The player's own songs: keys "Your Music/<file>". */
	static final String OWN = "Your Music/";

	/** ultracraft/music in the game folder (made if missing). */
	static Path ownDir() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("ultracraft").resolve("music");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	/** The song files in it. */
	static List<String> own() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> files = Files.list(ownDir())) {
			files.map(f -> f.getFileName().toString()).filter(n -> {
				String l = n.toLowerCase(Locale.ROOT);
				return l.endsWith(".mp3") || l.endsWith(".ogg") || l.endsWith(".wav");
			}).sorted(String.CASE_INSENSITIVE_ORDER).forEach(n -> out.add(OWN + n));
		} catch (IOException ignored) {
		}
		return out;
	}

	/** Every song to pick from: the player's own first, then ULTRAKILL's soundtrack. */
	static List<String> all() {
		List<String> out = own();
		out.addAll(SONGS);
		return out;
	}

	/** A song as ULTRAKILL is told it: one of the player's own as file:<its path>. */
	static String wire(String key) {
		if (key == null || !key.startsWith(OWN)) return key;
		return "file:" + ownDir().resolve(key.substring(OWN.length())).toAbsolutePath();
	}

	/** A button that opens the Your Music folder. */
	static Button folderButton() {
		return Button.builder(Component.translatable("Open Your Music Folder"), b -> Util.getPlatform().openPath(ownDir()))
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("Put your own songs here (mp3, ogg or wav), then come back: they're listed under Your Music, for fights and bosses alike.")))
			.build();
	}

	/** SONGS key;key;...: ULTRAKILL's soundtrack. */
	static void songs(String data) {
		SONGS.clear();
		for (String k : data.split(";")) if (!k.isBlank()) SONGS.add(k.trim());
	}

	/** A song's name from its key ("Levels/Act 1/Limbo/Versus.asset": "Versus"). */
	static String name(String key) {
		if (key.equals("off")) return "Off";
		if (key.equals("random")) return "Random";
		String n = key.substring(key.lastIndexOf('/') + 1);
		if (key.startsWith(OWN)) {
			int dot = n.lastIndexOf('.');
			return dot > 0 ? n.substring(0, dot) : n;
		}
		return n.endsWith(".asset") ? n.substring(0, n.length() - 6) : n;
	}

	/** Where it plays ("Levels/Act 1/Limbo/Versus.asset": "Act 1: Limbo"). */
	static String group(String key) {
		String[] parts = key.split("/");
		if (parts.length >= 4 && parts[0].equals("Levels")) return parts[1] + ": " + parts[2];
		return parts.length >= 2 ? parts[parts.length - 2] : "Other";
	}

	@Override
	protected void addOptions() {
		list.addSmall(choice("off", "No ULTRAKILL music in fights."), choice("random", "A different ULTRAKILL song each fight."));
		list.addSmall(folderButton(), null);
		if (SONGS.isEmpty()) list.addHeader(Component.translatable("ULTRAKILL hasn't listed its soundtrack yet: start it (or open a world) and come back."));
		Map<String, List<String>> groups = new LinkedHashMap<>();
		for (String k : all()) groups.computeIfAbsent(group(k), g -> new ArrayList<>()).add(k);
		for (var e : groups.entrySet()) {
			list.addHeader(Component.translatable(e.getKey()));
			List<AbstractWidget> row = new ArrayList<>();
			for (String k : e.getValue()) row.add(choice(k, null));
			list.addSmall(row);
		}
	}

	private Button choice(String key, String tooltip) {
		boolean picked = UltracraftConfig.fightMusic.equals(key);
		Button b = Button.builder(Component.literal((picked ? "> " : "") + name(key) + (picked ? " <" : "")), btn -> {
			UltracraftConfig.fightMusic = key;
			UltracraftConfig.sendMusic();
			UltracraftConfig.save();
			// the list again, with the new pick marked
			minecraft.setScreen(new UcMusicScreen(lastScreen));
		}).build();
		if (tooltip != null) b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(tooltip)));
		return b;
	}

	/** A short label for the settings page ("Random", "Versus"). */
	static String current() {
		return name(UltracraftConfig.fightMusic);
	}
}
