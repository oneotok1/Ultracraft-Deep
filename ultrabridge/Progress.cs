// UltraBridge, part three: what V1 earns and owns in a Minecraft world, and ULTRAKILL's bosses coming for it.
//
// - P: the style points V1 earns become P (PEARN), which the Minecraft world keeps (MONEY says how much it has).
// - Gear: what V1 owns in this world (GEAR). The shop's own buttons buy with that P (PADD, GEARADD); the real save
//   file is never touched. V1 starts with the Piercer revolver and the Feedbacker; the base weapons, the arms and
//   the alternate revolver, shotgun and nailgun have Ultracraft prices, the variants keep ULTRAKILL's.
// - Bosses: Minecraft warns and counts down, then calls one in (BOSS); its death or leaving goes back (BOSSDEAD,
//   BOSSGONE), and where it is while it lives (BOSSPOS).
// - The Sandbox's "CHEATS ENABLED" box stays hidden (the Sandbox is how Ultracraft runs, not a cheat).

using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;
using HarmonyLib;
using TMPro;
using ULTRAKILL.Cheats;
using UnityEngine;
using UnityEngine.UI;

namespace UltraBridge
{
    public partial class Bridge
    {
        // ------------------------------------------------------------ P and gear

        /// <summary>This world's P (Minecraft keeps it; deltas go there as they happen).</summary>
        public static int UcMoney;
        /// <summary>The world unlocks everything (Minecraft's allGear setting).</summary>
        public static bool UcAllGear;
        static readonly HashSet<string> UcGear = new HashSet<string> { "rev0", "arm0" };
        static bool gearKnown;
        int styleSeen = -1;
        float nextStylePoll;

        /// <summary>Every shipped weapon variant, the alternates, the Knuckleblaster (arm1) and the Whiplash (arm2); not
        /// the unshipped slots (rev3, arm3...) whose prefabs don't exist.</summary>
        public static readonly HashSet<string> Shipped = new HashSet<string>
        {
            "rev0", "rev1", "rev2", "revalt", "sho0", "sho1", "sho2", "shoalt", "nai0", "nai1", "nai2", "naialt",
            "rai0", "rai1", "rai2", "rock0", "rock1", "rock2", "arm1", "arm2"
        };

        /// <summary>What ULTRAKILL never sells (it hands them out in its levels), priced for Ultracraft's shop.</summary>
        static readonly Dictionary<string, int> UcPrices = new Dictionary<string, int>
        {
            { "sho0", 5000 }, { "nai0", 10000 }, { "rock0", 25000 }, { "rai0", 40000 },
            { "arm1", 15000 }, { "arm2", 20000 },
            { "revalt", 30000 }, { "shoalt", 30000 }, { "naialt", 30000 },
        };

        // ------------------------------------------------------------ upgrades (what they do: Upgrades.cs)

        static readonly Dictionary<int, string> weaponKinds = new Dictionary<int, string>();

        /// <summary>Which of V1's weapons a weapon object is (rev, sho, nai, rai, rock), or null for anything else.</summary>
        public static string WeaponKind(GameObject w)
        {
            if (w == null) return null;
            int id = w.GetInstanceID();
            if (weaponKinds.TryGetValue(id, out var k)) return k;
            k = w.GetComponent<Revolver>() != null ? "rev"
                : w.GetComponent<Shotgun>() != null || w.GetComponent<ShotgunHammer>() != null ? "sho"
                : w.GetComponent<Nailgun>() != null ? "nai"
                : w.GetComponent<Railcannon>() != null ? "rai"
                : w.GetComponent<RocketLauncher>() != null ? "rock" : null;
            weaponKinds[id] = k;
            return k;
        }

        /// <summary>Which of V1's weapons this world has equipped: weapon.rev0 = 0 (off), 1 (on), 2 (the alternate);
        /// a missing one is on.</summary>
        public static readonly Dictionary<string, int> Equips = new Dictionary<string, int>();

        public static bool IsEquipKey(string key) => key != null && key.StartsWith("weapon.") && (key == "weapon.arm0" || Shipped.Contains(key.Substring(7)));

        public static int EquipOf(string key) => Equips.TryGetValue(key, out var v) ? v : 1;

        public void SetEquip(string key, int value)
        {
            Equips[key] = value;
            if (Net.Connected) Net.Send("EQUIP " + key + " " + value);
        }

        public static bool Owns(string gear)
        {
            if (string.IsNullOrEmpty(gear)) return false;
            // the Feedbacker is V1's own arm
            if (gear == "arm0") return true;
            if (gear.StartsWith("color")) return UcAllGear || UcGear.Contains(gear);
            if (!Shipped.Contains(gear)) return false;
            return UcAllGear || UcGear.Contains(gear);
        }

        static int PriceOf(string gear, int shopCost)
        {
            if (UcPrices.TryGetValue(gear, out var p)) return p;
            if (shopCost > 0) return shopCost;
            // a variant the shop has no price for: green, then red, cost more
            return gear.EndsWith("2") ? 20000 : 10000;
        }

        /// <summary>The shop spent (or the world paid) P: ULTRAKILL's AddMoney, kept here instead of in the save.</summary>
        /// <summary>The P V1 can spend (endless with the Infinite P cheat).</summary>
        static int Spendable => InfiniteP ? int.MaxValue / 2 : UcMoney;

        public void AddUcMoney(int delta)
        {
            // Infinite P: the shop takes nothing
            if (InfiniteP && delta < 0) return;
            UcMoney = Math.Max(0, UcMoney + delta);
            if (Net.Connected) Net.Send("PADD " + delta);
        }

        /// <summary>The shop sold a piece of gear: ULTRAKILL's AddGear, kept here instead of in the save.</summary>
        public void AddUcGear(string gear)
        {
            if (string.IsNullOrEmpty(gear) || !UcGear.Add(gear)) return;
            if (Net.Connected) Net.Send("GEARADD " + gear);
        }

