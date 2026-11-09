// UltraBridge, part five and three quarters: one blue punch, every parryable thing in reach.
//
// ULTRAKILL's Punch.ActiveFrame walks the colliders in a tiny sphere around the fist and stops at the first one
// it can parry (Punch.cs:549-560, the `break` at the end of the loop). Physics.OverlapSphere returns no
// particular order, so when a sawblade and an enemy's parryable blow share the frame, whichever one the loop
// happened to reach first took the parry and the other was left swinging. This tries every one of them, in the
// same sphere and the same frame, and lets Punch's own code stand aside afterwards - TryParryProjectile sets
// parriedSomething itself, and the punch's opening branch is skipped once that is true.

using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    [HarmonyPatch(typeof(Punch), "ActiveFrame")]
    static class PunchParriesEverything
    {
        static readonly System.Func<Punch, Transform, bool, bool> TryParry =
            (System.Func<Punch, Transform, bool, bool>)AccessTools.Method(typeof(Punch), "TryParryProjectile")
                .CreateDelegate(typeof(System.Func<Punch, Transform, bool, bool>));

        static void Prefix(Punch __instance, bool firstFrame)
        {
            if (!Bridge.ParryEverything || __instance == null) return;
            if (__instance.type != FistType.Standard || __instance.parriedSomething) return;
            var cc = MonoSingleton<CameraController>.Instance;
            if (cc == null) return;
            // the same reach the punch uses for its own first pass
            var near = Physics.OverlapSphere(cc.GetDefaultPos(), 0.01f, 16384, QueryTriggerInteraction.Collide);
            var seen = new HashSet<Transform>();
            foreach (var c in near)
            {
                if (c == null) continue;
                var t = c.attachedRigidbody != null ? c.attachedRigidbody.transform : c.transform;
                // one rigidbody has a collider per part: the parry is asked of the body, once
                if (t == null || !seen.Add(t)) continue;
                TryParry(__instance, t, firstFrame);
            }
        }
    }
}
