package dev.ultracraft;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Which of ULTRAKILL's looks the Cyber Grind may take on, one at a time, grouped by layer. */
public final class GrindLooksScreen extends GrindFilterScreen {
	public GrindLooksScreen(Screen last) {
		super(last, Component.translatable("Cyber Grind Looks"));
	}

	@Override
	protected void fill() {
		for (String layer : GrindStyleRules.LAYERS) {
			List<String> of = new ArrayList<>();
			for (int i = 0; i < GrindStyleRules.lookCount(); i++)
				if (GrindStyleRules.lookLayer(i).equals(layer)) of.add(Integer.toString(i));
			if (!of.isEmpty()) layers.put(layer, of);
		}
	}

	@Override
	protected boolean on(String layer, String entry) {
		return GrindStyleRules.lookOn(Integer.parseInt(entry));
	}

	@Override
	protected Component label(String layer, String entry) {
		return Component.literal(GrindStyleRules.lookTitle(Integer.parseInt(entry)));
	}

	@Override
	protected Component describe(String layer, String entry) {
		return Component.translatable("A look the Grind's arena is built in.");
	}

	@Override
	protected void setOn(String layer, String entry, boolean on) {
		GrindStyleRules.setLook(Integer.parseInt(entry), on);
	}

	@Override
	protected boolean layerOn(String layer) {
		for (int i = 0; i < GrindStyleRules.lookCount(); i++)
			if (GrindStyleRules.lookLayer(i).equals(layer) && !GrindStyleRules.lookOn(i)) return false;
		return true;
	}

	@Override
	protected void setLayer(String layer, boolean on) {
		GrindStyleRules.setLayer(layer, on);
	}

	@Override
	protected void defaults() {
		UltracraftConfig.grindLooks.clear();
		UltracraftConfig.grindLayers.clear();
	}
}
