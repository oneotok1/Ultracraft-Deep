// UltraBridge: getting about on Minecraft's blocky ground. ULTRAKILL's levels are ramps and smooth floors; Minecraft's
// are one-block steps, which ULTRAKILL's player and enemies were never made for.
//
// - V1 steps up a block (as Steve steps up a slab) instead of bumping against it, and comes down one without a stutter.
// - Enemies are watched for every way of getting stuck on blocks (see below), and freed.

using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;
using UnityEngine.AI;

namespace UltraBridge
{
    public partial class Bridge
    {
        static readonly AccessTools.FieldRef<NewMovement, Vector3> InputDir = AccessTools.FieldRefAccess<NewMovement, Vector3>("inputDir");
        const int EnvironmentMask = 1 << 8;
        float nextStep;

        /// <summary>Every physics step: V1 walking into a rise of up to a block, with room above it, is lifted onto it.</summary>
        void StepUp(NewMovement nm)
        {
            if (!levelPrepared || !v1Landed || nm == null || nm.dead || nm.rb == null || nm.rb.isKinematic || nm.gc == null) return;
            if (!nm.gc.touchingGround || nm.jumping || Time.time < nextStep) return;
            var col = nm.playerCollider;
            if (col == null) return;
            // where V1 is trying to go (pressed against a wall, its velocity that way is already gone)
            var dir = InputDir(nm);
            dir.y = 0f;
            if (dir.sqrMagnitude < 0.01f)
            {
                var v = nm.rb.velocity;
                dir = new Vector3(v.x, 0f, v.z);
                if (dir.sqrMagnitude < 1f) return;
            }
            dir.Normalize();
            var b = col.bounds;
            float feet = b.min.y, radius = b.extents.x, maxStep = K * 1.05f;
            // something solid of Minecraft's right in front, at foot height
            var low = new Vector3(b.center.x, feet + 0.12f, b.center.z);
            if (!Physics.Raycast(low, dir, out var wall, radius + 0.5f, EnvironmentMask, QueryTriggerInteraction.Ignore)) return;
            if (!IsMinecraftTerrain(wall.collider.transform) || Vector3.Dot(wall.normal, -dir) < 0.5f) return;
            // the top of it, a little past its face
            var probe = wall.point + dir * 0.35f;
            probe.y = feet + maxStep + 0.1f;
            if (!Physics.Raycast(probe, Vector3.down, out var top, maxStep + 0.1f, EnvironmentMask, QueryTriggerInteraction.Ignore)) return;
            float rise = top.point.y - feet;
            if (rise < 0.25f || rise > maxStep || top.normal.y < 0.7f) return;
            // room for all of V1 up there
            var lift = Vector3.up * (rise + 0.04f) + dir * 0.25f;
            var c = b.center + lift;
            float r = radius * 0.9f, half = Mathf.Max(0f, b.extents.y - r);
            if (Physics.CheckCapsule(c + Vector3.up * half, c - Vector3.up * half, r, EnvironmentMask, QueryTriggerInteraction.Ignore)) return;
            nm.rb.position += lift;
            var vel = nm.rb.velocity;
            if (vel.y < 0f) nm.rb.velocity = new Vector3(vel.x, 0f, vel.z);
            nextStep = Time.time + 0.08f;
        }

        // ------------------------------------------------------------ enemies

        // Why ULTRAKILL's enemies get stuck on Minecraft's blocks:
        // - an enemy only lands when its GroundCheckEnemy trigger enters a floor. Knocked or stepping off a block edge,
        //   its body can come to rest on a block corner without the trigger ever entering, so it stays "falling" (or
        //   knocked back) forever, sliding in place;
        // - off the navmesh (a section that wasn't baked yet, the navmesh's edge) its agent does nothing at all;
        // - through ground that wasn't there yet, or off an edge into the void;
        // - hopping up onto a one-block pillar, then being stranded and put back down, over and over.
        // Every enemy is watched: each of those is fixed where it happens, and one that needs fixing again and again is
        // brought somewhere it can walk from, near V1.

