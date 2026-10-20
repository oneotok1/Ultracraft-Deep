// UltraBridge, part eight: Steve's health, the way ULTRAKILL has to see it. Two Minecraft effects move V1's health
// pool around: a Health Boost effect raises Steve's maximum (12 rows of hearts is 240 health), and absorption hearts
// (the yellow ones) soak damage before his real health. Both are scaled by one rule: V1's maximum is
// E = U * M / 20, capped at 500, where M is Steve's maximum and U is V1's own full health (100, or 200 on Harmless).
// At Steve's usual 20 that is 100, which is what the mod has always done. Minecraft sends both as absolute values
// (MC_MAX, ABSORB) rather than deltas, so a missed message costs a tick instead of drifting out of step for good.
//
// Absorption hearts are one pool with two mouths. Minecraft's damage never reaches this side with them: the hit has
// already been soaked by the time it arrives, and only what the absorption left over is mirrored here. ULTRAKILL's
// damage - V1's own blasts and slams, and its enemies' own hits - never passes through Minecraft at all, so it is
// taken off the pool here instead (see the GetHurt patch below), and the spend is handed back to the server, which
// keeps the pool. Either way the yellow hearts are Steve's.

using System.Collections.Generic;
using System.Reflection;
using System.Reflection.Emit;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>Steve's maximum health, in Minecraft health points.</summary>
        internal static int McMax = 20;
        /// <summary>V1's maximum health, scaled to it.</summary>
        internal static int UkMax = 100;
        /// <summary>Steve's absorption hearts, in Minecraft health points.</summary>
        internal static int AbsorbMc;

        const int HighestMax = 500;

        /// <summary>Extra Blood Counts as Health Cap: the absorption hearts are the top of V1's health bar rather than
        /// a thing beside it - healing past his own maximum fills them back up, and the bar counts them.</summary>
        internal static bool PoolCeiling = true;

        /// <summary>Set while this side hands V1 a hit Minecraft has already applied: Minecraft took the absorption
        /// hearts with it, so the pool must not be charged for that hit a second time.</summary>
        internal static bool FromMc;

        /// <summary>Set when the maximum moved, so SendPose sends the HP line even if the health itself did not.</summary>
        internal static bool MaxChanged;

        /// <summary>Steve's maximum as Minecraft just reported it, held until there is a V1 to resize.</summary>
        static int mcPending = -1;

        /// <summary>The pool as this side last handed it to the server, and when: the server echoes the same number
        /// back a frame later, and that echo must not undo a spend of ours.</summary>
        static int pushedMc = -1;
        static float pushedAt;

        /// <summary>Steve's absorption hearts, in ULTRAKILL health (the HUD draws this many).</summary>
        internal static float AbsorbHp => McMax <= 0 ? 0f : AbsorbMc * (float)UkMax / McMax;

        /// <summary>V1's maximum health, for NewMovement's own hard-coded caps (see the transpilers below).</summary>
        internal static int MaxHpNowInt() => UkMax;

        /// <inheritdoc cref="MaxHpNowInt"/>
        internal static float MaxHpNowFloat() => UkMax;

        /// <summary>V1's maximum for a level on this difficulty, with Steve's maximum where it is.</summary>
        static int ScaledMax(NewMovement nm, int mc) => Mathf.Clamp(MaxHp(nm) * Mathf.Max(1, mc) / 20, 1, HighestMax);

        /// <summary>Resizes V1 when Steve's maximum (or the difficulty behind it) moved. Safe to call every frame.</summary>
        internal static void SyncMax(NewMovement nm)
        {
            if (nm == null) return;
            int mc = mcPending > 0 ? mcPending : McMax;
            if (mc == McMax && ScaledMax(nm, mc) == UkMax) return;
            ApplyMax(nm, mc);
        }

        /// <summary>
        /// Raises or lowers V1's maximum. His health follows Steve's own, which Minecraft keeps where it is when a
        /// Health Boost effect moves his maximum - so a bigger pool never quietly heals him.
        /// </summary>
        static void ApplyMax(NewMovement nm, int mc)
        {
            int oldUk = UkMax, oldMc = McMax;
            int e = ScaledMax(nm, mc);
            int steve = Mathf.Max(1, Mathf.RoundToInt(nm.hp * (float)oldMc / oldUk));
            mcPending = -1;
            McMax = mc;
            UkMax = e;
            nm.hp = Mathf.Clamp(Mathf.RoundToInt(steve * (float)e / mc), 1, e);
            if (nm.antiHp > e) nm.antiHp = e;
            if (e == oldUk) return;
            MaxChanged = true; // SendPose tells Minecraft the new maximum at once
            if (Plugin.Log != null) Plugin.Log.LogInfo("V1's maximum health " + oldUk + " -> " + e + " (Steve's " + mc + ")");
        }

        /// <summary>MC_MAX from Minecraft, and ABSORB from the server, which keeps the pool (both only when they change).</summary>
        void HandleVitals(string cmd, string rest)
        {
            switch (cmd)
            {
                case "MC_MAX":
                    if (!int.TryParse(rest.Trim(), out int max)) break;
                    max = Mathf.Clamp(max, 1, 9999);
                    if (max == McMax) { mcPending = -1; break; }
                    mcPending = max; // held until there is a V1: SendPose resizes him once one exists
                    if (levelPrepared && MonoSingleton<NewMovement>.Instance != null) SyncMax(MonoSingleton<NewMovement>.Instance);
                    break;
                case "ABSORB":
                    // the pool lives on the server (Steve's own absorption hearts), so this value is the one that
                    // counts; the number it sends back right after a spend of ours is our own, a frame late
                    if (!int.TryParse(rest.Trim(), out int absorb)) break;
                    absorb = Mathf.Max(0, absorb);
                    if (absorb == AbsorbMc) break;
                    if (absorb == pushedMc && Time.unscaledTime - pushedAt < 0.5f) break;
                    AbsorbMc = absorb;
                    break;
            }
        }

        // ------------------------------------------------------- NewMovement's own "100" (see the transpilers)

        static readonly MethodInfo MaxInt = AccessTools.Method(typeof(Bridge), nameof(MaxHpNowInt));
        static readonly MethodInfo MaxFloat = AccessTools.Method(typeof(Bridge), nameof(MaxHpNowFloat));

        internal static bool IsInt(CodeInstruction c, int v)
        {
            if (c.opcode == OpCodes.Ldc_I4) return (int)c.operand == v;
            if (c.opcode == OpCodes.Ldc_I4_S) return (sbyte)c.operand == v;
            return false;
        }

        internal static bool IsFloat(CodeInstruction c, float v) => c.opcode == OpCodes.Ldc_R4 && (float)c.operand == v;

        internal static bool IsHp(CodeInstruction c) =>
            c.opcode == OpCodes.Ldfld && c.operand is FieldInfo f && f.Name == "hp";

        internal static bool IsStoreHp(CodeInstruction c) =>
            c.opcode == OpCodes.Stfld && c.operand is FieldInfo f && f.Name == "hp";

        // The replacement keeps whatever labels and exception blocks the constant carried, so branches still land
        internal static CodeInstruction CallMaxInt(CodeInstruction from) => Scaled(OpCodes.Call, MaxInt, from);

        internal static CodeInstruction CallMaxFloat(CodeInstruction from) => Scaled(OpCodes.Call, MaxFloat, from);

        internal static CodeInstruction Scaled(OpCode call, MethodInfo target, CodeInstruction from)
        {
            var made = new CodeInstruction(call, target);
            made.labels.AddRange(from.labels);
            made.blocks.AddRange(from.blocks);
            return made;
        }

        /// <summary>Rewrites NewMovement's hard-coded full health into V1's scaled maximum, logging what it found.</summary>
        internal static IEnumerable<CodeInstruction> Fix(string what, IEnumerable<CodeInstruction> instructions,
            System.Func<List<CodeInstruction>, int, bool> replace)
        {
            var list = new List<CodeInstruction>(instructions);
            int hits = 0;
            for (int i = 0; i < list.Count; i++)
                if (replace(list, i)) hits++;
            if (Plugin.Log != null) Plugin.Log.LogInfo("NewMovement." + what + ": " + hits + " cap(s) scaled to V1's maximum");
            return list;
        }

        // ------------------------------------------------------- the pool: what Minecraft's own hits cannot reach

        /// <summary>Hands the pool's new value to the server, which is where the pool lives.</summary>
        static void Spend(int mc)
        {
            AbsorbMc = Mathf.Max(0, mc);
            pushedMc = AbsorbMc;
            pushedAt = Time.unscaledTime;
            Net.Send("POOL " + AbsorbMc);
        }

        /// <summary>
        /// Takes an ULTRAKILL hit off Steve's absorption hearts first, the way Minecraft's own damage does: V1's own
        /// blasts and slams, and ULTRAKILL's enemies' own hits, never pass through Minecraft, so nothing else would
        /// ever pay the pool for them. What the pool covers comes off the damage before V1's own health sees it.
        /// </summary>
        internal static void Soak(NewMovement nm, ref int damage)
        {
            if (FromMc || damage <= 0 || AbsorbMc <= 0 || nm.hp <= 0 || UkMax <= 0 || McMax <= 0) return;
            int back = Mathf.Min(damage, Mathf.Max(0, Mathf.CeilToInt(AbsorbHp)));
            if (back <= 0) return;
            // the pool pays in Steve's health points (Minecraft's own units, which is what the server keeps); the
            // rounding is there because both of them count in whole points and a hit is rarely a whole one of ours
            Spend(Mathf.RoundToInt((AbsorbHp - back) * McMax / UkMax));
            damage -= back;
        }

        /// <summary>
        /// The other way round: with the pool counted as the cap on V1's health, healing past his own maximum fills
        /// his absorption hearts instead of being thrown away, so the blood he drinks can put them back.
        /// </summary>
        internal static void Fill(NewMovement nm)
        {
            if (!PoolCeiling || nm == null || nm.dead || UkMax <= 0 || McMax <= 0) return;
            int over = nm.hp - UkMax;
            if (over <= 0) return;
            nm.hp = UkMax;
            Spend(AbsorbMc + Mathf.Max(1, Mathf.RoundToInt(over * (float)McMax / UkMax)));
        }

        /// <summary>V1's healing cap: his own maximum, or the top of the pool when the pool counts as it.</summary>
        internal static float HealCapFloat() => PoolCeiling ? UkMax + Mathf.RoundToInt(AbsorbHp) : UkMax;

        /// <summary>Hands V1 a hit Minecraft has already worked out, absorption hearts and all.</summary>
        internal static void McHurt(NewMovement nm, int damage)
        {
            FromMc = true;
            try { nm.GetHurt(damage, true); }
            finally { FromMc = false; }
        }

        /// <summary>The bottom of the world: V1 dies outright, and Minecraft's own void took the pool with it.</summary>
        internal static void McVoid(NewMovement nm)
        {
            FromMc = true;
            try { nm.GetHurt(nm.hp * 10 + 999, false, 0f, ignoreInvincibility: true); }
            finally { FromMc = false; }
        }
    }

    /// <summary>The places NewMovement decides how much health V1 has keep it at 100.</summary>
    static class SteveMaxPatches
    {
        // GetHurt: hard damage only piles up while V1 is at or below his full health
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHurt))]
        static class Hurt
        {
            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("GetHurt", code, (l, i) =>
                {
                    if (i == 0 || !Bridge.IsInt(l[i], 100) || !Bridge.IsHp(l[i - 1])) return false;
                    l[i] = Bridge.CallMaxInt(l[i]);
                    return true;
                });
        }

        // GetHurt: the pool takes ULTRAKILL's own hits, before V1's health does (Minecraft's arrive already soaked)
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHurt))]
        static class HurtPool
        {
            // Assist Mode's own invincibility, which zeroes the damage right after this patch has run; its class is
            // not in the game's assembly under that name on every version, and no patch of ours depends on finding it
            static readonly System.Reflection.MemberInfo Invincible = FindInvincible();

            static System.Reflection.MemberInfo FindInvincible()
            {
                try
                {
                    var t = AccessTools.TypeByName("Invincibility");
                    if (t == null) return null;
                    return (System.Reflection.MemberInfo)t.GetProperty("Enabled") ?? t.GetField("Enabled");
                }
                catch { return null; }
            }

            [HarmonyPriority(Priority.Low)] // last in: what it soaks is the damage V1 was about to take
            [HarmonyPrefix]
            static void Prefix(NewMovement __instance, ref int damage, bool invincible, bool ignoreInvincibility)
            {
                // GetHurt's own early outs, so that a hit nothing was going to come of costs the pool nothing either
                if (__instance.dead || __instance.levelOver || damage <= 0) return;
                if (invincible && __instance.gameObject.layer == 15 && !ignoreInvincibility) return;
                if (Invincible != null) // Assist Mode's invincibility: it zeroes the damage right after this
                {
                    object on = Invincible is FieldInfo f ? f.GetValue(null) : ((PropertyInfo)Invincible).GetValue(null);
                    if (on is bool b && b) return;
                }
                Bridge.Soak(__instance, ref damage);
            }
        }

        // ForceAntiHP: hard damage is set against full health in four places
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.ForceAntiHP))]
        static class AntiHp
        {
            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("ForceAntiHP", code, (l, i) =>
                {
                    bool i100 = Bridge.IsInt(l[i], 100), f100 = Bridge.IsFloat(l[i], 100f);
                    // hp > 100, the early out
                    if (i100 && i > 0 && Bridge.IsHp(l[i - 1])) { l[i] = Bridge.CallMaxInt(l[i]); return true; }
                    // hp > 100 - amount
                    if (f100 && i > 1 && Bridge.IsHp(l[i - 2])) { l[i] = Bridge.CallMaxFloat(l[i]); return true; }
                    // amount = 100 - hp
                    if (i100 && i + 2 < l.Count && Bridge.IsHp(l[i + 2])) { l[i] = Bridge.CallMaxInt(l[i]); return true; }
                    // hp = RoundToInt(100 - amount)
                    if (f100 && i + 1 < l.Count && l[i + 1].opcode == OpCodes.Ldarg_1) { l[i] = Bridge.CallMaxFloat(l[i]); return true; }
                    return false;
                });
        }

        // GetHealth: num2 is the full health it caps healing at, and with the pool counted as the cap's top, healing
        // past V1's own maximum fills his absorption hearts (Bridge.Fill brings him back down to his own maximum)
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHealth))]
        static class Health
        {
            static readonly MethodInfo HealCap = AccessTools.Method(typeof(Bridge), nameof(Bridge.HealCapFloat));

            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("GetHealth", code, (l, i) =>
                {
                    if (!Bridge.IsFloat(l[i], 100f) && !Bridge.IsFloat(l[i], 200f)) return false;
                    l[i] = Bridge.Scaled(OpCodes.Call, HealCap, l[i]);
                    return true;
                });

            [HarmonyPostfix]
            static void Postfix(NewMovement __instance) => Bridge.Fill(__instance);
        }

        // Respawn: the health V1 comes back with
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.Respawn))]
        static class RespawnPatch
        {
            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("Respawn", code, (l, i) =>
                {
                    if (i + 1 >= l.Count) return false;
                    if (!(Bridge.IsInt(l[i], 200) || Bridge.IsInt(l[i], 100))) return false;
                    if (!Bridge.IsStoreHp(l[i + 1])) return false;
                    l[i] = Bridge.CallMaxInt(l[i]);
                    return true;
                });
        }

        // SuperCharge: the blast heals, then hands V1 full health outright (200)
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.SuperCharge))]
        static class SuperChargePatch
        {
            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("SuperCharge", code, (l, i) =>
                {
                    if (i + 1 >= l.Count) return false;
                    if (!Bridge.IsInt(l[i], 200) || !Bridge.IsStoreHp(l[i + 1])) return false;
                    l[i] = Bridge.CallMaxInt(l[i]);
                    return true;
                });
        }

        // Start: Harmless hands V1 200 health before anything else runs
        [HarmonyPatch(typeof(NewMovement), "Start")]
        static class StartPatch
        {
            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("Start", code, (l, i) =>
                {
                    if (i + 1 >= l.Count) return false;
                    if (!Bridge.IsInt(l[i], 200) || !Bridge.IsStoreHp(l[i + 1])) return false;
                    l[i] = Bridge.CallMaxInt(l[i]);
                    return true;
                });
        }
    }
}
