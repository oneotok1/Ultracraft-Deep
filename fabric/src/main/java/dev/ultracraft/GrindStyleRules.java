package dev.ultracraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Cyber Grind's looks and its skies: which of them a run may take on.
 *
 * <p>Both are the same shape as the Mob Damage Filter: everything is on unless it is turned off, an entry of its own
 * beats its layer's, and an empty result falls back to everything rather than to nothing (a run with no look or no
 * sky would be a run without the thing this is meant to choose).
 *
 * <p>The looks are ULTRAKILL's layers as the grid dresses itself in them ({@link GrindArenas#ARENAS}); the skies are
 * ULTRAKILL's own skybox materials, which the bridge loads by address, so any of them can be had in any layer.
 */
public final class GrindStyleRules {
	/** A sky ULTRAKILL ships, by the layer its default use belongs to. */
	public record Sky(String address, String layer, String name) {}

	private GrindStyleRules() {}

	// ------------------------------------------------------------------ the looks

	/** How many looks the grid has. */
	public static int lookCount() {
		return GrindArenas.ARENAS.size();
	}

	public static String lookTitle(int index) {
		return GrindArenas.def(index).title();
	}

	/** The layer a look is after, as the skies are grouped by it. */
	public static String lookLayer(int index) {
		return GrindArenas.def(index).layer();
	}

	/** Whether a run may take on this look: its own entry, else its layer's, else on. */
	public static boolean lookOn(int index) {
		Boolean own = UltracraftConfig.grindLooks.get(lookKey(index));
		if (own != null) return own;
		return layerOn(lookLayer(index));
	}

	public static void setLook(int index, boolean on) {
		UltracraftConfig.grindLooks.put(lookKey(index), on);
	}

	public static String lookKey(int index) {
		return Integer.toString(index);
	}

	/** Every look a run may take on, in their own order. Never empty: all of them if none would be left. */
	public static List<Integer> looksOn() {
		List<Integer> on = new ArrayList<>();
		for (int i = 0; i < lookCount(); i++) if (lookOn(i)) on.add(i);
		if (on.isEmpty()) for (int i = 0; i < lookCount(); i++) on.add(i);
		return on;
	}

	// ------------------------------------------------------------------ the layers

	/** The layers ULTRAKILL's looks and skies are grouped by, in its own order. */
	public static final List<String> LAYERS = List.of(
		"CYBER GRIND", "PRELUDE", "LIMBO", "LUST", "GLUTTONY", "GREED", "WRATH", "HERESY",
		"VIOLENCE", "FRAUD", "TREACHERY", "PRIME SANCTUM", "HEAVEN");

	/** Whether a layer's looks and skies are on: its own entry, else on. */
	public static boolean layerOn(String layer) {
		Boolean own = UltracraftConfig.grindLayers.get(layer);
		return own == null || own;
	}

	public static void setLayer(String layer, boolean on) {
		UltracraftConfig.grindLayers.put(layer, on);
	}

	// ------------------------------------------------------------------ the skies

