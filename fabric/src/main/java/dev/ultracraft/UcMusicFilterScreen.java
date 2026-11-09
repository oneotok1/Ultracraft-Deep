package dev.ultracraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Which songs the random pick leaves out: ULTRAKILL's own and the player's, from the folder on the Fight Music
 * screen. Only the random pick: a song chosen by hand plays whether it is left out here or not.
 *
 * <p>As wide as the screen: a heading per part of ULTRAKILL a song belongs to (or the player's own folder), with a
 * gap above it, then every song of that part as buttons across the row. A song lit up is in the draw; a dark,
 * struck-through one is left out. A scrollbar down the right shows how much is left; the buttons along the bottom act
 * on everything, never on what happens to be showing.
 */
final class UcMusicFilterScreen extends Screen {
	private static final int ROW = 24;
	private static final int BUTTON_W = 103;
	private static final int BUTTON_H = 20;
	private static final int GAP = 6;
	private static final int TOP = 46;
	private static final int BOTTOM = 40;
	private static final int BAR_W = 6;
	private static final int BAR_GAP = 6;

	private final Screen last;
	/** The songs to show, in order, grouped by the part of ULTRAKILL they belong to. */
	private final Map<String, List<String>> groups = new LinkedHashMap<>();

	private int scroll;
	private boolean rebuildQueued;
	/** Dragging the scrollbar: where in the slider the pointer took hold. */
	private boolean dragging;
	private int grab;

	UcMusicFilterScreen(Screen last) {
		super(Component.translatable("Music Filter"));
		this.last = last;
		for (String k : UcMusicScreen.all()) groups.computeIfAbsent(UcMusicScreen.group(k), g -> new ArrayList<>()).add(k);
	}

	/** The widest the grid may be: the screen less its margins. */
	private int gridW() {
		return width - 40;
	}

	/** The grid's left edge, centred. */
	private int gridX() {
		return 20;
	}

	private int listTop() {
		return TOP;
	}

	/** Buttons across one row, with the scrollbar's column always kept clear: how many rows there are depends on
	 * this, so it cannot depend on whether the bar shows. */
	private int columns() {
		int usable = gridW() - (BAR_GAP + BAR_W);
		return Math.max(1, (usable + GAP) / (BUTTON_W + GAP));
	}

	/** The rows the page has: a heading, a gap, then a row of songs at a time. */
	private List<Object[]> lines() {
		List<Object[]> lines = new ArrayList<>();
		int columns = columns();
		for (var e : groups.entrySet()) {
			if (!lines.isEmpty()) lines.add(new Object[] {null, null});
			lines.add(new Object[] {e.getKey(), null});
			List<String> songs = e.getValue();
			for (int i = 0; i < songs.size(); i += columns) {
				lines.add(new Object[] {e.getKey(), songs.subList(i, Math.min(songs.size(), i + columns))});
			}
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

		int top = listTop();
		for (int r = scroll; r < Math.min(lines.size(), scroll + rows); r++) {
			@SuppressWarnings("unchecked")
			List<String> songs = (List<String>) lines.get(r)[1];
			// a heading is drawn, not a button: it names the part and is pressed for nothing
			if (songs == null) continue;
			int y = top + (r - scroll) * ROW;
			int x = gridX();
			for (String song : songs) {
				addRenderableWidget(toggle(song, x, y));
				x += BUTTON_W + GAP;
			}
		}

		int by = height - 30;
		int wide = Math.min(110, (width - 40 - 3 * 6) / 4);
		addRenderableWidget(Button.builder(Component.translatable("All On"), b -> setAll(false))
			.bounds(20, by, wide, 20).tooltip(Tooltip.create(Component.translatable("Every song back in the random pick."))).build());
		addRenderableWidget(Button.builder(Component.translatable("All Off"), b -> setAll(true))
			.bounds(20 + wide + 6, by, wide, 20).tooltip(Tooltip.create(Component.translatable("Leave every song out of the random pick."))).build());
		addRenderableWidget(Button.builder(Component.translatable("Done"), b -> onClose()).bounds(width - 20 - wide, by, wide, 20).build());
	}

	private Button toggle(String song, int x, int y) {
		boolean leftOut = Boolean.TRUE.equals(UltracraftConfig.musicMuted.get(song));
		Component label = Component.literal(UcMusicScreen.name(song));
		if (leftOut) label = label.copy().withStyle(ChatFormatting.STRIKETHROUGH, ChatFormatting.DARK_GRAY);
		return Button.builder(label, b -> {
			if (leftOut) UltracraftConfig.musicMuted.remove(song);
			else UltracraftConfig.musicMuted.put(song, Boolean.TRUE);
			UltracraftConfig.sendMusic();
			UltracraftConfig.save();
			// the button again, lit or dark as it now is, without leaving the page
			rebuildLater();
		}).bounds(x, y, BUTTON_W, BUTTON_H)
			.tooltip(Tooltip.create(Component.translatable(leftOut ? "Left out of the random pick." : "In the random pick."))).build();
	}

	/** Every song in, or every song out: of the whole list, never just what is on screen. */
	private void setAll(boolean leftOut) {
		UltracraftConfig.musicMuted.clear();
		if (leftOut) for (String k : UcMusicScreen.all()) UltracraftConfig.musicMuted.put(k, Boolean.TRUE);
		UltracraftConfig.sendMusic();
		UltracraftConfig.save();
		rebuildLater();
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
		if (groups.isEmpty()) {
			g.drawCenteredString(font, Component.translatable("ULTRAKILL hasn't listed its soundtrack yet: start it (or open a world) and come back."),
				width / 2, height / 2 - 10, 0xFFA0A0A0);
			return;
		}
		g.drawCenteredString(font, Component.translatable("Songs left out here are skipped when Fight Music is Random."),
			width / 2, 30, 0xFFA0A0A0);
		headings(g);
		scrollbar(g);
	}

	/** The group headings of the rows on show, over their songs. */
	private void headings(GuiGraphics g) {
		List<Object[]> lines = lines();
		int rows = visibleRows();
		for (int r = scroll; r < Math.min(lines.size(), scroll + rows); r++) {
			String group = (String) lines.get(r)[0];
			if (group == null || lines.get(r)[1] != null) continue;
			int y = listTop() + (r - scroll) * ROW + 7;
			g.drawString(font, Component.translatable(group), gridX() + 2, y, 0xFFFFD24D);
		}
	}

	/** Whether the bar is showing: only then is there anything to drag. */
	private boolean needsBar() {
		return lines().size() > visibleRows();
	}

	private boolean hasBar() {
		return needsBar();
	}

	/** The bar's left edge: past the widest the grid is allowed to be. */
	private int barX() {
		return gridX() + gridW() + BAR_GAP - BAR_W;
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
		return Math.max(20, barHeight() * visibleRows() / Math.max(1, lines().size()));
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

	/** A bar down the right, only while the list is longer than a screen. */
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
