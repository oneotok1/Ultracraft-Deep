# Changelog

Every released version of Ultracraft, newest first. Each heading links to that
release on GitHub; the current download lives on the [latest release](https://github.com/oneotok1/Ultracraft-Deep/releases/latest).

## [1.1.3](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.1.3) — 2026-11-13

- **Minecraft armour now counts.** Armour, protection and resistance reduce what ULTRAKILL enemies do to V1, using Minecraft's own formula.
- **Creative mode stops the fight.** In creative or spectator, ULTRAKILL enemies no longer track V1 and nothing can hurt it — they still go after everything else.
- **The undying totem runs Minecraft's own check.** A totem in either hand saves V1 the way it saves Steve, sound and particles included, and totems added by other mods work too.
- **Minecraft mobs follow ULTRAKILL's own rules.** Whether a mob flung by a slam or a blast dies on landing is now a switch (on by default). ULTRAKILL's blasts throw Minecraft mobs the way they throw V1, V1's own rocket jump throws them too, slam height follows ULTRAKILL's own numbers, and a rocket that catches something in the air goes off in ULTRAKILL's red blast.
- **Two new filters.** Pick which of the 19 ULTRAKILL enemies may spawn at all, and which of the 16 boss modifiers may roll.
- **The world holds still while V1 takes over.** During the first takeover the Minecraft side is frozen like a single-player pause — mobs stand still, nothing falls, nothing spawns — and resumes once V1 is on the ground with the terrain under it built. Going back and forth afterwards no longer freezes anything.
- **Dark arenas get night vision.** The Cyber Grind and all twelve boss arenas light up on entry and go back to normal on exit; potions you drank yourself are left alone.
- **The Grind gathers its drops and XP at the shop.** A finished wave moves the drops and experience orbs to the shop's ledge; quit or die mid-run and they are put where you came in, with a line in chat to say so.
- **Style scoring follows ULTRAKILL.** A mob killed by the slam scores GROUND SLAM (60), or AIR SLAM (160) when the slam caught it off the ground, instead of a plain kill; the in-air multiplier applies where ULTRAKILL applies it and is left off the fixed-value entries.
- **A few things fixed.** The charge upgrade now cools the shotgun's overheat down as well as its two charges; going back to Steve with the trigger held no longer leaves ULTRAKILL firing; and Minecraft's action bar sits higher, clear of ULTRAKILL's message box.

## [1.1.2](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.1.2) — 2026-11-09

- **Calm music is the Cyber Grind's alone.** A run opens on the track's battle version and drops to the calm one between waves (*Cyber Grind Calm Music*).
- **Music filter.** A new *Music Filter* page decides song by song which ones may come up in the random draw. Whether ULTRAKILL's fight music plays in Minecraft's world is a separate switch.
- **Start the Cyber Grind mid-run.** `/uc grind wave <n>` jumps to a wave; without cheats it only reaches as far as your best.
- **Waves can come in batches.** At higher waves the enemies arrive in a few batches instead of all at once (off by default, slightly raises the total).
- **Filter the arena's looks and skies.** Two new pages toggle each of the 13 layers and 46 skies; the Grind's terrain now changes in a random order.
- **The last three enemies are marked.** With three left in a wave a marker appears, so the last ones are not lost.
- **Enemy silhouettes drawn by Minecraft.** Enemy outlines drawn Minecraft's way, close to ULTRAKILL's own minor assist.
- **V1 can sneak.** A new sneak key, which makes building as V1 a good deal easier.
- **One blue punch parries everything in reach.** Every parryable thing it can reach that frame goes off together (switchable, on by default).
- **A round of interface and logic fixes.**

## [1.1.1](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.1.1) — 2026-11-03

- **Mobs with spears flash yellow.** Spear-wielding mobs (zombies, piglins, and zombified piglins) now flash yellow before thrusting. The parry window opens at the spear's full attack reach rather than close punching distance.
- **Cyber Grind columns no longer trap you inside them.** Rising columns now test your hitbox against all overlapping blocks and lift you smoothly onto the highest surface, checking continuously even while columns stand still.
- **Absorption hearts protect against all damage.** Extra health from golden apples and absorption potions now absorbs self-damage (like point-blank rockets) and hits from ULTRAKILL enemies, drawing from the same pool as Minecraft attacks.
- **Extra blood counts as health cap (on by default).** With *Extra Blood Counts as Health Cap* on (in Gameplay settings), healing and blood absorption refill the yellow hearts up to the boosted cap. With it off, the extra hearts act as a one-time damage shield.
- **Explosions no longer destroy mob drops.** Dropped item entities take zero damage from explosions, so mob loot survives point-blank rocket and slam blasts intact.
- **Mob Damage Filter: choose which mobs ULTRAKILL can touch.** A new page under *Enemies & Bosses* lets you toggle which Minecraft mobs ULTRAKILL interacts with. Filtered mobs are completely invisible to ULTRAKILL: weapons pass through, attacks cannot be parried, Whiplash cannot latch, and explosions ignore them. Includes category filters (hostile, neutral, passive, other) and a search box supporting JEI syntax (`-` to exclude, `|` for OR, spaces for AND).
- **Generic punch upgrade entries sink to the bottom of the list.** On the shop's *Upgrades* page the generic per-arm entries (Seismic and the rest) come last, so the fist-specific ones stay grouped together. Purchase and save data are untouched.
- **Fire on your own body actually goes out.** *Blasts Put Fire Out* used to clear flames on the ground only; it now also clears burning entities inside the blast, including you.
- **Jade names and health for ULTRAKILL enemies.** Point Jade at an ULTRAKILL enemy and it now shows the enemy's real name instead of the `uk_enemy` placeholder, and its actual health instead of a fixed 20/20.
- **Hand skins from ULTRAKILL-side skin plugins show up in Minecraft.** With UltraSkins installed, the first-person arm renders your selected skin, glow layer included. The mod now goes through the main menu on its way to the Sandbox, which is the only place those plugins load a skin.
- **Health cap is 4x V1's own full bar.** A Health Boost or a heart-adding mod scales V1's pool up to four times its own maximum (400 at 100 health, 800 on Harmless) rather than a flat +100.
- **The terminal's upgrade page shows Chinese.** With Minecraft set to Chinese, the shop's *Upgrades* page renders its names, descriptions and effect text in Chinese. The font comes from the optional `fontpack.bundle`.
- **The mod's keybinds have their own category.** F8 and V now sit under a separate Ultracraft category in the controls screen instead of Miscellaneous.
- **Boss intro banners show difficulty and modifiers.** The second line reads `Difficulty: Brutal | Modifiers: Radiant Undying`, and is omitted entirely when the boss has no modifiers.
- **Assorted small visual and interface issues tidied up.**

## [1.1.0](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.1.0) — 2026-10-20

- **V1's health scales with Minecraft max health.** A standard 20-health Steve maps to V1's 100 health. Health Boost effects or heart-increasing mods scale V1's health pool proportionally (capped at 500).
- **Absorption hearts visible on the health bar.** Yellow hearts from golden apples or absorption potions render as a distinct, darker segment across the ULTRAKILL health bar.
- **The shop terminal's Upgrades page is in.** Spend P points on upgrade lines, adjusted with `^ v - +`. Purchased upgrade levels are remembered across visits.
- **Mouse side buttons bind correctly.**

## [1.0.19](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.19) — 2026-10-19

- **Kills belong to whoever delivered the final blow.**
- **Authentic Cyber Grind corpse physics.**
- **Rising Cyber Grind columns lift you up.** When a column rises beneath your feet, you are lifted smoothly to the surface instead of clipping inside the block.
- **Style streak display options.** Added settings under *Shop & Rewards* for streak text (Off, Hotbar Line, Both) without affecting points or drops.
- **Smooth Whiplash mob reeling.** Pulled mobs update their position continuously every tick rather than stuttering across client updates.
- **Assorted small visual and interface issues tidied up.**

## [1.0.18](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.18) — 2026-10-18

- **Explosions carve spherical craters.** Blasts hollow out smooth spheres rather than jagged raycast craters, with damage tapering off towards the rim and safety caps to prevent server overload.
- **Blasts can extinguish fires.** A new *Blasts Put Fire Out* setting in Gameplay clears flames around an explosion, including fires started by the blast itself.
- **Assorted small visual and interface issues tidied up.**

## [1.0.17](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.17) — 2026-10-17

- **Whiplash drags Minecraft mobs.** Hooking a mob pulls it towards you smoothly: dragged mobs pause their attacks while hooked, their physics remain active, and fast reels maintain their grip reliably.
- **Minecraft kills build multi-kill chains.** Defeating Minecraft mobs now increments the multi-kill counter alongside granting style points.
- **Melee swings can be dodged.** Attacks must land within physical reach to deal damage.

## [1.0.16](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.16) — 2026-10-15

- **Soul sand valleys no longer stutter.** Uneven blocks like soul sand, soul soil, mud, farmland, and concrete powder now merge collision geometry like solid stone, eliminating severe frame drops in valley biomes.
- **Smoother terrain collision mesh generation.** Terrain collision meshing now accounts for upcoming section costs within strict per-frame budgets, eliminating frame hitches when streaming new chunks.

## [1.0.15](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.15) — 2026-10-15

- **Normalized multiplayer melee damage.** Fixed an issue where incoming melee damage was scaled up twice over the bridge, which likely caused enemies to one-shot a full-health V1 in multiplayer.
- **Optimized navmesh generation.**

## [1.0.14](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.14) — 2026-10-14

- **Independent boss setups.** V2, V2 (Second Encounter), the Judge of Hell, and the Apostate of Hate are now four distinct bosses in Boss Setup, each with independent health, speed, damage multipliers, traits, and practice fights.
- **Gabriel's entrance follows fight history.** Gabriel delivers his level monologue upon your first encounter, switching to his fiery rematch taunts on subsequent fights (with `(Default) Auto` and `Level Intro` options in Boss Music & Intros).

## [1.0.12](https://github.com/oneotok1/Ultracraft-Deep/releases/tag/v1.0.12) — 2026-10-14

- **Gabriel's rematch taunts are his default 6-2 entrance.** The Apostate of Hate opens automatically with his iconic rematch taunts ("IS THIS WHAT I LOST TO!? YOU'RE GETTING RUSTY, MACHINE!"). His original level speech remains available via *Boss Music & Intros → Apostate of Hate → Entrance: Level Intro*.
