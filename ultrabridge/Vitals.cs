// UltraBridge, part eight: Steve's health, the way ULTRAKILL has to see it. Two Minecraft effects move V1's health
// pool around: a Health Boost effect raises Steve's maximum (12 rows of hearts is 240 health), and absorption hearts
// (the yellow ones) soak damage before his real health. Both are scaled by one rule: V1's maximum is
// E = U * M / 20, capped at 500, where M is Steve's maximum and U is V1's own full health (100, or 200 on Harmless).
// At Steve's usual 20 that is 100, which is what the mod has always done. Minecraft sends both as absolute values
// (MC_MAX, ABSORB) rather than deltas, so a missed message costs a tick instead of drifting out of step for good.
//
// Absorption needs nothing on this side to work: Minecraft takes it off the damage before the hit ever reaches the
// mod, so what it soaks is damage V1 never sees and can never leave hard damage behind. What is left over is mirrored
// here so the HUD can draw it.

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

        /// <summary>Set when the maximum moved, so SendPose sends the HP line even if the health itself did not.</summary>
        internal static bool MaxChanged;

        /// <summary>Steve's maximum as Minecraft just reported it, held until there is a V1 to resize.</summary>
        static int mcPending = -1;

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

        /// <summary>MC_MAX and ABSORB, from Minecraft (sent only when they change).</summary>
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
                    if (!int.TryParse(rest.Trim(), out int absorb)) break;
                    AbsorbMc = Mathf.Max(0, absorb);
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

        static CodeInstruction Scaled(OpCode call, MethodInfo target, CodeInstruction from)
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

        // GetHealth: num2 is the full health it caps healing at
        [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHealth))]
        static class Health
        {
            [HarmonyTranspiler]
            static IEnumerable<CodeInstruction> Transpiler(IEnumerable<CodeInstruction> code) =>
                Bridge.Fix("GetHealth", code, (l, i) =>
                {
                    if (!Bridge.IsFloat(l[i], 100f) && !Bridge.IsFloat(l[i], 200f)) return false;
                    l[i] = Bridge.CallMaxFloat(l[i]);
                    return true;
                });
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
