package dev.ultracraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Boss Trait Filter: which of a boss's traits it may never be given at random.
 *
 * <p>One button per trait, lit for one that may still turn up and struck through for one left out. Only the random draw
 * is affected: a boss set to "Picked" in its own Boss Setup page still gets exactly the traits switched on there, and
 * the Trait Chance setting still decides how often a trait comes at all.
 */
public final class UcTraitFilterScreen extends Screen {
	private static final int BUTTON_W = 140;
	private static final int BUTTON_H = 20;
	private static final int GAP = 6;
	private static final int TOP = 60;
	private static final int BOTTOM = 40;

	private final Screen last;

	public UcTraitFilterScreen(Screen last) {
		super(Component.translatable("Boss Trait Filter"));
		this.last = last;
	}

	private static Component name(UkBosses.Mod m) {
		return Component.translatable("trait.ultracraft." + m.key());
	}

	private int columns() {
		return Math.max(1, (width - 40 + GAP) / (BUTTON_W + GAP));
	}

	@Override
	protected void init() {
		clearWidgets();
		int columns = columns();
		int x0 = (width - (columns * BUTTON_W + (columns - 1) * GAP)) / 2;
		for (int i = 0; i < UkBosses.MODS.size(); i++) {
			int x = x0 + (i % columns) * (BUTTON_W + GAP);
			int y = TOP + (i / columns) * (BUTTON_H + GAP);
			addRenderableWidget(button(UkBosses.MODS.get(i), x, y));
		}
		int by = height - BOTTOM - 4;
		// everything is on unless it is put on the list, so "all on" is also the way back to the defaults
		addRenderableWidget(Button.builder(Component.translatable("All On (default)"), b -> {
			UltracraftConfig.traitOff.clear();
			rebuild();
		}).bounds(x0, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Let a boss be given any trait again."))).build());
		addRenderableWidget(Button.builder(Component.translatable("All Off"), b -> {
			for (UkBosses.Mod m : UkBosses.MODS) UltracraftConfig.traitOff.put(m.key(), Boolean.TRUE);
			rebuild();
		}).bounds(x0 + 116, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Leave every trait out: a boss then comes with none at all."))).build());
		addRenderableWidget(Button.builder(Component.translatable("Done"), b -> onClose())
			.bounds(width - 20 - 110, by, 110, 20).build());
	}

	private Button button(UkBosses.Mod m, int x, int y) {
		boolean off = UltracraftConfig.traitOff.getOrDefault(m.key(), Boolean.FALSE);
		Component label = name(m);
		if (off) label = label.copy().withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.DARK_GRAY);
		Component tip = name(m).copy().append(Component.literal("\n")).append(Component.translatable(off
			? "Left out: a boss is never given this one at random."
			: "In: a boss may be given this one at random, at the Trait Chance setting."));
		return Button.builder(label, b -> {
			if (off) UltracraftConfig.traitOff.remove(m.key());
			else UltracraftConfig.traitOff.put(m.key(), Boolean.TRUE);
			rebuild();
		}).bounds(x, y, BUTTON_W, BUTTON_H).tooltip(Tooltip.create(tip)).build();
	}

	/** Coalesced: a press asks for one rebuild, not one each. */
	private boolean rebuildQueued;

	private void rebuild() {
		if (rebuildQueued) return;
		rebuildQueued = true;
		Minecraft.getInstance().execute(() -> {
			rebuildQueued = false;
			if (Minecraft.getInstance().screen == this) init();
		});
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawString(font, title, 20, 20, 0xFFFFFFFF);
		int off = 0;
		for (UkBosses.Mod m : UkBosses.MODS) if (UltracraftConfig.traitOff.getOrDefault(m.key(), Boolean.FALSE)) off++;
		Component status = off == 0 ? Component.translatable("Every trait may come")
			: Component.translatable("%s traits left out", off);
		graphics.drawString(font, status, 20, 38, 0xFFA0A0A0);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(last);
	}

	@Override
	public void removed() {
		UltracraftConfig.save();
		super.removed();
	}
}
