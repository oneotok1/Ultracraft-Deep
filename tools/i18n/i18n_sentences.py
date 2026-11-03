# -*- coding: utf-8 -*-
"""Second pass: turn concatenated English sentences into translatable keys.

Only sentences players read are touched. Names (bosses, players, arenas,
ranks), numbers, and already-dynamic text are left alone -- a translated
sentence must not be built by gluing a translated tail onto a raw name.
"""
import io
import os
import sys

ROOT = 'fabric/src/main/java/dev/ultracraft/'

# (file, old, new)  -- old must appear exactly once in that file
EDITS = [
    # ---- Arenas -------------------------------------------------------
    ('Arenas.java',
     'Component.literal(L.title + " ARENA: CLEARED")',
     'Component.literal(L.title).append(Component.translatable(" ARENA: CLEARED"))'),
    ('Arenas.java',
     'Component.literal(L.title + " ARENA")',
     'Component.literal(L.title).append(Component.translatable(" ARENA"))'),
    ('Arenas.java',
     'Component.literal("   " + boss.name() + " waits inside")',
     'Component.literal("   " + boss.name()).append(Component.translatable(" waits inside"))'),
    ('Arenas.java',
     'Component.literal("[Ultracraft] " + L.title + " arena cleared: a chest of loot waits on the dais.")',
     'Component.literal("[Ultracraft] " + L.title + " ").append(Component.translatable("arena cleared: a chest of loot waits on the dais."))'),

    # ---- BossParty ----------------------------------------------------
    ('BossParty.java',
     'Component.literal("You\'re down: watching " + mate.getName().getString() + ". A teammate standing where you fell brings you back.")',
     'Component.translatable("You\'re down: watching %s. A teammate standing where you fell brings you back.", mate.getName().getString())'),
    ('BossParty.java',
     'Component.literal(sp.getName().getString() + " is down! Stand where they fell to bring them back.")',
     'Component.translatable("%s is down! Stand where they fell to bring them back.", sp.getName().getString())'),
    ('BossParty.java',
     'd.displayClientMessage(Component.literal(on.get(0).getName().getString() + " is bringing you back  " + pct + "%")',
     'd.displayClientMessage(Component.translatable("%s is bringing you back  %s%%", on.get(0).getName().getString(), pct)'),
    ('BossParty.java',
     'Component.literal(on.get(0).getName().getString() + " brought " + d.getName().getString() + " back!")',
     'Component.translatable("%s brought %s back!", on.get(0).getName().getString(), d.getName().getString())'),
    ('BossParty.java',
     'String bar = "Reviving " + d.getName().getString() + "  "',
     'String bar = net.minecraft.network.chat.Component.translatable("Reviving %s", d.getName().getString()).getString() + "  "'),

    # ---- CyberGrind ---------------------------------------------------
    ('CyberGrind.java',
     'String.format(Locale.ROOT, "You left the Cyber Grind at wave %d; it goes on without you.", wave)',
     'Component.translatable("You left the Cyber Grind at wave %s; it goes on without you.", wave).getString()'),
    ('CyberGrind.java',
     'Component.literal(first ? "THE CYBER GRIND" : "WAVE " + (wave + 1))',
     'first ? Component.translatable("THE CYBER GRIND") : Component.translatable("WAVE %s", wave + 1)'),
    ('CyberGrind.java',
     'String.format(Locale.ROOT, "The Cyber Grind is over (%s): wave %d, best %d", why, wave, UltracraftConfig.grindBest)',
     'Component.translatable("The Cyber Grind is over (%s): wave %s, best %s", why, wave, UltracraftConfig.grindBest).getString()'),

    # ---- Duels --------------------------------------------------------
    ('Duels.java',
     'Component.literal("Fight " + from.getName().getString())',
     'Component.translatable("Fight %s", from.getName().getString())'),
    ('Duels.java',
     'Component.literal(from.getName().getString() + " challenges you to a duel!  ")',
     'Component.translatable("%s challenges you to a duel!  ", from.getName().getString())'),
    ('Duels.java',
     'Component.literal("DUEL vs " + other.getName().getString())',
     'Component.translatable("DUEL vs %s", other.getName().getString())'),
    ('Duels.java',
     'String sub = why != null ? why : won ? "you won the duel" : winner != null ? winner.getName().getString() + " won the duel" : "";',
     'String sub = why != null ? why : won ? "you won the duel" : winner != null ? winner.getName().getString() + " " + Component.translatable("won the duel").getString() : "";'),
    ('Duels.java',
     'Component.literal(String.format(Locale.ROOT, "[Ultracraft] DUEL %s: %s%s", names, result, why != null ? " (" + why + ")" : ""))',
     'Component.translatable("[Ultracraft] DUEL %s: %s%s", names, result, why != null ? " (" + why + ")" : "")'),

    # ---- ServerOps ----------------------------------------------------
    ('ServerOps.java',
     'Component.literal(UltracraftConfig.ukSpawns ? "ULTRAKILL\'s enemies spawn in the dark again" : "ULTRAKILL\'s enemies no longer spawn in the dark")',
     'Component.translatable(UltracraftConfig.ukSpawns ? "ULTRAKILL\'s enemies spawn in the dark again" : "ULTRAKILL\'s enemies no longer spawn in the dark")'),

    # ---- UkShopBlock ---------------------------------------------------
    ('UkShopBlock.java',
     'Component.literal(UkLink.connected ? "The shop is ULTRAKILL\'s: become V1 (F8) and walk up to the screen"',
     'Component.translatable(UkLink.connected ? "The shop is ULTRAKILL\'s: become V1 (F8) and walk up to the screen"'),

    # ---- Ultracraft ----------------------------------------------------
    ('Ultracraft.java',
     'Component.literal(on ? "Minecraft hands (V for guns)" : "V1\'s guns (V for Minecraft hands)")',
     'Component.translatable(on ? "Minecraft hands (V for guns)" : "V1\'s guns (V for Minecraft hands)")'),

    # ---- UkCommands ----------------------------------------------------
    ('UkCommands.java',
     'c.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GOLD), false);',
     'c.getSource().sendSuccess(() -> Component.translatable(text).withStyle(ChatFormatting.GOLD), false);'),
    ('UkCommands.java',
     'c.getSource().sendFailure(Component.literal(text));',
     'c.getSource().sendFailure(Component.translatable(text));'),

    # ---- StyleRewards --------------------------------------------------
    ('StyleRewards.java',
     'Component.literal("STYLE STREAK x" + st.streak)',
     'Component.translatable("STYLE STREAK x%s", st.streak)',
     'all'),

    # ---- UkBosses ------------------------------------------------------
    ('UkBosses.java',
     'Component.literal(b.name + " IS COMING")',
     'Component.translatable("%s IS COMING", b.name)'),
    ('UkBosses.java',
     'String.format(Locale.ROOT, "[Ultracraft] WARNING: %s is coming in %d seconds, where the red pillar stands. Get ready!",',
     'Component.translatable("[Ultracraft] WARNING: %s is coming in %s seconds, where the red pillar stands. Get ready!",'),

    # ---- UcSettingsScreen ----------------------------------------------
    ('UcSettingsScreen.java',
     'Component.literal(name + "...")',
     'Component.translatable(name + "...")'),
    ('UcSettingsScreen.java',
     'super(last, Minecraft.getInstance().options, Component.literal(name));',
     'super(last, Minecraft.getInstance().options, Component.translatable(name));'),
    ('UcSettingsScreen.java',
     'list.addHeader(Component.literal(text));',
     'list.addHeader(Component.translatable(text));'),
    ('UcSettingsScreen.java',
     'Component.literal(p.tooltip + " (ULTRAKILL\'s own settings stay as they are.)")',
     'Component.translatable(p.tooltip).append(Component.translatable(" (ULTRAKILL\'s own settings stay as they are.)"))'),

    # ---- UcMusicScreen -------------------------------------------------
    ('UcMusicScreen.java',
     'list.addHeader(Component.literal(e.getKey()));',
     'list.addHeader(Component.translatable(e.getKey()));'),

    # ---- UcBossMusicScreen ---------------------------------------------
    ('UcBossMusicScreen.java',
     'Component.literal(boss.name() + ": Music & Intro")',
     'Component.literal(boss.name() + ": ").append(Component.translatable("Music & Intro"))'),
    ('UcBossMusicScreen.java',
     'Component.literal("The song that plays when " + b.name() + " comes. (Default): its own ULTRAKILL theme."',
     'Component.translatable("The song that plays when %s comes. (Default): its own ULTRAKILL theme.", b.name()'),

    # ---- UcBossSetupScreen ---------------------------------------------
    ('UcBossSetupScreen.java',
     'Component.literal(s.isEmpty() ? "As ULTRAKILL has it." : s.substring(2, s.length() - 1))',
     'Component.translatable(s.isEmpty() ? "As ULTRAKILL has it." : s.substring(2, s.length() - 1))'),
    ('UcBossSetupScreen.java',
     'super(last, Minecraft.getInstance().options, Component.literal(boss.name()));',
     'super(last, Minecraft.getInstance().options, Component.literal(boss.name()));'),

    # ---- MobDamageScreen -----------------------------------------------
    ('MobDamageScreen.java',
     '"Filter by " + names[i].toLowerCase(Locale.ROOT) + " mobs (click again to show all categories)."',
     '"Filter by " + names[i].toLowerCase(Locale.ROOT) + " mobs (click again to show all categories)."', 'keep'),
    ('MobDamageScreen.java',
     '"\\n" + group + " mob. " + (allowed',
     '"\\n" + Component.translatable(group + " mob.").getString() + " " + (allowed'),
]


def main(apply):
    ok = miss = 0
    by_file = {}
    for item in EDITS:
        fn, old, new = item[0], item[1], item[2]
        replace_all = len(item) > 3 and item[3] == 'all'
        path = ROOT + fn
        src = open(path, encoding='utf-8').read()
        n = src.count(old)
        if n == 0:
            print('MISS  %-26s %s' % (fn, old[:64]))
            miss += 1
            continue
        if n > 1 and not replace_all:
            print('DUPE(%d) %-24s %s' % (n, fn, old[:60]))
            miss += 1
            continue
        if apply:
            open(path, 'w', encoding='utf-8', newline='').write(src.replace(old, new))
        by_file[fn] = by_file.get(fn, 0) + n
        ok += 1

    print()
    for fn in sorted(by_file):
        print('  %-26s %d' % (fn, by_file[fn]))
    print()
    print('applied: %d   unresolved: %d' % (ok, miss))
    if not apply:
        print('DRY RUN')


if __name__ == '__main__':
    main('--apply' in sys.argv)