        class Stride
        {
            public Vector3 at, good;
            public bool hasGood;
            public float since, limboSince = -1f, offMeshSince = -1f;
            // recent fixes (times), to tell a loop from a one-off
            public readonly List<float> fixes = new List<float>();
            public Enemy enemy;
            public Rigidbody rb;
        }
        readonly Dictionary<int, Stride> enemyProgress = new Dictionary<int, Stride>();
        float nextUnstick;
        static readonly NavMeshPath scratchPath = new NavMeshPath();

        /// <summary>Four times a second: every enemy on the ground is checked for each way of being stuck.</summary>
        void UnstickEnemies()
        {
            if (Time.time < nextUnstick) return;
            nextUnstick = Time.time + 0.25f;
            var nm = MonoSingleton<NewMovement>.Instance;
            List<int> gone = null;
            foreach (var kv in ukEnemies)
            {
                var eid = kv.Value;
                if (eid == null || eid.dead)
                {
                    (gone ?? (gone = new List<int>())).Add(kv.Key);
                    continue;
                }
                if (!eid.gameObject.activeInHierarchy) continue;
                if (!enemyProgress.TryGetValue(kv.Key, out var pr))
                {
                    enemyProgress[kv.Key] = pr = new Stride { at = eid.transform.position, since = Time.time };
                    pr.enemy = eid.GetComponent<Enemy>();
                    pr.rb = eid.GetComponent<Rigidbody>();
                }
                try { Watch(eid, pr, nm, bossEids.Contains(eid)); }
                catch (System.Exception e) { Plugin.Log.LogDebug("unstick " + eid.name + ": " + e.Message); }
            }
            if (gone != null) foreach (var id in gone) enemyProgress.Remove(id);
        }

