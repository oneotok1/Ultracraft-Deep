package dev.ultracraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of ULTRAKILL's looks or skies the Cyber Grind may take on, grouped by the layer they belong to.
 *
 * <p>One column down the middle: a heading per layer, lit while everything in it is on and struck through while
 * nothing is, that turns the whole layer on or off; underneath it, one row per entry, lit for one the Grind may use
 * and dark and struck through for one it may not, which overrides its layer's answer. Every layer starts folded, since
 * forty-six skies are longer than a screen; the arrow beside a heading opens it. A scrollbar down the right of the
 * column shows how much is left. The buttons along the bottom act on everything, never on what happens to be showing.
 */
abstract class GrindFilterScreen extends Screen {
	private static final int ROW = 22;
	private static final int COLUMN_W = 260;
	private static final int BUTTON_H = 20;
	private static final int FOLD_W = 20;
	private static final int TOP = 46;
	private static final int BOTTOM = 40;
	private static final int BAR_W = 6;

	protected final Screen last;
	/** The layers to show, in order, each with its entries. */
	protected final Map<String, List<String>> layers = new LinkedHashMap<>();
	/** Layers folded shut. */
	private final List<String> folded = new ArrayList<>();

	private int scroll;
	private boolean rebuildQueued;
	/** Dragging the scrollbar: where in the slider the pointer took hold. */
	private boolean dragging;
	private int grab;

	GrindFilterScreen(Screen last, Component title) {
		super(title);
		this.last = last;
		fill();
		// everything starts folded: the list is longer than a screen, and a layer's heading says enough to begin with
		folded.addAll(layers.keySet());
	}

	/** Puts the layers and their entries into {@link #layers}. */
	protected abstract void fill();

	/** Whether an entry is on: its own answer, else its layer's. */
	protected abstract boolean on(String layer, String entry);

	/** The label an entry goes by. */
	protected abstract Component label(String layer, String entry);

	/** What an entry is, in a sentence. */
	protected abstract Component describe(String layer, String entry);

	protected abstract void setOn(String layer, String entry, boolean on);

	/** Whether a whole layer is on: every entry of it is. */
	protected abstract boolean layerOn(String layer);

	protected abstract void setLayer(String layer, boolean on);

	/** Puts everything back to how it starts. */
	protected abstract void defaults();

	/** How much room a screen needs above its list for a switch of its own. */
	protected int extraTop() {
		return 0;
	}

	/** A screen's own control, drawn in the room {@link #extraTop()} asked for. */
	protected void extras(int x, int y, int w) {
	}

	/** The column's left edge, centred on the screen. */
	private int columnX() {
		return Math.max(20, (width - COLUMN_W) / 2);
	}

	private int listTop() {
		return TOP + extraTop();
	}

	/** The rows the page has: a layer's heading, then its entries while it is open. */
	private List<Object[]> lines() {
		List<Object[]> lines = new ArrayList<>();
		for (var e : layers.entrySet()) {
			lines.add(new Object[] {e.getKey(), null});
			if (folded.contains(e.getKey())) continue;
			for (String entry : e.getValue()) lines.add(new Object[] {e.getKey(), entry});
		}
		return lines;
	}

	/** The rows that fit between the title and the bottom buttons. */
	private int visibleRows() {
		return Math.max(1, (height - listTop() - BOTTOM) / ROW);
	}

