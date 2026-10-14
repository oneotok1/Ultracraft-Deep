// UltraBridge, part five and a half: what the arms' own upgrades do (see Upgrades.cs for the list), and the blasts
// they and the bosses' modifiers set off (SpawnBlast).

using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>A blast whose size is set where it's made: the Payload/Shockwave upgrades leave it alone.</summary>
    public class NoUpgrade : MonoBehaviour
    {
    }

    /// <summary>A blast that leaves Minecraft's blocks alone (one going off right where V1 stands).</summary>
    public class NoCrater : MonoBehaviour
    {
    }

    public partial class Bridge
    {
        /// <summary>ULTRAKILL's own explosion, size times as big. byEnemy: it hurts V1 and Minecraft's mobs, never
        /// the enemy that set it off (origin); otherwise it only hurts enemies. crater: it blows a hole in Minecraft
        /// too.</summary>
        public static GameObject SpawnBlast(Vector3 pos, float size, bool byEnemy, bool crater, EnemyIdentifier origin = null, int playerDamage = -1)
        {
            var drm = MonoSingleton<DefaultReferenceManager>.Instance;
            if (drm == null || drm.explosion == null || size <= 0.01f) return null;
            var go = Instantiate(drm.explosion, pos, Quaternion.identity);
            go.AddComponent<NoUpgrade>();
            if (!crater) go.AddComponent<NoCrater>();
            var exs = go.GetComponentsInChildren<Explosion>(true);
            bool rootIsBlast = go.GetComponent<Explosion>() != null;
            if (!rootIsBlast && Mathf.Abs(size - 1f) > 0.01f)
            {
                // the whole effect grows: its sphere (each grows as fast, relative to its parent, to size times as far)
                go.transform.localScale *= size;
                foreach (var ps in go.GetComponentsInChildren<ParticleSystem>(true))
                {
                    var main = ps.main;
                    main.scalingMode = ParticleSystemScalingMode.Hierarchy;
                }
            }
            foreach (var l in go.GetComponentsInChildren<Light>(true)) l.range *= size;
            foreach (var ex in exs)
            {
                ex.maxSize *= size;
                if (rootIsBlast) ex.speed *= size;
                if (byEnemy)
                {
                    ex.enemy = true;
                    ex.canHit = AffectedSubjects.All;
                    ex.originEnemy = origin;
                    if (origin != null)
                    {
                        if (ex.toIgnore == null) ex.toIgnore = new List<EnemyType>();
                        ex.toIgnore.Add(origin.enemyType);
                    }
                    if (playerDamage >= 0) ex.playerDamageOverride = playerDamage;
                }
                else
                {
                    ex.enemy = false;
                    ex.canHit = AffectedSubjects.EnemiesOnly;
                }
            }
            return go;
        }

        /// <summary>Enemies (and Minecraft's mobs) within reach of a point, nearest first.</summary>
        public static List<EnemyIdentifier> EnemiesNear(Vector3 at, float range, EnemyIdentifier except = null)
        {
            var found = new List<EnemyIdentifier>();
            foreach (var col in Physics.OverlapSphere(at, range, (1 << 10) | (1 << 11), QueryTriggerInteraction.Collide))
            {
                var eidid = col.GetComponent<EnemyIdentifierIdentifier>();
                var eid = eidid != null ? eidid.eid : col.GetComponentInParent<EnemyIdentifier>();
                if (eid == null || eid == except || eid.dead || found.Contains(eid)) continue;
                found.Add(eid);
            }
            found.Sort((a, b) => (a.transform.position - at).sqrMagnitude.CompareTo((b.transform.position - at).sqrMagnitude));
            return found;
        }

        /// <summary>A crackle of lightning from one point to another (Arc, Live Wire).</summary>
        public static void Zap(Vector3 from, Vector3 to, Color color)
        {
            var go = new GameObject("uc zap");
            var lr = go.AddComponent<LineRenderer>();
            int n = 8;
            lr.positionCount = n;
            lr.useWorldSpace = true;
            lr.widthMultiplier = 0.18f;
            var shader = Shader.Find("Sprites/Default");
            if (shader != null) lr.material = new Material(shader);
            lr.startColor = lr.endColor = color;
            var dir = to - from;
            var side = Vector3.Cross(dir, Vector3.up).normalized;
            if (side.sqrMagnitude < 0.01f) side = Vector3.right;
            var up = Vector3.Cross(side, dir).normalized;
            for (int i = 0; i < n; i++)
            {
                float t = i / (float)(n - 1);
                var jitter = i == 0 || i == n - 1 ? Vector3.zero : (side * Random.Range(-1f, 1f) + up * Random.Range(-1f, 1f)) * dir.magnitude * 0.06f;
                lr.SetPosition(i, Vector3.Lerp(from, to, t) + jitter);
            }
            var light = new GameObject("uc zap light").AddComponent<Light>();
            light.transform.SetParent(go.transform, false);
            light.transform.position = to;
            light.color = color;
            light.range = 6f;
            light.intensity = 3f;
            var drm = MonoSingleton<DefaultReferenceManager>.Instance;
            if (drm != null && drm.zapImpactParticle != null) Destroy(Instantiate(drm.zapImpactParticle, to, Quaternion.identity), 2f);
            Destroy(go, 0.15f);
        }

        static readonly Color ArcBlue = new Color(0.45f, 0.85f, 1f, 1f);

        /// <summary>A punch landed (Bloodfist, Arc, Detonator).</summary>
        public static void PunchLanded(EnemyIdentifier eid, bool heavy, Vector3 hitPoint)
        {
            if (!AllWeapons || eid == null) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (!heavy)
            {
                float heal = Arm("arm0.bloodfist");
                if (heal > 0f && nm != null && !nm.dead) nm.GetHealth(Mathf.RoundToInt(heal), false);
                int arcs = Mathf.RoundToInt(Arm("arm0.arc"));
                if (arcs > 0)
                {
                    float dmg = ArcDamage * Power("arm0");
                    var from = hitPoint;
                    int done = 0;
                    foreach (var other in EnemiesNear(hitPoint, 8f * K, eid))
                    {
                        if (done >= arcs) break;
                        var to = EnemyBounds(other).center;
                        Zap(from, to, ArcBlue);
                        other.hitter = "zap";
                        other.DeliverDamage(other.gameObject, (to - from).normalized * 500f, to, dmg, false);
                        from = to;
                        done++;
                    }
                }
            }
            else
            {
                float size = Arm("arm1.detonator");
                if (size > 0f) SpawnBlast(hitPoint, size, false, true);
            }
        }

        /// <summary>A parry (Counterblast, Adrenaline).</summary>
        public static void Parried()
        {
            if (!AllWeapons) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            var cam = MonoSingleton<CameraController>.Instance;
            if (nm == null || cam == null) return;
            float size = Arm("arm0.counter");
            if (size > 0f) SpawnBlast(cam.transform.position + cam.transform.forward * 4f, size, false, false);
            float stamina = Arm("arm0.adrenaline");
            if (stamina > 0f)
            {
                nm.boostCharge = Mathf.Min(300f, nm.boostCharge + 100f * stamina);
                if (UpLevel("arm0.adrenaline") >= 5 && !nm.dead) nm.GetHealth(25, false);
            }
        }

        /// <summary>A slam landed (Seismic): a blast around V1 that only hurts enemies, bigger from higher up.</summary>
        public void Seismic(NewMovement nm, float drop)
        {
            float size = Arm("arm1.seismic");
            if (size <= 0f || drop < 1f) return;
            var feet = nm.gc != null ? nm.gc.transform.position : nm.transform.position;
            SpawnBlast(feet, size * (1f + Mathf.Min(drop / 20f, 1f)), false, false);
        }
    }

    /// <summary>Bloodfist, Arc and Detonator: whatever a punch hits.</summary>
    [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.DeliverDamage))]
    static class ArmHits
    {
        static void Prefix(EnemyIdentifier __instance, out string __state) => __state = __instance.hitter;

        static void Postfix(EnemyIdentifier __instance, Vector3 hitPoint, string __state)
        {
            if (__state != "punch" && __state != "heavypunch") return;
            Bridge.PunchLanded(__instance, __state == "heavypunch", hitPoint);
        }
    }

    /// <summary>Counterblast and Adrenaline: every parry flashes.</summary>
    [HarmonyPatch(typeof(TimeController), nameof(TimeController.ParryFlash))]
    static class ArmParry
    {
        static void Postfix() => Bridge.Parried();
    }

    /// <summary>Ironclad: the Knuckleblaster out, V1 takes less.</summary>
    [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHurt))]
    static class ArmIronclad
    {
        [HarmonyPriority(Priority.Low)]
        static void Prefix(ref int damage)
        {
            if (!Bridge.AllWeapons || damage <= 1) return;
            var fc = MonoSingleton<FistControl>.Instance;
            if (fc == null || fc.currentPunch == null || fc.currentPunch.type != FistType.Heavy) return;
            float cut = Bridge.Arm("arm1.ironclad");
            if (cut > 0f) damage = Mathf.Max(1, Mathf.RoundToInt(damage * (1f - cut)));
        }
    }

    /// <summary>Overcharge: punch stamina comes back faster with the Knuckleblaster out.</summary>
    [HarmonyPatch(typeof(WeaponCharges), nameof(WeaponCharges.Charge))]
    static class ArmOvercharge
    {
        static void Prefix(WeaponCharges __instance, out float __state) => __state = __instance.punchStamina;

        static void Postfix(WeaponCharges __instance, float __state)
        {
            if (!Bridge.AllWeapons) return;
            var fc = MonoSingleton<FistControl>.Instance;
            if (fc == null || fc.currentPunch == null || fc.currentPunch.type != FistType.Heavy) return;
            float r = Bridge.Arm("arm1.overcharge");
            if (r > 1f) __instance.punchStamina = UpgradedRecharge.Up(__state, __instance.punchStamina, r, 2f);
        }
    }

    /// <summary>The Whiplash's own: Grapple (into blocks), Live Wire (shocks what it holds), Slingshot (a pull ends
    /// in a launch), Ripcord (what it reels in bursts).</summary>
    [HarmonyPatch]
    static class ArmWhiplash
    {
        static readonly AccessTools.FieldRef<HookArm, Vector3> HookPoint = AccessTools.FieldRefAccess<HookArm, Vector3>("hookPoint");
        static readonly AccessTools.FieldRef<HookArm, Vector3> ThrowDirection = AccessTools.FieldRefAccess<HookArm, Vector3>("throwDirection");
        static readonly AccessTools.FieldRef<HookArm, LayerMask> EnviroMask = AccessTools.FieldRefAccess<HookArm, LayerMask>("enviroMask");
        static readonly AccessTools.FieldRef<HookArm, EnemyIdentifier> CaughtEid = AccessTools.FieldRefAccess<HookArm, EnemyIdentifier>("caughtEid");
        static readonly AccessTools.FieldRef<HookArm, bool> LightTarget = AccessTools.FieldRefAccess<HookArm, bool>("lightTarget");

        static bool wasPulled;
        static float shock;
        static float nextZap;

        [HarmonyPatch(typeof(HookArm), nameof(HookArm.StopThrow))]
        [HarmonyPrefix]
        static void StopThrow(HookArm __instance)
        {
            if (!Bridge.AllWeapons) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm == null) return;
            if (__instance.state == HookState.Throwing)
            {
                // Grapple: the hook struck a block (a wall, the ground): V1 is yanked there
                float speed = Bridge.Arm("arm2.grapple");
                if (speed <= 0f) return;
                var dir = ThrowDirection(__instance);
                var from = HookPoint(__instance) - dir * 3f;
                if (!Physics.Raycast(from, dir, out var hit, 10f, EnviroMask(__instance), QueryTriggerInteraction.Ignore)) return;
                var to = hit.point - nm.transform.position;
                var v = to.normalized * speed;
                // off the ground: a little lift, so a yank along the floor doesn't just scrape
                if (v.y < 8f) v.y = Mathf.Max(v.y, 8f);
                nm.rb.velocity = v;
                nm.jumping = true;
                nm.Invoke("NotJumping", 0.25f);
                Bridge.Zap(__instance.hand != null ? __instance.hand.position : nm.transform.position, hit.point, new Color(1f, 0.85f, 0.4f, 1f));
            }
            else if (__instance.state == HookState.Pulling)
            {
                // Ripcord: an enemy reeled in bursts as it lets go
                var eid = CaughtEid(__instance);
                float size = Bridge.Arm("arm2.ripcord");
                if (size > 0f && eid != null && !eid.dead && LightTarget(__instance))
                    Bridge.SpawnBlast(Bridge.EnemyBounds(eid).center, size, false, false);
            }
        }

        [HarmonyPatch(typeof(HookArm), "FixedUpdate")]
        [HarmonyPostfix]
        static void FixedUpdate(HookArm __instance)
        {
            if (!Bridge.AllWeapons) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm == null) return;
            // Slingshot: a pull to something heavy (or a hook point) just ended: off V1 goes, where it looks
            if (wasPulled && !__instance.beingPulled)
            {
                float boost = Bridge.Arm("arm2.slingshot");
                var cam = MonoSingleton<CameraController>.Instance;
                if (boost > 0f && cam != null) nm.rb.velocity += cam.transform.forward * boost + Vector3.up * boost * 0.25f;
            }
            wasPulled = __instance.beingPulled;
            // Live Wire: what the hook holds is shocked
            var eid = CaughtEid(__instance);
            float dps = Bridge.Arm("arm2.livewire");
            if (dps <= 0f || eid == null || eid.dead || (__instance.state != HookState.Caught && __instance.state != HookState.Pulling))
            {
                shock = 0f;
                return;
            }
            shock += dps * Bridge.Power("arm2") * Time.fixedDeltaTime;
            if (Time.time < nextZap) return;
            nextZap = Time.time + 0.25f;
            var at = Bridge.EnemyBounds(eid).center;
            Bridge.Zap(__instance.hand != null ? __instance.hand.position : nm.transform.position, at, new Color(0.45f, 0.85f, 1f, 1f));
            eid.hitter = "zap";
            eid.DeliverDamage(eid.gameObject, Vector3.zero, at, shock, false);
            shock = 0f;
        }
    }
}
