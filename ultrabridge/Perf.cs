// UltraBridge: the Performance page's effect settings (OPTS fx= gore= stains=). Effects Quality uses ULTRAKILL's own
// switches for its effects (simple explosions, fire and spawns, hit sparks, environment particles, how much gore stays at
// once), set for this session only like every other ULTRAKILL setting Ultracraft changes; Low also drops shadows and
// ULTRAKILL's costlier render settings.
//
// Also: the Sandbox put to sleep (not just hidden) once V1 is in it, and the PERF readout (/uc debug uk PERF).

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>Ultracraft's extra blood on deaths (bursts and sprays on top of ULTRAKILL's own).</summary>
        public static bool ExtraGore = true;
        /// <summary>Minecraft keeps blood stains on its blocks (Performance → Blood Stains not Off).</summary>
        public static bool StainsOn = true;
        int effectsLevel = 2;
        ShadowQuality? shadowsWere;

        // what each level sets (High: nothing, ULTRAKILL as it is)
        static readonly Dictionary<string, object> MediumFx = new Dictionary<string, object>
        {
            { "simpleExplosions", true }, { "simpleFire", true }, { "disableEnvironmentParticles", true }, { "maxGore", 1500f },
        };
        static readonly Dictionary<string, object> LowFx = new Dictionary<string, object>
        {
            { "simpleExplosions", true }, { "simpleFire", true }, { "disableEnvironmentParticles", true }, { "maxGore", 500f },
            { "simpleSpawns", true }, { "disableHitParticles", true },
        };

        /// <summary>OPTS fx=0|1|2 (Low, Medium, High).</summary>
        void SetEffects(int level)
        {
            level = Mathf.Clamp(level, 0, 2);
            if (level == effectsLevel) return;
            effectsLevel = level;
            var want = level == 0 ? LowFx : level == 1 ? MediumFx : null;
            foreach (var key in LowFx.Keys)
            {
                if (want != null && want.TryGetValue(key, out var v)) SetPref(key, v);
                else ClearPref(key);
            }
            // shadows: the sun's, through every leaf and enemy; and the render settings that cost the most for the least
            if (level == 0)
            {
                if (shadowsWere == null) shadowsWere = QualitySettings.shadows;
                QualitySettings.shadows = ShadowQuality.Disable;
                LowRender(true);
            }
            else
            {
                if (shadowsWere != null)
                {
                    QualitySettings.shadows = shadowsWere.Value;
                    shadowsWere = null;
                }
                LowRender(false);
            }
            Plugin.Log.LogInfo("effects quality " + (level == 0 ? "Low" : level == 1 ? "Medium" : "High"));
        }

        /// <summary>OPTS stains=0|1: with Minecraft keeping none, ULTRAKILL doesn't paint any either.</summary>
        void SetStains(bool on)
        {
            if (on == StainsOn) return;
            StainsOn = on;
            if (on) ClearPref("bloodStainChance");
            else SetPref("bloodStainChance", 0f);
        }

        void SetPref(string key, object value)
        {
            PrefOverrides[key] = value;
            ApplyPref(key, value);
        }

        /// <summary>Back to ULTRAKILL's own stored value (the player's ULTRAKILL setting).</summary>
        void ClearPref(string key)
        {
            if (!PrefOverrides.Remove(key)) return;
            var pm = MonoSingleton<PrefsManager>.Instance;
            if (pm == null) return;
            object stored;
            ReadingStoredPrefs = true;
            try
            {
                stored = key == "maxGore" ? pm.GetFloatLocal(key, 3000f) : key == "bloodStainChance" ? pm.GetFloatLocal(key, 0.5f) : (object)pm.GetBoolLocal(key);
            }
            finally { ReadingStoredPrefs = false; }
            ApplyPref(key, stored);
        }

        // ------------------------------------------------------------ Low: ULTRAKILL's render settings

        class RenderWas
        {
            public int pixelLights, particleBudget;
            public bool probes, softParticles;
            public AnisotropicFiltering aniso;
            public SkinWeights skin;
            public float lodBias;
        }
        RenderWas renderWas;

        /// <summary>Per-pixel lights down to one (Minecraft does the world's light; ULTRAKILL's only lights V1, its
        /// enemies and effects), no realtime reflection probes, soft particles or anisotropic filtering, two-bone skinning
        /// and coarser LODs. Put back as they were when Low is left.</summary>
        void LowRender(bool on)
        {
            if (on && renderWas == null)
            {
                renderWas = new RenderWas
                {
                    pixelLights = QualitySettings.pixelLightCount, particleBudget = QualitySettings.particleRaycastBudget,
                    probes = QualitySettings.realtimeReflectionProbes, softParticles = QualitySettings.softParticles,
                    aniso = QualitySettings.anisotropicFiltering, skin = QualitySettings.skinWeights, lodBias = QualitySettings.lodBias,
                };
                QualitySettings.pixelLightCount = 1;
                QualitySettings.particleRaycastBudget = Mathf.Min(QualitySettings.particleRaycastBudget, 16);
                QualitySettings.realtimeReflectionProbes = false;
                QualitySettings.softParticles = false;
                QualitySettings.anisotropicFiltering = AnisotropicFiltering.Disable;
                QualitySettings.skinWeights = SkinWeights.TwoBones;
                QualitySettings.lodBias = Mathf.Min(QualitySettings.lodBias, 0.6f);
            }
            else if (!on && renderWas != null)
            {
                QualitySettings.pixelLightCount = renderWas.pixelLights;
                QualitySettings.particleRaycastBudget = renderWas.particleBudget;
                QualitySettings.realtimeReflectionProbes = renderWas.probes;
                QualitySettings.softParticles = renderWas.softParticles;
                QualitySettings.anisotropicFiltering = renderWas.aniso;
                QualitySettings.skinWeights = renderWas.skin;
                QualitySettings.lodBias = renderWas.lodBias;
                renderWas = null;
            }
        }

        // ------------------------------------------------------------ the Sandbox, asleep

        // Hiding the Sandbox (renderers and colliders off) still left all of it running: its scripts' Updates, animators,
        // particle systems simulating unseen, every object in the scene's culling. A root holding nothing but Unity's own
        // components (meshes, lights, probes, particles, audio) or plain level dressing is switched off whole; one with
        // ULTRAKILL's scripts in it stays on (something may look it up) but its animators and particles stop.
        readonly List<string> sandboxKept = new List<string>();
        int sandboxSlept;

        // ULTRAKILL scripts that are only level dressing: a root with nothing else of ULTRAKILL's may sleep too. From the
        // Sandbox's roots as they are (its buildings, grass, lake, garages, ambience: every one carries a few of these);
        // what's left awake is what matters (stats, checkpoint, shops, navmesh, cheats, the sun's lighting, V1's spawn).
        // The log's "Sandbox asleep" line lists what stayed awake and why, to grow this from.
        static readonly HashSet<string> DressingScripts = new HashSet<string>
        {
            "ExecuteOnTreeChange", "PreservedOriginalMesh", "PhysicsSceneStateEnforcer", "IgnoreSceneOptimizer", "AudioPitcher",
            "PlayOnAwakeTracker", "ObjectActivator", "Crossfade", "TriggerEnterMessage", "RigidbodyStopper", "CancellationTokenHelper",
            "TimeOfDayChanger", "Water", "AltPickUp", "AlwaysIncludeSceneOptimizer", "TreeRustle", "ScrollingTexture", "Rotate",
            "LightFlicker", "Flicker", "SineMove",
        };

        void SleepSandboxRoot(GameObject root)
        {
            var blockers = new HashSet<string>();
            // never off: the sun (Minecraft's sun drives it), clicks for the shop's screen, any canvas
            foreach (var l in root.GetComponentsInChildren<Light>(true)) if (l.type == LightType.Directional) blockers.Add("sun");
            if (root.GetComponentInChildren<UnityEngine.EventSystems.EventSystem>(true) != null) blockers.Add("EventSystem");
            if (root.GetComponentInChildren<Canvas>(true) != null) blockers.Add("Canvas");
            foreach (var mb in root.GetComponentsInChildren<MonoBehaviour>(true))
            {
                if (mb == null) continue;
                var t = mb.GetType();
                var asm = t.Assembly.GetName().Name;
                if (asm.StartsWith("UnityEngine") || asm.StartsWith("Unity.") || asm == "Assembly-CSharp-firstpass") continue;
                if (DressingScripts.Contains(t.Name)) continue;
                blockers.Add(t.Name);
            }
            if (blockers.Count == 0)
            {
                root.SetActive(false);
                sandboxSlept++;
                return;
            }
            foreach (var a in root.GetComponentsInChildren<Animator>(true)) a.enabled = false;
            foreach (var ps in root.GetComponentsInChildren<ParticleSystem>(true)) ps.Stop(false, ParticleSystemStopBehavior.StopEmittingAndClear);
            if (sandboxKept.Count < 40) sandboxKept.Add(root.name + "(" + string.Join(",", blockers.Take(4)) + ")");
        }

        // ------------------------------------------------------------ PERF

        /// <summary>One line of what ULTRAKILL is spending its frames on, for a laggy player to send in
        /// (/uc debug uk PERF; it lands in both games' logs).</summary>
        void PerfDump()
        {
            var sb = new StringBuilder("PERF");
            float sum = 0f, worst = 0f;
            int n = Mathf.Min(frameCount, frameTimes.Length);
            for (int i = 0; i < n; i++)
            {
                sum += frameTimes[i];
                worst = Mathf.Max(worst, frameTimes[i]);
            }
            if (n > 0 && sum > 0f) sb.Append(" fps=").Append((n / sum).ToString("0")).Append(" worst=").Append((worst * 1000f).ToString("0.0")).Append("ms");
            sb.Append(" res=").Append(Screen.width).Append('x').Append(Screen.height).Append(" cap=").Append(FpsCap).Append(" lowLatency=").Append(LowLatency);
            sb.Append(" fx=").Append(effectsLevel).Append(" shadows=").Append(QualitySettings.shadows).Append(" pixelLights=").Append(QualitySettings.pixelLightCount);
            sb.Append(" sections=").Append(sections.Count).Append(" enemies=").Append(ukEnemies.Count).Append(" mobs=").Append(proxies.Count).Append(" shops=").Append(shops.Count);
            sb.Append(" sandboxAsleep=").Append(sandboxSlept).Append(" awake=[").Append(string.Join(" ", sandboxKept)).Append(']');
            var cams = Camera.allCameras;
            sb.Append(" cams=").Append(cams.Length).Append('[');
            foreach (var c in cams) sb.Append(c.name).Append(c.targetTexture != null ? "->rt " : "->screen ");
            sb.Append(']');
            int lights = 0, rends = 0, ps = 0, anims = 0, audio = 0;
            foreach (var l in FindObjectsOfType<Light>()) if (l.enabled) lights++;
            foreach (var r in FindObjectsOfType<Renderer>()) if (r.enabled && r.isVisible) rends++;
            foreach (var p in FindObjectsOfType<ParticleSystem>()) if (p.isPlaying) ps++;
            foreach (var a in FindObjectsOfType<Animator>()) if (a.enabled) anims++;
            foreach (var a in FindObjectsOfType<AudioSource>()) if (a.isPlaying) audio++;
            sb.Append(" lights=").Append(lights).Append(" visibleRenderers=").Append(rends).Append(" particles=").Append(ps)
              .Append(" animators=").Append(anims).Append(" audio=").Append(audio);
            // the most common scripts running (enabled, on active objects)
            var byType = new Dictionary<string, int>();
            foreach (var mb in FindObjectsOfType<MonoBehaviour>())
            {
                if (!mb.enabled) continue;
                var name = mb.GetType().Name;
                byType.TryGetValue(name, out var c);
                byType[name] = c + 1;
            }
            sb.Append(" scripts=").Append(byType.Values.Sum()).Append(" top=");
            foreach (var kv in byType.OrderByDescending(k => k.Value).Take(12)) sb.Append(kv.Key).Append(':').Append(kv.Value).Append(',');
            sb.Append(" heap=").Append(GC.GetTotalMemory(false) >> 20).Append("MB");
            var line = sb.ToString();
            Plugin.Log.LogInfo(line);
            Net.Send(line);
        }
    }
}
