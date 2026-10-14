// UltraBridge: bosses go out the way ULTRAKILL ends their fights (Ultracraft... -> Enemies & Bosses -> Boss Deaths).
//
// - Minos Prime (P-1) screams and falls to his knees ("Forgive me my children, for I have failed to bring you
//   salvation from this cold, dark world"), then rises in shafts of light and bursts;
// - Sisyphus Prime (P-2) the same, laughing ("So concludes the life and times of King Sisyphus... And I don't regret a
//   SECOND of it!");
// - Gabriel (3-2) kneels, rages ("What..? How can this be? Bested by this... this thing..? You insignificant FUCK!
//   THIS IS NOT OVER!") and vanishes: "May your woes be many, and your days few";
// - Gabriel (6-2) kneels in wonder ("Twice!? Beaten by an object... Is this my blood? ...We will meet again, machine")
//   and vanishes.
// Each with ULTRAKILL's own outro animation, voice and subtitles (read out of its levels), its light shafts and
// bursts, and Minecraft only hears the boss is beaten once it's over (so nothing ends the fight in the middle).

using System;
using System.Collections;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;
using UnityEngine.AI;

namespace UltraBridge
{
    public partial class Bridge
    {
        class OutroDef
        {
            // what it says, one after another
            public Voice[] voices;
            // a Prime: its own death (kneel, light shafts, burst), its rise held until the speech is nearly done
            public bool prime;
            // Gabriel: ULTRAKILL's outro animator for him (the fake Gabriel its level shows), and the line said as he goes
            public string anim;
            public Voice leaving;
        }