	/**
	 * ULTRAKILL's skybox materials (its Addressables' assets, read from its catalog): the address the bridge loads,
	 * the layer whose look it belongs with, and the name it goes by. Everything under Assets/Materials that is a sky
	 * and is not one of its own test or credit scenes.
	 */
	public static final List<Sky> SKIES = List.of(
		sky("Assets/Materials/Skyboxes/EndlessSky.mat", "CYBER GRIND"),
		sky("Assets/Materials/Skyboxes/EndlessSky 1.mat", "CYBER GRIND"),
		sky("Assets/Materials/Skyboxes/RedSky.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/RedSky2.mat", "GLUTTONY"),
		sky("Assets/Materials/Skyboxes/RedSky3.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/DuskSky.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/DuskSky Fog Variant.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/DuskSky2.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/DuskSky2 1.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/DuskSky3.mat", "PRELUDE"),
		sky("Assets/Materials/Skyboxes/SkyBlue.mat", "LIMBO"),
		sky("Assets/Materials/Skyboxes/DaySky.mat", "LIMBO"),
		sky("Assets/Materials/Skyboxes/DaySky 1.mat", "LIMBO"),
		sky("Assets/Materials/Skyboxes/DaySky 2.mat", "LIMBO"),
		sky("Assets/Materials/Skyboxes/EveningSky.mat", "LIMBO"),
		sky("Assets/Materials/Skyboxes/EveningSky 1.mat", "LIMBO"),
		sky("Assets/Materials/Skyboxes/EveningSky 3.mat", "HEAVEN"),
		sky("Assets/Materials/Skyboxes/EveningSky 4.mat", "HEAVEN"),
		sky("Assets/Materials/LustSky.mat", "LUST"),
		sky("Assets/Materials/LustSky 1.mat", "LUST"),
		sky("Assets/Materials/LustSky 2.mat", "LUST"),
		sky("Assets/Materials/Skyboxes/GreedSky.mat", "GREED"),
		sky("Assets/Materials/Skyboxes/GreedSky2.mat", "GREED"),
		sky("Assets/Materials/Skyboxes/GreedSky3.mat", "GREED"),
		sky("Assets/Materials/Skyboxes/GreedSky3 1.mat", "GREED"),
		sky("Assets/Materials/Skyboxes/GreedSky4.mat", "GREED"),
		sky("Assets/Materials/Skyboxes/GreedSky5.mat", "GREED"),
		sky("Assets/Materials/Environment/Layer 5/OvercastSky.mat", "WRATH"),
		sky("Assets/Materials/Environment/Layer 5/OvercastSky 1.mat", "WRATH"),
		sky("Assets/Materials/Skyboxes/FogCoveredSky.mat", "WRATH"),
		sky("Assets/Materials/Skyboxes/NightSky2.mat", "WRATH"),
		sky("Assets/Materials/Skyboxes/NightSky1.mat", "HERESY"),
		sky("Assets/Materials/Skyboxes/NightSky3.mat", "HERESY"),
		// "ViolenceSky 2" is left out on purpose: it is the one sky ULTRAKILL ships on psx/unlit/additive/nofog
		// (one-to-one additive blending), which never covers what is already in the frame - anything moving in front
		// of it leaves a bright ghost behind. It was taken out of GrindArenas for that reason once already; adding it
		// here puts the ghosts back.
		sky("Assets/Materials/Skyboxes/ViolenceSky.mat", "VIOLENCE"),
		sky("Assets/Materials/Skyboxes/ViolenceSky 1.mat", "VIOLENCE"),
		sky("Assets/Materials/Skyboxes/ViolenceSky3.mat", "VIOLENCE"),
		sky("Assets/Materials/Skyboxes/ViolenceSky4.mat", "VIOLENCE"),
		sky("Assets/Materials/Skyboxes/FraudCity_SkyMat.mat", "FRAUD"),
		sky("Assets/Materials/Skyboxes/FraudCity_SkyMat_Night.mat", "FRAUD"),
		sky("Assets/Materials/Skyboxes/SpaceSky.mat", "TREACHERY"),
		sky("Assets/Materials/Skyboxes/NightSky4.mat", "TREACHERY"),
		sky("Assets/Materials/Skyboxes/NightSky5.mat", "PRIME SANCTUM"),
		sky("Assets/Materials/Skyboxes/DawnSky.mat", "HEAVEN"),
		sky("Assets/Materials/Skyboxes/DawnSky 1.mat", "HEAVEN"),
		sky("Assets/Materials/Skyboxes/Skybox.mat", "HEAVEN"));

	private static Sky sky(String address, String layer) {
		String file = address.substring(address.lastIndexOf('/') + 1);
		return new Sky(address, layer, file.endsWith(".mat") ? file.substring(0, file.length() - 4) : file);
	}

	/** The address a sky goes by (the key its own setting is stored under). */
	public static String skyKey(Sky sky) {
		return sky.address();
	}

	/** Whether a sky may be picked: its own entry, else its layer's, else on. */
	public static boolean skyOn(Sky sky) {
		Boolean own = UltracraftConfig.grindSkies.get(skyKey(sky));
		if (own != null) return own;
		return layerOn(sky.layer());
	}

	public static void setSky(Sky sky, boolean on) {
		UltracraftConfig.grindSkies.put(skyKey(sky), on);
	}

	/** The skies a run may pick from, narrowed to a layer's when it is given. Never empty. */
	public static List<Sky> skiesOn(String layer) {
		List<Sky> on = new ArrayList<>();
		for (Sky s : SKIES) if (skyOn(s) && (layer == null || s.layer().equals(layer))) on.add(s);
		if (on.isEmpty()) {
			// that layer has none left: any that is on at all, so a run still gets a sky
			for (Sky s : SKIES) if (skyOn(s)) on.add(s);
		}
		if (on.isEmpty()) on.addAll(SKIES);
		return on;
	}

	/** The skies of one layer, whatever their own settings say (for the list to show). */
	public static List<Sky> skiesOf(String layer) {
		List<Sky> on = new ArrayList<>();
		for (Sky s : SKIES) if (s.layer().equals(layer)) on.add(s);
		return on;
	}

	/** Every sky by the name it goes by, for a list to be built from. */
	public static Map<String, List<Sky>> byLayer() {
		Map<String, List<Sky>> all = new LinkedHashMap<>();
		for (String layer : LAYERS) {
			List<Sky> of = skiesOf(layer);
			if (!of.isEmpty()) all.put(layer, of);
		}
		return all;
	}

	/** Whether anything at all would be left for a layer to pick, as the screen shows it. */
	public static int onCount() {
		int n = 0;
		for (Sky s : SKIES) if (skyOn(s)) n++;
		return n;
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
