// UltraBridge, part six: playing together. Every player runs their own ULTRAKILL; the enemies each one runs are shown in
// the others' as puppets, and the other players as V1's body.
//
// - Our own enemies go to Minecraft with what a puppet needs (UKE ...,key,yaw,anim) and when they die (UKDIE).
// - PUPS id,key,x,y,z,yaw,anim,dead,w,h;...: another player's enemies near us (id: its Minecraft stand-in). Each is the
//   real enemy with its own brain off, moved and animated as its owner's is. Our shots hurt it for real: the hit goes
//   to its owner's ULTRAKILL (PHIT), where the enemy actually lives; here it bleeds (and heals us) like any enemy.
// - EHIT id damage head explosion by: another player (by: their Minecraft id) hit one of our enemies (through its
//   puppet in their game).
// - Other players who are V1 come in ENTS as "v1": V1's own body stands there, and our enemies go for them as for us
//   (their hits go through Minecraft to that player's ULTRAKILL). Our shots stop at a teammate but don't hurt them.

using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.Rendering;

namespace UltraBridge
{
    /// <summary>Marks a puppet: another player's enemy, shown here with its brain off.</summary>
    public class UkPuppet : MonoBehaviour
    {
        public int id;
        public string key;
        public EnemyIdentifier eid;
        public Animator anim;
        public int animHash;
        public Vector3 feetOffset;
        public Vector3 target;
        public float yaw;
        public bool placed;
    }

    public partial class Bridge
    {
        readonly Dictionary<int, UkPuppet> puppets = new Dictionary<int, UkPuppet>();
        /// <summary>The other V1 we're dueling (their Minecraft id): our shots hurt them (Minecraft's Duels).</summary>
        internal int duelWith = int.MinValue;

        static bool IsPuppet(EnemyIdentifier eid) => eid != null && eid.GetComponentInParent<UkPuppet>() != null;

        void HandleMultiplayer(string cmd, string rest)
        {
            switch (cmd)
            {
                case "PUPS":
                    ApplyPuppets(rest);
                    break;
                case "PEERS":
                    // PEERS n: other players who are V1 (only then do we send what our V1 and our game do)
                    mpPeers = int.Parse(rest.Trim());
                    break;
                case "RV1":
                    HandleRemoteV1(rest);
                    break;
                case "RFX":
                    HandleRemoteFx(rest);
                    break;
                case "DOWNED":
                    downed = rest.Trim().StartsWith("1");
                    Plugin.Log.LogInfo(downed ? "V1 is down: watching a teammate" : "V1 is back up");
                    break;
                case "PREFAB":
                {
                    // debug: PREFAB name - is that prefab loaded here, and what's loaded like it
                    var name = rest.Trim();
                    var found = Prefab(name);
                    var sb = new StringBuilder("MPINFO prefab " + name + " found=" + (found != null) + " total=" + prefabs.Count + " like:");
                    int n = 0;
                    foreach (var k in prefabs.Keys) if (n < 12 && k.IndexOf(name.Split(' ')[0], StringComparison.OrdinalIgnoreCase) >= 0) { sb.Append(" [").Append(k).Append(']'); n++; }
                    Net.Send(sb.ToString());
                    break;
                }
                case "MPLOOP":
                {
                    // debug: our own V1 and effects shown back to us on a stand-in teammate in front
                    var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
                    if (nm != null) StartLoop(nm);
                    break;
                }
                case "CLICK":
                    // debug: CLICK seconds - hold the fire button that long
                    forceClickUntil = Time.unscaledTime + F(rest.Trim());
                    break;
                case "MPINFO":
                    Net.Send(MpInfo());
                    break;
                case "EHIT":
                {
                    // EHIT id damage head explosion [by]: another player's shot, through this enemy's puppet in their game
                    var a = rest.Split(' ');
                    if (a.Length < 2 || !levelPrepared) break;
                    if (!ukEnemies.TryGetValue(int.Parse(a[0]), out var eid) || eid == null || eid.dead) break;
                    bool head = a.Length > 2 && a[2] == "1";
                    bool blast = a.Length > 3 && a[3] == "1";
                    var target = head && eid.weakPoint != null ? eid.weakPoint : eid.gameObject;
                    try
                    {
                        eid.hitter = blast ? "explosion" : "revolver";
                        eid.DeliverDamage(target, Vector3.zero, target.transform.position, F(a[1]), false, head ? 1f : 0f, null, false, blast);
                        // who did it (their Minecraft id): it counts towards whom the enemy goes for
                        if (a.Length > 4) AddThreat(eid, int.Parse(a[4]), F(a[1]));
                    }
                    catch (Exception e) { Plugin.Log.LogDebug("EHIT: " + e.Message); }
                    break;
                }
                case "PUPINFO":
                {
                    var sb = new StringBuilder("PUPINFO n=" + puppets.Count);
                    foreach (var p in puppets.Values) if (p != null) sb.Append(' ').Append(p.id).Append(':').Append(p.key).Append('@').Append(UkToMc(p.transform.position));
                    int v1s = 0;
                    foreach (var p in proxies.Values) if (p != null && p.type == "v1") v1s++;
                    sb.Append(" v1bodies=").Append(v1s);
                    Net.Send(sb.ToString());
                    break;
                }
            }
        }

