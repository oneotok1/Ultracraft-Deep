// UltraBridge, part six and a half: seeing the other players as they are in their own ULTRAKILL.
//
// Every player's ULTRAKILL runs its own V1 and its own enemies; what they do shows in the others' games through
// Minecraft's server (only while another V1 is about: PEERS n):
// - V1STATE grounded sliding pitch weapon: how our V1 stands (on the ground, sliding), where it looks, and the gun in
//   its hand. The others' ULTRAKILL gets it as RV1 <their entity> ...: V1's body there animates from it (not from
//   Minecraft's choppy movement, which had it jumping over and over in the air) and holds that gun, aimed.
// - FX ...: what our ULTRAKILL spawns that flies or flashes (beams, pellets, nails, saws, rockets, cannonballs, cores,
//   coins, screwdrivers, gasoline, explosions; ours and our enemies'), in Minecraft's coordinates. The others' get RFX
//   <their entity> ... and show the same thing from their own copy of the prefab, harmless: a beam's line, a projectile
//   flying where ours flies (kept in step ten times a second), an explosion's blast with its damage off. The real hit
//   already happened in the game that fired it.
//   L prefab n x,y,z;...   a beam's line          P id prefab x y z vx vy vz fx fy fz   a projectile starts
//   U id,x,y,z,vx,vy,vz;...  where they are now   D id,...  gone                       E prefab x y z scale   a blast
//   A clip x y z volume pitch   a sound: V1's (its guns, arms, movement) or one of our enemies', heard where it was

