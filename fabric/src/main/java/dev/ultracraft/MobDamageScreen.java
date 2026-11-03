package dev.ultracraft;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Mob Damage Filter: which of Minecraft's mobs ULTRAKILL's side may touch at all.
 *
 * <p>One button per mob, lit for one ULTRAKILL may hit and dark and struck through for one it may not. The four
 * headings across the top pick a group (pressing one again drops it), the box beside the title searches the way
 * JEI's does - words all have to match, "-word" leaves them out, "one|other" takes either - against a mob's name,
 * its id and, with the pinyin mod installed, its pinyin. The three buttons along the bottom act on what the search
 * and the heading leave showing, never on everything.
 */
public final class MobDamageScreen extends Screen {
    private static final int ROW = 24;
    private static final int BUTTON_W = 103;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;
    private static final int TOP = 96;
    private static final int BOTTOM = 52;

    private final Screen last;
    private final List<EntityType<?>> all = MobDamageRules.mobs();
    private final List<EntityType<?>> shown = new ArrayList<>();
    private final int[] counts = new int[4];

    private EditBox search;
    private String query = "";
    private int chip = -1;
    private int scroll;

    public MobDamageScreen(Screen last) {
        super(Component.translatable("Mob Damage Filter"));
        this.last = last;
        for (EntityType<?> type : all) counts[MobDamageRules.category(type)]++;
        filter();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        // the box is made afresh here, so whoever was typing into it keeps typing
        boolean typing = search != null && search.isFocused();
        clearWidgets();
        int x = 20;
        int y = 14;
        int searchW = 148;
        search = new EditBox(font, width - 20 - searchW, y, searchW, 20, Component.translatable("Search"));
        search.setMaxLength(50);
        search.setValue(query);
        search.setResponder(s -> {
            // setValue below runs this too: only a real change may rebuild, or the two would keep asking
            if (s.equals(query)) return;
            query = s;
            scroll = 0;
            filter();
            rebuildLater();
        });
        addRenderableWidget(search);
        if (typing) search.setFocused(true);

        String[] names = {"Hostile", "Neutral", "Passive", "Other"};
        for (int i = 0; i < 4; i++) {
            final int which = i;
            Button b = Button.builder(Component.translatable(names[i]).copy().append("  " + counts[i]), btn -> {
                chip = chip == which ? -1 : which;
                scroll = 0;
                filter();
                rebuildLater();
            }).bounds(x, 46, 92, 20).tooltip(Tooltip.create(Component.translatable("Filter by %s mobs (click again to show all categories).", Component.translatable(names[i]).getString()))).build();
            // the group showing: its heading lit, the others plain
            b.setMessage(Component.translatable(names[i]).copy().append("  " + counts[i])
                    .withStyle(chip == which ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
            addRenderableWidget(b);
            x += 98;
        }

        int columns = Math.max(1, (width - 40 + GAP) / (BUTTON_W + GAP));
        int rows = Math.max(1, (height - TOP - BOTTOM) / ROW);
        int total = (shown.size() + columns - 1) / columns;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - rows)));
        for (int r = scroll; r < Math.min(total, scroll + rows); r++) {
            for (int c = 0; c < columns; c++) {
                int i = r * columns + c;
                if (i >= shown.size()) break;
                addRenderableWidget(mobButton(shown.get(i), 20 + c * (BUTTON_W + GAP), TOP + (r - scroll) * ROW));
            }
        }

