// UltraBridge: cheats, switched from Minecraft's Ultracraft settings (CHEAT id 0/1): ULTRAKILL's own (its Sandbox
// cheats: noclip, flight, invincibility, no weapon cooldown...) and a few of Ultracraft's (infinite P, one-hit kills,
// slow motion, super speed, infinite stamina). None is ever saved by ULTRAKILL: it keeps its cheats in its settings
// files (cheat.*), and while it runs for Ultracraft those are neither read nor written (Settings.cs).

using System;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>The cheats Minecraft has switched (id: on), applied again whenever ULTRAKILL's level is made.</summary>
        static readonly Dictionary<string, bool> Cheats = new Dictionary<string, bool>();

        static bool CheatOn(string id) => Cheats.TryGetValue(id, out var on) && on;

        /// <summary>Ultracraft's cheat: P never runs out (the shop takes nothing).</summary>
        public static bool InfiniteP => CheatOn("ultracraft.infinite-p");

        /// <summary>Ultracraft's cheat: everything V1 hits dies.</summary>
        public static bool OneHitKills => CheatOn("ultracraft.one-hit-kills");

        /// <summary>CHEAT id 0/1.</summary>
        void HandleCheat(string rest)
        {
            var a = rest.Trim().Split(' ');
            if (a.Length < 2) return;
            bool on = a[1] == "1";
            // Kill All Enemies is a button, not a switch
            if (a[0] == "ultrakill.kill-all-enemies")
            {
                if (on && levelPrepared) KillAllUkEnemies();
                return;
            }
            Cheats[a[0]] = on;
            if (levelPrepared) ApplyCheat(a[0], on);
        }

        void ApplyCheats()
        {
            foreach (var kv in new List<KeyValuePair<string, bool>>(Cheats)) ApplyCheat(kv.Key, kv.Value);
        }

        void ApplyCheat(string id, bool on)
        {
            try
            {
                switch (id)
                {
                    case "ultracraft.infinite-p":
                        RefreshShopGear();
                        return;
                    case "ultracraft.one-hit-kills":
                    case "ultracraft.infinite-stamina":
                    case "ultracraft.super-speed":
                        // read where they act (UpgradedDamage, KeepCheats, ApplyEffects)
                        return;
                    case "ultracraft.slow-motion":
                    {
                        var tc = MonoSingleton<TimeController>.Instance;
                        if (tc == null) return;
                        tc.timeScaleModifier = on ? 0.5f : 1f;
                        if (Time.timeScale > 0f) Time.timeScale = tc.timeScale * tc.timeScaleModifier;
                        return;
                    }
                }
                var cm = MonoSingleton<CheatsManager>.Instance;
                var cheat = cm != null ? FindCheat(cm, id) : null;
                if (cheat == null)
                {
                    Plugin.Log.LogWarning("cheat " + id + ": ULTRAKILL has no such cheat here");
                    return;
                }
                if (cheat.IsActive == on) return;
                // never saved: Minecraft's settings hold it
                cm.SetCheatActive(cheat, on, false);
                try { cm.RefreshCheatStates(); }
                catch (Exception) { }
                Plugin.Log.LogInfo("cheat " + id + (on ? " on" : " off"));
            }
            catch (Exception e) { Plugin.Log.LogWarning("cheat " + id + ": " + e.Message); }
        }

        static readonly AccessTools.FieldRef<CheatsManager, Dictionary<string, List<ICheat>>> RegisteredCheats =
            AccessTools.FieldRefAccess<CheatsManager, Dictionary<string, List<ICheat>>>("allRegisteredCheats");

        static ICheat FindCheat(CheatsManager cm, string id)
        {
            var all = RegisteredCheats(cm);
            if (all == null) return null;
            foreach (var list in all.Values)
                foreach (var c in list)
                    if (c != null && c.Identifier == id) return c;
            return null;
        }

        /// <summary>Kill All Enemies, for ULTRAKILL's own enemies only: Minecraft's mobs (their stand-ins) and other
        /// players' enemies (puppets of theirs) are left to their own worlds.</summary>
        void KillAllUkEnemies()
        {
            int n = 0;
            foreach (var eid in FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid == null || eid.dead || IsProxy(eid) || IsPuppet(eid)) continue;
                try
                {
                    eid.InstaKill();
                    n++;
                }
                catch (Exception e) { Plugin.Log.LogDebug("kill all: " + e.Message); }
            }
            Plugin.Log.LogInfo("killed " + n + " of ULTRAKILL's enemies");
        }

        /// <summary>Every frame: the cheats that hold something steady.</summary>
        void KeepCheats()
        {
            if (!levelPrepared) return;
            if (CheatOn("ultracraft.infinite-stamina"))
            {
                var nm = MonoSingleton<NewMovement>.Instance;
                if (nm != null) nm.boostCharge = 300f;
            }
        }
    }
}
