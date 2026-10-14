// UltraBridge, part four: Ultracraft's settings screen (in Minecraft) acting on ULTRAKILL.
//
// - OPTS: how long impact frames last (0.1x to 3x of ULTRAKILL's own).
// - UKPREF key type:value: one of ULTRAKILL's own settings (mouse sensitivity, field of view, screen shake...) as
//   Ultracraft plays it. It is answered in place of the stored one while ULTRAKILL runs and never written into its
//   settings files, so ULTRAKILL on its own stays as the player set it. UKPREFS reports the stored ones.
// - QUIT: Minecraft closed, and it started this ULTRAKILL.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>Impact frames (TimeController.HitStop/TrueStop) last this many times as long.</summary>
        public static float ImpactScale = 1f;

        /// <summary>ULTRAKILL settings Ultracraft has set: answered in place of PrefsManager's own.</summary>
        public static readonly Dictionary<string, object> PrefOverrides = new Dictionary<string, object>();
        /// <summary>Set while reading ULTRAKILL's own stored settings (UKPREFS), past the overrides.</summary>
        public static bool ReadingStoredPrefs;

        // the settings Minecraft's screen shows, with their types (f float, i int, b bool; l = a local pref)
        static readonly string[][] ReportedPrefs =
        {
            new[] { "difficulty", "i" }, new[] { "mouseSensitivity", "fl" }, new[] { "fieldOfView", "f" }, new[] { "screenShake", "f" }, new[] { "cameraTilt", "b" },
            new[] { "parryFlash", "b" }, new[] { "weaponHoldPosition", "i" }, new[] { "mouseReverseY", "b" }, new[] { "bloodEnabled", "bl" },
            new[] { "allVolume", "f" }, new[] { "musicVolume", "f" },
        };

        void HandleSettings(string cmd, string rest)
        {
            switch (cmd)
            {
                case "OPTS":
                    // OPTS key=value ...
                    foreach (var kv in rest.Split(' '))
                    {
                        var p = kv.Split('=');
                        if (p.Length != 2) continue;
                        if (p[0] == "impact" && float.TryParse(p[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var f)) ImpactScale = Mathf.Clamp(f, 0.1f, 3f);
                        // how often ULTRAKILL draws: every frame it draws costs the graphics card Minecraft needs too
                        if (p[0] == "fps" && int.TryParse(p[1], out var fps)) FpsCap = Mathf.Clamp(fps, 30, 240);
                        if (p[0] == "lowlat") LowLatency = p[1] == "1";
                        if (p[0] == "fx" && int.TryParse(p[1], out var fx)) SetEffects(fx);
                        if (p[0] == "gore") ExtraGore = p[1] == "1";
                        if (p[0] == "stains") SetStains(p[1] == "1");
                    }
                    break;
                case "VIEW":
                    // VIEW 0|1|2: Minecraft's camera is first person, behind V1 or in front (F5)
                    SetView(int.TryParse(rest.Trim(), out var view) ? view : 0);
                    break;
                case "COMBATTEST":
                    Net.Send("COMBATTEST " + CombatTest(rest.Trim()));
                    break;
                case "ARMINFO":
                    Net.Send("ARMINFO " + ArmInfo());
                    break;
                case "STEVE":
                case "STEVECAM":
                case "STEVEPOS":
                    HandleSteve(cmd, rest);
                    break;
                case "UKBIND":
                {
                    // UKBIND Map/Action[/part] <glfw key | -1-mouse button | ->: rebind one of ULTRAKILL's controls (runtime only)
                    var a = rest.Trim().Split(' ');
                    if (a.Length >= 2) { PendingBinds[a[0]] = a[1]; if (levelPrepared) ApplyBinds(); }
                    break;
                }
                case "SKY":
                    HandleSky(rest);
                    break;
                case "GUN":
                {
                    // GUN <slot 1-6> <colour 0 blue, 1 green, 2 red>: a weapon's own key (Minecraft's ULTRAKILL Controls)
                    var a = rest.Trim().Split(' ');
                    if (a.Length >= 2 && levelPrepared) Net.Send("GUNINFO " + SwitchToGun(int.Parse(a[0]), int.Parse(a[1])));
                    break;
                }
                case "BINDINFO":
                {
                    // debug: where ULTRAKILL's controls are bound now (overrides included)
                    var im = levelPrepared ? MonoSingleton<InputManager>.Instance : null;
                    var asset = im != null && im.InputSource != null ? im.InputSource.Actions.asset : null;
                    var sb = new System.Text.StringBuilder("BINDINFO pending=" + PendingBinds.Count);
                    if (asset != null)
                        foreach (var name in new[] { "Movement/Move", "Movement/Jump", "Movement/Dodge", "Fist/Punch", "Weapon/PrimaryFire", "Weapon/Revolver" })
                        {
                            var act = asset.FindAction(name);
                            if (act == null) continue;
                            sb.Append(" | ").Append(name).Append(':');
                            foreach (var b in act.bindings) if (!b.isComposite && b.effectivePath.StartsWith("<")) sb.Append(' ').Append(b.name).Append('=').Append(b.effectivePath);
                        }
                    Net.Send(sb.ToString());
                    break;
                }
                case "UKPREF":
                {
                    // UKPREF key type:value
                    var a = rest.Split(' ');
                    if (a.Length < 2 || a[1].Length < 3 || a[1][1] != ':') break;
                    string key = a[0], v = a[1].Substring(2);
                    object value;
                    switch (a[1][0])
                    {
                        case 'f': value = float.Parse(v, CultureInfo.InvariantCulture); break;
                        case 'i': value = int.Parse(v, CultureInfo.InvariantCulture); break;
                        case 'b': value = v == "true" || v == "1"; break;
                        default: return;
                    }
                    PrefOverrides[key] = value;
                    ApplyPref(key, value);
                    break;
                }
                case "CHEAT":
                    HandleCheat(rest);
                    break;
                case "MUSIC":
                case "MUSICOPTS":
                case "THEME":
                    HandleMusic(cmd, rest);
                    break;
                case "MUSICINFO":
                    Net.Send(MusicInfo());
                    break;
                case "ADDSTYLE":
                    // debug: ADDSTYLE n: style points, as if earned
                    MonoSingleton<StyleHUD>.Instance?.AddPoints(int.Parse(rest.Trim()), "ultrakill.kill", null, null);
                    break;
                case "QUIT":
                    Plugin.Log.LogInfo("Minecraft closed: quitting");
                    Application.Quit();
                    break;
                default:
                    HandleMultiplayer(cmd, rest);
                    break;
            }
        }

        /// <summary>Everything listening for that setting hears it changed, as if set in ULTRAKILL's own menu.</summary>
        void ApplyPref(string key, object value)
        {
            if (!levelPrepared) return;
            try
            {
                if (key == "mouseSensitivity" && value is float s)
                {
                    var opm = MonoSingleton<OptionsManager>.Instance;
                    if (opm != null) opm.mouseSensitivity = s;
                }
                if (key == "difficulty" && value is int d)
                {
                    // V1 took its difficulty in Start: its full health (200 on Harmless) follows now; enemies take it
                    // as they spawn
                    var nm = MonoSingleton<NewMovement>.Instance;
                    if (nm != null)
                    {
                        NmDifficulty(nm) = d;
                        if (nm.hp > MaxHp(nm)) nm.hp = MaxHp(nm);
                        lastHp = -1;
                        Plugin.Log.LogInfo("ULTRAKILL difficulty " + d + ": V1's full health " + MaxHp(nm));
                    }
                }
                PrefsManager.onPrefChanged?.Invoke(key, value);
            }
            catch (Exception e) { Plugin.Log.LogWarning("setting " + key + ": " + e.Message); }
        }

        /// <summary>The level is ready: every setting Ultracraft has made applies (again), and Minecraft hears the
        /// stored ones for its settings screen.</summary>
        static readonly Dictionary<string, string> PendingBinds = new Dictionary<string, string>();

        /// <summary>Straight to one weapon: the variant in that slot with that colour (whichever version, Alternate or
        /// not, V1 has out). Nothing if V1 doesn't own it, or it's already in hand.</summary>
        string SwitchToGun(int slot, int colour)
        {
            var gc = MonoSingleton<GunControl>.Instance;
            if (gc == null || slot < 1 || slot > gc.slots.Count) return "no slot";
            var list = gc.slots[slot - 1];
            var seen = new System.Text.StringBuilder();
            for (int i = 0; i < list.Count; i++)
            {
                var icon = list[i] != null ? list[i].GetComponentInChildren<WeaponIcon>(true) : null;
                int c = icon != null && icon.weaponDescriptor != null ? (int)icon.weaponDescriptor.variationColor : -1;
                seen.Append(list[i] != null ? list[i].name : "null").Append('=').Append(c).Append(' ');
                if (c != colour) continue;
                if (gc.currentSlotIndex == slot && gc.currentVariationIndex == i && gc.currentWeapon == list[i]) return "already " + seen;
                gc.SwitchWeapon(slot, i);
                return "switched to " + i + " (" + seen + ")";
            }
            return "none of " + seen;
        }

        /// <summary>The rebound controls as overrides on ULTRAKILL's own bindings (never saved to its files).</summary>
        void ApplyBinds()
        {
            var im = MonoSingleton<InputManager>.Instance;
            var asset = im != null && im.InputSource != null ? im.InputSource.Actions.asset : null;
            if (asset == null) return;
            foreach (var kv in PendingBinds)
            {
                try
                {
                    var parts = kv.Key.Split('/');
                    if (parts.Length < 2) continue;
                    var action = asset.FindAction(parts[0] + "/" + parts[1]);
                    if (action == null) continue;
                    string part = parts.Length > 2 ? parts[2] : null;
                    string path = null;
                    if (kv.Value != "-" && int.TryParse(kv.Value, out var code))
                    {
                        if (code < 0) path = "<Mouse>/" + (code == -1 ? "leftButton" : code == -2 ? "rightButton" : "middleButton");
                        else
                        {
                            var key = GlfwKey(code);
                            var kb = UnityEngine.InputSystem.InputSystem.GetDevice<UnityEngine.InputSystem.Keyboard>();
                            if (key != UnityEngine.InputSystem.Key.None && kb != null) path = "<Keyboard>/" + kb[key].name;
                        }
                    }
                    for (int i = 0; i < action.bindings.Count; i++)
                    {
                        var b = action.bindings[i];
                        if (b.isComposite) continue;
                        if (part != null ? !(b.isPartOfComposite && b.name == part) : b.isPartOfComposite) continue;
                        var p = b.path ?? "";
                        if (!p.StartsWith("<Keyboard>") && !p.StartsWith("<Mouse>")) continue;
                        if (path == null) UnityEngine.InputSystem.InputActionRebindingExtensions.RemoveBindingOverride(action, i);
                        else UnityEngine.InputSystem.InputActionRebindingExtensions.ApplyBindingOverride(action, i, path);
                        break;
                    }
                }
                catch (Exception e) { Plugin.Log.LogWarning("bind " + kv.Key + ": " + e.Message); }
            }
        }

        void PrefsOnReady()
        {
            ApplyBinds();
            foreach (var kv in PrefOverrides) ApplyPref(kv.Key, kv.Value);
            ApplyCheats();
            rankSent = -1;
            if (Net.Connected) SendSongs();
            MusicChanged();
            var pm = MonoSingleton<PrefsManager>.Instance;
            if (pm == null) return;
            var sb = new StringBuilder("UKPREFS ");
            ReadingStoredPrefs = true;
            try
            {
                foreach (var p in ReportedPrefs)
                {
                    bool local = p[1].Length > 1;
                    string v;
                    switch (p[1][0])
                    {
                        case 'f': v = "f:" + S(local ? pm.GetFloatLocal(p[0]) : pm.GetFloat(p[0])); break;
                        case 'i': v = "i:" + (local ? pm.GetIntLocal(p[0]) : pm.GetInt(p[0])); break;
                        default: v = "b:" + ((local ? pm.GetBoolLocal(p[0]) : pm.GetBool(p[0])) ? "true" : "false"); break;
                    }
                    sb.Append(p[0]).Append('=').Append(v).Append(';');
                }
            }
            catch (Exception e) { Plugin.Log.LogWarning("UKPREFS: " + e.Message); }
            finally { ReadingStoredPrefs = false; }
            Net.Send(sb.ToString());
        }
    }

    /// <summary>ULTRAKILL asks for a setting: Ultracraft's, if it set one.</summary>
    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetFloat))]
    static class PrefFloat
    {
        static void Postfix(string key, ref float __result)
        {
            if (!Bridge.ReadingStoredPrefs && Bridge.PrefOverrides.TryGetValue(key, out var v) && v is float f) __result = f;
        }
    }

    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetFloatLocal))]
    static class PrefFloatLocal
    {
        static void Postfix(string key, ref float __result)
        {
            if (!Bridge.ReadingStoredPrefs && Bridge.PrefOverrides.TryGetValue(key, out var v) && v is float f) __result = f;
        }
    }

    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetInt))]
    static class PrefInt
    {
        static void Postfix(string key, ref int __result)
        {
            if (Bridge.ReadingStoredPrefs) return;
            if (Bridge.PrefOverrides.TryGetValue(key, out var v) && v is int i) __result = i;
            // which weapons are equipped (and as the alternate) is this world's
            else if (Bridge.AllWeapons && Bridge.IsEquipKey(key)) __result = Bridge.EquipOf(key);
        }
    }

    /// <summary>The shop's equip arrows, its purchases and the Alternate button set what's equipped in this world (EQUIP):
    /// never in ULTRAKILL's own settings.</summary>
    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.SetInt))]
    static class WorldEquip
    {
        static bool Prefix(string key, int content)
        {
            if (!Bridge.AllWeapons || !Bridge.IsEquipKey(key)) return true;
            Bridge.I?.SetEquip(key, content);
            return false;
        }
    }

    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetIntLocal))]
    static class PrefIntLocal
    {
        static void Postfix(string key, ref int __result)
        {
            if (!Bridge.ReadingStoredPrefs && Bridge.PrefOverrides.TryGetValue(key, out var v) && v is int i) __result = i;
        }
    }

    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetBool))]
    static class PrefBool
    {
        static void Postfix(string key, bool fallback, ref bool __result)
        {
            if (Bridge.ReadingStoredPrefs) return;
            if (Bridge.PrefOverrides.TryGetValue(key, out var v) && v is bool b) __result = b;
            // ULTRAKILL's cheats as the real game left them stay out of Ultracraft (Minecraft's Cheats settings switch them)
            else if (Bridge.AllWeapons && key != null && key.StartsWith("cheat.")) __result = fallback;
        }
    }

    /// <summary>ULTRAKILL saves its cheats in its settings files (cheat.*); while it runs for Ultracraft they're never
    /// written there, nor cleared (the real game's own choices stay as they were).</summary>
    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.SetBool))]
    static class CheatsNotSaved
    {
        static bool Prefix(string key) => !(Bridge.AllWeapons && key != null && key.StartsWith("cheat."));
    }

    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.DeleteKey))]
    static class CheatsNotCleared
    {
        static bool Prefix(string key) => !(Bridge.AllWeapons && key != null && key.StartsWith("cheat."));
    }

    [HarmonyPatch(typeof(PrefsManager), nameof(PrefsManager.GetBoolLocal))]
    static class PrefBoolLocal
    {
        static void Postfix(string key, ref bool __result)
        {
            if (!Bridge.ReadingStoredPrefs && Bridge.PrefOverrides.TryGetValue(key, out var v) && v is bool b) __result = b;
        }
    }

    /// <summary>Impact frames as long as the Impact Frames setting says (0.1x to 3x).</summary>
    [HarmonyPatch(typeof(TimeController), nameof(TimeController.HitStop))]
    static class HitStopLength
    {
        static void Prefix(ref float length) => length *= Bridge.ImpactScale;
    }

    [HarmonyPatch(typeof(TimeController), nameof(TimeController.TrueStop))]
    static class TrueStopLength
    {
        static void Prefix(ref float length) => length *= Bridge.ImpactScale;
    }
}
