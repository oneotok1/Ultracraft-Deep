// UltraBridge: ULTRAKILL's bosses on Minecraft's ground.
//
// V2 was made for smooth floors and walls: it wall-jumps off any wall its wall check touches while in the air, every
// quarter second. Against Minecraft's cliffs, trees and block walls that chained into a climb high into the sky, and
// a dodge in the air holds it level, so it then came down slowly. Here it wall-jumps at most twice before it lands
// again, and only near the ground; it rises no higher than nine blocks over the ground, and falls properly.

using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    [HarmonyPatch(typeof(V2), "WallJump")]
    static class V2WallJumps
    {
        static readonly AccessTools.FieldRef<V2, Rigidbody> Rb = AccessTools.FieldRefAccess<V2, Rigidbody>("rb");
        static readonly Dictionary<V2, int> sinceGround = new Dictionary<V2, int>();
        const int EnvironmentMask = 1 << 8;

        static bool Prefix(V2 __instance)
        {
            var gc = __instance.gc;
            if (gc != null && gc.onGround) sinceGround[__instance] = 0;
            sinceGround.TryGetValue(__instance, out var n);
            if (n >= 2) return false;
            // only near the ground: a wall jump way up a cliff or a tree is how it climbed into the sky
            var at = __instance.transform.position;
            if (!Physics.Raycast(at + Vector3.up * 0.5f, Vector3.down, out var hit, 64f * Bridge.K, EnvironmentMask, QueryTriggerInteraction.Ignore)
                || hit.distance > 4.5f * Bridge.K) return false;
            sinceGround[__instance] = n + 1;
            if (sinceGround.Count > 32) sinceGround.Clear();
            return true;
        }

        /// <summary>Landed: its wall jumps come back.</summary>
        public static void Landed(V2 v2) => sinceGround.Remove(v2);

        public static Rigidbody Body(V2 v2) => Rb(v2);
    }

    /// <summary>On every V2 (the boss or not): no climbing into the sky, no hanging there.</summary>
    [HarmonyPatch(typeof(V2), "FixedUpdate")]
    static class V2AirLeash
    {
        static readonly Dictionary<V2, float> airborne = new Dictionary<V2, float>();
        const int EnvironmentMask = 1 << 8;

        static void Postfix(V2 __instance)
        {
            if (__instance.inIntro || !__instance.isActiveAndEnabled) return;
            var rb = V2WallJumps.Body(__instance);
            var gc = __instance.gc;
            if (rb == null || rb.isKinematic || gc == null) return;
            if (gc.onGround)
            {
                airborne.Remove(__instance);
                V2WallJumps.Landed(__instance);
                return;
            }
            if (!airborne.TryGetValue(__instance, out var since)) airborne[__instance] = since = Time.time;
            float air = Time.time - since, dt = Time.fixedDeltaTime;
            var v = rb.velocity;
            var at = __instance.transform.position;
            bool ground = Physics.Raycast(at + Vector3.up * 0.5f, Vector3.down, out var hit, 96f * Bridge.K, EnvironmentMask, QueryTriggerInteraction.Ignore);
            float height = ground ? hit.distance : 96f * Bridge.K;
            // never higher than nine blocks over what's under it
            if (height > 9f * Bridge.K && v.y > 0f) v.y = 0f;
            // after a moment in the air, it falls like a machine, not a feather
            if (air > 0.8f && v.y > -80f) v.y -= 35f * dt;
            // a long time up (holding level in a dodge, or over a drop): down it comes
            if (air > 4f && height > 2f * Bridge.K) v.y = Mathf.Min(v.y, -40f);
            rb.velocity = v;
            if (airborne.Count > 32)
            {
                var gone = new List<V2>();
                foreach (var k in airborne.Keys) if (k == null) gone.Add(k);
                foreach (var k in gone) airborne.Remove(k);
            }
        }
    }
}