        // ------------------------------------------------------------ what our enemies are, for the others

        /// <summary>What another player's ULTRAKILL spawns to show this enemy: a boss by its key ("b:v2"), a Sandbox
        /// spawn by name ("s:Filth"), else its type ("t:Filth").</summary>
        string PuppetKey(EnemyIdentifier eid)
        {
            foreach (var f in bosses.Values)
                foreach (var part in f.parts)
                    if (part.eid == eid) return "b:" + f.key;
            var inst = eid.GetComponentInParent<Sandbox.EnemySpawnableInstance>();
            if (inst != null && inst.sourceObject != null && !string.IsNullOrEmpty(inst.sourceObject.objectName)) return "s:" + inst.sourceObject.objectName.Replace(',', ' ').Replace(';', ' ');
            return "t:" + eid.enemyType;
        }

        /// <summary>The state its animator plays (layer 0), for its puppets to play too.</summary>
        static int AnimState(EnemyIdentifier eid)
        {
            var anim = eid.GetComponentInChildren<Animator>();
            if (anim == null || !anim.isActiveAndEnabled || anim.layerCount == 0) return 0;
            try { return anim.GetCurrentAnimatorStateInfo(0).fullPathHash; }
            catch { return 0; }
        }

        // ------------------------------------------------------------ the others' enemies here

        void ApplyPuppets(string rest)
        {
            if (!levelPrepared || !originSet) return;
            var seen = new HashSet<int>();
            foreach (var rec in rest.Split(';'))
            {
                if (rec.Length == 0) continue;
                var a = rec.Split(',');
                if (a.Length < 8) continue;
                int id = int.Parse(a[0]);
                seen.Add(id);
                bool dead = a[7] == "1";
                puppets.TryGetValue(id, out var p);
                if (p == null)
                {
                    if (dead) continue;
                    p = MakePuppet(id, a[1]);
                    if (p == null)
                    {
                        // can't show it (unknown here): don't try every frame
                        puppets[id] = null;
                        continue;
                    }
                    puppets[id] = p;
                }
                p.target = McToUk(new Vector3(F(a[2]), F(a[3]), F(a[4])));
                p.yaw = F(a[5]);
                int anim = int.Parse(a[6]);
                if (anim != 0 && anim != p.animHash && p.anim != null)
                {
                    p.animHash = anim;
                    try { if (p.anim.HasState(0, anim)) p.anim.CrossFade(anim, 0.1f, 0); }
                    catch (Exception e) { Plugin.Log.LogDebug("puppet anim: " + e.Message); }
                }
                if (dead) KillPuppet(id, p);
            }
            var gone = new List<int>();
            foreach (var kv in puppets) if (!seen.Contains(kv.Key)) gone.Add(kv.Key);
            foreach (var id in gone)
            {
                var p = puppets[id];
                puppets.Remove(id);
                if (p != null) Destroy(p.gameObject);
            }
        }

