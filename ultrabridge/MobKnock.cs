// UltraBridge: ULTRAKILL's enemies knock Minecraft's mobs about as they knock V1 about. The attacks that launch V1 (a
// Swordsmachine's or Cerberus's swing, a stomp's shockwave, a Virtue's pillar, an enemy's blast) launch a mob they hit
// the same way: ULTRAKILL's own sums on V1's own mass, sent to Minecraft as the mob's new speed (MKNOCK), and Minecraft
// spares it the fall.

using System;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>The launch the enemy attack under way would give V1 (NewMovement.LaunchFromPoint), set while that attack
    /// hits something.</summary>
    static class MobKnock
    {
        public static bool active;
        public static string source = "";
        static bool behind;
        static Vector3 point, forward;
        static float strength, maxDistance;

        /// <summary>From a point behind whatever is hit (forward: the way it's knocked; zero: straight up).</summary>
        public static void Behind(Vector3 fwd, float force, float maxDist)
        {
            active = true;
            behind = true;
            forward = fwd;
            strength = force;
            maxDistance = maxDist;
        }

        /// <summary>From a fixed point (a blast's centre).</summary>
        public static void FromPoint(Vector3 at, float force, float maxDist)
        {
            active = true;
            behind = false;
            point = at;
            strength = force;
            maxDistance = maxDist;
        }

        public static void Clear() => active = false;

        /// <summary>ULTRAKILL units per second, as LaunchFromPoint and Launch work it out for V1 standing at target.</summary>
        public static Vector3 Velocity(Vector3 target, float mass)
        {
            var from = behind ? target - forward : point;
            var dir = (target - from).normalized;
            if (from == target) dir = Vector3.up;
            float num = Mathf.Max(0f, maxDistance - Vector3.Distance(target, from));
            var v = num * strength * dir;
            v = Vector3.ProjectOnPlane(v, Vector3.up) + Vector3.up * (0.5f * num * strength);
            // Launch: an impulse on V1's body
            return Vector3.ClampMagnitude(v, 1000f) * 8f / Mathf.Max(0.01f, mass);
        }

        public static void Apply()
        {
            var h = new Harmony("dev.ultracraft.ultrabridge.mobknock");
            Patch(h, typeof(SwingCheck2), "CheckEidCollision", nameof(Swing));
            Patch(h, typeof(Explosion), "Collide", nameof(Blast));
            Patch(h, typeof(PhysicalShockwave), "CheckCollision", nameof(Shockwave));
            Patch(h, typeof(VirtueInsignia), "OnTriggerEnter", nameof(Pillar));
        }

        static void Patch(Harmony h, Type t, string method, string prefix)
        {
            try
            {
                var m = AccessTools.Method(t, method);
                if (m == null) { Plugin.Log.LogWarning("mob knock: no " + t.Name + "." + method); return; }
                h.Patch(m, prefix: new HarmonyMethod(typeof(MobKnock), prefix), finalizer: new HarmonyMethod(typeof(MobKnock), nameof(Done)));
            }
            catch (Exception e) { Plugin.Log.LogWarning("mob knock " + t.Name + ": " + e.Message); }
        }

        static void Swing(SwingCheck2 __instance)
        {
            if (__instance.knockBackForce <= 0f || __instance.knockBackDirection == Vector3.down) return;
            var fwd = __instance.knockBackDirectionOverride ? __instance.knockBackDirection : __instance.transform.forward;
            Behind(fwd, __instance.knockBackForce, __instance.knockBackForce);
            source = "swing";
        }

        static void Blast(Explosion __instance, Vector3 __1)
        {
            if (!__instance.enemy || __instance.harmless) return;
            FromPoint(__1, 200f * __instance.pushForceMultiplier, __instance.maxSize);
            source = "blast";
        }

        static void Shockwave(PhysicalShockwave __instance)
        {
            if (!__instance.enemy) return;
            Behind(Vector3.up, 30f, 30f);
            source = "shockwave";
        }

        static void Pillar(VirtueInsignia __instance)
        {
            var t = __instance.target;
            if (t == null || t.isPlayer || t.enemyIdentifier == null || t.enemyIdentifier.GetComponent<McProxy>() == null) return;
            // the pillar's hit is an enemy's (the mob fights back against the Virtue)
            t.enemyIdentifier.hitter = "enemy";
            Behind(Vector3.zero, 200f, 5f);
            source = "pillar";
        }

        static Exception Done(Exception __exception)
        {
            active = false;
            return __exception;
        }
    }

    public partial class Bridge
    {
        readonly Dictionary<int, float> mobKnockedAt = new Dictionary<int, float>();
        int mobKnockLogs;
        public static int MobKnockLogCap = 40;
        // V1 falls slower than a mob (World.cs's LiftScale): a mob needs this much more lift to go as high
        const float MobLift = 1f / LiftScale;

        /// <summary>One of ULTRAKILL's enemies hit this mob with an attack that launches V1: the mob goes flying the
        /// same (MKNOCK id vx vy vz, blocks per tick, the mob's new speed).</summary>
        public void KnockMob(McProxy p)
        {
            if (!MobKnock.active || p == null || p.type == "v1" || p.type == "player" || p.type == "end_crystal") return;
            if (!levelPrepared || !originSet || !Net.Connected) return;
            // a blast reaches each of its colliders: one launch
            if (mobKnockedAt.TryGetValue(p.id, out var at) && Time.time - at < 0.2f) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            float mass = nm != null && nm.rb != null ? nm.rb.mass : 1f;
            var v = MobKnock.Velocity(p.center != null ? p.center.position : p.transform.position, mass);
            if (v.sqrMagnitude < 1f) return;
            if (mobKnockedAt.Count > 256) mobKnockedAt.Clear();
            mobKnockedAt[p.id] = Time.time;
            var m = McDir(v) / 20f;
            m.y *= MobLift;
            m = Vector3.ClampMagnitude(m, 4f);
            Net.Send("MKNOCK " + p.id + " " + S(m.x) + " " + S(m.y) + " " + S(m.z));
            if (mobKnockLogs++ < MobKnockLogCap) Plugin.Log.LogInfo("mob knock (" + MobKnock.source + "): " + p.type + " " + m.ToString("F2") + " (V1 mass " + mass + ")");
        }
    }
}