	@Override
	protected void init() {
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		int rows = visibleRows();
		List<Object[]> lines = lines();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));

		int x = columnX();
		int top = listTop();
		if (extraTop() > 0) extras(x, TOP, COLUMN_W);
		for (int r = scroll; r < Math.min(lines.size(), scroll + rows); r++) {
			String layer = (String) lines.get(r)[0];
			String entry = (String) lines.get(r)[1];
			int y = top + (r - scroll) * ROW;
			if (entry == null) {
				addRenderableWidget(heading(layer, x, y, COLUMN_W - FOLD_W - 2));
				addRenderableWidget(fold(layer, x + COLUMN_W - FOLD_W, y));
			} else {
				addRenderableWidget(entryButton(layer, entry, x, y));
			}
		}

		int by = height - 30;
		int wide = Math.min(110, (width - 40 - 3 * 6) / 4);
		addRenderableWidget(Button.builder(Component.translatable("All On"), b -> {
			for (var e : layers.entrySet()) setLayer(e.getKey(), true);
			rebuildLater();
		}).bounds(20, by, wide, 20).tooltip(Tooltip.create(Component.translatable("Turn every layer, and everything in it, on."))).build());
		addRenderableWidget(Button.builder(Component.translatable("All Off"), b -> {
			for (var e : layers.entrySet()) setLayer(e.getKey(), false);
			rebuildLater();
		}).bounds(20 + wide + 6, by, wide, 20).tooltip(Tooltip.create(Component.translatable("Turn every layer, and everything in it, off. One is always left on, so the Grind is never without."))).build());
		addRenderableWidget(Button.builder(Component.translatable("Defaults"), b -> {
			defaults();
			rebuildLater();
		}).bounds(20 + 2 * (wide + 6), by, wide, 20).tooltip(Tooltip.create(Component.translatable("Back to everything on."))).build());
		addRenderableWidget(Button.builder(Component.translatable("Done"), b -> onClose()).bounds(width - 20 - wide, by, wide, 20).build());
	}

	private Button heading(String layer, int x, int y, int w) {
		boolean all = layerOn(layer);
		Component label = Component.translatable(layer).copy();
		if (!all) label = label.copy().withStyle(ChatFormatting.STRIKETHROUGH);
		label = label.copy().withStyle(all ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY);
		return Button.builder(label, b -> {
			setLayer(layer, !all);
			rebuildLater();
		}).bounds(x, y, w, BUTTON_H).tooltip(Tooltip.create(Component.translatable(all
				? "Everything in this layer is on. Press to turn the whole layer off."
				: "Nothing in this layer is on. Press to turn the whole layer on."))).build();
	}

	private Button fold(String layer, int x, int y) {
		boolean shut = folded.contains(layer);
		return Button.builder(Component.literal(shut ? "v" : "^"), b -> {
			if (shut) folded.remove(layer);
			else folded.add(layer);
			rebuildLater();
		}).bounds(x, y, FOLD_W, BUTTON_H).tooltip(Tooltip.create(Component.translatable(shut
				? "Show this layer's list."
				: "Fold this layer's list away."))).build();
	}

	private Button entryButton(String layer, String entry, int x, int y) {
		boolean on = on(layer, entry);
		Component label = label(layer, entry).copy();
		if (!on) label = label.copy().withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.DARK_GRAY);
		Component tip = describe(layer, entry).copy().append("\n").append(Component.translatable(on
				? "On: the Grind may use it."
				: "Off: the Grind leaves it out."));
		return Button.builder(label, b -> {
			setOn(layer, entry, !on);
			rebuildLater();
		}).bounds(x, y, COLUMN_W, BUTTON_H).tooltip(Tooltip.create(tip)).build();
	}

	/** Coalesced: a press asks for one rebuild, not one each. */
	private void rebuildLater() {
		if (rebuildQueued) return;
		rebuildQueued = true;
		Minecraft.getInstance().execute(() -> {
			rebuildQueued = false;
			if (Minecraft.getInstance().screen == this) rebuild();
		});
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int before = scroll;
		int most = Math.max(0, lines().size() - visibleRows());
		scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), most));
		if (scroll != before) {
			rebuildLater();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, getTitle(), width / 2, 16, 0xFFFFFF);
		scrollbar(g);
	}

	/** Whether the bar is showing: only then is there anything to drag. */
	private boolean hasBar() {
		return lines().size() > visibleRows();
	}

	/** The bar's left edge. */
	private int barX() {
		return columnX() + COLUMN_W + 8;
	}

	/** The bar's track, top and length. */
	private int barTop() {
		return listTop();
	}

	private int barHeight() {
		return visibleRows() * ROW - 2;
	}

	/** The slider's height inside the track: a screenful's share of the whole list. */
	private int thumbHeight() {
		return Math.max(20, barHeight() * visibleRows() / lines().size());
	}

	/** The slider's top edge, where the list is scrolled to. */
	private int thumbTop() {
		int travel = barHeight() - thumbHeight();
		return barTop() + (int) ((long) travel * scroll / Math.max(1, lines().size() - visibleRows()));
	}

	/** Scrolls to wherever the slider's top edge is put. */
	private void scrollTo(int thumbTop) {
		int travel = barHeight() - thumbHeight();
		if (travel <= 0) {
			scroll = 0;
			return;
		}
		int most = Math.max(0, lines().size() - visibleRows());
		scroll = Math.max(0, Math.min(most, (int) Math.round((double) (thumbTop - barTop()) * most / travel)));
	}

	/** A bar down the right of the column, only while the list is longer than a screen. */
	private void scrollbar(GuiGraphics g) {
		if (!hasBar()) return;
		int x = barX();
		int top = barTop();
		g.fill(x, top, x + BAR_W, top + barHeight(), 0xFF202020);
		int y = thumbTop();
		g.fill(x, y, x + BAR_W, y + thumbHeight(), 0xFFB0B0B0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mouseX = e.x(), mouseY = e.y();
		if (e.button() == 0 && hasBar()) {
			int x = barX();
			// wider than the bar itself, so it can be caught without aiming
			if (mouseX >= x - 4 && mouseX <= x + BAR_W + 4 && mouseY >= barTop() && mouseY <= barTop() + barHeight()) {
				int thumb = thumbTop();
				int height = thumbHeight();
				if (mouseY >= thumb && mouseY <= thumb + height) {
					grab = (int) mouseY - thumb;
				} else {
					// pressed the track: jump there, then carry on dragging from the middle of the slider
					grab = height / 2;
					scrollTo((int) mouseY - grab);
					rebuildLater();
				}
				dragging = true;
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dragX, double dragY) {
		if (dragging) {
			scrollTo((int) e.y() - grab);
			rebuildLater();
			return true;
		}
		return super.mouseDragged(e, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (dragging) {
			dragging = false;
			return true;
		}
		return super.mouseReleased(e);
	}

	@Override
	public void onClose() {
		UltracraftConfig.save();
		Minecraft.getInstance().setScreen(last);
	}
}