        void Watch(EnemyIdentifier eid, Stride pr, NewMovement nm, bool boss)
        {
            var en = pr.enemy;
            var nma = en != null && en.nma != null ? en.nma : eid.GetComponent<NavMeshAgent>();
            // fliers and drones have no agent and no ground to be stuck on
            if (nma == null || (en != null && (en.limp || en.overrideFalling))) return;
            var pos = eid.transform.position;
            var gc = en != null ? en.gc : null;

            // somewhere it could stand: remembered, so a fall out of the world has somewhere to go back to
            if (nma.enabled && nma.isOnNavMesh && (gc == null || gc.onGround))
            {
                pr.good = pos;
                pr.hasGood = true;
            }

            // 1. out of the world: under all of Minecraft's ground, or falling a long time with nothing below
            // (bosses have their own, in UpdateBosses)
            bool belowWorld = pos.y < McToUk(new Vector3(0f, -80f, 0f)).y;
            bool longFall = en != null && en.falling && en.fallTime > 3f
                && !Physics.Raycast(pos + Vector3.up * 0.5f, Vector3.down, 96f * K, EnvironmentMask, QueryTriggerInteraction.Ignore);
            if (belowWorld || longFall)
            {
                if (!boss) Rescue(eid, pr, nm, belowWorld ? "below the world" : "falling with no ground below");
                return;
            }

            // 2. limbo: a physics body at rest (falling or knocked back) that never landed
            bool physics = pr.rb != null && !pr.rb.isKinematic && !nma.enabled;
            bool resting = physics && pr.rb.velocity.sqrMagnitude < 2.25f && en != null && (en.falling || en.knockedBack);
            if (!resting) pr.limboSince = -1f;
            else if (pr.limboSince < 0f) pr.limboSince = Time.time;
            else if (Time.time - pr.limboSince > 0.6f)
            {
                pr.limboSince = -1f;
                Land(eid, pr, nm, en, boss);
                return;
            }
            if (physics) return;

            // 3. off the navmesh: the nearest navmesh close by, then further, then near V1
            if (nma.enabled && !nma.isOnNavMesh)
            {
                if (pr.offMeshSince < 0f) pr.offMeshSince = Time.time;
                float waited = Time.time - pr.offMeshSince;
                float radius = waited < 1.5f ? K * 3f : K * 8f;
                if (NavMesh.SamplePosition(pos, out var near, radius, nma.areaMask) && Mathf.Abs(near.position.y - pos.y) < K * 4f)
                {
                    nma.Warp(near.position);
                    pr.offMeshSince = -1f;
                }
                // the navmesh is baked around V1; far off, it may still be coming
                else if (waited > 6f && !boss) Rescue(eid, pr, nm, "no navmesh near it");
                return;
            }
            pr.offMeshSince = -1f;
            if (boss || !nma.enabled) return;

            // 4. stuck on its way: hop up a block or two, down off a patch it can't walk off, or round
            bool stranded = nma.hasPath && !nma.isStopped && nma.pathStatus == NavMeshPathStatus.PathPartial
                && nma.remainingDistance <= K * 2.5f && (nma.destination - pos).sqrMagnitude > K * K * 16f;
            bool wantsToGo = stranded || (nma.hasPath && !nma.isStopped && nma.remainingDistance > K * 2.5f);
            if (!wantsToGo || (pos - pr.at).sqrMagnitude > K * K * 0.25f)
            {
                pr.at = pos;
                pr.since = Time.time;
                return;
            }
            if (Time.time - pr.since < 1.5f) return;
            pr.at = pos;
            pr.since = Time.time;
            if (Looping(pr))
            {
                Rescue(eid, pr, nm, "stuck again and again");
                return;
            }
            Note(pr);
            var dest = nma.destination;
            if (stranded)
            {
                // down (or over) towards where it's going: the first ground ahead it can go on from
                var to = dest - pos;
                to.y = 0f;
                var dir = to.normalized;
                for (int step = 1; step <= 6; step++)
                {
                    var spot = pos + dir * (K * step * 1.5f);
                    if (!NavMesh.SamplePosition(spot, out var hit, K * 5f, nma.areaMask)) continue;
                    if (hit.position.y - pos.y > K * 2.3f || (hit.position - pos).sqrMagnitude < K * K * 1.5f) continue;
                    if (!Reaches(hit.position, dest, nma.areaMask)) continue;
                    nma.Warp(hit.position);
                    nma.SetDestination(dest);
                    return;
                }
                return;
            }
            // up onto the ground ahead (two blocks at most), only where it can go on from there: never onto a pillar
            // it would be stranded on (that was the hop up, put down, hop up loop)
            var ahead = nma.steeringTarget - pos;
            ahead.y = 0f;
            if (ahead.sqrMagnitude < 0.01f) ahead = dest - pos;
            ahead.y = 0f;
            if (ahead.sqrMagnitude < 0.01f) return;
            ahead = ahead.normalized;
            for (int step = 1; step <= 3; step++)
            {
                var spot = pos + ahead * (K * step) + Vector3.up * (K * 2.2f);
                if (!NavMesh.SamplePosition(spot, out var hit, K * 1.5f, nma.areaMask)) continue;
                if (hit.position.y - pos.y > K * 2.3f || (hit.position - pos).sqrMagnitude < K * K * 0.5f) continue;
                if (!Reaches(hit.position, dest, nma.areaMask)) continue;
                nma.Warp(hit.position);
                nma.SetDestination(dest);
                return;
            }
            // nothing ahead to step onto: round it, from the nearest navmesh beside it that does reach
            for (int i = 1; i < 8; i++)
            {
                var side = Quaternion.Euler(0f, i * 45f, 0f) * ahead * (K * 2f);
                if (!NavMesh.SamplePosition(pos + side, out var hit, K * 1.5f, nma.areaMask)) continue;
                if (Mathf.Abs(hit.position.y - pos.y) > K * 2.3f || !Reaches(hit.position, dest, nma.areaMask)) continue;
                nma.Warp(hit.position);
                nma.SetDestination(dest);
                return;
            }
        }

        static bool Reaches(Vector3 from, Vector3 to, int mask)
            => NavMesh.CalculatePath(from, to, mask, scratchPath) && scratchPath.status == NavMeshPathStatus.PathComplete;

        static void Note(Stride pr)
        {
            pr.fixes.Add(Time.time);
            pr.fixes.RemoveAll(t => Time.time - t > 12f);
        }

        /// <summary>Fixed four times in twelve seconds: wherever it is, it can't walk from there.</summary>
        static bool Looping(Stride pr)
        {
            pr.fixes.RemoveAll(t => Time.time - t > 12f);
            return pr.fixes.Count >= 4;
        }