using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>Marks what another player's game is showing in ours: never sent back, never hurts anything.</summary>
    public class UcRemoteFx : MonoBehaviour
    {
        public Vector3 velocity;
        public bool gravity;
        public float dieAt;
        public LineRenderer line;
        public float lineWidth;
        public float born;

        void Update()
        {
            float now = Time.time;
            if (line != null)
            {
                // a beam fades in a moment, as ULTRAKILL's do
                float t = (now - born) / 0.35f;
                line.widthMultiplier = lineWidth * Mathf.Clamp01(1f - t);
            }
            if (velocity != Vector3.zero || gravity)
            {
                if (gravity) velocity += Physics.gravity * Time.deltaTime;
                transform.position += velocity * Time.deltaTime;
            }
            if (now > dieAt) Destroy(gameObject);
        }
    }

    public partial class Bridge
    {
        int mpPeers;
        float nextV1State;
        string lastV1State = "";

        // ------------------------------------------------------------ what we send

        sealed class Tracked { public int id; public Vector3 last; public float lastAt; }
        static readonly Dictionary<GameObject, Tracked> tracked = new Dictionary<GameObject, Tracked>();
        static readonly List<GameObject> trackedGone = new List<GameObject>();
        static int nextFxId = 1;
        float nextFxUpdate;
        static readonly StringBuilder fxBatch = new StringBuilder();

        bool SendingFx => mpPeers > 0 && levelPrepared && originSet && Net.Connected;

        // debug MPLOOP: what we send comes straight back as another player's (a stand-in V1 in front of us), to see it
        // all in one game
        bool mpLoop;
        readonly List<string> loopBack = new List<string>();
        const int LoopId = -77;

        void SendMp(string line)
        {
            if (mpLoop) loopBack.Add(line);
            else Net.Send(line);
        }

        void StartLoop(NewMovement nm)
        {
            mpLoop = true;
            mpPeers = Math.Max(mpPeers, 1);
            if (proxies.TryGetValue(LoopId, out var old) && old != null) Destroy(old.gameObject);
            var p = MakeProxy(LoopId, "v1", 0.6f * K, 1.8f * K, 1.62f * K);
            var cc = MonoSingleton<CameraController>.Instance;
            var fwd = cc != null ? Vector3.ProjectOnPlane(cc.transform.forward, Vector3.up).normalized : Vector3.forward;
            p.transform.position = nm.transform.position + fwd * 4f * K + Vector3.down * 1.5f;
            p.tickPos = p.transform.position;
            // facing us (Minecraft's yaw: ULTRAKILL's turned half round)
            p.yaw = Quaternion.LookRotation(-fwd).eulerAngles.y - 180f;
            proxies[LoopId] = p;
            if (p.v1Body != null)
            {
                var sb = new StringBuilder("V1 body bones:");
                foreach (var t in p.v1Body.GetComponentsInChildren<Transform>(true)) sb.Append(" [").Append(t.name).Append(" <").Append(t.parent != null ? t.parent.name : "").Append(">]");
                Plugin.Log.LogInfo(sb.ToString());
            }
        }

        void PumpLoop()
        {
            if (!mpLoop || loopBack.Count == 0) return;
            var lines = loopBack.ToArray();
            loopBack.Clear();
            foreach (var l in lines)
            {
                if (l.StartsWith("FX ")) HandleRemoteFx(LoopId + " " + l.Substring(3));
                else if (l.StartsWith("V1STATE ")) HandleRemoteV1(LoopId + " " + l.Substring(8));
            }
        }

        static string Mc3(Vector3 v) => S(v.x) + " " + S(v.y) + " " + S(v.z);
        Vector3 McDir(Vector3 u) => new Vector3(u.x / K, u.y / K, -u.z / K);
        Vector3 UkDir(Vector3 m) => new Vector3(m.x * K, m.y * K, -m.z * K);

        /// <summary>The prefab an object came from: the nearest of it and its parents named "...(Clone)", and that name.</summary>
        static GameObject CloneRoot(Transform t, out string prefab)
        {
            for (var c = t; c != null; c = c.parent)
            {
                if (c.name.EndsWith("(Clone)"))
                {
                    prefab = c.name.Substring(0, c.name.Length - 7).Trim();
                    return c.gameObject;
                }
            }
            prefab = null;
            return null;
        }

        static bool IsRemote(Component c) => c != null && c.GetComponentInParent<UcRemoteFx>() != null;

        /// <summary>Something that flies started (its Start ran): it goes to the others, and is followed until it's gone.</summary>
        public void FxProjectile(Component c)
        {
            if (!SendingFx || IsRemote(c) || IsPuppet(c.GetComponentInParent<EnemyIdentifier>())) return;
            var root = CloneRoot(c.transform, out var prefab);
            if (root == null || tracked.ContainsKey(root)) return;
            var tr = new Tracked { id = nextFxId++, last = root.transform.position, lastAt = Time.time };
            tracked[root] = tr;
            var rb = root.GetComponent<Rigidbody>() ?? c.GetComponent<Rigidbody>();
            var v = rb != null && !rb.isKinematic ? rb.velocity : Vector3.zero;
            bool grav = rb != null && !rb.isKinematic && rb.useGravity;
            var m = UkToMc(root.transform.position);
            SendMp("FX P " + tr.id + " " + prefab.Replace(' ', '_') + " " + Mc3(m) + " " + Mc3(McDir(v)) + " " + Mc3(McDir(root.transform.forward)) + " " + (grav ? 1 : 0));
        }

        /// <summary>A beam (RevolverBeam: revolvers, railcannons, ricoshots): its line once it's drawn (next frame).</summary>
        readonly List<GameObject> pendingBeams = new List<GameObject>();

        public void FxBeam(Component c)
        {
            if (!SendingFx || IsRemote(c)) return;
            var root = CloneRoot(c.transform, out _);
            if (root != null && !pendingBeams.Contains(root)) pendingBeams.Add(root);
        }

        /// <summary>A blast: one line for all its spheres.</summary>
        readonly HashSet<GameObject> sentBlasts = new HashSet<GameObject>();

        public void FxExplosion(Explosion e)
        {
            if (!SendingFx || IsRemote(e)) return;
            var root = CloneRoot(e.transform, out var prefab);
            if (root == null || !sentBlasts.Add(root)) return;
            var m = UkToMc(root.transform.position);
            SendMp("FX E " + prefab.Replace(' ', '_') + " " + Mc3(m) + " " + S(root.transform.lossyScale.x));
        }

        /// <summary>Every frame: beams drawn last frame go out; ten times a second, where everything in flight is.</summary>
        void UpdateMpSync(NewMovement nm)
        {
            if (!SendingFx)
            {
                tracked.Clear();
                pendingBeams.Clear();
                return;
            }
            PumpLoop();
            SendV1State(nm);
            AimBosses(nm);
            for (int i = pendingBeams.Count - 1; i >= 0; i--)
            {
                var root = pendingBeams[i];
                pendingBeams.RemoveAt(i);
                if (root == null) continue;
                var lr = root.GetComponentInChildren<LineRenderer>();
                if (lr == null || lr.positionCount < 2) continue;
                var sb = new StringBuilder("FX L ");
                CloneRoot(root.transform, out var prefab);
                sb.Append((prefab ?? "x").Replace(' ', '_')).Append(' ').Append(lr.positionCount).Append(' ');
                for (int k = 0; k < lr.positionCount; k++)
                {
                    var p = lr.GetPosition(k);
                    if (!lr.useWorldSpace) p = lr.transform.TransformPoint(p);
                    var m = UkToMc(p);
                    sb.Append(S(m.x)).Append(',').Append(S(m.y)).Append(',').Append(S(m.z)).Append(';');
                }
                SendMp(sb.ToString());
            }
            if (sentBlasts.Count > 64) sentBlasts.Clear();
            if (Time.time < nextFxUpdate) return;
            nextFxUpdate = Time.time + 0.1f;
            fxBatch.Length = 0;
            trackedGone.Clear();
            var gone = new StringBuilder();
            foreach (var kv in tracked)
            {
                if (kv.Key == null || !kv.Key.activeInHierarchy)
                {
                    trackedGone.Add(kv.Key);
                    gone.Append(kv.Value.id).Append(',');
                    continue;
                }
                var pos = kv.Key.transform.position;
                var tr = kv.Value;
                var v = (pos - tr.last) / Mathf.Max(0.01f, Time.time - tr.lastAt);
                tr.last = pos;
                tr.lastAt = Time.time;
                var m = UkToMc(pos);
                var mv = McDir(v);
                fxBatch.Append(tr.id).Append(',').Append(S(m.x)).Append(',').Append(S(m.y)).Append(',').Append(S(m.z)).Append(',')
                    .Append(S(mv.x)).Append(',').Append(S(mv.y)).Append(',').Append(S(mv.z)).Append(';');
            }
            foreach (var g in trackedGone) tracked.Remove(g);
            if (fxBatch.Length > 0) SendMp("FX U " + fxBatch);
            if (gone.Length > 0) SendMp("FX D " + gone);
        }

        /// <summary>Our V1 as the others should see it, when it changes (and every couple of seconds).</summary>
        void SendV1State(NewMovement nm)
        {
            if (nm == null || Time.time < nextV1State) return;
            nextV1State = Time.time + 0.1f;
            var gc = MonoSingleton<GunControl>.Instance;
            string weapon = "-";
            if (!hands && gc != null && gc.currentWeapon != null && gc.currentWeapon.activeInHierarchy)
                weapon = gc.currentWeapon.name.Replace("(Clone)", "").Trim().Replace(' ', '_');
            var cc = MonoSingleton<CameraController>.Instance;
            float pitch = cc != null ? cc.transform.eulerAngles.x : 0f;
            if (pitch > 180f) pitch -= 360f;
            bool grounded = nm.gc != null && nm.gc.touchingGround;
            string state = (grounded ? 1 : 0) + " " + (nm.sliding ? 1 : 0) + " " + Mathf.RoundToInt(pitch) + " " + weapon;
            if (state == lastV1State && Time.time < nextV1Keep) return;
            lastV1State = state;
            nextV1Keep = Time.time + 2f;
            SendMp("V1STATE " + state);
        }

        float nextV1Keep;

        // ------------------------------------------------------------ sounds

        string lastSoundClip;
        float lastSoundAt;
        int soundsThisSecond;
        float soundSecond;

        /// <summary>One of ULTRAKILL's sounds played (its AudioSource Play/PlayOneShot): V1's or one of our enemies'
        /// goes to the others (the ones flying things and blasts make come with their copies already).</summary>
        public void FxSound(AudioSource src, AudioClip clip, float volume)
        {
            if (!SendingFx || src == null) return;
            if (clip == null) clip = src.clip;
            if (clip == null || IsRemote(src)) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            bool ours = nm != null && src.transform.IsChildOf(nm.transform);
            if (!ours)
            {
                var eid = src.GetComponentInParent<EnemyIdentifier>();
                if (eid == null || IsPuppet(eid) || eid.GetComponent<McProxy>() != null || eid.GetComponentInParent<McProxy>() != null) return;
            }
            float now = Time.unscaledTime;
            if (clip.name == lastSoundClip && now - lastSoundAt < 0.03f) return;
            if (now > soundSecond) { soundSecond = now + 1f; soundsThisSecond = 0; }
            if (++soundsThisSecond > 40) return;
            lastSoundClip = clip.name;
            lastSoundAt = now;
            // V1's own sounds are flat (it's the player); for the others they come from where V1 stands
            var at = ours ? nm.transform.position : src.transform.position;
            var m = UkToMc(at);
            SendMp("FX A " + clip.name.Replace(' ', '_') + " " + Mc3(m) + " " + S(src.volume * volume) + " " + S(src.pitch));
        }

        readonly Dictionary<string, AudioClip> clips = new Dictionary<string, AudioClip>();
        float nextClipScan;
        UnityEngine.Audio.AudioMixerGroup sfxGroup;

        AudioClip Clip(string name)
        {
            if (clips.TryGetValue(name, out var c) && c != null) return c;
            if (Time.unscaledTime < nextClipScan) return null;
            nextClipScan = Time.unscaledTime + 3f;
            clips.Clear();
            foreach (var a in Resources.FindObjectsOfTypeAll<AudioClip>()) if (a != null && !clips.ContainsKey(a.name)) clips[a.name] = a;
            clips.TryGetValue(name, out c);
            return c;
        }

        /// <summary>Another player's sound, where it was made, through ULTRAKILL's sound-effects mix (its volume setting).</summary>
        void PlayRemoteSound(string clipName, Vector3 at, float volume, float pitch)
        {
            var clip = Clip(clipName);
            if (clip == null) return;
            if (sfxGroup == null)
            {
                var gc = MonoSingleton<GunControl>.Instance;
                if (gc != null) foreach (var au in gc.GetComponentsInChildren<AudioSource>(true)) if (au.outputAudioMixerGroup != null) { sfxGroup = au.outputAudioMixerGroup; break; }
            }
            var go = new GameObject("uc remote sound");
            go.transform.position = at;
            go.AddComponent<UcRemoteFx>().dieAt = Time.time + clip.length / Mathf.Max(0.1f, Mathf.Abs(pitch)) + 0.2f;
            var src = go.AddComponent<AudioSource>();
            src.clip = clip;
            src.volume = Mathf.Clamp01(volume);
            src.pitch = pitch == 0f ? 1f : pitch;
            src.spatialBlend = 1f;
            src.rolloffMode = AudioRolloffMode.Linear;
            src.minDistance = 4f * K;
            src.maxDistance = 64f * K;
            src.dopplerLevel = 0f;
            if (sfxGroup != null) src.outputAudioMixerGroup = sfxGroup;
            src.Play();
        }

        // ------------------------------------------------------------ bosses with friends

        /// <summary>DOWNED 1|0: our V1 is down in a boss fight (watching a teammate) or back up.</summary>
        bool downed;
        float nextBossAim;

        /// <summary>Who a boss here is after: 0 our V1, else a teammate (their Minecraft id), since when, and when it may
        /// turn on someone else just because.</summary>
        sealed class Aim { public int who = -1; public float since, nextShuffle; }
        readonly Dictionary<int, Aim> aims = new Dictionary<int, Aim>();
        // how much each V1 hurt each of our enemies lately (by enemy, then 0 for us or the teammate's Minecraft id)
        readonly Dictionary<int, Dictionary<int, float>> threat = new Dictionary<int, Dictionary<int, float>>();
        float nextThreatDecay;

        /// <summary>A V1 hurt one of our enemies: it counts towards whom that enemy goes for.</summary>
        public void AddThreat(EnemyIdentifier eid, int who, float damage)
        {
            if (eid == null || damage <= 0f || mpPeers <= 0) return;
            int id = eid.GetInstanceID();
            if (!threat.TryGetValue(id, out var t)) threat[id] = t = new Dictionary<int, float>();
            t.TryGetValue(who, out var v);
            t[who] = v + damage;
        }

        float ThreatOf(int enemy, int who) => threat.TryGetValue(enemy, out var t) && t.TryGetValue(who, out var v) ? v : 0f;

        McProxy Mate(int id) => proxies.TryGetValue(id, out var p) && p != null && p.type == "v1" && p.eid != null ? p : null;

        /// <summary>
        /// Our bosses fight everyone, not just us: four times a second each one checks whom it's after. It goes for
        /// whoever hurts it most lately and is near, holds on to them for a few seconds at least, and now and then turns
        /// on someone else anyway. A V1 who is down (or dead, or Steve) is never its target; with ours down, every enemy
        /// of ours goes for the teammates still standing.
        /// </summary>
        void AimBosses(NewMovement nm)
        {
            if (Time.unscaledTime < nextBossAim) return;
            nextBossAim = Time.unscaledTime + 0.25f;
            float now = Time.time;
            if (now >= nextThreatDecay)
            {
                // what they did a while ago counts for less and less
                nextThreatDecay = now + 1f;
                foreach (var t in threat.Values)
                    foreach (var k in new List<int>(t.Keys)) t[k] *= 0.85f;
            }
            bool usUp = nm != null && !nm.dead && !downed && !SteveView;
            var mates = new List<McProxy>();
            foreach (var p in proxies.Values) if (p != null && p.type == "v1" && p.eid != null && p.gameObject.activeInHierarchy) mates.Add(p);
            foreach (var kv in ukEnemies)
            {
                var eid = kv.Value;
                if (eid == null || eid.dead) continue;
                bool boss = IsBossEid(eid);
                if (!boss && usUp)
                {
                    // we're back up: Retarget has our ordinary enemies again (one still on a teammate stays there
                    // only while the teammate is clearly nearer)
                    aims.Remove(kv.Key);
                    continue;
                }
                var pos = eid.transform.position;
                if (!aims.TryGetValue(kv.Key, out var aim)) aims[kv.Key] = aim = new Aim { nextShuffle = now + UnityEngine.Random.Range(12f, 20f) };
                // how much it wants each: who hurt it (damage, fading), and who's near
                float Score(int who, Vector3 at) => ThreatOf(kv.Key, who) + 4f / (1f + (at - pos).magnitude / (K * 8f));
                int best = -1;
                float bestScore = float.MinValue, curScore = float.MinValue;
                bool curOk = false;
                if (usUp)
                {
                    float s0 = Score(0, nm.transform.position);
                    if (aim.who == 0) { curOk = true; curScore = s0; }
                    best = 0;
                    bestScore = s0;
                }
                foreach (var m in mates)
                {
                    float s = Score(m.id, m.transform.position);
                    if (aim.who == m.id) { curOk = true; curScore = s; }
                    if (s > bestScore) { bestScore = s; best = m.id; }
                }
                if (best < 0) continue;
                int pick = aim.who;
                if (!curOk) pick = best;
                else if (best != aim.who && now - aim.since > 4f && bestScore > curScore * 1.3f + 0.5f) pick = best;
                else if (boss && now >= aim.nextShuffle && (mates.Count + (usUp ? 1 : 0)) > 1)
                {
                    // now and then it turns on someone else anyway (bosses don't fixate on one V1)
                    var others = new List<int>();
                    if (usUp && aim.who != 0) others.Add(0);
                    foreach (var m in mates) if (m.id != aim.who) others.Add(m.id);
                    if (others.Count > 0) pick = others[UnityEngine.Random.Range(0, others.Count)];
                }
                if (boss && now >= aim.nextShuffle) aim.nextShuffle = now + UnityEngine.Random.Range(12f, 20f);
                if (pick != aim.who)
                {
                    aim.who = pick;
                    aim.since = now;
                }
                AimAt(eid, aim.who);
            }
            if (aims.Count > ukEnemies.Count * 2 + 8)
                foreach (var k in new List<int>(aims.Keys)) if (!ukEnemies.ContainsKey(k)) { aims.Remove(k); threat.Remove(k); }
        }

        /// <summary>Point an enemy at V1 (0) or a teammate's V1 body, and keep it there: ULTRAKILL itself puts an enemy back
        /// on the player unless it "prioritizes enemies" (EnemyIdentifier.UpdateTarget).</summary>
        void AimAt(EnemyIdentifier eid, int who)
        {
            if (who == 0)
            {
                if (eid.target == null || !eid.target.isPlayer) TargetV1(eid);
                return;
            }
            var m = Mate(who);
            if (m == null) return;
            eid.attackEnemies = true;
            eid.prioritizeEnemiesUnlessAttacked = true;
            if (eid.target == null || eid.target.enemyIdentifier != m.eid) eid.target = new EnemyTarget(m.eid);
        }

        /// <summary>An enemy's choice made in AimBosses, for a hit from our V1 not to snap it straight back to us (the
        /// hit counts as threat instead).</summary>
        internal int AimOf(EnemyIdentifier eid) => eid != null && mpPeers > 0 && aims.TryGetValue(eid.GetInstanceID(), out var a) ? a.who : -1;

        // ------------------------------------------------------------ what the others send

        /// <summary>Loaded prefabs by name, to show what another game spawned from our own copy (looked up again on a miss,
        /// as ULTRAKILL loads more).</summary>
        readonly Dictionary<string, GameObject> prefabs = new Dictionary<string, GameObject>();
        float nextPrefabScan;

        GameObject Prefab(string name)
        {
            if (prefabs.TryGetValue(name, out var go) && go != null) return go;
            if (Time.unscaledTime < nextPrefabScan) return null;
            nextPrefabScan = Time.unscaledTime + 3f;
            prefabs.Clear();
            foreach (var g in Resources.FindObjectsOfTypeAll<GameObject>())
            {
                if (g == null || g.transform.parent != null || g.scene.IsValid() || prefabs.ContainsKey(g.name)) continue;
                prefabs[g.name] = g;
            }
            prefabs.TryGetValue(name, out go);
            return go;
        }

        static readonly HashSet<string> KeepScripts = new HashSet<string> { "RemoveOnTime", "ScaleNFade", "Spin", "ScaleTransform", "Explosion" };

        /// <summary>Our copy of another game's thing: its looks and sounds only (scripts that do anything stripped,
        /// blasts harmless, colliders off).</summary>
        GameObject Visual(GameObject prefab, Vector3 pos, Quaternion rot, float life)
        {
            var holder = new GameObject("uc remote fx");
            holder.SetActive(false);
            GameObject go;
            try
            {
                go = Instantiate(prefab, pos, rot, holder.transform);
                for (int pass = 0; pass < 2; pass++)
                {
                    foreach (var mb in go.GetComponentsInChildren<MonoBehaviour>(true))
                    {
                        if (mb == null) continue;
                        if (mb is Explosion ex)
                        {
                            ex.harmless = true;
                            ex.damage = 0;
                            continue;
                        }
                        if (KeepScripts.Contains(mb.GetType().Name)) continue;
                        try { DestroyImmediate(mb); } catch (Exception) { }
                    }
                }
                rfxShown++;
                foreach (var col in go.GetComponentsInChildren<Collider>(true)) col.enabled = false;
                foreach (var rb in go.GetComponentsInChildren<Rigidbody>(true))
                {
                    rb.isKinematic = true;
                    rb.detectCollisions = false;
                }
                var fx = go.AddComponent<UcRemoteFx>();
                fx.born = Time.time;
                fx.dieAt = Time.time + life;
                go.transform.SetParent(null, true);
            }
            catch (Exception e)
            {
                if (fxWarnings++ < 20) Plugin.Log.LogWarning("remote fx " + prefab.name + ": " + e);
                go = null;
            }
            Destroy(holder);
            return go;
        }

        readonly Dictionary<long, UcRemoteFx> remoteFx = new Dictionary<long, UcRemoteFx>();

        int rfxGot, rv1Got, rfxShown, fxWarnings;

        string MpInfo()
        {
            int guns = 0, bodies = 0;
            foreach (var p in proxies.Values) if (p != null && p.type == "v1") { bodies++; if (p.gun != null) guns++; }
            int alive = 0;
            foreach (var f in remoteFx.Values) if (f != null) alive++;
            return "MPINFO peers=" + mpPeers + " rv1=" + rv1Got + " rfx=" + rfxGot + " shown=" + rfxShown + " flying=" + alive + " bodies=" + bodies + " guns=" + guns
                   + " tracked=" + tracked.Count + GunDebug();
        }

        string GunDebug()
        {
            foreach (var p in proxies.Values)
            {
                if (p == null || p.gun == null) continue;
                int on = 0, all = 0;
                var b = new Bounds();
                foreach (var r in p.gun.GetComponentsInChildren<Renderer>(true))
                {
                    all++;
                    if (!r.enabled || !r.gameObject.activeInHierarchy) continue;
                    if (on++ == 0) b = r.bounds; else b.Encapsulate(r.bounds);
                }
                return " | gun " + p.weaponName + " active=" + p.gun.activeInHierarchy + " renderers=" + on + "/" + all + " pos=" + p.gun.transform.position.ToString("F1")
                       + " scale=" + p.gun.transform.lossyScale.ToString("F2") + " bounds=" + b.center.ToString("F1") + " size=" + b.size.ToString("F2")
                       + " hand=" + (p.hand != null ? p.hand.position.ToString("F1") : "none") + " body=" + p.transform.position.ToString("F1") + " layer=" + p.gun.layer
                       + " center=" + p.gunCenter.ToString("F2") + " pose=" + gunPoseNote;
            }
            return " | no gun";
        }

        void HandleRemoteFx(string rest)
        {
            rfxGot++;
            if (!levelPrepared || !originSet) return;
            var a = rest.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (a.Length < 3) return;
            long who = long.Parse(a[0]) << 32;
            try
            {
                switch (a[1])
                {
                    case "L":
                    {
                        var prefab = Prefab(a[2].Replace('_', ' '));
                        var pts = a[4].Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries);
                        if (pts.Length < 2) break;
                        var list = new Vector3[pts.Length];
                        for (int i = 0; i < pts.Length; i++)
                        {
                            var c = pts[i].Split(',');
                            list[i] = McToUk(new Vector3(F(c[0]), F(c[1]), F(c[2])));
                        }
                        GameObject go = prefab != null ? Visual(prefab, list[0], Quaternion.LookRotation(list[1] - list[0] + Vector3.forward * 1e-4f), 1f) : null;
                        LineRenderer lr = go != null ? go.GetComponentInChildren<LineRenderer>(true) : null;
                        if (lr == null)
                        {
                            // no prefab of ours to show it with: a plain bright line
                            if (go != null) Destroy(go);
                            go = new GameObject("uc remote beam");
                            go.AddComponent<UcRemoteFx>().dieAt = Time.time + 1f;
                            lr = go.AddComponent<LineRenderer>();
                            lr.material = new Material(Shader.Find("Sprites/Default"));
                            lr.startColor = lr.endColor = new Color(1f, 0.9f, 0.6f);
                            lr.widthMultiplier = 0.25f;
                        }
                        lr.enabled = true;
                        lr.useWorldSpace = true;
                        lr.positionCount = list.Length;
                        lr.SetPositions(list);
                        var fx = go.GetComponent<UcRemoteFx>();
                        fx.line = lr;
                        fx.lineWidth = lr.widthMultiplier;
                        fx.born = Time.time;
                        break;
                    }
                    case "P":
                    {
                        // P id prefab x y z vx vy vz fx fy fz grav
                        var prefab = Prefab(a[3].Replace('_', ' '));
                        if (prefab == null) break;
                        var pos = McToUk(new Vector3(F(a[4]), F(a[5]), F(a[6])));
                        var vel = UkDir(new Vector3(F(a[7]), F(a[8]), F(a[9])));
                        var fwd = UkDir(new Vector3(F(a[10]), F(a[11]), F(a[12])));
                        var go = Visual(prefab, pos, fwd.sqrMagnitude > 1e-6f ? Quaternion.LookRotation(fwd) : Quaternion.identity, 4f);
                        if (go == null) break;
                        var fx = go.GetComponent<UcRemoteFx>();
                        fx.velocity = vel;
                        fx.gravity = a.Length > 13 && a[13] == "1";
                        remoteFx[who | uint.Parse(a[2])] = fx;
                        break;
                    }
                    case "U":
                    {
                        foreach (var e in a[2].Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries))
                        {
                            var c = e.Split(',');
                            if (c.Length < 7 || !remoteFx.TryGetValue(who | uint.Parse(c[0]), out var fx) || fx == null) continue;
                            var at = McToUk(new Vector3(F(c[1]), F(c[2]), F(c[3])));
                            var v = UkDir(new Vector3(F(c[4]), F(c[5]), F(c[6])));
                            // a tenth of a second behind: where it is now
                            fx.transform.position = at + v * 0.05f;
                            fx.velocity = v;
                            fx.gravity = false;
                            if (v.sqrMagnitude > 1f) fx.transform.rotation = Quaternion.LookRotation(v);
                            fx.dieAt = Time.time + 1f;
                        }
                        break;
                    }
                    case "D":
                    {
                        foreach (var id in a[2].Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries))
                        {
                            long key = who | uint.Parse(id);
                            if (remoteFx.TryGetValue(key, out var fx) && fx != null) Destroy(fx.gameObject);
                            remoteFx.Remove(key);
                        }
                        break;
                    }
                    case "A":
                    {
                        // A clip x y z volume pitch
                        PlayRemoteSound(a[2].Replace('_', ' '), McToUk(new Vector3(F(a[3]), F(a[4]), F(a[5]))), F(a[6]), F(a[7]));
                        break;
                    }
                    case "E":
                    {
                        var prefab = Prefab(a[2].Replace('_', ' '));
                        if (prefab == null) break;
                        var pos = McToUk(new Vector3(F(a[3]), F(a[4]), F(a[5])));
                        var go = Visual(prefab, pos, Quaternion.identity, 6f);
                        if (go != null && a.Length > 6) go.transform.localScale = Vector3.one * F(a[6]);
                        break;
                    }
                }
            }
            catch (Exception e) { if (fxWarnings++ < 20) Plugin.Log.LogWarning("RFX " + (rest.Length > 120 ? rest.Substring(0, 120) : rest) + ": " + e); }
            if (remoteFx.Count > 512)
            {
                var dead = new List<long>();
                foreach (var kv in remoteFx) if (kv.Value == null) dead.Add(kv.Key);
                foreach (var k in dead) remoteFx.Remove(k);
            }
        }

        // ------------------------------------------------------------ the other V1s' bodies

        /// <summary>RV1 entity grounded sliding pitch weapon: another player's V1, as their ULTRAKILL has it.</summary>
        void HandleRemoteV1(string rest)
        {
            rv1Got++;
            var a = rest.Split(' ');
            if (a.Length < 5 || !proxies.TryGetValue(int.Parse(a[0]), out var p) || p == null) return;
            p.remoteKnown = true;
            p.remoteGrounded = a[1] == "1";
            p.remoteSliding = a[2] == "1";
            p.pitch = F(a[3]);
            var weapon = a[4].Replace('_', ' ');
            if (weapon == p.weaponName) return;
            p.weaponName = weapon;
            if (p.gun != null) Destroy(p.gun);
            p.gun = null;
            if (weapon == "-" || p.v1Body == null) return;
            var prefab = Prefab(weapon);
            if (prefab == null)
            {
                // not loaded yet here: try again on the next state
                p.weaponName = null;
                return;
            }
            try { p.gun = HeldGun(prefab, out p.gunFromEye, out p.gunTurn, out p.gunCenter, out p.gunLength); }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("held gun " + weapon + ": " + e.Message);
                p.weaponName = null;
            }
            // goes with the body (and away with it)
            if (p.gun != null) p.gun.transform.SetParent(p.v1Body, true);
        }

        /// <summary>A gun for another V1: the first-person model, laid out exactly as ULTRAKILL holds it in front of its
        /// camera (built under our own camera's gun holder), scaled to a body; fromEye and turn say where it sits from
        /// the eye. (A first-person model's pivot is the camera's, not the gun's: placed by it, the gun hung at the hip.)</summary>
        GameObject HeldGun(GameObject prefab, out Vector3 fromEye, out Quaternion turn, out Vector3 center, out float length)
        {
            center = Vector3.zero;
            fromEye = new Vector3(0.3f * K, -0.3f * K, 0.5f * K);
            turn = Quaternion.identity;
            var gc = MonoSingleton<GunControl>.Instance;
            var cc = MonoSingleton<CameraController>.Instance;
            Transform cam = cc != null ? cc.transform : null;
            Transform parent = gc != null && gc.currentWeapon != null ? gc.currentWeapon.transform.parent : gc != null ? gc.transform : cam;
            var holder = new GameObject("uc held gun");
            holder.SetActive(false);
            if (parent != null) holder.transform.SetParent(parent, false);
            var go = Instantiate(prefab, holder.transform);
            for (int pass = 0; pass < 2; pass++)
                foreach (var mb in go.GetComponentsInChildren<MonoBehaviour>(true))
                    if (mb != null) try { DestroyImmediate(mb); } catch (Exception) { }
            var anims = go.GetComponentsInChildren<Animator>(true);
            foreach (var an in anims) an.enabled = false;
            foreach (var col in go.GetComponentsInChildren<Collider>(true)) col.enabled = false;
            foreach (var rb in go.GetComponentsInChildren<Rigidbody>(true)) { rb.isKinematic = true; rb.detectCollisions = false; }
            foreach (var au in go.GetComponentsInChildren<AudioSource>(true)) au.enabled = false;
            foreach (var lt in go.GetComponentsInChildren<Light>(true)) lt.enabled = false;
            foreach (var r in go.GetComponentsInChildren<Renderer>(true))
            {
                // the gun itself, not its muzzle flashes, sprites or particles
                if (!(r is MeshRenderer) && !(r is SkinnedMeshRenderer)) { r.enabled = false; continue; }
                r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            }
            foreach (var t in go.GetComponentsInChildren<Transform>(true)) t.gameObject.layer = 0;
            // ULTRAKILL keeps its gun prefabs switched off (equipping one switches it on)
            go.SetActive(true);
            holder.SetActive(true);
            // posed as first person shows it: its animator's idle (left alone, a gun's bones sit in their bind pose,
            // pointing anywhere), then held still
            foreach (var an in anims)
            {
                if (an == null || an.runtimeAnimatorController == null) continue;
                try
                {
                    an.cullingMode = AnimatorCullingMode.AlwaysAnimate;
                    an.fireEvents = false;
                    an.applyRootMotion = false;
                    an.enabled = true;
                    an.Rebind();
                    for (int i = 0; i < 6; i++) an.Update(0.25f);
                }
                catch (Exception e) { Plugin.Log.LogDebug("held gun pose: " + e.Message); }
                an.enabled = false;
            }
            // its size, and where it is from our eye, as first person has it
            var b = new Bounds();
            bool any = false;
            foreach (var r in go.GetComponentsInChildren<Renderer>())
            {
                if (!r.enabled) continue;
                if (!any) { b = r.bounds; any = true; }
                else b.Encapsulate(r.bounds);
            }
            float size = any ? Mathf.Max(b.size.x, Mathf.Max(b.size.y, b.size.z)) : 1f;
            float scale = size > 0.01f ? 0.95f * K / size : 1f;
            length = 0.95f * K;
            if (cam != null)
            {
                fromEye = cam.InverseTransformPoint(go.transform.position) * scale;
                turn = Quaternion.Inverse(cam.rotation) * go.transform.rotation;
            }
            go.transform.SetParent(null, true);
            go.transform.localScale *= scale;
            Destroy(holder);
            // as first person holds it (turned as the camera was): its barrel should run ahead, along z
            go.transform.rotation = turn;
            go.transform.position = Vector3.zero;
            var lb = HeldBounds(go, out bool lany);
            if (lany && cam != null && lb.size.z >= 0.8f * Mathf.Max(lb.size.x, lb.size.y))
            {
                center = go.transform.InverseTransformPoint(lb.center);
                length = lb.size.z;
                gunPoseNote = "first person";
                return go;
            }
            // otherwise: its longest side, away from where its middle lies from its pivot (a first-person gun's pivot
            // sits out by its muzzle end)
            gunPoseNote = "longest side";
            go.transform.rotation = Quaternion.identity;
            lb = HeldBounds(go, out lany);
            if (lany)
            {
                center = go.transform.InverseTransformPoint(lb.center);
                var off = lb.center - go.transform.position;
                int axis = lb.size.x >= lb.size.y && lb.size.x >= lb.size.z ? 0 : lb.size.y >= lb.size.z ? 1 : 2;
                var dir = axis == 0 ? Vector3.right : axis == 1 ? Vector3.up : Vector3.forward;
                if (off[axis] > 0f) dir = -dir;
                length = lb.size[axis];
                turn = Quaternion.FromToRotation(dir, Vector3.forward);
            }
            return go;
        }

        string gunPoseNote = "-";

        static Bounds HeldBounds(GameObject go, out bool any)
        {
            var b = new Bounds();
            any = false;
            foreach (var r in go.GetComponentsInChildren<Renderer>())
            {
                if (!r.enabled) continue;
                if (!any) { b = r.bounds; any = true; }
                else b.Encapsulate(r.bounds);
            }
            return b;
        }

        /// <summary>Each frame, after the bodies' animation: V1's right arm reaches out where its player aims, and the
        /// gun sits in that hand (its body just ahead of the grip), pointing the same way.</summary>
        void PlaceRemoteGuns()
        {
            foreach (var p in proxies.Values)
            {
                if (p == null || p.type != "v1" || p.v1Body == null || p.gun == null) continue;
                if (p.hand == null)
                {
                    p.hand = Bone(p.v1Body, "hand.R");
                    p.foreArm = Bone(p.v1Body, "forearm.R");
                    p.upperArm = Bone(p.v1Body, "upper_arm.R");
                }
                var aim = Quaternion.Euler(p.pitch, p.yaw + 180f, 0f);
                var dir = aim * Vector3.forward;
                if (p.upperArm != null && p.foreArm != null && p.hand != null)
                {
                    // the arm out along the aim, a little in towards the middle, the forearm straight on from it
                    var body = Quaternion.Euler(0f, p.yaw + 180f, 0f);
                    var reach = (dir + body * Vector3.left * 0.15f).normalized;
                    var up = p.foreArm.position - p.upperArm.position;
                    if (up.sqrMagnitude > 1e-6f) p.upperArm.rotation = Quaternion.FromToRotation(up, reach) * p.upperArm.rotation;
                    var fore = p.hand.position - p.foreArm.position;
                    if (fore.sqrMagnitude > 1e-6f) p.foreArm.rotation = Quaternion.FromToRotation(fore, reach) * p.foreArm.rotation;
                }
                var grip = p.hand != null ? p.hand.position : p.transform.position + Vector3.up * (1.3f * K) + aim * new Vector3(0.3f * K, 0f, 0.4f * K);
                p.gun.transform.rotation = aim * p.gunTurn;
                // the gun's middle a third of its length ahead of the hand
                var want = grip + dir * (p.gunLength * 0.3f);
                p.gun.transform.position += want - p.gun.transform.TransformPoint(p.gunCenter);
            }
        }

        static Transform Bone(Transform root, string name)
        {
            foreach (var t in root.GetComponentsInChildren<Transform>(true)) if (t.name == name) return t;
            return null;
        }

        static Transform FindHand(Transform body)
        {
            Transform best = null;
            foreach (var t in body.GetComponentsInChildren<Transform>(true))
            {
                var n = t.name.ToLowerInvariant();
                if (!n.Contains("hand")) continue;
                bool right = n.Contains("right") || n.EndsWith(".r") || n.EndsWith("_r") || n.Contains(" r") || n.EndsWith("r");
                if (right) return t;
                if (best == null) best = t;
            }
            if (best == null)
            {
                var sb = new StringBuilder("V1 body bones (no hand found):");
                foreach (var t in body.GetComponentsInChildren<Transform>(true)) sb.Append(' ').Append(t.name);
                Plugin.Log.LogInfo(sb.ToString());
            }
            return best;
        }
    }

    /// <summary>What flies, from where its own Start runs: each kind's Start, patched one by one (a kind missing in this
    /// ULTRAKILL is skipped, not fatal).</summary>
    static class MpFxPatches
    {
        public static void Apply()
        {
            var h = new Harmony("dev.ultracraft.ultrabridge.mpfx");
            var projectile = new HarmonyMethod(typeof(MpFxPatches), nameof(Projectile));
            foreach (var t in new[] { typeof(global::Projectile), typeof(Nail), typeof(Grenade), typeof(Cannonball), typeof(Coin), typeof(Harpoon), typeof(GasolineProjectile) })
                Patch(h, t, projectile);
            Patch(h, typeof(RevolverBeam), new HarmonyMethod(typeof(MpFxPatches), nameof(Beam)));
            Patch(h, typeof(Explosion), new HarmonyMethod(typeof(MpFxPatches), nameof(Blast)));
            // ULTRAKILL plays its sounds through these
            try
            {
                var ext = typeof(AudioSourceExtensions);
                h.Patch(AccessTools.Method(ext, "Play", new[] { typeof(AudioSource), typeof(bool) }), postfix: new HarmonyMethod(typeof(MpFxPatches), nameof(Played)));
                h.Patch(AccessTools.Method(ext, "PlayOneShot", new[] { typeof(AudioSource), typeof(AudioClip), typeof(bool) }), postfix: new HarmonyMethod(typeof(MpFxPatches), nameof(OneShot)));
                h.Patch(AccessTools.Method(ext, "PlayOneShot", new[] { typeof(AudioSource), typeof(AudioClip), typeof(float), typeof(bool) }),
                    postfix: new HarmonyMethod(typeof(MpFxPatches), nameof(OneShotVol)));
            }
            catch (Exception e) { Plugin.Log.LogWarning("mp fx sounds: " + e.Message); }
        }

        static void Played(AudioSource __0) => Bridge.I?.FxSound(__0, null, 1f);
        static void OneShot(AudioSource __0, AudioClip __1) => Bridge.I?.FxSound(__0, __1, 1f);
        static void OneShotVol(AudioSource __0, AudioClip __1, float __2) => Bridge.I?.FxSound(__0, __1, __2);

        static void Patch(Harmony h, Type t, HarmonyMethod post)
        {
            try
            {
                var m = AccessTools.Method(t, "Start") ?? AccessTools.Method(t, "Awake");
                if (m != null) h.Patch(m, postfix: post);
                else Plugin.Log.LogWarning("mp fx: " + t.Name + " has no Start");
            }
            catch (Exception e) { Plugin.Log.LogWarning("mp fx " + t.Name + ": " + e.Message); }
        }

        static void Projectile(Component __instance) => Bridge.I?.FxProjectile(__instance);
        static void Beam(Component __instance) => Bridge.I?.FxBeam(__instance);
        static void Blast(Explosion __instance) => Bridge.I?.FxExplosion(__instance);
    }
}
