// UltraBridge: Minecraft's mobs in ULTRAKILL's fights, the parts their stand-ins (McProxy) skip by not running
// EnemyIdentifier's own code: conduction (electricity through nails, magnets or water), the Firestarter's gasoline
// (on mobs and on the ground) and the fire it catches, and the Whiplash reeling a light mob in.

using System.Collections.Generic;
using System.Globalization;
using System.Text;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        static readonly AccessTools.FieldRef<EnemyIdentifier, GameObject> AfterShockWeapon =
            AccessTools.FieldRefAccess<EnemyIdentifier, GameObject>("afterShockSourceWeapon");
        static readonly AccessTools.FieldRef<EnemyIdentifier, bool> WaterOnlyAftershock =
            AccessTools.FieldRefAccess<EnemyIdentifier, bool>("waterOnlyAftershock");

        /// <summary>A hit on a mob's stand-in, after it's been passed on: what EnemyIdentifier.DeliverDamage would do
        /// for a real enemy. Electricity into a mob with nails (or magnets) in it, or standing in water, shocks it again a
        /// moment later and arcs to everything near: ULTRAKILL's conduction.</summary>
        public static void Conduct(EnemyIdentifier eid, McProxy p, GameObject sourceWeapon)
        {
            try
            {
                if (eid.nails.Count > 10)
                {
                    for (int j = 0; j < eid.nails.Count - 10; j++)
                    {
                        if (eid.nails[j] != null) Object.Destroy(eid.nails[j].gameObject);
                        eid.nails.RemoveAt(j);
                    }
                }
                if (!eid.beingZapped && eid.hitterAttributes.Contains(HitterAttribute.Electricity) && eid.hitter != "aftershock"
                    && (eid.nailsAmount > 0 || eid.stuckMagnets.Count > 0 || eid.touchingWaters.Count > 0))
                {
                    eid.beingZapped = true;
                    foreach (var nail in eid.nails) if (nail != null) nail.Zap();
                    AfterShockWeapon(eid) = sourceWeapon;
                    WaterOnlyAftershock(eid) = eid.nailsAmount == 0 && eid.stuckMagnets.Count == 0;
                    eid.DelayedAfterShock(0.5f, p.center != null ? p.center.position : p.transform.position);
                }
            }
            catch (System.Exception e) { Plugin.Log.LogDebug("conduct: " + e.Message); }
            // a real enemy forgets what hit it after each hit; without this every later hit would count as electric
            eid.hitterAttributes.Clear();
        }

        // ------------------------------------------------------------ gasoline

        readonly Dictionary<int, int> oilSent = new Dictionary<int, int>();
        float nextOilCheck;

        /// <summary>Which mobs are soaked in gasoline (OILED id amount, 0-10): Minecraft drips it off them.</summary>
        void UpdateOil()
        {
            if (!levelPrepared || Time.unscaledTime < nextOilCheck) return;
            nextOilCheck = Time.unscaledTime + 0.25f;
            foreach (var kv in proxies)
            {
                var p = kv.Value;
                if (p == null || p.eid == null || p.eid.flammables == null) continue;
                float fuel = 0f;
                foreach (var f in p.eid.flammables) if (f != null) fuel = Mathf.Max(fuel, f.fuel);
                int amount = Mathf.CeilToInt(fuel * 2f);
                oilSent.TryGetValue(kv.Key, out var was);
                if (amount == was) continue;
                oilSent[kv.Key] = amount;
                Net.Send("OILED " + kv.Key + " " + amount);
            }
        }

        /// <summary>Gasoline on the ground: ULTRAKILL folds it into its picture the way it does blood, so over Minecraft's
        /// blocks it would vanish; Minecraft paints it (OILS x,y,z,nx,ny,nz).</summary>
        public void ReportOil(Vector3 at, Vector3 normal)
        {
            if (!levelPrepared || !originSet || !Net.Connected) return;
            var m = UkToMc(at);
            Net.Send(string.Format(CultureInfo.InvariantCulture, "OILS {0:0.###},{1:0.###},{2:0.###},{3:0.##},{4:0.##},{5:0.##}", m.x, m.y, m.z, normal.x, normal.y, -normal.z));
        }

        /// <summary>Gasoline caught fire there: it burns away (OILBURN x y z).</summary>
        public void ReportOilBurn(Vector3 at)
        {
            if (!levelPrepared || !originSet || !Net.Connected) return;
            var m = UkToMc(at);
            Net.Send("OILBURN " + S(m.x) + " " + S(m.y) + " " + S(m.z));
        }

        /// <summary>Debug: COMBATTEST blast|oil|burn|zap on the Minecraft mob nearest V1.</summary>
        string CombatTest(string what)
        {
            var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
            if (nm == null) return "no V1";
            McProxy best = null;
            float bestD = float.MaxValue;
            foreach (var p in proxies.Values)
            {
                if (p == null || p.type == "v1" || p.eid == null) continue;
                float d = Vector3.Distance(p.transform.position, nm.transform.position);
                if (d < bestD) { bestD = d; best = p; }
            }
            if (best == null) return "no mob";
            var eid = best.eid;
            var at = best.center != null ? best.center.position : best.transform.position;
            switch (what)
            {
                case "blast":
                    SpawnBlast(at, 1f, false, false);
                    break;
                case "oil":
                    eid.AddFlammable(1f);
                    break;
                case "burn":
                    eid.StartBurning(2f);
                    break;
                case "zap":
                    eid.nailsAmount += 3;
                    eid.hitter = "zapper";
                    eid.hitterAttributes.Add(HitterAttribute.Electricity);
                    eid.DeliverDamage(best.body.gameObject, Vector3.zero, at, 0.2f, false);
                    break;
            }
            return what + " on " + best.type + " " + best.id + " at " + bestD.ToString("F1") + ", flammables " + eid.flammables.Count + ", burners " + eid.burners.Count;
        }

        // ------------------------------------------------------------ the Whiplash

        /// <summary>A mob the Whiplash drags V1 to, as ULTRAKILL's heavy enemies do: anything about V1's size or
        /// bigger (a zombie, a cow, an iron golem...). Only smaller ones (chickens, wolves, cats, babies...) are reeled in.</summary>
        public static bool HeavyMob(McProxy p)
        {
            if (p == null || p.body == null) return true;
            var s = p.body.size;
            float w = s.x / K, h = (s.y + (p.head != null ? p.head.GetComponent<BoxCollider>().size.y : 0f)) / K;
            // as ULTRAKILL sorts its own: anything up to a Stray's size (Steve, zombies, skeletons, endermen, cows, wolves)
            // is reeled in; big ones (spiders, horses, iron golems, ravagers, ghasts, big slimes) pull V1 to them.
            // Steve (0.6 x 1.8) is 0.65, an enderman 1.04, a cow 1.13, a spider 1.76, an iron golem 5.3
            return w * w * h > 1.5f;
        }
    }

    /// <summary>V1's explosions (rockets, cores, the Knuckleblaster's blast...) hurt every Minecraft mob in reach: full
    /// at the middle, about a third at the edge, not through walls. ULTRAKILL's own sphere only catches what it happens
    /// to touch as it grows, which missed mobs off to the side; they're marked as hit so it doesn't count them twice.</summary>
    [HarmonyPatch(typeof(Explosion), "Start")]
    static class BlastsReachMobs
    {
        static readonly AccessTools.FieldRef<Explosion, HashSet<int>> HitColliders = AccessTools.FieldRefAccess<Explosion, HashSet<int>>("hitColliders");

        static void Postfix(Explosion __instance)
        {
            if (__instance.harmless || __instance.enemy || __instance.damage <= 0 || Bridge.I == null) return;
            var at = __instance.transform.position;
            float reach = Mathf.Max(__instance.maxSize, 1f);
            var hit = HitColliders(__instance);
            foreach (var p in Bridge.I.ProxyList())
            {
                if (p == null || p.eid == null || p.type == "v1" || p.body == null) continue;
                var near = p.body.ClosestPoint(at);
                float d = Vector3.Distance(at, near);
                if (d > reach) continue;
                if (d > 0.5f && Physics.Linecast(at, near, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)) continue;
                var root = p.eid.GetComponent<Collider>();
                if (root != null && hit != null && !hit.Add(root.GetInstanceID())) continue;
                float dmg = __instance.damage / 10f * __instance.enemyDamageMultiplier * Mathf.Lerp(1f, 0.35f, d / reach);
                p.eid.hitter = "explosion";
                p.eid.DeliverDamage(p.body.gameObject, Vector3.zero, near, dmg, false, 0f, __instance.sourceWeapon, false, true);
            }
        }
    }

    /// <summary>Gasoline landing on Minecraft's blocks shows there (Minecraft paints it).</summary>
    [HarmonyPatch(typeof(GasolineStain), nameof(GasolineStain.AttachTo))]
    static class OilOnBlocks
    {
        static void Postfix(GasolineStain __instance)
        {
            Bridge.I?.ReportOil(__instance.transform.position, -__instance.transform.forward);
        }
    }

    /// <summary>The Whiplash on a Minecraft mob: a light one (most of them) is reeled in to V1, as ULTRAKILL reels in its
    /// light enemies; V1 is pulled to a heavy one. Minecraft moves the mob (WHIP id vx vy vz, blocks a tick).</summary>
    [HarmonyPatch(typeof(HookArm), "FixedUpdate")]
    static class WhipMobs
    {
        static readonly AccessTools.FieldRef<HookArm, EnemyIdentifier> CaughtEid = AccessTools.FieldRefAccess<HookArm, EnemyIdentifier>("caughtEid");
        static readonly AccessTools.FieldRef<HookArm, bool> LightTarget = AccessTools.FieldRefAccess<HookArm, bool>("lightTarget");
        static readonly AccessTools.FieldRef<HookArm, Rigidbody> EnemyRigidbody = AccessTools.FieldRefAccess<HookArm, Rigidbody>("enemyRigidbody");

        static McProxy pulling;
        static Vector3 lastVelocity;
        static float nextSend;

        static McProxy Caught(HookArm hook)
        {
            var eid = CaughtEid(hook);
            return eid != null ? eid.GetComponent<McProxy>() : null;
        }

        static void Prefix(HookArm __instance, out Quaternion __state)
        {
            __state = Quaternion.identity;
            var p = Caught(__instance);
            if (p == null || p.type == "v1") return;
            __state = p.transform.rotation;
            if (__instance.state != HookState.Caught && __instance.state != HookState.Pulling) return;
            if (Bridge.HeavyMob(p)) return;
            // light: what the pull moves is the mob (its stand-in's body; Minecraft does the actual moving)
            LightTarget(__instance) = true;
            if (EnemyRigidbody(__instance) == null) EnemyRigidbody(__instance) = p.rb;
        }

        static void Postfix(HookArm __instance, Quaternion __state)
        {
            var p = Caught(__instance);
            bool pull = p != null && p.type != "v1" && __instance.state == HookState.Pulling && LightTarget(__instance);
            // the pull turns what it holds to face V1; the stand-in keeps the mob's own way of standing
            if (p != null && p.type != "v1") p.transform.rotation = __state;
            if (!pull)
            {
                if (pulling != null)
                {
                    // let go: the mob carries on a little, as a reeled-in enemy does
                    Send(pulling, lastVelocity * 0.35f);
                    pulling = null;
                }
                return;
            }
            pulling = p;
            var cc = MonoSingleton<CameraController>.Instance;
            var to = cc != null ? cc.transform.position : MonoSingleton<NewMovement>.Instance.transform.position;
            var from = p.center != null ? p.center.position : p.transform.position;
            // ULTRAKILL reels a light enemy in at 60 units a second
            lastVelocity = (to - from).normalized * 60f;
            if (Time.time < nextSend) return;
            nextSend = Time.time + 0.05f;
            Send(p, lastVelocity);
        }

        static void Send(McProxy p, Vector3 ukVelocity)
        {
            // ULTRAKILL units a second to Minecraft blocks a tick
            var v = ukVelocity / Bridge.K / 20f;
            Net.Send(string.Format(CultureInfo.InvariantCulture, "WHIP {0} {1:0.###} {2:0.###} {3:0.###}", p.id, v.x, v.y, -v.z));
        }
    }
}
