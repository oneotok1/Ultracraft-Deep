// UltraBridge: conduction damage from nails, put back the way ULTRAKILL meant it.
//
// EnemyIdentifier.DelayedAfterShock works out how hard the aftershock should be - up to 6 for nails stuck in the
// target (15 nails per point), plus one per magnet, at least 1 when standing in water - and then builds the
// Aftershock object with its delay, its position and its waterOnly flag, but never its extraDamage. That field
// keeps its default 0, and EnemyIdentifier.Zap takes `Mathf.Max(2f, damage)` per enemy, so a target carrying
// ninety nails still conducts the same 2 points a bare one does. The number is computed and thrown away.
//
// This is the same method with that one assignment added. The body is copied rather than patched around because
// the value has to be in place before ZapFrom starts its coroutine, and because a postfix would run after the
// coroutine had already been handed the zero.

using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    [HarmonyPatch(typeof(EnemyIdentifier), "DelayedAfterShock")]
    static class ConductionKeepsItsDamage
    {
        static bool Prefix(EnemyIdentifier __instance, float delay, Vector3 pos,
                           ref bool ___afterShockFromZap,
                           List<Magnet> ___stuckMagnets,
                           int ___nailsAmount,
                           HashSet<Water> ___touchingWaters,
                           bool ___waterOnlyAftershock)
        {
            float damage = Mathf.Min(6f, ___nailsAmount / 15f);
            ___afterShockFromZap = false;
            if (___stuckMagnets.Count > 0)
            {
                damage += ___stuckMagnets.Count;
                foreach (var magnet in ___stuckMagnets)
                    if (magnet != null) magnet.health -= 1f;
            }
            if (damage < 1f && ___touchingWaters.Count > 0) damage = 1f;
            var aftershock = new GameObject().AddComponent<Aftershock>();
            aftershock.delay = delay;
            aftershock.transform.position = pos;
            aftershock.waterOnly = ___waterOnlyAftershock;
            // the line ULTRAKILL leaves out
            aftershock.extraDamage = damage;
            aftershock.ZapFrom(__instance);
            return false;
        }
    }
}