        /// <summary>A body at rest that never landed: on ground, it lands now; wedged on something, onto the navmesh.</summary>
        void Land(EnemyIdentifier eid, Stride pr, NewMovement nm, Enemy en, bool boss)
        {
            if (!boss && Looping(pr))
            {
                Rescue(eid, pr, nm, "kept falling in place");
                return;
            }
            Note(pr);
            var pos = eid.transform.position;
            var nma = en.nma;
            if (nma == null) return;
            bool ground = Physics.Raycast(pos + Vector3.up * 0.3f, Vector3.down, out var under, K * 0.9f, EnvironmentMask, QueryTriggerInteraction.Ignore)
                && under.normal.y > 0.6f;
            // the ground is right there (and walkable): it lands on it. Wedged on a corner or between blocks: the
            // nearest ground it can walk on
            if ((ground && NavMesh.SamplePosition(under.point, out var near, K * 0.75f, nma.areaMask))
                || NavMesh.SamplePosition(pos, out near, K * 3f, nma.areaMask))
            {
                SettleBody(eid, pr, near.position);
                ForceGrounded(en);
                return;
            }
            if (!boss) Rescue(eid, pr, nm, "wedged with no ground near");
        }

        /// <summary>Enemy's own landing runs on its next physics step (agent back on, landing animation, sound).</summary>
        static void ForceGrounded(Enemy en)
        {
            if (en == null) return;
            if (en.knockedBack) en.StopKnockBack();
            var gc = en.gc;
            if (gc != null && gc.forcedOff <= 0)
            {
                gc.touchingGround = true;
                gc.onGround = true;
            }
        }

        static void SettleBody(EnemyIdentifier eid, Stride pr, Vector3 at)
        {
            if (pr.rb != null)
            {
                if (!pr.rb.isKinematic)
                {
                    pr.rb.velocity = Vector3.zero;
                    pr.rb.angularVelocity = Vector3.zero;
                }
                pr.rb.position = at;
            }
            eid.transform.position = at;
            Physics.SyncTransforms();
        }

        /// <summary>Back where it last stood (unless that's where it keeps getting stuck), or else onto the navmesh near
        /// V1, somewhere it can reach V1 from, behind V1 if there's such a spot.</summary>
        void Rescue(EnemyIdentifier eid, Stride pr, NewMovement nm, string why)
        {
            var en = pr.enemy;
            var nma = en != null && en.nma != null ? en.nma : eid.GetComponent<NavMeshAgent>();
            int mask = nma != null ? nma.areaMask : NavMesh.AllAreas;
            Vector3? to = null;
            bool loop = Looping(pr);
            if (!loop && pr.hasGood && NavMesh.SamplePosition(pr.good, out var back, K * 2f, mask)) to = back.position;
            if (to == null && nm != null) to = SpotNear(nm.transform.position, mask);
            Note(pr);
            if (to == null)
            {
                Plugin.Log.LogInfo("enemy " + eid.enemyType + " " + why + ": nowhere to put it yet");
                return;
            }
            SettleBody(eid, pr, to.Value);
            ForceGrounded(en);
            if (nma != null && nma.enabled) nma.Warp(to.Value);
            if (loop) pr.fixes.Clear();
            pr.at = to.Value;
            pr.since = Time.time;
            Plugin.Log.LogInfo("enemy " + eid.enemyType + " " + why + ": put " + (loop || !pr.hasGood ? "near V1" : "back where it stood"));
        }

        /// <summary>Somewhere on the navmesh 8 to 16 blocks from V1 with a full path to V1, behind V1 if possible.</summary>
        static Vector3? SpotNear(Vector3 center, int mask)
        {
            Vector3? fallback = null;
            var cam = MonoSingleton<CameraController>.Instance;
            var fwd = cam != null ? cam.transform.forward : Vector3.forward;
            fwd.y = 0f;
            for (int i = 0; i < 16; i++)
            {
                var off = Quaternion.Euler(0f, Random.Range(0f, 360f), 0f) * Vector3.forward * (K * Random.Range(8f, 16f));
                if (!NavMesh.SamplePosition(center + off, out var hit, K * 3f, mask)) continue;
                if (!Reaches(hit.position, center, mask)) continue;
                if (Vector3.Dot(off, fwd) < 0f) return hit.position;
                if (fallback == null) fallback = hit.position;
            }
            return fallback;
        }
    }
}