        void HandleProgress(string cmd, string rest)
        {
            switch (cmd)
            {
                case "WORLDRESET":
                {
                    // Minecraft left its world: nothing of that world's carries over to the next one (its own GEAR,
                    // EQUIPS and UPGRADES come when V1 is there); until then V1 starts as a new world's V1 would
                    UcMoney = 0;
                    UcGear.Clear();
                    UcGear.Add("rev0");
                    UcGear.Add("arm0");
                    UcAllGear = false;
                    Equips.Clear();
                    foreach (var t in UcUp.Values) { t.level = 1; t.bought = 1; }
                    gearKnown = false;
                    HandleSky("-");
                    if (levelPrepared) RefreshLoadout();
                    UpgradeTexts();
                    // and the position sync starts over with it. ULTRAKILL stays on its menu scene, where V1 still
                    // stands wherever the world we just left put it, and keeps sending that pose: Minecraft follows
                    // it into the next world, the server rejects the spot and pushes back. No origin, no pose and no
                    // TP until the next world sends its own.
                    originSet = false;
                    tpReceived = false;
                    v1Landed = false;
                    Plugin.Log.LogInfo("world left: progress reset until the next world's comes");
                    break;
                }
                case "GEAR":
                {
                    // GEAR money all|gear,gear,...: what V1 has in this world
                    var a = rest.Split(' ');
                    if (a.Length < 1) break;
                    UcMoney = int.Parse(a[0]);
                    bool all = a.Length > 1 && a[1] == "all";
                    var had = new HashSet<string>(UcGear);
                    bool hadAll = UcAllGear;
                    UcGear.Clear();
                    UcGear.Add("rev0");
                    UcGear.Add("arm0");
                    if (a.Length > 1 && !all) foreach (var g in a[1].Split(',')) if (g.Length > 0 && g != "-") UcGear.Add(g);
                    UcAllGear = all;
                    bool changed = !gearKnown || hadAll != all || !had.SetEquals(UcGear);
                    gearKnown = true;
                    if (changed) RefreshLoadout();
                    RefreshShopGear();
                    break;
                }
                case "EQUIPS":
                {
                    // EQUIPS weapon.rev0=2,...: what this world has equipped (sent before GEAR)
                    var now = new Dictionary<string, int>();
                    foreach (var kv in rest.Trim().Split(','))
                    {
                        var e = kv.Split('=');
                        if (e.Length == 2 && IsEquipKey(e[0]) && int.TryParse(e[1], out var v)) now[e[0]] = v;
                    }
                    bool changed = now.Count != Equips.Count;
                    foreach (var kv in now) if (!Equips.TryGetValue(kv.Key, out var old) || old != kv.Value) changed = true;
                    if (!changed) break;
                    Equips.Clear();
                    foreach (var kv in now) Equips[kv.Key] = kv.Value;
                    RefreshLoadout();
                    RefreshShopGear();
                    break;
                }
                case "MONEY":
                    // MONEY m: the world's P after a change (a boss paid out, or our own spending came back)
                    UcMoney = int.Parse(rest.Trim());
                    RefreshShopGear();
                    break;
                case "UPGRADES":
                {
                    // UPGRADES key=level/max/cost,...: every upgrade's level in this world
                    bool rose = ApplyUpgrades(rest);
                    if (rose && upgradeSound != null) Instantiate(upgradeSound);
                    UpgradeTexts();
                    break;
                }
                case "HUD":
                {
                    // HUD text: a line in ULTRAKILL's own hint box
                    var hud = levelPrepared ? MonoSingleton<HudMessageReceiver>.Instance : null;
                    if (hud != null) hud.SendHudMessage(rest, "", "", 0, false);
                    break;
                }
                case "BOSS":
                {
                    // BOSS id key x y z [difficulty mods [intro [flags]]]: one of ULTRAKILL's bosses arrives there (feet,
                    // Minecraft coordinates), with its difficulty (0 EASY .. 4 V1 MUST DIE), modifiers (radiant,volatile or -),
                    // its entrance (intro, introfull or nointro: BossIntro.cs), and flags: alt=<entrance> for another of
                    // its entrances, outro for its death scene (BossOutro.cs)
                    var a = rest.Split(' ');
                    if (a.Length < 5) break;
                    int id = int.Parse(a[0]);
                    if (!levelPrepared || !originSet)
                    {
                        Net.Send("BOSSGONE " + id + " notready");
                        break;
                    }
                    int tier = a.Length > 5 ? int.Parse(a[5]) : 0;
                    var mods = a.Length > 6 && a[6] != "-" ? a[6].Split(',') : new string[0];
                    string alt = null;
                    bool outro = false;
                    // the rest: its Boss Setup (BossSetup.cs), name=value or a name alone
                    var setup = new Dictionary<string, string>();
                    if (a.Length > 8)
                        foreach (var fl in a[8].Split(','))
                        {
                            if (fl.Length == 0 || fl == "-") continue;
                            if (fl.StartsWith("alt=")) alt = fl.Substring(4);
                            else if (fl == "outro") outro = true;
                            else
                            {
                                int eq = fl.IndexOf('=');
                                if (eq > 0) setup[fl.Substring(0, eq)] = fl.Substring(eq + 1);
                                else setup[fl] = "1";
                            }
                        }
                    SpawnBoss(id, a[1], new Vector3(F(a[2]), F(a[3]), F(a[4])), tier, mods, a.Length > 7 && a[7].StartsWith("intro"), a.Length > 7 && a[7] == "introfull", alt, outro, setup);
                    break;
                }
                case "BOSSCLEAR":
                {
                    // BOSSCLEAR [id]: the boss leaves (V1 died, ran, or turned back into Steve)
                    var t = rest.Trim();
                    if (t.Length == 0) ClearBosses();
                    else ClearBoss(int.Parse(t));
                    break;
                }
                case "BOSSINFO":
                {
                    // debug: the bosses here and how they are
                    var sb = new StringBuilder("BOSSINFO v2s=" + FindObjectsOfType<V2>().Length + " n=" + bosses.Count + " money=" + UcMoney + " all=" + UcAllGear + " gear=" + string.Join(",", UcGear));
                    foreach (var f in bosses.Values)
                    {
                        sb.Append(" | ").Append(f.id).Append(' ').Append(f.key);
                        foreach (var part in f.parts)
                        {
                            bool alive = part.go != null && part.eid != null;
                            sb.Append(" [").Append(alive ? part.eid.enemyType.ToString() : "gone")
                              .Append(" hp=").Append(alive ? S(part.eid.health) : "-")
                              .Append(" dead=").Append((object)part.eid != null && part.eid.dead)
                              .Append(" active=").Append(part.go != null && part.go.activeInHierarchy)
                              .Append(" bar=").Append(alive && part.eid.GetComponent<BossHealthBar>() != null ? part.eid.GetComponent<BossHealthBar>().bossName : "-")
                              .Append(" tier=").Append(part.tier).Append(" mods=").Append(string.Join("/", new List<string>(part.mods).ToArray())).Append(" twin=").Append(part.twin)
                              .Append(" diff=").Append(alive ? part.eid.difficultyOverride : -1)
                              .Append(" hpx=").Append(alive ? S(part.eid.totalHealthModifier) : "-").Append(" spx=").Append(alive ? S(part.eid.totalSpeedModifier) : "-")
                              .Append(" dmgx=").Append(alive ? S(part.eid.totalDamageModifier) : "-").Append(" scale=").Append(alive ? S(part.go.transform.localScale.x) : "-")
                              .Append(" aim=").Append(alive ? AimOf(part.eid) : -2)
                              .Append(" tgt=").Append(!alive || part.eid.target == null ? "none" : part.eid.target.isPlayer ? "me" : part.eid.target.enemyIdentifier != null ? part.eid.target.enemyIdentifier.name : "other")
                              .Append(" at=").Append(alive ? UkToMc(part.eid.transform.position).ToString() : "-").Append(']');
                        }
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                case "BOSSHURT":
                {
                    // debug: BOSSHURT id damage: hurt the boss (a big number kills it)
                    var a = rest.Split(' ');
                    if (a.Length < 2 || !bosses.TryGetValue(int.Parse(a[0]), out var f)) break;
                    foreach (var part in f.parts)
                        if (part.go != null && part.eid != null && !part.eid.dead) part.eid.SimpleDamage(F(a[1]));
                    break;
                }
                case "GEARINFO":
                {
                    // debug: the loadout and the shop's prices as they stand
                    var sb = new StringBuilder("GEARINFO money=" + UcMoney + " all=" + UcAllGear + " known=" + gearKnown + " gear=" + string.Join(",", UcGear));
                    // what V1 carries: each weapon slot's guns
                    var gc = levelPrepared ? MonoSingleton<GunControl>.Instance : null;
                    if (gc != null && gc.slots != null)
                    {
                        sb.Append(" slots=");
                        foreach (var slot in gc.slots)
                        {
                            sb.Append('[');
                            if (slot != null) foreach (var gun in slot) if (gun != null) sb.Append(gun.name.Replace("(Clone)", "").Replace(' ', '_')).Append(' ');
                            sb.Append(']');
                        }
                        if (gc.currentWeapon != null) sb.Append(" current=").Append(gc.currentSlotIndex).Append('/').Append(gc.currentVariationIndex).Append(':').Append(gc.currentWeapon.name.Replace("(Clone)", "").Replace(' ', '_'));
                    }
                    var fc = levelPrepared ? MonoSingleton<FistControl>.Instance : null;
                    if (fc != null) sb.Append(" arms=").Append(string.Join(",", Traverse.Create(fc).Field("spawnedArmNums").GetValue<List<int>>() ?? new List<int>()));
                    foreach (var go in shops.Values)
                    {
                        if (go == null) continue;
                        foreach (var vi in go.GetComponentsInChildren<VariationInfo>(true))
                            sb.Append(' ').Append(vi.weaponName).Append('=').Append(vi.cost).Append(vi.alreadyOwned ? "(owned)" : "");
                        break;
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                default:
                    HandleSettings(cmd, rest);
                    break;
            }
        }

        /// <summary>The style V1 earns becomes this world's P, point for point, as ULTRAKILL pays it out at a level's end.</summary>
        void PollStyle()
        {
            if (Time.unscaledTime < nextStylePoll) return;
            nextStylePoll = Time.unscaledTime + 0.5f;
            var sm = MonoSingleton<StatsManager>.Instance;
            if (sm == null) return;
            int pts = sm.stylePoints;
            // the first look, or the Sandbox started its count over
            if (styleSeen < 0 || pts < styleSeen)
            {
                styleSeen = pts;
                return;
            }
            int gain = pts - styleSeen;
            if (gain <= 0) return;
            styleSeen = pts;
            UcMoney += gain;
            Net.Send("PEARN " + gain);
        }

        /// <summary>The guns and arms V1 carries, rebuilt from what it owns now.</summary>
        void RefreshLoadout()
        {
            if (!levelPrepared || !v1Landed) return;
            try
            {
                MonoSingleton<GunSetter>.Instance?.ResetWeapons();
                MonoSingleton<FistControl>.Instance?.ResetFists();
                if (hands || shopTouch) MonoSingleton<GunControl>.Instance?.NoWeapon();
            }
            catch (Exception e) { Plugin.Log.LogWarning("loadout: " + e.Message); }
        }

        // ------------------------------------------------------------ the shop selling gear

        // each shop's alternate buttons: the button, the gear it sells, the weapon it needs, the purchase sound
        readonly List<(GameObject button, string gear, string needs, GameObject sound)> altButtons = new List<(GameObject, string, string, GameObject)>();

        /// <summary>A new shop's Weapons pages, made this world's: every weapon listed, priced, owned or not by what this
        /// world has; and on the revolver, shotgun and nailgun pages a button for the alternate version.</summary>
        void DressShopGear(GameObject go)
        {
            var log = new StringBuilder();
            foreach (var vi in go.GetComponentsInChildren<VariationInfo>(true))
            {
                var w = vi.weaponName;
                if (string.IsNullOrEmpty(w)) continue;
                log.Append(' ').Append(w).Append('=').Append(vi.cost);
                if (!Shipped.Contains(w) && w != "arm0") continue;
                if (w != "rev0" && w != "arm0") vi.cost = PriceOf(w, vi.cost);
                vi.alreadyOwned = Owns(w);
            }
            if (!shopPricesLogged)
            {
                shopPricesLogged = true;
                Plugin.Log.LogInfo("shop prices (ULTRAKILL's):" + log);
            }
            var done = new HashSet<Transform>();
            foreach (var vi in go.GetComponentsInChildren<VariationInfo>(true))
            {
                var list = vi.transform.parent;
                var w = vi.weaponName;
                if (list == null || string.IsNullOrEmpty(w) || w.Length < 2 || !done.Add(list)) continue;
                var kind = w.Substring(0, w.Length - 1);
                if (kind != "rev" && kind != "sho" && kind != "nai") continue;
                var row = list.Find("Info and Color Panel");
                var color = row != null ? row.Find("ColorButton") : null;
                if (color == null) continue;
                var alt = Instantiate(color.gameObject, row, false);
                alt.name = "AltButton";
                string gear = kind + "alt", needs = kind + "0";
                var sound = vi.buySound;
                Rewire(alt.transform, () =>
                {
                    if (Owns(gear)) ToggleAlternate(kind, false);
                    else BuyAlternate(gear, needs, sound);
                });
                var text = alt.GetComponentInChildren<TMP_Text>(true);
                if (text != null)
                {
                    text.enableAutoSizing = true;
                    text.fontSizeMin = 6f;
                    text.fontSizeMax = Mathf.Max(text.fontSize, 8f);
                }
                FitRow(row);
                altButtons.Add((alt, gear, needs, sound));
            }
            ShopGearTexts();
            try { AddUpgradesPage(go); }
            catch (Exception e) { Plugin.Log.LogWarning("upgrades page: " + e); }
        }

        bool shopPricesLogged;

        // ------------------------------------------------------------ the shop's Upgrades page

        class UpgradePage
        {
            public GameObject page;
            public readonly Dictionary<string, Transform> buttons = new Dictionary<string, Transform>();
            public TMP_Text title, text;
            /// <summary>The P V1 has, on this page too (the weapons page gets one from each weapon window; this page has none).</summary>
            public MoneyText money;
            /// <summary>The shop's own P label on the top bar (a sibling of the pages), shown or hidden with the page.</summary>
            public GameObject moneyGo;
            /// <summary>The top strip's own bar that carries the P (the shop cuts this one out per page).</summary>
            public Transform moneyBar;
            public Vector2 moneyHome;
            public readonly List<UpgradeBuy> buys = new List<UpgradeBuy>();
            public string group = "rev";
            /// <summary>Which of the picked weapon's or arm's upgrades the Buy button buys.</summary>
            public int track;
            /// <summary>The button the window came with (Sandbox's Enter), kept as the source the five others are copied from.</summary>
            public RectTransform source;
            /// <summary>The text area and its row, which have to be moved up as the buttons take their room, and where
            /// they were before that (the shift is redone whenever the page is shown, so it has to start from these).</summary>
            public RectTransform inset, row;
            public Vector2 insetBase, rowBase;
            /// <summary>The drop-down that wraps the text, so the shrinking is not clamped straight back.</summary>
            public LayoutElement drop;
            public float dropBase;
            /// <summary>The source button's height, width and gap, kept for the re-fit every time the page is shown.</summary>
            public float bh, bw, bgap;
        }

        class UpgradeBuy
        {
            public RectTransform rt;
            public TMP_Text text;
            public Image image;
            public ShopButton sb;
            /// <summary>Level buttons keep the colour the update set; the others are reset there.</summary>
            public bool level;
        }

        readonly List<UpgradePage> upgradePages = new List<UpgradePage>();
        GameObject upgradeSound;
        /// <summary>The main menu's own Upgrades button. The upgrade page refreshes itself when Minecraft's
        /// language changes, but that button sits outside the page, so it is refreshed from here too.</summary>
        TMP_Text upgradesMenuLabel;

        /// <summary>Puts the menu's Upgrades button in Minecraft's language, font and all.</summary>
        void LocalizeMenuButton()
        {
            if (upgradesMenuLabel == null) return;
            upgradesMenuLabel.text = UiText("Upgrades");
            ApplyShopFont(upgradesMenuLabel.transform);
        }
        static readonly string[] UpgradeButtons = { "RevolverButton", "ShotgunButton", "NailgunButton", "RailcannonButton", "RocketLauncherButton", "ArmButton" };
        // the arms get a button each: ArmButton and two copies of it
        static readonly string[] UpgradeListGroups = { "rev", "sho", "nai", "rai", "rock", "arm0", "arm1", "arm2" };

        static string[] GroupOf(string group)
        {
            foreach (var g in UpGroups) if (g[0] == group) return g;
            return UpGroups[0];
        }

        /// <summary>The upgrades of a weapon or arm: its Power, then its own.</summary>
        static string[] TracksOf(string group)
        {
            var g = GroupOf(group);
            var keys = new List<string> { group + ".power" };
            for (int i = 2; i + 1 < g.Length; i += 2) keys.Add(group + "." + g[i]);
            return keys.ToArray();
        }

        static string TrackName(string key)
        {
            int dot = key.IndexOf('.');
            var g = GroupOf(key.Substring(0, dot));
            string t = key.Substring(dot + 1);
            if (t == "power") return UpName("Power");
            for (int i = 2; i + 1 < g.Length; i += 2) if (g[i] == t) return UpName(g[i + 1]);
            return t;
        }

        /// <summary>A page of SmileOS's own: Upgrades, under Weapons on the main menu. It is the Weapons page's list
        /// (one button per weapon, and Arms, which goes through the Feedbacker, Knuckleblaster and Whiplash) with a
        /// window beside it (the Sandbox page's) telling the picked one's three upgrades, with a button to buy each.</summary>
        void AddUpgradesPage(GameObject go)
        {
            var main = go.transform.Find("Canvas/Background/Main Panel");
            var weapons = main != null ? main.Find("Weapons") : null;
            var sandboxPanel = main != null ? main.Find("Sandbox/Sandbox Panel") : null;
            var menuButtons = main != null ? main.Find("Main Menu/Buttons") : null;
            var sandboxButton = menuButtons != null ? menuButtons.Find("SandboxButton") : null;
            var weaponsButton = menuButtons != null ? menuButtons.Find("WeaponsButton") : null;
            var mainMenu = main != null ? main.Find("Main Menu") : null;
            if (weapons == null || sandboxPanel == null || sandboxButton == null || weaponsButton == null || mainMenu == null)
            {
                Plugin.Log.LogWarning("upgrades page: the shop isn't laid out as expected");
                return;
            }
            var tipT = main.Find("Tip of the Day");
            var tip = tipT != null ? tipT.gameObject : null;
            if (upgradeSound == null)
            {
                var vi = go.GetComponentInChildren<VariationInfo>(true);
                if (vi != null) upgradeSound = vi.buySound;
            }
            var shape = weapons.Find("Revolver Window/Variation Screen") as RectTransform;

            // the list: the Weapons page without its weapon windows
            var page = Instantiate(weapons.gameObject, main, false);
            page.name = "Upgrades";
            page.SetActive(false);
            foreach (var c in page.GetComponents<ShopGearChecker>()) DestroyImmediate(c);
            // the weapons page's own top bar carries the P, and so does every weapon window. The windows go, the bar
            // stays (otherwise this page would have no dosh on it at all)
            var drop = new List<GameObject>();
            foreach (Transform child in page.transform)
            {
                bool money = child.GetComponentInChildren<MoneyText>(true) != null;
                bool window = child.name.IndexOf("Window", StringComparison.Ordinal) >= 0;
                if (child.name != "Weapons Panel" && !(money && !window)) drop.Add(child.gameObject);
            }
            foreach (var d in drop) DestroyImmediate(d);
            var panel = page.transform.Find("Weapons Panel");
            if (panel == null) return;
            SetText(panel.Find("Menu Title"), UiText("Upgrades"));
            var up = new UpgradePage { page = page };
            var list = panel.Find("Buttons");
            // the Weapons list greys out the button last pressed (and ignores it until another is): here every button
            // stays pressable, and the picked one is marked instead
            if (list != null) foreach (var lc in list.GetComponents<ShopButtonListController>()) DestroyImmediate(lc);
            var entries = new List<Transform>();
            for (int i = 0; i < UpgradeButtons.Length && list != null; i++)
            {
                var found = list.Find(UpgradeButtons[i]);
                if (found != null) entries.Add(found);
            }
            var armButton = list != null ? list.Find("ArmButton") : null;
            if (armButton != null && entries.Count == UpgradeButtons.Length)
            {
                // two more arm buttons, and the eight laid out where the six were (a little slimmer)
                for (int k = 1; k <= 2; k++)
                {
                    var copy = Instantiate(armButton.gameObject, armButton.parent, false).transform;
                    copy.name = "ArmButton" + k;
                    copy.SetSiblingIndex(armButton.GetSiblingIndex() + k);
                    entries.Add(copy);
                }
                var first = entries[0] as RectTransform;
                var last = armButton as RectTransform;
                if (first != null && last != null)
                {
                    float top = first.anchoredPosition.y, bottom = last.anchoredPosition.y;
                    float oldStep = (top - bottom) / 5f, step = (top - bottom) / 7f;
                    float height = first.sizeDelta.y * step / Mathf.Max(oldStep, 0.001f);
                    for (int k = 0; k < entries.Count; k++)
                    {
                        var rt = entries[k] as RectTransform;
                        if (rt == null) continue;
                        rt.anchoredPosition = new Vector2(rt.anchoredPosition.x, top - k * step);
                        rt.sizeDelta = new Vector2(rt.sizeDelta.x, height);
                    }
                }
            }
            for (int i = 0; i < entries.Count && i < UpgradeListGroups.Length; i++)
            {
                var b = entries[i];
                foreach (var c in b.GetComponents<ShopCategory>()) DestroyImmediate(c);
                b.gameObject.SetActive(true);
                var bt = b.GetComponent<Button>();
                if (bt != null) bt.interactable = true;
                var bsb = b.GetComponent<ShopButton>();
                if (bsb != null) bsb.deactivated = false;
                string kind = UpgradeListGroups[i];
                Rewire(b, () =>
                {
                    if (up.group != kind) up.track = 0;
                    up.group = kind;
                    UpgradeTexts();
                });
                var t = b.GetComponentInChildren<TMP_Text>(true);
                if (t != null)
                {
                    t.enableAutoSizing = true;
                    t.fontSizeMin = 6f;
                    t.fontSizeMax = Mathf.Max(t.fontSize, 8f);
                }
                up.buttons[kind] = b;
            }
            var back = list != null ? list.Find("BackButton") : null;
            var menuGo = mainMenu.gameObject;
            if (back != null)
                Rewire(back, () =>
                {
                    page.SetActive(false);
                    menuGo.SetActive(true);
                    if (tip != null) tip.SetActive(true);
                });

            // the window beside it: the Sandbox page's (a title, a text and a button), where a weapon window would be
            var info = Instantiate(sandboxPanel.gameObject, page.transform, false);
            info.name = "Upgrade Panel";
            var irt = info.transform as RectTransform;
            if (shape != null && irt != null)
            {
                irt.anchorMin = irt.anchorMax = new Vector2(0.5f, 0.5f);
                irt.pivot = shape.pivot;
                irt.sizeDelta = shape.rect.size;
                irt.position = shape.position;
                irt.rotation = shape.rotation;
                irt.localScale = Vector3.one;
            }
            var titleT = info.transform.Find("Title");
            up.title = titleT != null ? titleT.GetComponent<TMP_Text>() : null;
            // the P: the shop keeps its own label on the top bar, which is a sibling of the pages rather than part of
            // any one of them (Canvas/Background/Money Display), so this page shows that very node instead of a copy --
            // that is the label the Weapons page draws, in the place and with the manner it appears there
            var md = go.transform.Find("Canvas/Background/Money Display");
            up.money = md != null ? md.GetComponentInChildren<MoneyText>(true) : null;
            // the label sits on its own bar (the HudOpenEffect is on the bar, not on the label), and the shop cuts that
            // bar out of the top strip every time a page is chosen - so this page asks the bar to do it again for itself
            up.moneyGo = md != null ? md.gameObject : null;
            // the bar is the label's own parent (Money Display: a rect, a background image and the open effect);
            // one step higher is the whole shop background, which would unfold the entire screen
            // Money Display is the bar itself: its own rect, its own background image and the label on it. It carries no
            // effect of its own -- the only HudOpenEffect nearby is on the shop Background, one level up, and playing that
            // unfolds the whole screen -- so this page switches the bar on and leaves the rest of the shop alone.
            up.moneyBar = up.moneyGo != null ? up.moneyGo.transform : null;
            up.moneyHome = up.moneyBar is RectTransform mbr ? mbr.anchoredPosition : Vector2.zero;
            // nothing to reuse (a shop laid out differently): a copy of any label is put under the title instead
            if (up.money == null && titleT != null && titleT.parent != null)
            {
                MoneyText src = null;
                foreach (var mt in FindObjectsOfType<MoneyText>(true))
                    if (mt != null && mt.gameObject != info && mt.transform.IsChildOf(info.transform) == false) { src = mt; break; }
                GameObject moneyGo;
                if (src != null)
                {
                    moneyGo = Instantiate(src.gameObject, titleT.parent, false);
                    moneyGo.name = "Upgrade Money";
                    moneyGo.SetActive(true);
                }
                else
                {
                    moneyGo = new GameObject("Upgrade Money", typeof(RectTransform), typeof(TextMeshProUGUI));
                    var plain = moneyGo.GetComponent<TextMeshProUGUI>();
                    var tt = titleT.GetComponent<TMP_Text>();
                    if (tt != null)
                    {
                        plain.font = tt.font;
                        plain.fontSharedMaterial = tt.fontSharedMaterial;
                        plain.fontSize = tt.fontSize;
                    }
                    else plain.fontSize = 20f;
                    plain.alignment = TextAlignmentOptions.Right;
                    plain.color = Color.white;
                    plain.raycastTarget = false;
                }
                var mrt = (RectTransform)moneyGo.transform;
                mrt.SetParent(titleT, false);
                mrt.SetAsLastSibling();
                mrt.anchorMin = new Vector2(1f, 0.5f);
                mrt.anchorMax = new Vector2(1f, 0.5f);
                mrt.pivot = new Vector2(1f, 0.5f);
                float th = titleT is RectTransform trt ? trt.rect.height : 20f;
                mrt.sizeDelta = new Vector2(Mathf.Max(160f, mrt.sizeDelta.x), Mathf.Max(20f, th));
                mrt.anchoredPosition = new Vector2(-10f, 0f);
                mrt.localScale = Vector3.one;
                up.money = moneyGo.GetComponent<MoneyText>();
                if (up.money == null) up.money = moneyGo.AddComponent<MoneyText>();
                up.money.UpdateMoney();
            }
            var textT = info.transform.Find("Panel/Text Inset/Text");
            up.text = textT != null ? textT.GetComponent<TMP_Text>() : null;
            if (up.text != null)
            {
                up.text.enableAutoSizing = true;
                up.text.fontSizeMin = 5f;
                up.text.fontSizeMax = Mathf.Max(up.text.fontSize, 8f);
            }
            // the text area, its row and the drop-down over it all have to give up the room the buttons take
            var insetIn = info.transform.Find("Panel/Text Inset") as RectTransform;
            if (insetIn != null)
            {
                up.inset = insetIn;
                up.row = insetIn.parent as RectTransform;
                up.drop = up.row != null ? up.row.GetComponent<LayoutElement>() : null;
            }
            // where the one Enter button was: three rows. Buy alone at the top, then which upgrade (^ above, v below),
            // then its level (- and +). The original button is switched off and only used as the source of the copies:
            // something in the panel owns its position, so moving it by hand does nothing.
            var enter = info.transform.Find("Panel/Enter Button") as RectTransform;
            if (enter != null)
            {
                up.source = enter;
                // the button stays where the panel puts it and becomes Buy; the four small ones are copies in the row
                // just above it, which is the only room this window has to spare
                Rewire(enter, () => BuyUpgrade(up));
                float h = enter.rect.height, w = enter.rect.width, gap = Mathf.Max(2f, h * 0.15f);
                float quarter = (w - 3f * gap) / 4f;
                for (int i = 1; i <= 4; i++)
                {
                    var bt = Instantiate(enter.gameObject, enter.parent, false).transform as RectTransform;
                    bt.name = "Upgrade Button " + i;
                    bt.gameObject.SetActive(true);
                    bt.sizeDelta = new Vector2(bt.sizeDelta.x - (w - quarter), bt.sizeDelta.y);
                    // left of each pair is the one that goes back (^ up the list, - down a level), right is on
                    int step = i % 2 == 1 ? -1 : 1;
                    if (i <= 2) Rewire(bt, () =>
                    {
                        // which upgrade Buy buys (it wraps round the list)
                        int n = TracksOf(up.group).Length;
                        up.track = ((up.track + step) % n + n) % n;
                        UpgradeTexts();
                    });
                    else Rewire(bt, () => SetLevel(up, step));
                    var small = new UpgradeBuy { rt = bt, level = i >= 3, text = bt.GetComponentInChildren<TMP_Text>(true), image = bt.GetComponent<Image>(), sb = bt.GetComponent<ShopButton>() };
                    if (small.text != null)
                    {
                        small.text.enableAutoSizing = true;
                        small.text.fontSizeMin = 5f;
                        small.text.fontSizeMax = Mathf.Max(small.text.fontSize, 8f);
                    }
                    if (small.image != null) small.image.color = Color.white;
                    up.buys.Add(small);
                }
                var big = new UpgradeBuy { rt = enter, level = false, text = enter.GetComponentInChildren<TMP_Text>(true), image = enter.GetComponent<Image>(), sb = enter.GetComponent<ShopButton>() };
                if (big.text != null)
                {
                    big.text.enableAutoSizing = true;
                    big.text.fontSizeMin = 5f;
                    big.text.fontSizeMax = Mathf.Max(big.text.fontSize, 8f);
                }
                if (big.image != null) big.image.color = Color.white;
                up.buys.Insert(0, big);
                up.bh = h;
                up.bw = w;
                up.bgap = gap;
                FitButtons(up, true);
            }

            // its button on the main menu, under Weapons
            var ub = Instantiate(sandboxButton.gameObject, menuButtons, false);
            ub.name = "UpgradesButton";
            ub.transform.SetSiblingIndex(weaponsButton.GetSiblingIndex() + 1);
            SetText(ub.transform.Find("Text"), UiText("Upgrades"));
            upgradesMenuLabel = ub.transform.Find("Text") != null ? ub.transform.Find("Text").GetComponent<TMP_Text>() : null;
            LocalizeMenuButton();
            var hide = new List<GameObject> { menuGo };
            if (tip != null) hide.Add(tip);
            var usb = ub.GetComponent<ShopButton>();
            if (usb != null && usb.toDeactivate != null)
                foreach (var g in usb.toDeactivate) if (g != null && !hide.Contains(g)) hide.Add(g);
            Rewire(ub.transform, () =>
            {
                foreach (var g in hide) if (g != null) g.SetActive(false);
                page.SetActive(true);
                // the bar is the shop's own, so it is only ever brought back, never remade: switch it on up the tree
                // (its parent may be off while no page is showing) and let its own effect cut it out of the top strip
                OpenMoneyBar(up);
                UpgradeTexts();
                // shown again: the panel has just run its layout over the buttons and the text, so put them back,
                // then say what the rects really came out as (this is the read that matters, after the layout pass)
                FitButtons(up, false);
            });
            upgradePages.Add(up);
            UpgradeTexts();
        }

        /// <summary>Puts the five buttons in their three rows (Buy on top, then ^ and v, then - and +) and takes the
        /// room they need out of the text above them. The panel runs its own layout every time the page is shown and
        /// that puts the text back, so this is redone each time; the numbers it moved from are kept in the page.</summary>
        void FitButtons(UpgradePage up, bool first)
        {
            if (up.source == null || up.buys.Count < 5) return;
            var parent = up.source.parent as RectTransform;
            if (parent == null) return;
            float h = up.bh, w = up.bw, gap = up.bgap;
            float quarter = (w - 3f * gap) / 4f, lift = h + gap;
            Vector2 origin = up.source.anchoredPosition;
            if (first)
            {
                if (up.inset != null) up.insetBase = up.inset.offsetMin;
                if (up.row != null) up.rowBase = up.row.anchoredPosition;
                if (up.drop != null) up.dropBase = up.drop.preferredHeight;
            }
            // four small buttons in one row just above Buy, in the order ^ v - +
            for (int i = 1; i < up.buys.Count && i <= 4; i++)
            {
                var rt = up.buys[i].rt;
                if (rt == null) continue;
                rt.anchoredPosition = origin + new Vector2((i - 2.5f) * (quarter + gap), lift);
            }
            // the text gives up the row the small buttons took (the inset is the only thing that decides where the
            // text lands; the log says what the rect came out as)
            if (up.inset != null) up.inset.offsetMin = up.insetBase + new Vector2(0f, lift);
            if (up.drop != null) up.drop.preferredHeight = Mathf.Max(20f, up.dropBase - lift);
        }

        /// <summary>Brings the P back for a page. The label lives on the shop's top strip, on its own bar, and that bar
        /// carries the effect that cuts it out of the strip - so it is switched on up the tree and asked to play again, which
        /// is exactly what the shop does when a page is chosen. Nothing is created or moved here.</summary>
        void OpenMoneyBar(UpgradePage up)
        {
            if (up.money == null || up.moneyBar == null) return;
            for (var t = up.moneyBar; t != null && !t.gameObject.activeSelf; t = t.parent) t.gameObject.SetActive(true);
            if (up.moneyBar is RectTransform br) br.anchoredPosition = up.moneyHome;
            up.money.UpdateMoney();
        }

        /// <summary>Moves the picked upgrade down (step -1) or up. It rests at the level it was set to, and nothing is
        /// refunded: it can only go back up to the highest level paid for, which comes back for nothing.</summary>
        void SetLevel(UpgradePage up, int step)
        {
            var all = TracksOf(up.group);
            var key = all[Mathf.Clamp(up.track, 0, all.Length - 1)];
            if (!UcUp.TryGetValue(key, out var t)) return;
            int lv = Mathf.Clamp(t.level, 1, Mathf.Max(1, t.max)) + step;
            if (lv < 1 || lv > Mathf.Clamp(t.bought, 1, Mathf.Max(1, t.max)) || lv == t.level) return;
            Net.Send("UPSET " + key + " " + lv);
        }

        void BuyUpgrade(UpgradePage up)
        {
            var all = TracksOf(up.group);
            var key = all[Mathf.Clamp(up.track, 0, all.Length - 1)];
            if (!UcUp.TryGetValue(key, out var t) || t.bought >= t.max || Spendable < t.cost) return;
            // Minecraft checks, takes the P and answers with UPGRADES and MONEY (nothing with Infinite P)
            Net.Send("UPBUY " + key + (InfiniteP ? " free" : ""));
        }

        void UpgradeTexts()
        {
            for (int p = upgradePages.Count - 1; p >= 0; p--)
            {
                var up = upgradePages[p];
                if (up.page == null)
                {
                    upgradePages.RemoveAt(p);
                    continue;
                }
                // the list: each weapon's power, the arms by the one picked
                for (int i = 0; i < UpgradeListGroups.Length; i++)
                {
                    string kind = UpgradeListGroups[i];
                    if (!up.buttons.TryGetValue(kind, out var b) || b == null) continue;
                    var t = b.GetComponentInChildren<TMP_Text>(true);
                    if (t == null) continue;
                    bool chosen = up.group == kind;
                    t.text = (chosen ? "<color=#FF4343>></color> " : "") + UpName(GroupOf(kind)[1]) + "  <color=#FF4343>" + Mathf.RoundToInt(Power(kind) * 100f) + "%</color>";
                    var bt = b.GetComponent<Button>();
                    if (bt != null) bt.interactable = true;
                }
                var g = GroupOf(up.group);
                if (up.title != null) up.title.text = UpName(g[1]).ToUpperInvariant();
                var tracks = TracksOf(up.group);
                up.track = Mathf.Clamp(up.track, 0, tracks.Length - 1);
                var picked = tracks[up.track];
                if (up.text != null)
                {
                    // every upgrade on a line (the picked one marked), then what the picked one does
                    var sb = new StringBuilder();
                    for (int i = 0; i < tracks.Length; i++)
                    {
                        var key = tracks[i];
                        int lv = UpLevel(key);
                        int max = UcUp.TryGetValue(key, out var tr) ? tr.max : 1;
                        bool on = i == up.track;
                        sb.Append(on ? "<color=#FF4343>> " : "<color=#BBBBBB>").Append(TrackName(key)).Append("</color> ").Append(lv).Append('/').Append(max)
                            .Append(": ").Append(UpEffect(key, lv)).Append('\n');
                    }
                    int plv = UpLevel(picked);
                    int pmax = UcUp.TryGetValue(picked, out var ptr) ? ptr.max : 1;
                    sb.Append('\n').Append(UpAbout(picked));
                    if (plv < pmax) sb.Append("\n<color=#888888>").Append(UiText("Next:")).Append(' ').Append(UpEffect(picked, plv + 1)).Append("</color>");
                    up.text.text = sb.ToString().TrimEnd();
                }
                if (up.money != null) up.money.UpdateMoney();
                for (int i = 0; i < up.buys.Count; i++)
                {
                    var buy = up.buys[i];
                    if (i > 0)
                    {
                        // rows 1 and 2: ^ / v pick the upgrade, - / + its level
                        if (buy.text != null) buy.text.text = i == 1 ? "^" : i == 2 ? "v" : i == 3 ? "-" : "+";
                        // the level buttons grey out where they would do nothing
                        if (buy.level)
                        {
                            UcUp.TryGetValue(picked, out var lt);
                            int lv = lt == null ? 1 : Mathf.Clamp(lt.level, 1, Mathf.Max(1, lt.max));
                            int top = lt == null ? 1 : Mathf.Clamp(lt.bought, 1, Mathf.Max(1, lt.max));
                            bool off = i == 3 ? lv <= 1 : lv >= top;
                            if (buy.sb != null) buy.sb.failure = off;
                            if (buy.image != null) buy.image.color = off ? Color.red : Color.white;
                        }
                        continue;
                    }
                    var key = picked;
                    UcUp.TryGetValue(key, out var tr);
                    bool maxed = tr == null || tr.bought >= tr.max;
                    bool can = !maxed && Spendable >= tr.cost;
                    if (buy.text != null)
                        buy.text.text = maxed ? TrackName(key) + ": " + UiText("Maxed")
                            : can ? UiText("Buy") + " " + TrackName(key) + ": " + MoneyText.DivideMoney(tr.cost) + " <color=#FF4343>P</color>"
                            : "<color=red>" + TrackName(key) + ": " + MoneyText.DivideMoney(tr.cost) + " P</color>";
                    if (buy.image != null) buy.image.color = can || maxed ? Color.white : Color.red;
                    if (buy.sb != null) buy.sb.failure = !can;
                }
                // the Chinese text needs a font with the glyphs: ULTRAKILL's own has none
                ApplyShopFont(up);
            }
        }

        /// <summary>The info, colour and alternate buttons share their row equally.</summary>
        static void FitRow(Transform row)
        {
            var h = row.GetComponent<HorizontalLayoutGroup>();
            var rt = row as RectTransform;
            if (h == null || rt == null) return;
            int n = 0;
            foreach (Transform c in row) if (c.gameObject.activeSelf) n++;
            float width = rt.rect.width;
            if (n == 0 || width <= 1f) return;
            float w = (width - h.padding.horizontal - h.spacing * (n - 1)) / n;
            foreach (Transform c in row)
            {
                if (!c.gameObject.activeSelf) continue;
                var crt = c as RectTransform;
                if (crt != null) crt.sizeDelta = new Vector2(w, crt.sizeDelta.y);
                var le = c.GetComponent<LayoutElement>();
                if (le != null && le.preferredWidth > 0f) le.preferredWidth = w;
            }
        }

        void BuyAlternate(string gear, string needs, GameObject sound)
        {
            if (Owns(gear)) return;
            var hud = MonoSingleton<HudMessageReceiver>.Instance;
            if (!Owns(needs))
            {
                if (hud != null) hud.SendHudMessage("Buy the weapon first.", "", "", 0, false);
                return;
            }
            int price = PriceOf(gear, 0);
            if (Spendable < price) return;
            GameProgressSaver.AddMoney(-price);
            GameProgressSaver.AddGear(gear);
            if (sound != null) Instantiate(sound);
            // straight into V1's hands
            ToggleAlternate(gear.Substring(0, gear.Length - 3), true);
        }

        static readonly Dictionary<string, string> WeaponTitles = new Dictionary<string, string> { { "rev", "Revolver" }, { "sho", "Shotgun" }, { "nai", "Nailgun" } };

        /// <summary>The weapon's variants are the alternate version (any of them).</summary>
        static bool AlternateOn(string kind)
        {
            for (int v = 0; v < 3; v++) if (Owns(kind + v) && EquipOf("weapon." + kind + v) == 2) return true;
            return false;
        }

        /// <summary>The Alternate button, once bought: the weapon's equipped variants switch between the standard and
        /// the alternate version (unequipped ones stay off).</summary>
        void ToggleAlternate(string kind, bool justBought)
        {
            int to = AlternateOn(kind) ? 1 : 2;
            bool any = false;
            for (int v = 0; v < 3; v++)
            {
                string key = "weapon." + kind + v;
                if (!Owns(kind + v) || EquipOf(key) == 0) continue;
                SetEquip(key, to);
                any = true;
            }
            var hud = MonoSingleton<HudMessageReceiver>.Instance;
            if (!any)
            {
                if (hud != null) hud.SendHudMessage("Equip one of its variants first (its arrows).", "", "", 0, false);
                ShopGearTexts();
                return;
            }
            RefreshLoadout();
            RefreshShopGear();
            WeaponTitles.TryGetValue(kind, out var title);
            if (hud != null)
                hud.SendHudMessage(justBought ? "Alternate " + title + " bought and equipped: <color=#FF4343>ALTERNATE</color> switches back and forth."
                    : title + ": " + (to == 2 ? "<color=#FF4343>alternate</color>" : "standard"), "", "", 0, false);
        }

        /// <summary>Every shop's prices, ownership and P after the money or the gear changed.</summary>
        void RefreshShopGear()
        {
            foreach (var go in shops.Values)
            {
                if (go == null) continue;
                foreach (var vi in go.GetComponentsInChildren<VariationInfo>(true))
                {
                    if (string.IsNullOrEmpty(vi.weaponName) || (!Shipped.Contains(vi.weaponName) && vi.weaponName != "arm0")) continue;
                    vi.alreadyOwned = Owns(vi.weaponName);
                    if (vi.isActiveAndEnabled)
                    {
                        try { vi.UpdateMoney(); }
                        catch (Exception e) { Plugin.Log.LogDebug("shop money: " + e.Message); }
                    }
                }
                foreach (var cat in go.GetComponentsInChildren<ShopCategory>(true)) cat.gameObject.SetActive(true);
            }
            foreach (var mt in FindObjectsOfType<MoneyText>())
            {
                try { mt.UpdateMoney(); }
                catch (Exception e) { Plugin.Log.LogDebug("money text: " + e.Message); }
            }
            ShopGearTexts();
            UpgradeTexts();
        }

        void ShopGearTexts()
        {
            for (int i = altButtons.Count - 1; i >= 0; i--)
            {
                var (button, gear, needs, _) = altButtons[i];
                if (button == null)
                {
                    altButtons.RemoveAt(i);
                    continue;
                }
                var text = button.GetComponentInChildren<TMP_Text>(true);
                var image = button.GetComponent<Image>();
                var sb = button.GetComponent<ShopButton>();
                int price = PriceOf(gear, 0);
                bool owned = Owns(gear), can = Owns(needs) && Spendable >= price;
                // once bought it's a switch: standard or alternate
                if (text != null)
                    text.text = owned ? (AlternateOn(gear.Substring(0, gear.Length - 3))
                            ? UiText("ALTERNATE") + "\n<color=#FF4343>" + UiText("ON") + "</color>"
                            : UiText("ALTERNATE") + "\n" + UiText("OFF"))
                        : can ? UiText("ALTERNATE") + "\n" + MoneyText.DivideMoney(price) + " <color=#FF4343>P</color>"
                        : "<color=red>" + UiText("ALTERNATE") + "\n" + MoneyText.DivideMoney(price) + " P</color>";
                if (text != null) ApplyShopFont(text.transform);
                if (image != null) image.color = owned || can ? Color.white : Color.red;
                if (sb != null) sb.failure = !can && !owned;
            }
        }

        // ------------------------------------------------------------ bosses

        class BossPart
        {
            public GameObject go;
            public EnemyIdentifier eid;
            // its difficulty and modifiers (UNDYING brings it back with the same), how high it stands off the
            // ground, where it was last seen alive, and whether its death has been seen to
            public int tier;
            public readonly HashSet<string> mods = new HashSet<string>();
            public bool twin;
            public float lift;
            public Vector3 lastAt;
            public bool deathSeen;
            // its death scene has been started (BossOutro.cs)
            public bool outroStarted;
            // gone from the fight without dying (V2 retreating, the Ferryman paid)
            public bool retreated;
            // the next look for it sunk into the blocks (Unsink)
            public float nextUnsink;
            // where it last stood on something (a boss that drops out of the world comes back there)
            public Vector3 ground;
            public bool hasGround;
            public int putBack;
        }

        class BossFight
        {
            // its Boss Setup (BossSetup.cs), and whether the Ferryman was paid
            public Dictionary<string, string> setup;
            public bool bribed;
            public int id;
            public string key;
            public float spawnedAt;
            public GameObject prefab;
            public SpawnableObject so;
            public float lift;
            public readonly List<BossPart> parts = new List<BossPart>();
        }

        // what Minecraft calls each boss: the Sandbox's own spawn (if it has one), else ULTRAKILL's prefab, and how many
        static readonly Dictionary<string, (string spawnable, string asset, int count)> BossDefs = new Dictionary<string, (string, string, int)>
        {
            { "swordsmachine", ("Swordsmachine", "SwordsMachine NonBoss", 1) },
            { "cerberus", ("Cerberus", "Cerberus", 2) },
            { "hideousmass", ("HideousMass", "Mass", 1) },
            { "mindflayer", ("Mindflayer", "Mindflayer", 1) },
            { "ferryman", ("Ferryman", "Ferryman", 1) },
            { "insurrectionist", ("Sisyphus", "Sisyphus", 1) },
            { "v2", (null, "V2", 1) },
            { "v2green", (null, "V2 Green Arm Variant", 1) },
            { "gabriel", (null, "Gabriel", 1) },
            { "gabriel2", (null, "Gabriel 2nd", 1) },
            { "minosprime", (null, "MinosPrime", 1) },
            { "sisyphusprime", (null, "SisyphusPrime", 1) },
        };

        readonly Dictionary<int, BossFight> bosses = new Dictionary<int, BossFight>();
        readonly HashSet<EnemyIdentifier> bossEids = new HashSet<EnemyIdentifier>();
        readonly Dictionary<string, GameObject> bossPrefabs = new Dictionary<string, GameObject>();
        float nextBossReport;

        /// <summary>A boss fights V1 alone: it never turns on Minecraft's mobs (see Retarget).</summary>
        bool IsBossEid(EnemyIdentifier eid) => eid != null && bossEids.Contains(eid);

        GameObject BossPrefab(string asset)
        {
            if (bossPrefabs.TryGetValue(asset, out var have) && have != null) return have;
            GameObject prefab = null;
            try { prefab = AssetHelper.LoadPrefab("Assets/Prefabs/Enemies/" + asset + ".prefab"); }
            catch (Exception e) { Plugin.Log.LogWarning("boss " + asset + ": " + e.Message); }
            if (prefab != null) bossPrefabs[asset] = prefab;
            return prefab;
        }

        void SpawnBoss(int id, string key, Vector3 mcFeet, int tier, string[] mods, bool intro = false, bool fullIntro = false, string alt = null, bool outro = false, Dictionary<string, string> setup = null)
        {
            ClearBoss(id);
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm == null || !BossDefs.TryGetValue(key, out var def))
            {
                Net.Send("BOSSGONE " + id + " unknown");
                return;
            }
            GameObject prefab = null;
            float lift = 0f;
            SpawnableObject so = null;
            if (def.spawnable != null)
            {
                so = FindSpawnable(def.spawnable);
                if (so != null)
                {
                    prefab = so.gameObject;
                    lift = so.spawnOffset;
                }
            }
            if (prefab == null) prefab = BossPrefab(def.asset);
            if (prefab == null)
            {
                Net.Send("BOSSGONE " + id + " missing");
                return;
            }
            var fight = new BossFight { id = id, key = key, spawnedAt = Time.time, prefab = prefab, so = so, lift = lift, setup = setup };
            var center = McToUk(mcFeet);
            var toV1 = nm.transform.position - center;
            toV1.y = 0f;
            if (toV1.sqrMagnitude < 0.01f) toV1 = Vector3.forward;
            var side = Vector3.Cross(Vector3.up, toV1.normalized);
            // TWIN: one more, a little weaker, tethered to the first
            bool twins = System.Array.IndexOf(mods, "twin") >= 0;
            int count = def.count + (twins ? 1 : 0);
            UcBoss first = null;
            var held = intro && Intros.ContainsKey(key) ? new List<Held>() : null;
            for (int i = 0; i < count; i++)
            {
                // a pair stands side by side, three blocks apart
                var p = center + side * ((i - (count - 1) * 0.5f) * 3f * K);
                if (Physics.Raycast(p + Vector3.up * 3f * K, Vector3.down, out var hit, 8f * K, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore))
                    p = hit.point;
                p += Vector3.up * lift;
                var look = nm.transform.position - p;
                look.y = 0f;
                bool isTwin = twins && i == count - 1;
                // V2's own entrance starts in the air: it drops in and lands
                if (held != null && Intros[key].style == Entrance.Native) p += Vector3.up * 5f * K;
                var go = SpawnBossPart(fight, p, Quaternion.LookRotation(look.sqrMagnitude > 0.01f ? look : Vector3.forward), tier, mods, isTwin, held);
                var ub = go != null ? go.GetComponent<UcBoss>() : null;
                if (ub == null) continue;
                if (first == null) first = ub;
                else if (isTwin) ub.tether = first;
            }
            if (fight.parts.Count == 0)
            {
                Net.Send("BOSSGONE " + id + " failed");
                return;
            }
            bosses[id] = fight;
            if (held != null && held.Count > 0) StartCoroutine(PlayIntro(key, held, tier, fullIntro, alt, setup != null && setup.ContainsKey("first")));
            // its death scene when it's beaten
            if (outro && Outros.ContainsKey(key)) foreach (var part in fight.parts) if (part.eid != null) outroWanted.Add(part.eid);
            // as set up (Boss Setup)
            ApplySetup(fight);
            Net.Send("BOSSSPAWNED " + id + " " + key + " " + fight.parts.Count);
            Plugin.Log.LogInfo("boss " + id + ": " + key + " x" + fight.parts.Count + " at " + mcFeet + " tier " + tier + " mods " + string.Join(",", mods));
        }

        /// <summary>One of a fight's bosses (a pair's one, a twin, one risen again), in the fight and after V1.</summary>
        GameObject SpawnBossPart(BossFight fight, Vector3 pos, Quaternion rot, int tier, IEnumerable<string> mods, bool twin, List<Held> held = null)
        {
            var go = SpawnConfigured(fight.prefab, pos, rot, fight.key, fight.so, tier, mods, twin, held, fight.setup != null && fight.setup.ContainsKey("grind"));
            if (go == null) return null;
            var eid = go.GetComponentInChildren<EnemyIdentifier>(true);
            var part = new BossPart { go = go, eid = eid, tier = tier, twin = twin, lift = fight.lift, lastAt = pos };
            if (mods != null) foreach (var m in mods) if (!string.IsNullOrEmpty(m)) part.mods.Add(m);
            fight.parts.Add(part);
            if (eid != null)
            {
                bossEids.Add(eid);
                TargetV1(eid);
            }
            return go;
        }

        /// <summary>The boss made ready before it wakes (its Awake and Start see these): no level of its own to escape
        /// into or end in, so it fights here and dies here, with ULTRAKILL's boss bar over V1's HUD.</summary>
        GameObject SpawnConfigured(GameObject prefab, Vector3 pos, Quaternion rot, string key, SpawnableObject so, int tier = 0, IEnumerable<string> mods = null, bool twin = false,
            List<Held> held = null, bool grind = false)
        {
            var holder = new GameObject("uc boss holder");
            holder.SetActive(false);
            GameObject go = null;
            bool barLater = false;
            try
            {
                go = Instantiate(prefab, pos, rot, holder.transform);
                go.name = "uc boss " + key;
                if (so != null) (go.GetComponent<Sandbox.EnemySpawnableInstance>() ?? go.AddComponent<Sandbox.EnemySpawnableInstance>()).sourceObject = so;
                foreach (var v2 in go.GetComponentsInChildren<V2>(true))
                {
                    // 1-4's V2 flees through a window at a third of its health, 4-4's falls into a pit: here it fights to
                    // the end and dies like any machine
                    v2.intro = false;
                    v2.longIntro = false;
                    v2.knockOutHealth = 0f;
                    v2.dontDie = false;
                    v2.escapeTarget = null;
                    // its limbs stay on: tearing them off leaves the body in pieces, over Minecraft
                    var ve = v2.GetComponent<Enemy>();
                    if (ve != null) ve.dismemberment = false;
                }
                foreach (var gabe in go.GetComponentsInChildren<GabrielBase>(true))
                {
                    // with a boss bar on at its start Gabriel waits for his level to end the fight; without one he says his
                    // line and leaves when beaten. The bar goes back on once he's up.
                    foreach (var bar in gabe.GetComponents<BossHealthBar>()) DestroyImmediate(bar);
                    barLater = true;
                }
                // a Prime as the Cyber Grind has it: no boss bar at its start, so ULTRAKILL plays its quick death
                if (grind && (go.GetComponentInChildren<MinosPrime>(true) != null || go.GetComponentInChildren<SisyphusPrime>(true) != null))
                {
                    foreach (var bar in go.GetComponentsInChildren<BossHealthBar>(true)) DestroyImmediate(bar);
                    barLater = true;
                }
                var eid = go.GetComponentInChildren<EnemyIdentifier>(true);
                if (eid != null) eid.spawnIn = true;
                PrepareBoss(go, tier, mods, twin);
                // its entrance: held still and unhurtable until it's made it (BossIntro.cs)
                if (held != null)
                {
                    var h = HoldForIntro(go, key);
                    if (h != null) held.Add(h);
                }
                go.transform.SetParent(null, true);
                go.SetActive(true);
                if (eid != null && !barLater && eid.GetComponent<BossHealthBar>() == null) eid.gameObject.AddComponent<BossHealthBar>();
                if (eid != null && barLater) StartCoroutine(BarSoon(eid));
            }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("boss " + key + ": " + e);
                if (go != null) Destroy(go);
                go = null;
            }
            finally
            {
                Destroy(holder);
            }
            return go;
        }

        static IEnumerator BarSoon(EnemyIdentifier eid)
        {
            yield return null;
            yield return null;
            if (eid != null && !eid.dead && eid.GetComponent<BossHealthBar>() == null) eid.gameObject.AddComponent<BossHealthBar>();
        }

        void ClearBoss(int id)
        {
            if (!bosses.TryGetValue(id, out var f)) return;
            bosses.Remove(id);
            foreach (var part in f.parts)
            {
                if (part.eid != null) bossEids.Remove(part.eid);
                // a beaten boss's body stays where it fell; one that leaves goes
                if (part.go != null && (part.eid == null || !part.eid.dead)) Destroy(part.go);
            }
        }

        void ClearBosses()
        {
            foreach (var id in new List<int>(bosses.Keys)) ClearBoss(id);
            bossEids.Clear();
        }

        /// <summary>A flying boss (Gabriel: no ground navigation) whose lower body is inside Minecraft's blocks, lifted to
        /// stand on them. ULTRAKILL lets Gabriel teleport and dash to spots worked out on its own flat floors; among
        /// Minecraft's steps, slabs and walls those land him in the blocks, where he sinks into the floor and shows
        /// through it.</summary>
        void Unsink(BossPart part)
        {
            if (part.go == null || part.eid == null || part.eid.dead || IntroHeld(part.eid) || OutroPlaying(part.eid)) return;
            if (Time.time < part.nextUnsink) return;
            part.nextUnsink = Time.time + 0.2f;
            if (part.go.GetComponentInChildren<UnityEngine.AI.NavMeshAgent>() != null) return;
            var b = EnemyBounds(part.eid);
            if (b.size.y < 0.5f) return;
            int env = LayerMaskDefaults.Get(LMD.Environment);
            // its lower third, a little narrower than it is (wings and swords stick out)
            var half = new Vector3(Mathf.Max(0.2f, b.extents.x * 0.4f), b.extents.y * 0.3f, Mathf.Max(0.2f, b.extents.z * 0.4f));
            var low = new Vector3(b.center.x, b.min.y + half.y + 0.05f, b.center.z);
            if (!Physics.CheckBox(low, half, Quaternion.identity, env, QueryTriggerInteraction.Ignore)) return;
            // the top of what it's in: down from over its head
            if (!Physics.Raycast(new Vector3(b.center.x, b.max.y + 0.5f * K, b.center.z), Vector3.down, out var hit, b.size.y + 0.5f * K, env, QueryTriggerInteraction.Ignore)) return;
            float lift = hit.point.y - b.min.y + 0.05f;
            if (lift <= 0.05f || lift > b.size.y) return;
            foreach (var rb in part.go.GetComponentsInChildren<Rigidbody>())
            {
                if (rb.isKinematic) continue;
                if (rb.velocity.y < 0f) rb.velocity = new Vector3(rb.velocity.x, 0f, rb.velocity.z);
            }
            part.go.transform.position += Vector3.up * lift;
            Physics.SyncTransforms();
        }

        /// <summary>A boss that fell out of the world, back on the ground it last stood on, still.</summary>
        void PutBack(BossPart part)
        {
            var to = part.ground + Vector3.up * (part.lift + 0.5f * K);
            var delta = to - part.eid.transform.position;
            foreach (var rb in part.go.GetComponentsInChildren<Rigidbody>())
            {
                if (rb.isKinematic) continue;
                rb.velocity = Vector3.zero;
                rb.angularVelocity = Vector3.zero;
            }
            var agent = part.eid.GetComponent<UnityEngine.AI.NavMeshAgent>();
            if (agent != null && agent.enabled && agent.isOnNavMesh) agent.Warp(to);
            else part.go.transform.position += delta;
            Physics.SyncTransforms();
        }

        /// <summary>Each boss's state for Minecraft: where it is (BOSSPOS id x y z health), and once it's beaten
        /// (BOSSDEAD id x y z) or gone without being beaten (BOSSGONE id why).</summary>
        void UpdateBosses(NewMovement nm)
        {
            if (bosses.Count == 0) return;
            bool report = Time.unscaledTime >= nextBossReport;
            if (report) nextBossReport = Time.unscaledTime + 1f;
            List<int> over = null;
            foreach (var f in bosses.Values)
            {
                int dead = 0, gone = 0;
                // a beaten boss still going out (its death scene): the fight isn't over yet for Minecraft
                bool goingOut = false;
                float hp = 0f;
                Vector3 at = Vector3.zero;
                bool haveAt = false;
                foreach (var part in f.parts.ToArray())
                {
                    // a destroyed enemy's fields still read: Gabriel's death removes him in the same frame
                    bool isDead = ((object)part.eid != null && part.eid.dead) || (part.retreated && !f.bribed);
                    if (!isDead && part.eid != null) part.lastAt = EnemyBounds(part.eid).center;
                    if (isDead && !part.deathSeen && !part.retreated)
                    {
                        part.deathSeen = true;
                        Plugin.Log.LogInfo("boss " + f.id + ": " + f.key + " part down (mods " + string.Join(",", new List<string>(part.mods).ToArray()) + ", parts " + f.parts.Count + ")");
                        // VOLATILE: it goes up in a big blast
                        if (part.mods.Contains("volatile")) SpawnBlast(part.lastAt, 2.5f, true, true, null, 30);
                        // UNDYING: not yet; it gets back up, at half health
                        if (part.mods.Contains("secondwind"))
                        {
                            f.parts.Remove(part);
                            if (Revive(f, part, nm) != null) continue;
                            f.parts.Add(part);
                        }
                    }
                    if (isDead && !part.outroStarted && !part.mods.Contains("secondwind"))
                    {
                        part.outroStarted = true;
                        StartOutro(f.key, part);
                    }
                    if (isDead && OutroPlaying(part.eid)) goingOut = true;
                    if (isDead)
                    {
                        dead++;
                        if (!haveAt && part.eid != null)
                        {
                            at = EnemyBounds(part.eid).center;
                            haveAt = true;
                        }
                        continue;
                    }
                    if (part.go == null || part.eid == null || !part.go.activeInHierarchy)
                    {
                        gone++;
                        continue;
                    }
                    // fallen out of the world (through ground ULTRAKILL didn't have yet, or off the edge): back where it
                    // last stood, a few times; after that, gone
                    float y = part.eid.transform.position.y;
                    if (report && !part.eid.dead && Physics.Raycast(part.eid.transform.position + Vector3.up * K, Vector3.down, out var under, 4f * K,
                            LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore))
                    {
                        part.ground = under.point;
                        part.hasGround = true;
                    }
                    bool outOfWorld = y < McToUk(new Vector3(0f, -128f, 0f)).y;
                    // (a deep drop with ground at its bottom is a fall, not out of the world)
                    if (outOfWorld || (report && part.hasGround && y < part.ground.y - 40f * K
                            && !Physics.Raycast(part.eid.transform.position, Vector3.down, 96f * K, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)))
                    {
                        if (part.hasGround && part.putBack < 3)
                        {
                            part.putBack++;
                            PutBack(part);
                            Plugin.Log.LogInfo("boss " + f.id + ": " + f.key + " fell out of the world, put back where it stood (" + part.putBack + ")");
                        }
                        else if (outOfWorld)
                        {
                            Destroy(part.go);
                            gone++;
                            continue;
                        }
                    }
                    Unsink(part);
                    hp += Mathf.Max(0f, part.eid.health);
                    if (!haveAt)
                    {
                        at = EnemyBounds(part.eid).center;
                        haveAt = true;
                    }
                }
                if (dead + gone >= f.parts.Count && !goingOut)
                {
                    Plugin.Log.LogInfo("boss " + f.id + ": over, dead " + dead + " gone " + gone + " of " + f.parts.Count);
                    (over ??= new List<int>()).Add(f.id);
                    if (dead > 0)
                    {
                        var mc = UkToMc(haveAt ? at : nm.transform.position);
                        Net.Send("BOSSDEAD " + f.id + " " + S(mc.x) + " " + S(mc.y) + " " + S(mc.z));
                    }
                    else
                    {
                        Net.Send("BOSSGONE " + f.id + (f.bribed ? " bribed" : " lost"));
                    }
                    continue;
                }
                if (report && haveAt)
                {
                    var mc = UkToMc(at);
                    Net.Send("BOSSPOS " + f.id + " " + S(mc.x) + " " + S(mc.y) + " " + S(mc.z) + " " + S(hp));
                }
            }
            if (over == null) return;
            foreach (var id in over)
            {
                var f = bosses[id];
                bosses.Remove(id);
                foreach (var part in f.parts) if (part.eid != null) bossEids.Remove(part.eid);
            }
        }
    }

    // ---------------------------------------------------------------- Harmony: this world's P and gear, not the save's

    /// <summary>For Minecraft, the intro goes to the main menu (and on to the Sandbox) even on a save that hasn't
    /// seen the intro or beaten the tutorial; otherwise it loads the Tutorial and V1 never gets to Minecraft. Only
    /// what's read changes: nothing is written to the save.</summary>
    [HarmonyPatch(typeof(GameProgressSaver))]
    static class ForMinecraftSkipTutorial
    {
        static bool ForMinecraft => Net.Connected || Bridge.LaunchedForMinecraft;

        [HarmonyPrefix, HarmonyPatch(nameof(GameProgressSaver.GetTutorial))]
        static bool Tutorial(ref bool __result)
        {
            if (!ForMinecraft) return true;
            __result = true;
            return false;
        }

        [HarmonyPrefix, HarmonyPatch(nameof(GameProgressSaver.GetIntro))]
        static bool Intro(ref bool __result)
        {
            if (!ForMinecraft) return true;
            __result = true;
            return false;
        }
    }

    [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.CheckGear))]
    static class WorldGear
    {
        static bool Prefix(string gear, ref int __result)
        {
            if (!Bridge.AllWeapons || gear == null) return true;
            __result = Bridge.Owns(gear) ? 1 : 0;
            return false;
        }
    }