        UkPuppet MakePuppet(int id, string key)
        {
            GameObject prefab = null;
            try
            {
                if (key.StartsWith("b:"))
                {
                    if (BossDefs.TryGetValue(key.Substring(2), out var def))
                    {
                        var so = def.spawnable != null ? FindSpawnable(def.spawnable) : null;
                        prefab = so != null ? so.gameObject : BossPrefab(def.asset);
                    }
                }
                else if (key.Length > 2)
                {
                    var so = FindSpawnable(key.Substring(2));
                    if (so != null) prefab = so.gameObject;
                }
            }
            catch (Exception e) { Plugin.Log.LogWarning("puppet " + key + ": " + e.Message); }
            if (prefab == null)
            {
                Plugin.Log.LogWarning("puppet " + key + ": nothing to show it with");
                return null;
            }
            // built asleep, its brain taken out before it wakes: only its body, its hitboxes and its animator are left
            var holder = new GameObject("uc puppet " + id);
            holder.SetActive(false);
            var go = Instantiate(prefab, holder.transform);
            go.name = "puppet " + key;
            foreach (var mb in go.GetComponentsInChildren<MonoBehaviour>(true))
            {
                if (mb is EnemyIdentifier || mb is EnemyIdentifierIdentifier) continue;
                var n = mb.GetType().Name;
                if (n.Contains("Simplifier") || n.Contains("Outline") || n.Contains("Texture") || n.Contains("Material")) continue;
                if (mb is BossHealthBar) { DestroyImmediate(mb); continue; }
                mb.enabled = false;
            }
            foreach (var agent in go.GetComponentsInChildren<NavMeshAgent>(true)) agent.enabled = false;
            foreach (var rb in go.GetComponentsInChildren<Rigidbody>(true))
            {
                rb.isKinematic = true;
                rb.useGravity = false;
            }
            foreach (var src in go.GetComponentsInChildren<AudioSource>(true)) src.enabled = false;
            var eid = go.GetComponentInChildren<EnemyIdentifier>(true);
            if (eid != null) eid.spawnIn = false;
            var p = holder.AddComponent<UkPuppet>();
            p.id = id;
            p.key = key;
            p.eid = eid;
            p.anim = go.GetComponentInChildren<Animator>(true);
            if (p.anim != null) p.anim.cullingMode = AnimatorCullingMode.AlwaysAnimate;
            holder.SetActive(true);
            // where its feet are from its pivot (Minecraft says where its feet are)
            if (eid != null)
            {
                var b = EnemyBounds(eid);
                p.feetOffset = go.transform.position - new Vector3(b.center.x, b.min.y, b.center.z);
            }
            Plugin.Log.LogInfo("puppet " + id + ": " + key);
            return p;
        }

        /// <summary>Its owner's ULTRAKILL says it died: it bursts here the way an enemy does, and goes.</summary>
        void KillPuppet(int id, UkPuppet p)
        {
            puppets[id] = null;
            if (p == null) return;
            var at = p.eid != null ? EnemyBounds(p.eid).center : p.transform.position;
            SpawnGore(p.eid, GoreType.Head, at, false, -1);
            SpawnGore(p.eid, GoreType.Body, at + Vector3.up * 0.5f, false, 3);
            SpawnGore(p.eid, GoreType.Body, at - Vector3.up * 0.5f, false, 3);
            if (p.eid != null)
            {
                var b = EnemyBounds(p.eid);
                BloodBurst(at, Mathf.Max(b.size.x, b.size.y) / K, p.eid);
            }
            Destroy(p.gameObject);
        }

        /// <summary>Our shot hit a puppet: the real enemy, in its owner's game, takes it; here it only bleeds.</summary>
        public bool PuppetHit(UkPuppet p, EnemyIdentifier eid, GameObject target, Vector3 hitPoint, float multiplier, float critMultiplier, bool fromExplosion)
        {
            bool head = target != null && eid.weakPoint != null && (target == eid.weakPoint || target.CompareTag("Head"));
            Net.Send("PHIT " + p.id + " " + S(multiplier) + " " + (head ? 1 : 0) + " " + (fromExplosion ? 1 : 0));
            var at = hitPoint != Vector3.zero ? hitPoint : target != null ? target.transform.position : eid.transform.position;
            SpawnGore(eid, head ? GoreType.Head : multiplier >= 1f || fromExplosion ? GoreType.Body : GoreType.Small, at, fromExplosion, -1);
            return false;
        }

        void ClearPuppets()
        {
            foreach (var p in puppets.Values) if (p != null) Destroy(p.gameObject);
            puppets.Clear();
        }

        // ------------------------------------------------------------ every frame

