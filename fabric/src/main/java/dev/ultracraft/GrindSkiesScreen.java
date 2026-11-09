package dev.ultracraft;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Which of ULTRAKILL's skies the Cyber Grind may take on, one at a time, grouped by layer. */
public final class GrindSkiesScreen extends GrindFilterScreen {
	public GrindSkiesScreen(Screen last) {
		super(last, Component.translatable("Cyber Grind Skies"));
	}

	@Override
	protected void fill() {
		for (String layer : GrindStyleRules.LAYERS) {
			List<String> of = new ArrayList<>();
			for (GrindStyleRules.Sky sky : GrindStyleRules.skiesOf(layer)) of.add(sky.address());
			if (!of.isEmpty()) layers.put(layer, of);
		}
	}

	private static GrindStyleRules.Sky sky(String address) {
		for (GrindStyleRules.Sky s : GrindStyleRules.SKIES) if (s.address().equals(address)) return s;
		return null;
	}

	@Override
	protected boolean on(String layer, String entry) {
		GrindStyleRules.Sky s = sky(entry);
		return s != null && GrindStyleRules.skyOn(s);
	}

	@Override
	protected Component label(String layer, String entry) {
		GrindStyleRules.Sky s = sky(entry);
		return Component.literal(s == null ? entry : s.name());
	}

	@Override
	protected Component describe(String layer, String entry) {
		return Component.literal(entry);
	}

	@Override
	protected void setOn(String layer, String entry, boolean on) {
		GrindStyleRules.Sky s = sky(entry);
		if (s != null) GrindStyleRules.setSky(s, on);
	}

	@Override
	protected boolean layerOn(String layer) {
		for (GrindStyleRules.Sky s : GrindStyleRules.skiesOf(layer))
			if (!GrindStyleRules.skyOn(s)) return false;
		return true;
	}

	@Override
	protected void setLayer(String layer, boolean on) {
		GrindStyleRules.setLayer(layer, on);
	}

	@Override
	protected void defaults() {
		UltracraftConfig.grindSkies.clear();
		UltracraftConfig.grindLayers.clear();
	}
}
