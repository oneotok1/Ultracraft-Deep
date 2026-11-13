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
            Patch(h, typeof(PhysicalShockwave), "CheckCollision", nameof(Shockwave), nameof(ShockwaveHit));
            Patch(h, typeof(VirtueInsignia), "OnTriggerEnter", nameof(Pillar));
        }

        static void Patch(Harmony h, Type t, string method, string prefix, string postfix = null)
        {
            try
            {
                var m = AccessTools.Method(t, method);
                if (m == null) { Plugin.Log.LogWarning("mob knock: no " + t.Name + "." + method); return; }
                h.Patch(m, prefix: new HarmonyMethod(typeof(MobKnock), prefix),
                    postfix: postfix != null ? new HarmonyMethod(typeof(MobKnock), postfix) : null,
                    finalizer: new HarmonyMethod(typeof(MobKnock), nameof(Done)));
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

        /// <summary>An explosion's own launch. An enemy's blast throws a mob as ULTRAKILL throws its own enemies; V1's
        /// own blast (a rocket jump's included) uses the numbers Explosion.Collide gives V1 himself, so a mob standing in
        /// a rocket jump goes up exactly as far as the jump throws V1 - and a rocket jump's blast deals no damage, so the
        /// hit would never reach the launch at all if this were left to the damage path.</summary>
        static void Blast(Explosion __instance, Vector3 __1)
        {
            if (__instance.harmless) return;
            if (__instance.enemy)
            {
                FromPoint(__1, 200f * __instance.pushForceMultiplier, __instance.maxSize);
                source = "blast";
                return;
            }
            // Explosion.Collide's own number for V1: 200, or for a rocket jump the shrinking 100 / ((jumps + 3) / 3).
            // The jump count itself is V1's (only his own hit on the blast raises it), so it is read, not stepped here.
            float force = 200f;
            if (__instance.rocketExplosion && __instance.damage == 0)
            {
                var nm = MonoSingleton<NewMovement>.Instance;
                int jumps = nm != null ? nm.rocketJumps : 0;
                force = Mathf.RoundToInt(100f / ((jumps + 3) / 3f));
            }
            FromPoint(__1, force * __instance.pushForceMultiplier, __instance.maxSize);
            source = "blast";
        }

        static void Shockwave(PhysicalShockwave __instance)
        {
            if (!__instance.enemy) return;
            Behind(Vector3.up, 30f, 30f);
            source = "shockwave";
        }

        /// <summary>V1's own landing shockwave (GroundCheck spawns it when the slam was held, and it is harmless): what
        /// it reaches is thrown straight up, harder the longer the slam was held - its force carries slamForce. It is not
        /// an enemy's, so the hit reaches the mob as "explosion" and the launch is taken here instead.</summary>
        static void ShockwaveHit(PhysicalShockwave __instance, Collider col)
        {
            if (__instance.enemy || __instance.fading || col == null || Bridge.I == null) return;
            if (col.gameObject.layer != 10) return;
            var idc = col.gameObject.GetComponent<EnemyIdentifierIdentifier>();
            var eid = idc != null ? idc.eid : null;
            var p = eid != null ? eid.GetComponent<McProxy>() : null;
            if (p == null) return;
            Bridge.I.SlamShock(p, __instance.force);
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
        // Only the horizontal part of a launch is capped: a shockwave's lift is its own number (0.5 * reach * strength),
        // not a direction component, so clamping the whole vector to 4 would cap the rise at the horizontal cap and no
        // slam could ever throw a mob higher than a walk. Vertical keeps ULTRAKILL's own value.
        const float MobKnockSpeed = 4f;
        // The slam wave's own launch, ULTRAKILL's numbers end to end: PhysicalShockwave hands its enemies the vector
        // up * force * 2 (PhysicalShockwave.cs:409), DeliverDamage takes a hundredth of it (EnemyIdentifier.cs:1701)
        // and KnockBack a tenth (Enemy.cs:1540), so a mob leaves the ground at force * 0.002 units a second, plus the
        // flat 1 unit KnockBack adds on top (Enemy.cs:1528). force already carries the slam's own hold time, because
        // GroundCheck scales the wave by slamForce * 2.25 (GroundCheck.cs:142).
        const float SlamKick = 0.002f;
        const float SlamPop = 1f;

        Vector3 KnockSpeed(Vector3 v)
        {
            var m = McDir(v) / 20f;
            m.y *= MobLift;
            var flat = Vector3.ClampMagnitude(new Vector3(m.x, 0f, m.z), MobKnockSpeed);
            return new Vector3(flat.x, m.y, flat.z);
        }

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
            var m = KnockSpeed(v);
            Net.Send("MKNOCK " + p.id + " " + S(m.x) + " " + S(m.y) + " " + S(m.z));
            if (mobKnockLogs++ < MobKnockLogCap) Plugin.Log.LogInfo("mob knock (" + MobKnock.source + "): " + p.type + " " + m.ToString("F2") + " (V1 mass " + mass + ")");
        }

        /// <summary>V1's own landing shockwave reached this mob: it is thrown straight up, as ULTRAKILL throws its own
        /// enemies, and only a light one (the ones the Whiplash reels in) - a heavy one stands its ground. The height
        /// is V1's own slamForce, the number GroundCheck multiplies this wave's force by: a tapped slam hops the mob,
        /// a held one throws it. Harmless either way (the slam wave's own damage is zero).</summary>
        public void SlamShock(McProxy p, float force)
        {
            if (p == null || p.type == "v1" || p.type == "player" || p.type == "end_crystal") return;
            if (!levelPrepared || !originSet || !Net.Connected) return;
            if (HeavyMob(p)) return;
            if (mobKnockedAt.TryGetValue(p.id, out var at) && Time.time - at < 0.2f) return;
            // How long the slam was held is already inside force: GroundCheck multiplies this wave by slamForce * 2.25
            // (GroundCheck.cs:142), and slamForce is 1 when the slam starts (NewMovement.cs:1132) and grows 5 a second
            // while it is held (NewMovement.cs:707). So the launch is ULTRAKILL's own, unit for unit: the speed its
            // KnockBack would give one of its enemies, brought over as blocks a tick. Minecraft's gravity then decides
            // how high that goes, and the mob's own fall decides whether it survives the landing - the wave is
            // harmless, so this is the whole of its effect.
            float up = force * SlamKick + SlamPop;
            float vy = up / 20f / K;
            if (mobKnockedAt.Count > 256) mobKnockedAt.Clear();
            mobKnockedAt[p.id] = Time.time;
            var m = new Vector3(0f, vy, 0f);
            if (m.y < 0.05f) return;
            Net.Send("MKNOCK " + p.id + " " + S(m.x) + " " + S(m.y) + " " + S(m.z));
            if (mobKnockLogs++ < MobKnockLogCap) Plugin.Log.LogInfo("mob knock (slam wave): " + p.type + " " + m.ToString("F2") + " (wave force " + force.ToString("F1") + ", launch " + up.ToString("F0") + " u/s = " + (up / 20f / K).ToString("F2") + " blocks a tick)");
        }
    }
}
