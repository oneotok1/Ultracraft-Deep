// UltraBridge: more blood. Every death (ULTRAKILL's enemies, Minecraft's mobs, other players' enemies) bursts in a
// spray far heavier than ULTRAKILL's own: chest bursts jetting up and out, splatters and a shower of gibs, all out of
// the body itself and sized to it. Blood from a death only stains the ground close around the body, so it pools there
// instead of speckling the whole area; it all still heals V1.

using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        static readonly GoreType[] BurstGore = { GoreType.Body, GoreType.Head, GoreType.Limb, GoreType.Splatter, GoreType.Small, GoreType.Splatter };
        // no BSType.gib: those are the big entrail pieces, which looked out of place on every Minecraft kill
        /// <summary>Only the blood of a chest burst: its solid pieces (organs, chunks) are switched off.</summary>
        public static void NoOrgans(GameObject burst)
        {
            foreach (var r in burst.GetComponentsInChildren<Renderer>(true))
                if (!(r is ParticleSystemRenderer) && !(r is TrailRenderer) && !(r is LineRenderer)) r.gameObject.SetActive(false);
        }

        static readonly BSType[] BurstGibs = { BSType.jawChunk, BSType.brainChunk, BSType.skullChunk, BSType.eyeball };

        /// <summary>Where something died lately, and how far around it its blood may stain.</summary>
        struct Death
        {
            public Vector3 at;
            public float radius, until;
        }

        static readonly System.Collections.Generic.List<Death> deaths = new System.Collections.Generic.List<Death>();

        /// <summary>Whether a stain here lands too far from a body that just burst: while its blood is flying, a stain
        /// near it but outside its radius is that blood, and isn't painted (other blood, from hits elsewhere, is).</summary>
        internal static bool TooFarFromBody(Vector3 stain)
        {
            if (deaths.Count == 0) return false;
            float now = Time.time;
            bool near = false;
            for (int i = deaths.Count - 1; i >= 0; i--)
            {
                var d = deaths[i];
                if (now > d.until)
                {
                    deaths.RemoveAt(i);
                    continue;
                }
                float sq = (stain - d.at).sqrMagnitude, r2 = d.radius * d.radius;
                if (sq <= r2) return false;
                if (sq <= r2 * 25f) near = true;
            }
            return near;
        }

        /// <summary>A body bursts: size is about how big it was (in blocks).</summary>
        public static void BloodBurst(Vector3 at, float size, EnemyIdentifier eid = null)
        {
            var bsm = MonoSingleton<BloodsplatterManager>.Instance;
            var gz = I != null ? I.McGoreZone() : null;
            if (bsm == null || gz == null || !bsm.goreOn || !ExtraGore) return;
            size = Mathf.Clamp(size, 0.6f, 6f);
            // ULTRAKILL's own enemies: the bursts come out of the body and go where it goes
            var body = eid != null && eid.GetComponent<McProxy>() == null ? BodyPart(eid, at) : null;
            deaths.Add(new Death { at = at, radius = Mathf.Clamp(1.2f + size * 0.6f, 1.5f, 4f) * K, until = Time.time + 4f });
            if (deaths.Count > 64) deaths.RemoveAt(0);
            // everything comes out of the body itself, and sprays from there
            float spread = Mathf.Max(0.15f, size * 0.15f) * K;
            try
            {
                // chest bursts: jets of blood up and out of the body
                int bursts = Mathf.Clamp(Mathf.RoundToInt(size * 1.5f), 2, 5);
                for (int i = 0; i < bursts; i++)
                {
                    var burst = bsm.GetFromQueue(BSType.chestExplosion);
                    if (burst == null) break;
                    burst.transform.position = at + Random.insideUnitSphere * spread * 0.5f;
                    burst.transform.rotation = Quaternion.AngleAxis(Random.Range(0f, 360f), Vector3.up) * Quaternion.AngleAxis(Random.Range(-40f, 40f), Vector3.right);
                    burst.transform.localScale = Vector3.one * Mathf.Clamp(size / 1.5f, 0.8f, 3f);
                    gz.SetGoreZone(burst);
                    NoOrgans(burst);
                    OnBody(burst, body);
                }
                // gibs, thrown hard
                // no gibs: ULTRAKILL's organ pieces looked like big entrails on Minecraft's mobs
                int gibs = 0;
                for (int i = 0; i < gibs; i++)
                {
                    var gib = bsm.GetGib(BurstGibs[i % BurstGibs.Length]);
                    if (gib == null) continue;
                    gib.transform.SetPositionAndRotation(at + Random.insideUnitSphere * spread, Random.rotation);
                    gz.SetGoreZone(gib);
                    gib.SetActive(true);
                    var rb = gib.GetComponent<Rigidbody>();
                    if (rb != null) rb.AddForce(Random.onUnitSphere * 7f + Vector3.up * 8f, ForceMode.VelocityChange);
                }
            }
            catch (System.Exception e) { Plugin.Log.LogDebug("burst: " + e.Message); }
            // more spray out of the body (it heals a little where it lands on V1)
            int splats = Mathf.Clamp(Mathf.RoundToInt(size * 3f), 3, 9);
            for (int i = 0; i < splats; i++)
            {
                var p = at + Random.insideUnitSphere * spread;
                SpawnGore(eid, BurstGore[i % BurstGore.Length], p, false, 3);
            }
        }

        /// <summary>The part of a body nearest a point (one of its hitboxes): a chest burst there moves and falls with the
        /// corpse.</summary>
        static Transform BodyPart(EnemyIdentifier eid, Vector3 at)
        {
            Transform best = null;
            float bd = float.MaxValue;
            foreach (var c in eid.GetComponentsInChildren<Collider>())
            {
                if (c == null || c.isTrigger) continue;
                float d = (c.bounds.center - at).sqrMagnitude;
                if (d < bd) { bd = d; best = c.transform; }
            }
            return best != null ? best : eid.transform;
        }

        /// <summary>A chest burst fountains for a while: ULTRAKILL hangs its own on a body that stays. Ours come with a death
        /// (a kill, a blast, Kill All Enemies) whose body may be thrown, blown apart or gone at once, and a fountain left
        /// behind sprayed on out of thin air where the body was. So ours ride along with the body (if it has one) for their
        /// first moment and only spurt; the blood already out falls and stains as before.</summary>
        internal static void OnBody(GameObject burst, Transform body)
        {
            if (I != null) I.StartCoroutine(Spurt(burst, body));
        }

        const float SpurtSeconds = 0.6f;

        static System.Collections.IEnumerator Spurt(GameObject burst, Transform body)
        {
            var offset = body != null ? burst.transform.position - body.position : Vector3.zero;
            var at = burst.transform.position;
            float until = Time.time + SpurtSeconds;
            while (Time.time < until)
            {
                yield return null;
                // (pooled: moved by anything but us, it's another death's now)
                if (burst == null || !burst.activeInHierarchy || (burst.transform.position - at).sqrMagnitude > 0.01f) yield break;
                if (body != null && body.gameObject.activeInHierarchy)
                {
                    at = body.position + offset;
                    burst.transform.position = at;
                }
            }
            foreach (var ps in burst.GetComponentsInChildren<ParticleSystem>()) ps.Stop(false, ParticleSystemStopBehavior.StopEmitting);
        }

        /// <summary>One of ULTRAKILL's own enemies died here.</summary>
        public static void EnemyDied(EnemyIdentifier eid)
        {
            if (!AllWeapons || eid == null || eid.GetComponent<McProxy>() != null) return;
            var b = EnemyBounds(eid);
            BloodBurst(b.center, Mathf.Max(b.size.x, b.size.y) / K, eid);
        }
    }

    /// <summary>A death's blood only stains close around the body (it still heals V1 wherever it lands on him). Every
    /// stain ULTRAKILL paints comes through here (its particles' hits are queued and painted in one go each frame).</summary>
    [HarmonyPatch(typeof(BloodstainParent), nameof(BloodstainParent.CreateChild))]
    static class StainsNearBody
    {
        static bool Prefix(Vector3 pos) => !Bridge.TooFarFromBody(pos);
    }

    /// <summary>No blood hanging in the air: a splatter's weightless puffs (round drops with no gravity and nothing to
    /// land on, under its head, limb and splatter gore) drift where they were thrown for a second or two, which over
    /// Minecraft looks like blood stuck in mid-air after every kill, crits most of all. The spray that falls and stains
    /// stays.</summary>
    [HarmonyPatch(typeof(Bloodsplatter), "OnEnable")]
    static class NoHangingBlood
    {
        static void Postfix(Bloodsplatter __instance)
        {
            if (!Bridge.AllWeapons) return;
            foreach (var ps in __instance.GetComponentsInChildren<ParticleSystem>(true))
            {
                if (ps == null || ps == __instance.part) continue;
                if (ps.main.gravityModifierMultiplier != 0f || ps.collision.enabled) continue;
                var em = ps.emission;
                if (!em.enabled) continue;
                em.enabled = false;
                ps.Clear(false);
            }
        }
    }

    /// <summary>ULTRAKILL's enemies die bloodier.</summary>
    [HarmonyPatch(typeof(EnemyIdentifier), nameof(EnemyIdentifier.Death), new[] { typeof(bool) })]
    static class DeathBurst
    {
        static void Prefix(EnemyIdentifier __instance, out bool __state) => __state = __instance.dead;

        static void Postfix(EnemyIdentifier __instance, bool __state)
        {
            // only the moment it dies (Death is called again on a corpse)
            if (!__state && __instance.dead) Bridge.EnemyDied(__instance);
        }
    }
}