    [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.GetMoney))]
    static class WorldMoney
    {
        static bool Prefix(ref int __result)
        {
            if (!Bridge.AllWeapons) return true;
            __result = Bridge.InfiniteP ? 999999999 : Bridge.UcMoney;
            return false;
        }
    }

    [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.AddMoney))]
    static class WorldSpend
    {
        static bool Prefix(int money)
        {
            if (!Bridge.AllWeapons) return true;
            Bridge.I?.AddUcMoney(money);
            return false;
        }
    }

    [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.AddGear))]
    static class WorldBuy
    {
        static bool Prefix(string gear)
        {
            if (!Bridge.AllWeapons) return true;
            Bridge.I?.AddUcGear(gear);
            return false;
        }
    }

    /// <summary>Custom weapon colours (a million P in the shop) belong to the world too.</summary>
    [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.UnlockWeaponCustomization))]
    static class WorldColorUnlock
    {
        static bool Prefix(GameProgressSaver.WeaponCustomizationType weapon)
        {
            if (!Bridge.AllWeapons) return true;
            Bridge.I?.AddUcGear("color" + (int)weapon);
            return false;
        }
    }

    [HarmonyPatch(typeof(GameProgressSaver), nameof(GameProgressSaver.HasWeaponCustomization))]
    static class WorldColorOwned
    {
        static bool Prefix(GameProgressSaver.WeaponCustomizationType weapon, ref bool __result)
        {
            if (!Bridge.AllWeapons) return true;
            __result = Bridge.Owns("color" + (int)weapon);
            return false;
        }
    }

    /// <summary>The Weapons page lists every weapon, owned or not: the ones V1 lacks are bought from their page.</summary>
    [HarmonyPatch(typeof(ShopCategory), nameof(ShopCategory.CheckGear))]
    static class EveryCategory
    {
        static bool Prefix(ShopCategory __instance)
        {
            if (!Bridge.AllWeapons) return true;
            __instance.gameObject.SetActive(true);
            return false;
        }
    }

    /// <summary>A weapon's other variants are for sale once V1 has the weapon itself.</summary>
    [HarmonyPatch(typeof(VariationInfo), nameof(VariationInfo.UpdateMoney))]
    static class VariantNeedsWeapon
    {
        static void Postfix(VariationInfo __instance)
        {
            var w = __instance.weaponName;
            if (!Bridge.AllWeapons || __instance.alreadyOwned || string.IsNullOrEmpty(w) || w.Length < 2 || w.StartsWith("arm")) return;
            if (w.EndsWith("0")) return;
            if (Bridge.Owns(w.Substring(0, w.Length - 1) + "0")) return;
            const string text = "<color=red>Buy the weapon first</color>";
            var shown = Bridge.Zh ? "<color=red>请先购买该武器</color>" : text;
            if (__instance.costText != null) __instance.costText.text = shown;
            var b = __instance.buyButton;
            if (b == null) return;
            var label = b.GetComponentInChildren<TMP_Text>();
            if (label != null) label.text = shown;
            b.failure = true;
            var button = b.GetComponent<Button>();
            if (button != null) button.interactable = false;
            var image = b.GetComponent<Image>();
            if (image != null) image.color = Color.red;
        }
    }

    /// <summary>Upgrades: V1's weapons, punches and hook hit as hard as their Power says (40% at first, up to 300%),
    /// against ULTRAKILL's enemies and Minecraft's mobs alike. Runs before the mob stand-ins' own damage handling.</summary>
    [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.DeliverDamage))]
    static class UpgradedDamage
    {
        static readonly HashSet<string> V1Hitters = new HashSet<string> { "punch", "heavypunch", "hook", "ground slam", "coin", "cannonball", "shotgunzone", "railcannon", "explosion" };

        [HarmonyPriority(Priority.First)]
        static void Prefix(EnemyIdentifier __instance, ref float multiplier, GameObject sourceWeapon)
        {
            if (!Bridge.AllWeapons) return;
            var kind = Bridge.WeaponKind(sourceWeapon);
            // the One-Hit Kills cheat: whatever V1 hits dies
            if (Bridge.OneHitKills && (kind != null || V1Hitters.Contains(__instance.hitter ?? ""))) multiplier *= 1000f;
            if (kind != null) multiplier *= Bridge.Power(kind);
            else if (__instance.hitter == "punch") multiplier *= Bridge.Power("arm0");
            else if (__instance.hitter == "heavypunch") multiplier *= Bridge.Power("arm1");
            // the Whiplash's hook: its Power, and its Barbs
            else if (__instance.hitter == "hook") multiplier *= Bridge.Power("arm2") * Bridge.Barbs;
        }
    }

    /// <summary>The Sandbox runs with cheats on (it's how Ultracraft runs ULTRAKILL); its "CHEATS ENABLED" box stays
    /// hidden unless the cheats menu is open.</summary>
    [HarmonyPatch(typeof(HideCheatsStatus), nameof(HideCheatsStatus.HideStatus), MethodType.Getter)]
    static class NoCheatsBox
    {
        static void Postfix(ref bool __result)
        {
            if (Bridge.AllWeapons) __result = true;
        }
    }

    /// <summary>V2 dying for good. ULTRAKILL's V2 never does (1-4's flees, 4-4's is knocked out), so its standard
    /// death leaves it out: no ragdoll, nothing removed, it just stood there. Here it goes limp the way other machines do
    /// (its animator off, its limbs falling); a model with no limbs to drop bursts apart instead and is gone.</summary>
    [HarmonyPatch(typeof(Enemy), nameof(Enemy.GoLimp), new[] { typeof(bool) })]
    static class V2Dies
    {
        static void Postfix(Enemy __instance)
        {
            var v2 = __instance != null ? __instance.GetComponent<V2>() : null;
            if (v2 == null || v2.dontDie || __instance.GetComponent<V2Dead>() != null) return;
            __instance.gameObject.AddComponent<V2Dead>();
            try
            {
                v2.enabled = false;
                var root = __instance.gameObject;
                var limbs = new List<Rigidbody>();
                foreach (var rb in root.GetComponentsInChildren<Rigidbody>(true)) if (rb.gameObject != root) limbs.Add(rb);
                if (limbs.Count >= 3)
                {
                    foreach (var anim in root.GetComponentsInChildren<Animator>(true)) UnityEngine.Object.Destroy(anim);
                    var col = root.GetComponent<Collider>();
                    if (col != null) UnityEngine.Object.Destroy(col);
                    var body = root.GetComponent<Rigidbody>();
                    if (body != null) UnityEngine.Object.Destroy(body);
                    foreach (var rb in limbs)
                    {
                        if (rb == null) continue;
                        rb.isKinematic = false;
                        rb.useGravity = true;
                        rb.AddForce(UnityEngine.Random.onUnitSphere * 4f + Vector3.up * 3f, ForceMode.VelocityChange);
                    }
                    Plugin.Log.LogInfo("V2 died: ragdoll, " + limbs.Count + " limbs");
                    return;
                }
                // no limbs to drop: what ULTRAKILL does with a V2 that isn't a boss, every part hit apart, and gone
                var eid = __instance.GetComponent<EnemyIdentifier>() ?? root.GetComponentInChildren<EnemyIdentifier>();
                if (eid != null)
                {
                    foreach (var part in root.GetComponentsInChildren<EnemyIdentifierIdentifier>())
                    {
                        try { eid.DeliverDamage(part.gameObject, Vector3.zero, part.transform.position, 10f, false); }
                        catch (Exception) { }
                    }
                }
                Plugin.Log.LogInfo("V2 died: burst apart");
                root.SetActive(false);
                UnityEngine.Object.Destroy(root);
            }
            catch (Exception e) { Plugin.Log.LogWarning("V2 death: " + e); }
        }
    }

    class V2Dead : MonoBehaviour { }
}