        void UpdateMultiplayer()
        {
            float t = Mathf.Clamp01(Time.unscaledDeltaTime * 15f);
            foreach (var p in puppets.Values)
            {
                if (p == null) continue;
                var want = p.target + p.feetOffset;
                // Minecraft's stand-in moves in ticks: glide between them
                p.transform.position = !p.placed || (p.transform.position - want).sqrMagnitude > 16f * K * K ? want : Vector3.Lerp(p.transform.position, want, t);
                p.placed = true;
                p.transform.rotation = Quaternion.Slerp(p.transform.rotation, Quaternion.Euler(0f, p.yaw, 0f), t);
                if (Time.frameCount % 10 == 0) LightUp(p.gameObject, LightNear(p.transform.position), 0.4f);
            }
            foreach (var p in proxies.Values)
            {
                if (p == null || p.type != "v1" || p.v1Body == null) continue;
                // Minecraft's yaw, turned into ULTRAKILL's (its z runs the other way)
                p.v1Body.rotation = Quaternion.Slerp(p.v1Body.rotation, Quaternion.Euler(0f, p.yaw + 180f, 0f), t);
                if (p.v1Anim != null)
                {
                    var v = p.velocity;
                    p.v1Anim.SetBool("Running", new Vector2(v.x, v.z).magnitude > 2f * K);
                    // in the air as their own ULTRAKILL has it (Minecraft's movement comes in ticks: going by it, the
                    // jump started over and over)
                    p.v1Anim.SetBool("InAir", p.remoteKnown ? !p.remoteGrounded : Mathf.Abs(v.y) > 2f * K);
                    if (HasParam(p.v1Anim, "Sliding")) p.v1Anim.SetBool("Sliding", p.remoteSliding);
                }
                if (Time.frameCount % 10 == 0) LightUp(p.v1Body.gameObject, LightNear(p.transform.position), 0.5f);
            }
        }

        static readonly Dictionary<Animator, HashSet<string>> animParams = new Dictionary<Animator, HashSet<string>>();

        static bool HasParam(Animator a, string name)
        {
            if (!animParams.TryGetValue(a, out var set))
            {
                set = new HashSet<string>();
                foreach (var p in a.parameters) set.Add(p.name);
                animParams[a] = set;
                Plugin.Log.LogInfo("V1 body animator: " + string.Join(", ", set));
            }
            return set.Contains(name);
        }

        // ------------------------------------------------------------ other V1s

        /// <summary>V1's own body (the platformer V1 of 4-S, stripped to its meshes and animator) standing in for
        /// another player, sized to their Minecraft height.</summary>
        void AddV1Body(McProxy p, float height)
        {
            try
            {
                var pt = MonoSingleton<PlayerTracker>.Instance;
                var prefab = pt != null ? pt.platformerPlayerPrefab : null;
                var pm = prefab != null ? prefab.GetComponentInChildren<PlatformerMovement>(true) : null;
                if (pm == null) return;
                var holder = new GameObject("v1 body holder");
                holder.SetActive(false);
                holder.transform.SetParent(p.transform, false);
                var body = Instantiate(pm.gameObject, holder.transform);
                body.transform.localPosition = Vector3.zero;
                body.transform.localRotation = Quaternion.identity;
                foreach (var c in body.GetComponentsInChildren<MonoBehaviour>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Joint>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Collider>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Rigidbody>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<AudioSource>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Camera>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Light>(true)) DestroyImmediate(c);
                foreach (var r in body.GetComponentsInChildren<Renderer>(true))
                {
                    if (!(r is SkinnedMeshRenderer) && !(r is MeshRenderer)) r.enabled = false;
                    r.shadowCastingMode = ShadowCastingMode.Off;
                }
                foreach (var tr in body.GetComponentsInChildren<Transform>(true)) tr.gameObject.layer = 0;
                holder.SetActive(true);
                // sized to the player: its meshes' height against theirs
                var b = new Bounds();
                bool any = false;
                foreach (var r in body.GetComponentsInChildren<Renderer>())
                {
                    if (!r.enabled) continue;
                    if (!any) { b = r.bounds; any = true; }
                    else b.Encapsulate(r.bounds);
                }
                if (any && b.size.y > 0.1f) body.transform.localScale *= height / b.size.y;
                p.v1Body = holder.transform;
                p.v1Anim = body.GetComponent<Animator>();
                if (p.v1Anim != null) p.v1Anim.cullingMode = AnimatorCullingMode.AlwaysAnimate;
            }
            catch (Exception e) { Plugin.Log.LogWarning("V1 body: " + e.Message); }
        }
    }
}
