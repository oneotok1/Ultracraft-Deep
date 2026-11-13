// UltraBridge, part eight: Steve's health, the way ULTRAKILL has to see it. Two Minecraft effects move V1's health
// pool around: a Health Boost effect raises Steve's maximum (12 rows of hearts is 240 health), and absorption hearts
// (the yellow ones) soak damage before his real health. Both are scaled by one rule: V1's maximum is
// E = U * M / 20, capped at 4 * U, where M is Steve's maximum and U is V1's own full health (100, or 200 on Harmless).
// At Steve's usual 20 that is 100, which is what the mod has always done. Minecraft sends both as absolute values
// (MC_MAX, ABSORB) rather than deltas, so a missed message costs a tick instead of drifting out of step for good.
//
// Absorption hearts are one pool with two mouths. Minecraft's damage never reaches this side with them: the hit has
// already been soaked by the time it arrives, and only what the absorption left over is mirrored here. ULTRAKILL's
// damage - V1's own blasts and slams, and its enemies' own hits - never passes through Minecraft at all, so it is
// taken off the pool here instead (see the GetHurt patch below), and the spend is handed back to the server, which
// keeps the pool. Either way the yellow hearts are Steve's.

using System.Collections.Generic;
using System.Globalization;
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

        /// <summary>Extra Blood Counts as Health Cap: the absorption hearts are the top of V1's health bar rather than
        /// a thing beside it - healing past his own maximum fills them back up, and the bar counts them.</summary>
        internal static bool PoolCeiling = true;

        /// <summary>Set while this side hands V1 a hit Minecraft has already applied: Minecraft took the absorption
        /// hearts with it, so the pool must not be charged for that hit a second time.</summary>
        internal static bool FromMc;

        /// <summary>Set while a hit lands that must not shake the camera. ULTRAKILL sizes its shake as damage / 20
        /// and decays it at 3 a second, so the killing blow the void deals (hp * 10 + 999) would leave the screen
        /// shaking for a minute and more - far past anything a normal death reaches.</summary>
        internal static bool NoShake;

        /// <summary>Set when the maximum moved, so SendPose sends the HP line even if the health itself did not.</summary>
        internal static bool MaxChanged;

        /// <summary>Totems of Undying V1 carries, as Minecraft last counted them. One spends itself on the blow that
        /// would have been his last; Minecraft's own death protection never runs while his health lives here.</summary>
        internal static int Totems;

        /// <summary>Set for the void's killing blow, which a totem cannot ward off any more than it can in Minecraft.</summary>
        static bool NoTotem;

        /// <summary>Minecraft has this V1 in creative (or watching as a spectator): nothing ULTRAKILL does reaches him.
        /// Sent as GOD 1/0 when the game mode changes, and never saved anywhere: it is Minecraft's state, not a cheat.</summary>
        internal static bool God;

        /// <summary>Steve's armour, as Minecraft last reported it (ARMOR line): the points, the toughness that keeps
        /// them from folding under a heavy blow, Protection's points, and Resistance's level. ULTRAKILL's own enemies
        /// never pass through Minecraft's pipeline, so the same arithmetic is done here from these four.</summary>
        internal static int ArmourPoints;
        internal static float ArmourToughness;
        internal static float ArmourProtection;
        internal static int ArmourResistance;

        /// <summary>Steve's maximum as Minecraft just reported it, held until there is a V1 to resize.</summary>
        static int mcPending = -1;

        /// <summary>The pool as this side last handed it to the server, and when: the server echoes the same number
        /// back a frame later, and that echo must not undo a spend of ours.</summary>
        static int pushedMc = -1;
        static float pushedAt;

        /// <summary>The most absorption the server itself gave Steve (a golden apple, a potion): the ceiling Fill
        /// refills to, so blood can put spent hearts back but never conjure more than the item granted.</summary>
        static int grantedMc;

        /// <summary>Steve's absorption hearts, in ULTRAKILL health (the HUD draws this many).</summary>
        internal static float AbsorbHp => McMax <= 0 ? 0f : AbsorbMc * (float)UkMax / McMax;

        /// <summary>V1's maximum health, for NewMovement's own hard-coded caps (see the transpilers below).</summary>
        internal static int MaxHpNowInt() => UkMax;

        /// <inheritdoc cref="MaxHpNowInt"/>
        internal static float MaxHpNowFloat() => UkMax;

        /// <summary>V1's ceiling: four times his own full health on this difficulty, however far a Health Boost
        /// pushes Steve's bar. Harmless doubles the whole scale (200 instead of 100, and every absorption heart with
        /// it), so its ceiling doubles as well: 400 on the usual 100, 800 on Harmless's 200 - still three digits, which
        /// is all the HUD's "/N" readout has room for.</summary>
        static int HighestMax(NewMovement nm) => MaxHp(nm) * 4;

        /// <summary>V1's maximum: his own full health, times Steve's maximum over the usual 20, and never more than
        /// four times his own. Without a Health Boost that is exactly his own full health (100, or 200 on Harmless);
        /// a Health Boost moves it up with Steve's bar and stops at the fourfold ceiling. It is his real health bar
        /// rather than absorption hearts, so Minecraft's own regeneration fills it the way it fills any other bar -
        /// it used to sit at his own maximum and stop there, which left Minecraft full and still paying food for
        /// health it could not deliver.</summary>
        static int ScaledMax(NewMovement nm, int mc) => Mathf.Clamp(MaxHp(nm) * Mathf.Max(1, mc) / 20, 1, HighestMax(nm));

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
                    // a fresh grant from the server (an apple, a potion): it lifted what Steve may hold, so blood
                    // may refill up to this much; our own spends only shrink, never raise, what was granted
                    if (absorb > AbsorbMc && absorb != pushedMc) grantedMc = absorb;
                    if (absorb == AbsorbMc) break;
                    if (absorb == pushedMc && Time.unscaledTime - pushedAt < 0.5f) break;
                    AbsorbMc = absorb;
                    break;
                case "TOTEM":
                    // TOTEM <n>: how many Totems of Undying V1 carries, counted by Minecraft and sent when it moves
                    if (int.TryParse(rest.Trim(), out int totems)) Totems = Mathf.Max(0, totems);
                    break;
                case "GOD":
                    // GOD 1/0: Minecraft put this V1 in creative (or a spectator); ULTRAKILL's own damage stops here too
                    God = rest.Trim() == "1";
                    break;
                case "ARMOR":
                    // ARMOR <points> <toughness> <protection> <resistance>: Steve's armour as Minecraft counts it, sent
                    // when any of the four moves. ULTRAKILL's enemies never reach Minecraft, so its arithmetic is redone
                    // here (see Ward).
                    {
                        var p = rest.Trim().Split(' ');
                        if (p.Length < 4) break;
                        if (!int.TryParse(p[0], out int points)) break;
                        if (!float.TryParse(p[1], NumberStyles.Float, CultureInfo.InvariantCulture, out float tough)) break;
                        if (!float.TryParse(p[2], NumberStyles.Float, CultureInfo.InvariantCulture, out float prot)) break;
                        if (!int.TryParse(p[3], out int res)) break;
                        ArmourPoints = Mathf.Max(0, points);
                        ArmourToughness = tough;
                        ArmourProtection = prot;
                        ArmourResistance = Mathf.Max(0, res);
                    }
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
            if (damage <= 0 || nm.hp <= 0) return;
            // a hit Minecraft has already reduced must not be reduced here a second time
            if (FromMc) return;
            // what Steve's armour and his Minecraft effects would have taken off it, done here because ULTRAKILL's
            // own enemies never reach that code at all
            Ward(ref damage);
            if (damage <= 0) return;
            // A Totem of Undying: the blow that would have been the last one is warded off instead. Minecraft's own
            // death protection runs entirely on the other side, where the item lives - the count only arrives here
            // (TOTEM n) so this track can tell the blow is survivable; the item itself is spent back there (TOTEMUSE)
            // through that same protection, sound, effects and all. It comes after the armour for the same reason
            // Minecraft checks it last: only what is left over is the blow.
            if (Totems > 0 && !NoTotem && damage >= nm.hp)
            {
                Totems--;
                damage = 0;
                Net.Send("TOTEMUSE");
                return;
            }
            if (AbsorbMc <= 0 || UkMax <= 0 || McMax <= 0) return;
            int back = Mathf.Min(damage, Mathf.Max(0, Mathf.CeilToInt(AbsorbHp)));
            if (back <= 0) return;
            // the pool pays in Steve's health points (Minecraft's own units, which is what the server keeps); the
            // rounding is there because both of them count in whole points and a hit is rarely a whole one of ours
            Spend(Mathf.RoundToInt((AbsorbHp - back) * McMax / UkMax));
            damage -= back;
        }

        /// <summary>
        /// The armour a hit wears off, worked out the way Minecraft works it out, because ULTRAKILL's enemies never
        /// reach that code: a Stray's beam is handed to GetHurt directly, so without this it lands on V1 with the
        /// damage it would do to someone wearing nothing at all.
        ///
        /// <para>Minecraft's own figures, in order, from CombatRules and LivingEntity. Armour first: the points are
        /// scaled down by the blow itself (a heavy hit drives them to their floor of a fifth, a light one leaves them
        /// whole), so one armour number is never a single percentage, and the sum is then divided by 25. Protection and
        /// its kin come next, as points on the same scale. Resistance last, five points a level. Nothing here runs for
        /// a hit Minecraft already reduced (FromMc), and nothing is ever reduced to nothing: a hit that does land does
        /// at least one point, as it does in Minecraft.</para>
        /// </summary>
        internal static void Ward(ref int damage)
        {
            if (damage <= 0) return;
            if (ArmourPoints > 0)
            {
                // CombatRules.getDamageAfterAbsorb: i = 2 + toughness/4, j = clamp(armour - damage/i, armour*0.2, 20)
                float keep = 2f + ArmourToughness / 4f;
                float points = Mathf.Clamp(ArmourPoints - damage / keep, ArmourPoints * 0.2f, 20f);
                float cut = points / 25f;
                damage = Mathf.Max(1, Mathf.RoundToInt(damage * (1f - cut)));
            }
            if (ArmourProtection > 0f)
            {
                // CombatRules.getDamageAfterMagicAbsorb: points clamp at 20, then a flat twenty-fifth each
                float points = Mathf.Clamp(ArmourProtection, 0f, 20f);
                damage = Mathf.Max(1, Mathf.RoundToInt(damage * (1f - points / 25f)));
            }
            if (ArmourResistance > 0)
            {
                // LivingEntity.getDamageAfterMagicAbsorb: five points a level, out of the same twenty-five
                int points = Mathf.Min(20, ArmourResistance * 5);
                damage = Mathf.Max(1, Mathf.RoundToInt(damage * (25f - points) / 25f));
            }
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
            // refill only the hearts the server actually granted: blood puts spent ones back, it never adds new
            // ones, so the cap stays what the item gave (a golden apple's 80, say) instead of growing with every heal
            int room = Mathf.Max(0, grantedMc - AbsorbMc);
            Spend(AbsorbMc + Mathf.Min(room, Mathf.Max(1, Mathf.RoundToInt(over * (float)McMax / UkMax))));
        }

        /// <summary>V1's healing cap: his own maximum plus the granted pool when the pool counts as it (blood refills
        /// the granted hearts but cannot grow them, so the cap is what the server's apple or potion gave).</summary>
        internal static float HealCapFloat() => PoolCeiling ? UkMax + Mathf.Min(AbsorbHp, grantedMc * (float)UkMax / McMax) : UkMax;

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
            NoShake = true;
            NoTotem = true; // the void takes V1 outright: a totem cannot ward off the bottom of the world
            try { nm.GetHurt(nm.hp * 10 + 999, false, 0f, ignoreInvincibility: true); }
            finally { FromMc = false; NoShake = false; NoTotem = false; }
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
                // creative (or watching): ULTRAKILL's enemies can still swing at him, but nothing of theirs lands
                if (Bridge.God) { damage = 0; return; }
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