        static readonly Voice MinosOutro = new Voice { clip = "Assets/Sounds/Voices/Minos Prime/mp_outro.wav", lines = new[] {
            (0f, "Aagh!"), (4.25f, "Forgive me my children"), (7.3f, "for I have failed to bring you salvation"), (11.3f, "from this cold, dark world") } };
        static readonly Voice SisyphusOutro = new Voice { clip = "Assets/Sounds/Voices/Sisyphus Prime/sp_outro.ogg", lines = new[] {
            (0f, "Ahh..."), (1.6f, "So concludes the life and times of King Sisyphus"), (6.3f, "A fitting end to an existence defined by futile struggle,"),
            (12.1f, "Doomed from the very start..."), (15.3f, "And I don't regret a SECOND of it!") } };
        static readonly Voice GabrielDefeat = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab_Insignificant2b.ogg", lines = new[] {
            (0.1f, "What..?"), (1.4f, "How can this be?"), (4.15f, "Bested by this..."), (6.4f, "this thing..?"), (9.2f, "You insignificant FUCK!"), (13f, "THIS IS NOT OVER!") } };
        static readonly Voice GabrielWoes = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab_Woes.ogg", lines = new[] {
            (0.3f, "May your woes be many"), (3.3f, "and your days few") } };
        static readonly Voice ApostateOutro = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab2nd_outro.ogg", lines = new[] {
            (0f, "Twice!?"), (1.1f, "Beaten by an object... Twice!"), (4.2f, "I've only known the taste of victory,"), (7f, "But this taste... Is-"),
            (9.3f, "Is this my blood?"), (12.8f, "I've never known such..."), (14.8f, "Such... relief..?"), (17.2f, "I- I need some time to think..."),
            (20.5f, "We will meet again, machine") } };

        static readonly Dictionary<string, OutroDef> Outros = new Dictionary<string, OutroDef>
        {
            { "minosprime", new OutroDef { prime = true, voices = new[] { MinosOutro } } },
            { "sisyphusprime", new OutroDef { prime = true, voices = new[] { SisyphusOutro } } },
            { "gabriel", new OutroDef { anim = "Assets/Models/Enemies/Gabriel/GabrielOutro.controller", voices = new[] { GabrielDefeat }, leaving = GabrielWoes } },
            { "gabriel2", new OutroDef { anim = "Assets/Models/Enemies/Gabriel/Gabriel2ndOutro.controller", voices = new[] { ApostateOutro } } },
        };

        /// <summary>Bosses that go out with their outro when beaten (their fight asked for it).</summary>
        static readonly HashSet<EnemyIdentifier> outroWanted = new HashSet<EnemyIdentifier>();
        /// <summary>Beaten bosses still going out: their fight isn't over for Minecraft yet.</summary>
        static readonly HashSet<EnemyIdentifier> outroPlaying = new HashSet<EnemyIdentifier>();
        /// <summary>Primes whose rise into the light waits for their last words.</summary>
        static readonly HashSet<EnemyIdentifier> ascendHeld = new HashSet<EnemyIdentifier>();

        internal static bool OutroWanted(EnemyIdentifier eid) => (object)eid != null && outroWanted.Contains(eid);
        static bool OutroPlaying(EnemyIdentifier eid) => (object)eid != null && outroPlaying.Contains(eid);

        /// <summary>From UpdateBosses, the moment a boss is beaten.</summary>
        void StartOutro(string key, BossPart part)
        {
            if (!Outros.TryGetValue(key, out var def) || !OutroWanted(part.eid)) return;
            outroPlaying.Add(part.eid);
            MusicForOutro();
            if (def.prime) ascendHeld.Add(part.eid);
            StartCoroutine(def.prime ? PrimeOutro(key, def, part) : GabrielOutroScene(key, def, part));
        }

        static float VoiceEnd(Voice v)
        {
            var clip = LoadIntroClip(v.clip);
            if (clip != null) return clip.length;
            return v.lines.Length > 0 ? v.lines[v.lines.Length - 1].at + 2f : 2f;
        }

        /// <summary>When a voice's last subtitle has been said (its last line plus a moment), not the clip's end (Sisyphus
        /// laughs on after).</summary>
        static float LastWords(Voice v)
        {
            float last = v.lines.Length > 0 ? v.lines[v.lines.Length - 1].at + 2.2f : 2f;
            return Mathf.Min(last, VoiceEnd(v));
        }

        /// <summary>Minos / Sisyphus Prime: their own death runs (the Outro animation: they fall to their knees and shake),
        /// they speak, and as the last words come they rise in ULTRAKILL's light shafts (seven seconds) and burst.</summary>
        IEnumerator PrimeOutro(string key, OutroDef def, BossPart part)
        {
            var eid = part.eid;
            var go = part.go;
            float start = Time.unscaledTime;
            Plugin.Log.LogInfo("boss outro: " + key);
            // the Prime's own death, if nothing called it (its kneel, its body still and out of the fight)
            yield return null;
            try
            {
                var mp = go != null ? go.GetComponentInChildren<MinosPrime>(true) : null;
                var sp = go != null ? go.GetComponentInChildren<SisyphusPrime>(true) : null;
                var anim = go != null ? go.GetComponentInChildren<Animator>() : null;
                bool kneeling = anim != null && anim.GetBool("Dead");
                if (!kneeling)
                {
                    if (mp != null) mp.Death();
                    else if (sp != null) sp.Death();
                }
            }
            catch (Exception e) { Plugin.Log.LogDebug("prime death: " + e.Message); }
            // beaten in the air: down to the ground to kneel
            if (go != null)
            {
                var from = go.transform.position;
                SetDown(go, eid);
                var to = go.transform.position;
                if ((from - to).sqrMagnitude > 0.01f)
                {
                    for (float t = 0f; t < 1f && go != null; t += Time.unscaledDeltaTime / 0.35f)
                    {
                        go.transform.position = Vector3.Lerp(from, to, t * t);
                        yield return null;
                    }
                    if (go != null) go.transform.position = to;
                    Shockwave(to);
                    PlayIntroSound("Assets/Sounds/Movement/LandingHeavy.wav", 1f);
                }
            }
            PlayIntroSound("Assets/Sounds/Impacts/Explosion Wave.ogg", 0.6f);
            MonoSingleton<CameraController>.Instance?.CameraShake(1f);
            float said = 0f;
            foreach (var v in def.voices) said += LastWords(v);
            StartCoroutine(SayAll(def.voices));
            // the rise lasts seven seconds: it starts so the burst comes with the last words
            float ascendAt = Mathf.Max(3.2f, said - 6.5f);
            while (Time.unscaledTime - start < ascendAt && go != null && go.activeInHierarchy) yield return null;
            ascendHeld.Remove(eid);
            try
            {
                var mp = go != null ? go.GetComponentInChildren<MinosPrime>(true) : null;
                var sp = go != null ? go.GetComponentInChildren<SisyphusPrime>(true) : null;
                if (mp != null) mp.Ascend();
                else if (sp != null) sp.Ascend();
            }
            catch (Exception e) { Plugin.Log.LogDebug("prime ascend: " + e.Message); }
            // the light swells as it goes
            var glow = go != null ? Lamp(EnemyBounds(eid).center, key == "minosprime" ? new Color(0.5f, 0.8f, 1f) : new Color(1f, 0.85f, 0.5f), 30f * K, 0f) : null;
            float rise = Time.unscaledTime;
            while (Time.unscaledTime - rise < 8.5f && go != null && go.activeInHierarchy)
            {
                if (glow != null)
                {
                    glow.transform.position = EnemyBounds(eid).center;
                    glow.intensity = Mathf.Lerp(0f, 8f, (Time.unscaledTime - rise) / 7f);
                }
                yield return null;
            }
            if (glow != null) Destroy(glow.gameObject);
            // its burst (ULTRAKILL's own, from OutroEnd), and the world shakes with it
            MonoSingleton<CameraController>.Instance?.CameraShake(2f);
            PlayIntroSound("Assets/Sounds/Impacts/Minos Prime Explosion.wav", 1f);
            if (go != null && go.activeInHierarchy)
            {
                try
                {
                    var mp = go.GetComponentInChildren<MinosPrime>(true);
                    var sp = go.GetComponentInChildren<SisyphusPrime>(true);
                    if (mp != null) mp.OutroEnd();
                    else if (sp != null) sp.OutroEnd();
                    else go.SetActive(false);
                }
                catch (Exception e) { Plugin.Log.LogDebug("prime outro end: " + e.Message); }
            }
            outroPlaying.Remove(eid);
            outroWanted.Remove(eid);
        }

        /// <summary>A beaten boss in the air brought down so its feet rest on the ground below (it kneels there, not in
        /// mid-air).</summary>
        static void SetDown(GameObject go, EnemyIdentifier eid)
        {
            if (go == null || eid == null) return;
            var b = EnemyBounds(eid);
            if (!Physics.Raycast(new Vector3(b.center.x, b.min.y + 0.5f * K, b.center.z), Vector3.down, out var hit, 64f * K,
                    LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)) return;
            float drop = b.min.y - hit.point.y;
            if (Mathf.Abs(drop) < 0.05f) return;
            go.transform.position += Vector3.down * drop;
            Physics.SyncTransforms();
            // (no streak from where it was)
            foreach (var t in go.GetComponentsInChildren<TrailRenderer>()) t.Clear();
        }

        IEnumerator SayAll(Voice[] voices)
        {
            foreach (var v in voices) yield return Say(v, 0f, 0f);
        }

        /// <summary>Gabriel: still where he was beaten, he plays the outro of his level (kneels, rages, spreads his wings)
        /// as he speaks, and goes in a flash of light.</summary>
        IEnumerator GabrielOutroScene(string key, OutroDef def, BossPart part)
        {
            var eid = part.eid;
            var go = part.go;
            Plugin.Log.LogInfo("boss outro: " + key);
            if (go == null)
            {
                outroPlaying.Remove(eid);
                yield break;
            }
            // out of the fight: no brain, no falling, on the ground he was beaten over
            foreach (var b in go.GetComponentsInChildren<MonoBehaviour>(true))
                if (b is EnemyScript || b is GabrielBase || b is GabrielVoice) b.enabled = false;
            foreach (var rb in go.GetComponentsInChildren<Rigidbody>(true))
            {
                rb.velocity = Vector3.zero;
                rb.isKinematic = true;
            }
            var agent = go.GetComponentInChildren<NavMeshAgent>(true);
            if (agent != null) agent.enabled = false;
            // standing on the ground under him (his feet on it, wherever his pivot is)
            SetDown(go, eid);
            var ground = go.transform.position;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm != null)
            {
                var look = nm.transform.position - ground;
                look.y = 0f;
                if (look.sqrMagnitude > 0.01f) go.transform.rotation = Quaternion.LookRotation(look);
            }
            // the outro animation, timed so he goes as his last words are said
            float said = 0f;
            foreach (var v in def.voices) said += LastWords(v);
            Animator an = null;
            foreach (var a in go.GetComponentsInChildren<Animator>(true))
                if (a.runtimeAnimatorController != null) { an = a; break; }
            var rac = LoadIntroAnim(def.anim);
            float animLen = 0f;
            if (an != null && rac != null)
            {
                foreach (var c in rac.animationClips) if (c.name.StartsWith("Outro")) animLen = c.length;
                if (an.GetComponent<OutroEvents>() == null) an.gameObject.AddComponent<OutroEvents>();
                an.runtimeAnimatorController = rac;
                an.applyRootMotion = false;
                an.speed = animLen > 0f ? Mathf.Clamp(animLen / Mathf.Max(said, 1f), 0.6f, 1.2f) : 1f;
                an.Play("Outro", 0, 0f);
            }
            float time = Mathf.Max(said, 4f);
            StartCoroutine(SayAll(def.voices));
            float start = Time.time;
            var events = an != null ? an.GetComponent<OutroEvents>() : null;
            bool raged = false, winged = false;
            Light glow = null;
            while (Time.time - start < time && go != null)
            {
                if (events != null && events.rage && !raged)
                {
                    // the rage: a shake and a red flash
                    raged = true;
                    MonoSingleton<CameraController>.Instance?.CameraShake(1.2f);
                    PlayIntroSound("Assets/Sounds/Impacts/Explosion Wave.ogg", 0.5f);
                    var flash = Lamp(EnemyBounds(eid).center, new Color(1f, 0.3f, 0.2f), 20f * K, 6f);
                    Destroy(flash.gameObject, 0.6f);
                }
                if (events != null && events.wings && !winged)
                {
                    // his wings open: the light gathers around him
                    winged = true;
                    PlayIntroSound("Assets/Sounds/Enemies/GabrielWeaponSpawn.wav", 0.9f);
                    glow = Lamp(EnemyBounds(eid).center, new Color(1f, 0.9f, 0.5f), 18f * K, 0f);
                }
                if (glow != null) glow.intensity = Mathf.Min(5f, glow.intensity + Time.deltaTime * 2f);
                if (events != null && events.gone) break;
                yield return null;
            }
            if (glow != null) Destroy(glow.gameObject);
            // gone in a flash (his teleport)
            if (go != null)
            {
                var at = EnemyBounds(eid).center;
                var gabe = go.GetComponentInChildren<GabrielBase>(true);
                try
                {
                    if (gabe != null && gabe.teleportSound != null) Instantiate(gabe.teleportSound, at, Quaternion.identity);
                    else PlayIntroSound("Assets/Sounds/Enemies/GabrielWeaponSpawn.wav", 1f);
                }
                catch (Exception e) { Plugin.Log.LogDebug("gabriel leaves: " + e.Message); }
                var flash = Lamp(at, new Color(1f, 0.95f, 0.7f), 40f * K, 10f);
                Destroy(flash.gameObject, 0.35f);
                MonoSingleton<CameraController>.Instance?.CameraShake(0.8f);
                Destroy(go);
            }
            if (def.leaving != null) StartCoroutine(Say(def.leaving, 0f, 0f));
            outroPlaying.Remove(eid);
            outroWanted.Remove(eid);
        }

        /// <summary>The outro animations' events (on ULTRAKILL's fake Gabriel these go to GabrielOutro), noted here.</summary>
        public class OutroEvents : MonoBehaviour
        {
            public bool rage, wings, gone;
            public void RageStart() => rage = true;
            public void EnableWings() => wings = true;
            public void Disappear() => gone = true;
            public void Vibrate() { }
            public void Ascend() { }
        }

        static void ForgetOutros()
        {
            outroWanted.RemoveWhere(e => e == null);
            introMusicHolds = 0;
            ForgetSetups();
            outroPlaying.Clear();
            ascendHeld.Clear();
        }

        // ------------------------------------------------------------ ULTRAKILL's own deaths, waiting for the speech

        [HarmonyPatch(typeof(MinosPrime), nameof(MinosPrime.Ascend))]
        static class MinosAscendWaits
        {
            static bool Prefix(MinosPrime __instance) => !ascendHeld.Contains(__instance.GetComponent<EnemyIdentifier>());
        }

        [HarmonyPatch(typeof(SisyphusPrime), nameof(SisyphusPrime.Ascend))]
        static class SisyphusAscendWaits
        {
            static bool Prefix(SisyphusPrime __instance) => !ascendHeld.Contains(__instance.GetComponent<EnemyIdentifier>());
        }

        /// <summary>A beaten Gabriel stays for his outro instead of ULTRAKILL's quick one (a fake that vanishes).</summary>
        [HarmonyPatch(typeof(GabrielBase), nameof(GabrielBase.Death))]
        static class GabrielStaysForOutro
        {
            static bool Prefix(GabrielBase __instance)
            {
                var eid = __instance.GetComponent<EnemyIdentifier>();
                if (!OutroWanted(eid)) return true;
                foreach (var f in new[] { "currentSwords", "currentEnrageEffect" })
                {
                    try
                    {
                        var o = Traverse.Create(__instance).Field(f).GetValue<GameObject>();
                        if (o != null) Destroy(o);
                    }
                    catch (Exception) { }
                }
                return false;
            }
        }
    }
}
