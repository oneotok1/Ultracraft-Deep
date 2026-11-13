package dev.ultracraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Enemy Spawn Filter: which of ULTRAKILL's enemies its spawn tables may draw.
 *
 * <p>One button per enemy, lit for one that may still turn up in the dark and dark and struck through for one left
 * out. The tables themselves are untouched: a banned enemy is simply never drawn, and a biome whose table ends up
 * empty spawns nothing at all. ULTRAKILL's bosses and the Cyber Grind's waves are not affected - they are called
 * in, not spawned.
 */
public final class EnemySpawnScreen extends Screen {
	private static final int BUTTON_W = 140;
	private static final int BUTTON_H = 20;
	private static final int GAP = 6;
	private static final int TOP = 60;
	private static final int BOTTOM = 40;

	private final Screen last;

	public EnemySpawnScreen(Screen last) {
		super(Component.translatable("Enemy Spawn Filter"));
		this.last = last;
	}

	private int columns() {
		return Math.max(1, (width - 40 + GAP) / (BUTTON_W + GAP));
	}

	@Override
	protected void init() {
		clearWidgets();
		List<String> types = UkSpawns.types();
		int columns = columns();
		int x0 = (width - (columns * BUTTON_W + (columns - 1) * GAP)) / 2;
		for (int i = 0; i < types.size(); i++) {
			String type = types.get(i);
			int x = x0 + (i % columns) * (BUTTON_W + GAP);
			int y = TOP + (i / columns) * (BUTTON_H + GAP);
			addRenderableWidget(button(type, x, y));
		}
		int by = height - BOTTOM - 4;
		// everything is on unless it is put on the list, so "all on" is also the way back to the defaults
		addRenderableWidget(Button.builder(Component.translatable("All On (default)"), b -> {
			UltracraftConfig.spawnBanned.clear();
			rebuild();
		}).bounds(x0, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Let every one of ULTRAKILL's enemies spawn again."))).build());
		addRenderableWidget(Button.builder(Component.translatable("All Off"), b -> {
			for (String type : types) UltracraftConfig.spawnBanned.put(type, Boolean.TRUE);
			rebuild();
		}).bounds(x0 + 116, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Leave every one of ULTRAKILL's enemies out: its spawn tables then draw nothing at all."))).build());
		addRenderableWidget(Button.builder(Component.translatable("Done"), b -> onClose())
			.bounds(width - 20 - 110, by, 110, 20).build());
	}

	private Button button(String type, int x, int y) {
		boolean banned = UkSpawns.banned(type);
		// the name is the entity's own, so the language files already carry it (entity.ultracraft.uk_enemy.<type>)
		Component name = Component.translatable("entity.ultracraft.uk_enemy." + type.toLowerCase(java.util.Locale.ROOT));
		Component label = name;
		if (banned) label = label.copy().withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.DARK_GRAY);
		Component tip = name.copy().append(Component.literal("\n")).append(Component.translatable(banned
			? "Left out: this enemy never turns up in the dark."
			: "Spawns: this enemy may turn up in the dark, where its biome's table has it."));
		return Button.builder(label, b -> {
			if (banned) UltracraftConfig.spawnBanned.remove(type);
			else UltracraftConfig.spawnBanned.put(type, Boolean.TRUE);
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
		// no renderBackground here: Screen's own entry point has already drawn it (and blurred once, which a
		// second call refuses: "Can only blur once per frame")
		graphics.drawString(font, title, 20, 20, 0xFFFFFFFF);
		int banned = 0;
		for (String type : UkSpawns.types()) if (UkSpawns.banned(type)) banned++;
		Component status = banned == 0 ? Component.translatable("Every enemy may spawn")
			: Component.translatable("%s left out", banned);
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