        int by = height - 40;
        addRenderableWidget(Button.builder(Component.translatable("Select Shown"), b -> {
            for (EntityType<?> type : shown) UltracraftConfig.dmgMobs.put(MobDamageRules.key(type), Boolean.TRUE);
            rebuildLater();
        }).bounds(20, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Allow ULTRAKILL interactions for all currently visible mobs."))).build());
        addRenderableWidget(Button.builder(Component.translatable("Invert Shown"), b -> {
            for (EntityType<?> type : shown) UltracraftConfig.dmgMobs.put(MobDamageRules.key(type), !MobDamageRules.allowed(type));
            rebuildLater();
        }).bounds(136, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Invert the filter state for all currently visible mobs."))).build());
        addRenderableWidget(Button.builder(Component.translatable("Defaults"), b -> {
            UltracraftConfig.dmgMobs.clear();
            UltracraftConfig.dmgHostile = true;
            UltracraftConfig.dmgNeutral = true;
            UltracraftConfig.dmgPassive = true;
            UltracraftConfig.dmgOther = true;
            rebuildLater();
        }).bounds(252, by, 110, 20).tooltip(Tooltip.create(Component.translatable("Reset all categories to allowed and clear per-mob overrides."))).build());
        addRenderableWidget(Button.builder(Component.translatable("Done"), b -> onClose()).bounds(width - 20 - 110, by, 110, 20).build());
    }

    private Button mobButton(EntityType<?> type, int x, int y) {
        boolean allowed = MobDamageRules.allowed(type);
        // a mob another mod brought in says so, the way JEI marks its own
        Component label = Component.literal(trim(MobDamageRules.englishName(type)) + (modded(type) ? " *" : ""));
        if (!allowed) label = label.copy().withStyle(ChatFormatting.STRIKETHROUGH);
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String where = id == null ? "" : id.toString();
        String group = switch (MobDamageRules.category(type)) {
            case MobDamageRules.HOSTILE -> "Hostile";
            case MobDamageRules.NEUTRAL -> "Neutral";
            case MobDamageRules.PASSIVE -> "Passive";
            default -> "Other";
        };
        Component tip = Component.literal(where + (modded(type) ? Component.translatable(" (modded)").getString() : "") + "\n")
                .append(Component.translatable(group + " mob."))
                .append(" ")
                .append(Component.translatable(allowed
                        ? "Allowed: affected by ULTRAKILL weapons, attacks, Whiplash, and blasts."
                        : "Ignored: completely immune to all ULTRAKILL weapons, enemies, Whiplash, and explosions."));
        return Button.builder(label, b -> {
            UltracraftConfig.dmgMobs.put(MobDamageRules.key(type), !allowed);
            rebuildLater();
        }).bounds(x, y, BUTTON_W, BUTTON_H).tooltip(Tooltip.create(tip)).build();
    }

    private static boolean modded(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null && !id.getNamespace().equals("minecraft");
    }

    private String trim(String name) {
        if (font.width(name) <= BUTTON_W - 8) return name;
        String cut = name;
        while (cut.length() > 1 && font.width(cut + "...") > BUTTON_W - 8) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    /** Coalesced: a press or a keystroke asks for one rebuild, not one each. */
    private boolean rebuildQueued;

    private void rebuildLater() {
        if (rebuildQueued) return;
        rebuildQueued = true;
        Minecraft.getInstance().execute(() -> {
            rebuildQueued = false;
            if (Minecraft.getInstance().screen == this) rebuild();
        });
    }

    // ------------------------------------------------------------------ the search

    private void filter() {
        shown.clear();
        for (EntityType<?> type : all) {
            if (chip >= 0 && MobDamageRules.category(type) != chip) continue;
            if (matches(type, query)) shown.add(type);
        }
    }

    /** JEI's own grammar: words all have to be there, "-word" keeps them out, "one|other" takes either. */
    private boolean matches(EntityType<?> type, String text) {
        String q = text.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        String name = MobDamageRules.englishName(type);
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String path = id == null ? "" : id.getPath();
        for (String word : q.split("\\s+")) {
            if (word.isEmpty()) continue;
            boolean no = word.startsWith("-");
            if (no) word = word.substring(1);
            if (word.isEmpty()) continue;
            boolean hit = false;
            for (String part : word.split("\\|")) {
                if (part.isEmpty()) continue;
                if (MobDamageRules.contains(name, part) || MobDamageRules.contains(path, part)) {
                    hit = true;
                    break;
                }
            }
            if (hit == no) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing and input

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // no renderBackground here: Screen's own entry point has already drawn it (and blurred once, which a
        // second call refuses: "Can only blur once per frame")
        graphics.drawString(font, title, 20, 20, 0xFFFFFFFF);
        int mods = 0;
        for (EntityType<?> type : all) if (modded(type)) mods++;
        int allowed = 0;
        for (EntityType<?> type : all) if (MobDamageRules.allowed(type)) allowed++;
        String status = all.size() + " mobs" + (mods > 0 ? " (" + mods + " from other mods)" : "");
        graphics.drawString(font, status, 20, 74, 0xFFA0A0A0);
        String tally = shown.size() + " shown  -  " + allowed + "/" + all.size() + " allowed";
        graphics.drawString(font, tally, width - 20 - font.width(tally), height - 58, 0xFFA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int columns = Math.max(1, (width - 40 + GAP) / (BUTTON_W + GAP));
            int rows = Math.max(1, (height - TOP - BOTTOM) / ROW);
            int total = (shown.size() + columns - 1) / columns;
            int before = scroll;
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, total - rows)));
            if (scroll != before) rebuildLater();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
