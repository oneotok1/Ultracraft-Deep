// UltraBridge: bosses arrive the way ULTRAKILL brings them in (Ultracraft... -> Enemies & Bosses -> Boss Intros: Off,
// Short, Full).
//
// For the length of its entrance a boss is held: its brain (EnemyScript / Enemy) is off, it can't be hurt, its traits
// wait. Meanwhile it plays ULTRAKILL's own entrance animation (the intro animator its level uses, put on the boss
// itself), says ULTRAKILL's own lines with ULTRAKILL's own subtitles at their times (read out of the levels), and
// comes in as its level brings it:
// - Swordsmachine (0-3) drops down the shaft and lands on its sword; Cerberus (0-5) are statues that wake;
// - the Hideous Mass (1-3) digs out of the ground; the Mindflayer (2-3) comes through a portal;
// - the Ferryman (5-2) drops onto the deck; the Sisyphean Insurrectionist (4-2) falls out of the sky and lands;
// - V2 does its own (1-4: drops in, lands, bows; 4-4 as well);
// - Gabriel (3-2) appears in a pillar of light: "BEHOLD! THE POWER OF AN ANGEL"; as Apostate (6-2) he rises and draws
//   his swords: "...HERE AND NOW!";
// - Minos Prime (P-1): a blue soul falls and shatters, and he rises from his knees: "...And thy punishment... is DEATH";
// - Sisyphus Prime (P-2) breaks out ("This prison... To hold ME?"), falls and lands in a spray of blood: "Come forth,
//   Child of Man... And DIE."
// Short: a few seconds, ending on the line its speech ends on. Full: all of it (the Primes' and Gabriel's whole speeches).
// Each with ULTRAKILL's title card (the typewriter one a level opens with).

using System;
using System.Collections;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;
using UnityEngine.AddressableAssets;
using UnityEngine.AI;

namespace UltraBridge
{
    public partial class Bridge
    {
        enum Entrance { Drop, Sky, Rise, Portal, Statue, Beam, Form, Still, Native }

        /// <summary>A voice line and its subtitles, as ULTRAKILL's level times them (seconds into the clip).</summary>
        class Voice
        {
            public string clip;
            public (float at, string text)[] lines;
        }

        class IntroDef
        {
            public Entrance style;
            public string level, sound;
            public float time;
            // ULTRAKILL's entrance animator for it, the state that plays (and in a full one, the one held before), and
            // how fast it plays in a short entrance
            public string anim, animState, animWait;
            public float animSpeedShort = 1f;
            // what it says: in a short entrance the last shortTail seconds of shortVoice; in a full one fullBefore
            // first (it isn't seen yet), then all of fullVoice
            public Voice shortVoice, fullVoice;
            public Voice[] fullBefore;
            // said first in a short entrance as well as a full one
            public Voice[] before;
            // said first when V1 meets it for the first time (never beaten it), unseen meanwhile
            public Voice[] firstBefore;
            // seen while fullBefore is said (Gabriel at his organ), or not there yet
            public bool seenBefore;
            public float shortTail;
        }

        // ULTRAKILL's lines, from its levels (P-1, P-2, 3-2, 6-2)
        static readonly Voice MinosIntro = new Voice { clip = "Assets/Sounds/Voices/Minos Prime/mp_intro2.wav", lines = new[] {
            (0f, "Ahh..."), (2.4f, "Free at last"), (7f, "O Gabriel"), (10f, "Now dawns thy reckoning"), (13.8f, "and thy gore shall glisten before the temples of man"),
            (22f, "Creature of steel..."), (25.5f, "My gratitude upon thee for my freedom"), (30.5f, "but the crimes thy kind have committed against humanity"),
            (37f, "are NOT forgotten"), (40.7f, "And thy punishment..."), (44f, "is DEATH") } };
        static readonly Voice SisyphusBreak = new Voice { clip = "Assets/Sounds/Voices/Sisyphus Prime/sp_thisprison.ogg", lines = new[] {
            (0f, "This prison..."), (1.5f, "To hold"), (2.8f, "ME?") } };
        static readonly Voice SisyphusIntro = new Voice { clip = "Assets/Sounds/Voices/Sisyphus Prime/sp_intro.ogg", lines = new[] {
            (0f, "A visitor?"), (1.7f, "Hmm... Indeed, I have slept long enough."), (6.6f, "The kingdom of heaven has long since forgotten my name"),
            (10.3f, "And I am EAGER to make them remember"), (15.8f, "However"), (17.2f, "The blood of Minos stains your hands, and I must admit..."),
            (22f, "I'm curious about your skills, Weapon."), (25.9f, "And so, before I tear down the cities and CRUSH the armies of heaven..."),
            (31.4f, "You shall do as an appetizer."), (35f, "Come forth, Child of Man..."), (37.4f, "And DIE.") } };
        static readonly Voice GabrielTurnBack = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab_Intro1d.ogg", lines = new[] {
            (1.2f, "Machine"), (2.4f, "Turn back now"), (5.1f, "The layers of this palace are not for your kind"), (10.7f, "Turn back or you will be crossing the will of God") } };
        static readonly Voice ApostateSpeech = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab2nd_intro1b.ogg", lines = new[] {
            (1.85f, "Limbo"), (3.3f, "Lust"), (4.7f, "All gone..."), (6.3f, "With Gluttony soon to follow."), (10.1f, "Your kind know nothing but hunger"),
            (12.7f, "Purged all life on the upper layers"), (15.3f, "And yet they remain unsatiated..."), (18.5f, "As do you."),
            (22.3f, "You've taken everything from me, machine"), (25.8f, "And now all that remains is"), (28.8f, "PERFECT"), (30.5f, "HATRED") } };
        static readonly Voice GabrielChoice = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab_Intro2b.ogg", lines = new[] {
            (0.1f, "Your choice is made"), (2.8f, "As the righteous hand of the father"), (5.85f, "I shall rend you apart"), (10.15f, "And you will become inanimate once more") } };
        static readonly Voice GabrielBehold = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab_Behold.ogg", lines = new[] { (0f, "BEHOLD! THE POWER OF AN ANGEL") } };
        static readonly Voice ApostateIntro = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab2nd_intro2.ogg", lines = new[] {
            (1.5f, "Machine"), (3.1f, "I will cut you down"), (6.1f, "Break you apart"), (7.4f, "Splay the gore of your profane form across the STARS!"),
            (11.3f, "I will grind you down until the very SPARKS CRY FOR MERCY!"), (15.1f, "My hands shall RELISH ENDING YOU..."), (18.2f, "HERE"), (18.9f, "AND"), (19.6f, "NOW!") } };

        // Gabriel's 6-2 taunts, Minos's battle cry
        static readonly Voice ApostateLostTo = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab2nd_TauntAngry1.ogg", lines = new[] { (0f, "IS THIS WHAT I LOST TO!?") } };
        static readonly Voice ApostateRusty = new Voice { clip = "Assets/Sounds/Voices/Gabriel/gab2nd_TauntAngry2.ogg", lines = new[] { (0f, "YOU'RE GETTING RUSTY, MACHINE!") } };
        static readonly Voice MinosPrepare = new Voice { clip = "Assets/Sounds/Voices/Minos Prime/mp_prepare.wav", lines = new[] { (0f, "Prepare thyself!") } };

        static readonly Dictionary<string, IntroDef> Intros = new Dictionary<string, IntroDef>
        {
            // other entrances (key:alt), picked per boss in Boss Music & Intros
            { "gabriel2:rematch", new IntroDef { style = Entrance.Still, level = "6-2: AESTHETICS OF HATE", time = 3f, sound = "Assets/Sounds/Enemies/GabrielSwordUnsheatheLong.wav",
                anim = "Assets/Models/Enemies/Gabriel/Gabriel2ndIntro.controller", animState = "Intro", animWait = "IntroLoop",
                before = new[] { ApostateLostTo, ApostateRusty }, shortVoice = ApostateIntro, shortTail = 8.2f } },
            { "gabriel:choice", new IntroDef { style = Entrance.Beam, level = "3-2: IN THE FLESH", time = 4f,
                anim = "Assets/Models/Enemies/Gabriel/GabrielFake.controller", animState = "Intro", shortVoice = GabrielChoice } },
            { "minosprime:prepare", new IntroDef { style = Entrance.Form, level = "P-1: SOUL SURVIVOR", time = 3f,
                anim = "Assets/Models/Enemies/Minos Prime/MinosPrimeIntro.controller", animState = "Intro", animSpeedShort = 1.6f, shortVoice = MinosPrepare } },
            { "sisyphusprime:prison", new IntroDef { style = Entrance.Sky, level = "P-2: WAIT OF THE WORLD", time = 3f,
                anim = "Assets/Models/Enemies/SisyphusPrime/SisyphusPrimeIntro.controller", animState = "Intro", animWait = "IntroFall",
                shortVoice = SisyphusBreak } },
            { "swordsmachine", new IntroDef { style = Entrance.Drop, level = "0-3: DOUBLE DOWN", time = 3.8f,
                anim = "Assets/Animations/SwordsMachineFake.controller", animState = "Intro" } },
            { "cerberus", new IntroDef { style = Entrance.Statue, level = "0-5: CERBERUS", time = 5f, sound = "Assets/Sounds/Enemies/statuecharge.wav",
                anim = "Assets/Models/Enemies/Cerberus/CerberusStatic.controller", animState = "Awaken", animWait = "IdleSitting", animSpeedShort = 1.5f } },
            { "hideousmass", new IntroDef { style = Entrance.Rise, level = "1-3: HALLS OF SACRED REMAINS", time = 3.2f, sound = "Assets/Sounds/Enemies/MassWindup.wav",
                anim = "Assets/Animations/MassFake.controller", animState = "Armature|Intro" } },
            { "mindflayer", new IntroDef { style = Entrance.Portal, level = "2-3: SHEER HEART ATTACK", time = 2.6f, sound = "Assets/Sounds/Enemies/Spawns/PortalMindflayer.wav" } },
            { "ferryman", new IntroDef { style = Entrance.Drop, level = "5-2: WAVES OF THE STARLESS SEA", time = 2.4f, sound = "Assets/Sounds/Enemies/Spawns/PortalFerryman.wav",
                anim = "Assets/Models/Enemies/Ferryman/FerrymanIntro2.controller", animState = "Armature|Falling2" } },
            { "insurrectionist", new IntroDef { style = Entrance.Sky, level = "4-2: GOD DAMN THE SUN", time = 3.6f, sound = "Assets/Sounds/Enemies/Spawns/PortalInsurrectionist.wav",
                anim = "Assets/Models/Enemies/Sisyphus/Sisyphus Intro.controller", animState = "BossIntro" } },
            { "v2", new IntroDef { style = Entrance.Native, level = "1-4: CLAIR DE LUNE", time = 7f } },
            { "v2green", new IntroDef { style = Entrance.Native, level = "4-4: CLAIR DE SOLEIL", time = 7f } },
            { "gabriel", new IntroDef { style = Entrance.Beam, level = "3-2: IN THE FLESH", time = 4f,
                anim = "Assets/Models/Enemies/Gabriel/GabrielFake.controller", animState = "Intro", shortVoice = GabrielBehold, fullBefore = new[] { GabrielTurnBack, GabrielChoice },
                firstBefore = new[] { GabrielChoice } } },
            { "gabriel2", new IntroDef { style = Entrance.Still, level = "6-2: AESTHETICS OF HATE", time = 3f, sound = "Assets/Sounds/Enemies/GabrielSwordUnsheatheLong.wav",
                anim = "Assets/Models/Enemies/Gabriel/Gabriel2ndIntro.controller", animState = "Intro", animWait = "IntroLoop",
                shortVoice = ApostateIntro, shortTail = 8.2f, fullBefore = new[] { ApostateSpeech }, seenBefore = true, fullVoice = ApostateIntro } },
            { "minosprime", new IntroDef { style = Entrance.Form, level = "P-1: SOUL SURVIVOR", time = 3f,
                anim = "Assets/Models/Enemies/Minos Prime/MinosPrimeIntro.controller", animState = "Intro",
                shortVoice = MinosIntro, shortTail = 10.6f, fullVoice = MinosIntro } },
            { "sisyphusprime", new IntroDef { style = Entrance.Sky, level = "P-2: WAIT OF THE WORLD", time = 3f,
                anim = "Assets/Models/Enemies/SisyphusPrime/SisyphusPrimeIntro.controller", animState = "Intro", animWait = "IntroFall",
                shortVoice = SisyphusIntro, shortTail = 5f, fullBefore = new[] { SisyphusBreak }, fullVoice = SisyphusIntro } },
        };

        /// <summary>Bosses being held for their entrance: no damage reaches them (EidDamage), their traits wait.</summary>
        static readonly HashSet<EnemyIdentifier> introHeld = new HashSet<EnemyIdentifier>();
        public static bool IntroHeld(EnemyIdentifier eid) => eid != null && introHeld.Count > 0 && introHeld.Contains(eid);

        class Held
        {
            public GameObject go;
            public EnemyIdentifier eid;
            public readonly List<Behaviour> brains = new List<Behaviour>();
            public readonly List<(Rigidbody rb, bool kinematic)> bodies = new List<(Rigidbody, bool)>();
            public NavMeshAgent agent;
            public bool agentWas;
            public Vector3 ground;
            public Animator animator;
            public RuntimeAnimatorController animWas;
            public bool rootMotionWas;
            public float animSpeedWas = 1f;
            // every part of it shown or not as it was (Gabriel 2nd has two rigs, one hidden: never both at once), and its
            // trails (the Swordsmachine's sword) off while it's moved into place, so they don't streak down from the sky
            public readonly List<(GameObject go, bool active)> parts = new List<(GameObject, bool)>();
            public readonly List<TrailRenderer> trails = new List<TrailRenderer>();
        }

        /// <summary>Its trails empty and off while it's being moved (on again with on=true).</summary>
        static void Trails(Held h, bool on)
        {
            foreach (var t in h.trails)
            {
                if (t == null) continue;
                t.Clear();
                t.emitting = on;
            }
        }

        /// <summary>Before it wakes (from SpawnConfigured, while it's still switched off): its brain stays off, its
        /// bodies still, so its Start runs only once the entrance is over. V2's own intro is used as it is.</summary>
        static Held HoldForIntro(GameObject go, string key)
        {
            if (!Intros.TryGetValue(key, out var def)) return null;
            var held = new Held { go = go, eid = go.GetComponentInChildren<EnemyIdentifier>(true) };
            foreach (var t in go.GetComponentsInChildren<Transform>(true)) held.parts.Add((t.gameObject, t.gameObject.activeSelf));
            foreach (var t in go.GetComponentsInChildren<TrailRenderer>(true)) if (t.emitting) held.trails.Add(t);
            Trails(held, false);
            if (def.style == Entrance.Native)
            {
                foreach (var v2 in go.GetComponentsInChildren<V2>(true))
                {
                    v2.intro = true;
                    v2.longIntro = true;
                }
                return held;
            }
            foreach (var b in go.GetComponentsInChildren<EnemyScript>(true))
                if (b.enabled) { b.enabled = false; held.brains.Add(b); }
            foreach (var b in go.GetComponentsInChildren<Enemy>(true))
                if (b.enabled) { b.enabled = false; held.brains.Add(b); }
            foreach (var rb in go.GetComponentsInChildren<Rigidbody>(true))
            {
                held.bodies.Add((rb, rb.isKinematic));
                rb.isKinematic = true;
            }
            held.agent = go.GetComponentInChildren<NavMeshAgent>(true);
            if (held.agent != null)
            {
                held.agentWas = held.agent.enabled;
                held.agent.enabled = false;
            }
            // the body's animator (the one with the body's avatar, not a gun's)
            foreach (var an in go.GetComponentsInChildren<Animator>(true))
            {
                if (an.runtimeAnimatorController == null || an.name.StartsWith("Enemy") || an.name.Contains("Nailgun")) continue;
                held.animator = an;
                break;
            }
            return held;
        }

        /// <summary>The entrance itself, then the boss let go.</summary>
        IEnumerator PlayIntro(string key, List<Held> parts, int difficultyTier, bool full = false, string alt = null, bool first = false)
        {
            // another of its entrances (Boss Music & Intros), if it has that one
            if (alt == null || !Intros.TryGetValue(key + ":" + alt, out var def))
                if (!Intros.TryGetValue(key, out def)) yield break;
            if (parts.Count == 0) yield break;
            foreach (var h in parts) if (h.eid != null) introHeld.Add(h.eid);
            // quiet through the entrance; its song starts the moment it ends
            HoldMusicForIntro();
            ShowTitle(def.level, BossDisplayName(key));
            if (def.style == Entrance.Native)
            {
                yield return NativeIntro(parts, def.time);
                MusicAfterIntro();
                yield break;
            }
            foreach (var h in parts)
            {
                if (h.go == null) continue;
                h.ground = h.go.transform.position;
                // its feet on the floor, wherever its model's middle is (Gabriel's sank half into it)
                var b = EnemyBounds(h.eid);
                if (b.size.y > 0.1f && Physics.Raycast(new Vector3(b.center.x, b.max.y, b.center.z), Vector3.down, out var floor, b.size.y + 2f * K,
                        LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore) && floor.point.y > b.min.y + 0.02f)
                {
                    h.ground += Vector3.up * (floor.point.y - b.min.y);
                    h.go.transform.position = h.ground;
                }
            }

            // its entrance animation, held at its first frame (or on its waiting loop) until it's time
            var rac = def.anim != null ? LoadIntroAnim(def.anim) : null;
            foreach (var h in parts) StartIntroAnim(h, rac, def, full);

            // a first meeting, in a short entrance: what it says to V1 before it shows itself (Gabriel: "Your choice is made")
            if (first && !full && def.firstBefore != null)
            {
                var unseen = HideAll(parts);
                foreach (var v in def.firstBefore) yield return Say(v, 0f, 0f);
                foreach (var r in unseen) if (r != null) r.forceRenderingOff = false;
            }
            // what it says first in any entrance (Gabriel's rematch taunts), seen meanwhile
            if (def.before != null) foreach (var v in def.before) yield return Say(v, 0f, 0f);
            // a full entrance: what's said before it's there (unseen meanwhile)
            if (full && def.fullBefore != null)
            {
                var unseen = def.seenBefore ? new List<Renderer>() : HideAll(parts);
                foreach (var v in def.fullBefore) yield return Say(v, 0f, 0f);
                foreach (var r in unseen) if (r != null) r.forceRenderingOff = false;
            }
            if (!string.IsNullOrEmpty(def.sound) && def.style != Entrance.Still) PlayIntroSound(def.sound, 0.9f);

            // what it says now, and so how long the entrance lasts
            var voice = full && def.fullVoice != null ? def.fullVoice : def.shortVoice;
            float tail = full && def.fullVoice != null ? 0f : def.shortTail;
            float said = 0f;
            // the song starts on its last word ("...HERE AND NOW!"), not when the clip's echo or the animation ends; one of the
            // player's own songs a little sooner, as "AND" leads into "NOW!" (see the loop)
            float musicAt = -1f;
            if (voice != null)
            {
                var clip = LoadIntroClip(voice.clip);
                if (clip != null)
                {
                    said = tail > 0f ? Mathf.Min(tail, clip.length) : clip.length;
                    float from = tail > 0f ? Mathf.Max(0f, clip.length - tail) : 0f;
                    // (a single line, "BEHOLD! THE POWER OF AN ANGEL": once it's said)
                    if (voice.lines.Length == 1) musicAt = said - 0.7f;
                    else if (voice.lines.Length > 0) musicAt = voice.lines[voice.lines.Length - 1].at - from;
                }
                StartCoroutine(Say(voice, tail, 0f));
            }
            float animLen = 0f;
            if (rac != null) foreach (var c in rac.animationClips) if (c.name == def.animState) animLen = c.length / (full ? 1f : def.animSpeedShort);
            float time = Mathf.Max(def.time, said + 0.3f, animLen);
            bool musicStarted = false;
            Plugin.Log.LogInfo("boss intro: " + key + " (" + def.style + (full ? ", full" : ", short") + ", " + time.ToString("0.0") + " s)");

            // its entrance animation ends as the entrance does
            float animAt = Mathf.Max(0f, time - animLen);
            var fx = new IntroFx(this, def, parts);
            float start = Time.time;
            bool animStarted = false;
            while (Time.time - start < time)
            {
                float e = Time.time - start;
                fx.Step(e, time);
                if (!animStarted && e >= animAt && (def.style != Entrance.Drop && def.style != Entrance.Sky || fx.landed))
                {
                    animStarted = true;
                    foreach (var h in parts) PlayIntroAnim(h, def, full);
                    if (def.style == Entrance.Still && !string.IsNullOrEmpty(def.sound)) PlayIntroSound(def.sound, 1f);
                    if (def.style == Entrance.Statue) PlayIntroSound("Assets/Sounds/Enemies/statuecharge2.wav", 1f);
                }
                // ULTRAKILL's songs just after the last word, the player's own (Your Music) 1.25 s sooner
                bool ownSong = (themeSong ?? songInPlayer ?? "").StartsWith("file:");
                if (!musicStarted && musicAt >= 0f && e >= Mathf.Clamp(musicAt + (ownSong ? -0.55f : 0.7f), 0f, said))
                {
                    musicStarted = true;
                    Plugin.Log.LogInfo("boss intro: " + key + " music at " + e.ToString("0.00") + " s (last word at " + musicAt.ToString("0.00") + " s, " + (ownSong ? "own song" : "ULTRAKILL's song") + ")");
                    MusicAfterIntro();
                }
                yield return null;
            }
            fx.End();
            foreach (var h in parts) Release(h);
            if (!musicStarted) MusicAfterIntro();
        }

        /// <summary>V2 drops in, lands and bows on its own; it lets itself go (IntroEnd) when the bow is done.</summary>
        IEnumerator NativeIntro(List<Held> parts, float time)
        {
            float until = Time.time + time;
            while (Time.time < until)
            {
                bool anyIn = false;
                foreach (var h in parts)
                {
                    var v2 = h.go != null ? h.go.GetComponent<V2>() : null;
                    if (v2 != null && v2.inIntro) anyIn = true;
                }
                if (!anyIn) break;
                yield return null;
            }
            // never landed or never finished bowing (no animation event here): it starts all the same
            foreach (var h in parts)
            {
                var v2 = h.go != null ? h.go.GetComponent<V2>() : null;
                if (v2 == null || !v2.inIntro) continue;
                try
                {
                    if (MonoSingleton<StatueIntroChecker>.Instance == null) new GameObject("uc statue intro checker").AddComponent<StatueIntroChecker>();
                    v2.IntroEnd();
                }
                catch (Exception e) { Plugin.Log.LogWarning("V2 intro end: " + e.Message); }
            }
            foreach (var h in parts)
            {
                if (h.eid != null) introHeld.Remove(h.eid);
                Trails(h, true);
            }
        }

        static List<Renderer> HideAll(List<Held> parts)
        {
            var unseen = new List<Renderer>();
            foreach (var h in parts)
                if (h.go != null)
                    foreach (var r in h.go.GetComponentsInChildren<Renderer>(true))
                        if (!r.forceRenderingOff) { r.forceRenderingOff = true; unseen.Add(r); }
            return unseen;
        }

        // ------------------------------------------------------------ the entrance animation

        static readonly Dictionary<string, RuntimeAnimatorController> introAnims = new Dictionary<string, RuntimeAnimatorController>();

        static RuntimeAnimatorController LoadIntroAnim(string address)
        {
            if (introAnims.TryGetValue(address, out var rac)) return rac;
            try { rac = Addressables.LoadAssetAsync<RuntimeAnimatorController>(address).WaitForCompletion(); }
            catch (Exception e)
            {
                Plugin.Log.LogDebug("intro animation " + address + ": " + e.Message);
                rac = null;
            }
            introAnims[address] = rac;
            return rac;
        }

        /// <summary>Its entrance animator on, waiting: on its waiting loop if it has one, else still on its first frame.</summary>
        static void StartIntroAnim(Held h, RuntimeAnimatorController rac, IntroDef def, bool full)
        {
            if (h.animator == null || rac == null) return;
            try
            {
                h.animWas = h.animator.runtimeAnimatorController;
                h.rootMotionWas = h.animator.applyRootMotion;
                h.animSpeedWas = h.animator.speed;
                h.animator.runtimeAnimatorController = rac;
                h.animator.applyRootMotion = false;
                if (def.animWait != null)
                {
                    h.animator.speed = 1f;
                    h.animator.Play(def.animWait, 0, 0f);
                }
                else
                {
                    h.animator.Play(def.animState, 0, 0f);
                    h.animator.speed = 0f;
                }
            }
            catch (Exception e) { Plugin.Log.LogDebug("intro animation: " + e.Message); }
        }

        static void PlayIntroAnim(Held h, IntroDef def, bool full)
        {
            if (h.animator == null || h.animWas == null) return;
            try
            {
                h.animator.speed = full ? 1f : def.animSpeedShort;
                h.animator.Play(def.animState, 0, 0f);
            }
            catch (Exception e) { Plugin.Log.LogDebug("intro animation: " + e.Message); }
        }

        // ------------------------------------------------------------ what comes with it

        /// <summary>The look of an entrance: the pillar of light, the soul, the fall and the landing.</summary>
        class IntroFx
        {
            readonly Bridge bridge;
            readonly IntroDef def;
            readonly List<Held> parts;
            public bool landed;
            GameObject pillar, orb;
            const float PillarWidth = 7f * K;
            const string Whoosh = "Assets/Sounds/Enemies/Whoosh,Organic,Styrofoam,Plank,Airy,Smooth,Texture,1.wav";
            Light light;
            readonly float fall;

            public IntroFx(Bridge bridge, IntroDef def, List<Held> parts)
            {
                this.bridge = bridge;
                this.def = def;
                this.parts = parts;
                fall = def.style == Entrance.Sky ? 1.4f : 0.9f;
                if (def.style == Entrance.Drop || def.style == Entrance.Sky) PlayIntroSound(Whoosh, 0.9f);
                if (def.style == Entrance.Rise) PlayIntroSound("Assets/Sounds/Environment/boulder_impact_on_stones_14.wav", 1f);
                var c = Center();
                if (def.style == Entrance.Beam)
                {
                    // 3-2: Gabriel appears in a great pillar of yellow light, his shape dark inside it
                    pillar = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
                    pillar.name = "uc gabriel pillar";
                    Destroy(pillar.GetComponent<Collider>());
                    // (a cylinder is two units tall: 60 high stands from his feet up to 120 blocks, none of it under the ground)
                    pillar.transform.position = c + Vector3.up * 60f * K;
                    pillar.transform.localScale = new Vector3(PillarWidth, 60f * K, PillarWidth);
                    var mr = pillar.GetComponent<MeshRenderer>();
                    mr.sharedMaterial = Glow(new Color(1f, 0.92f, 0.15f, 0.82f));
                    mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                    light = Lamp(c + Vector3.up * 2f * K, new Color(1f, 0.85f, 0.2f), 40f * K, 6f);
                    PlayIntroSound("Assets/Sounds/Impacts/Explosion Wave.ogg", 0.7f);
                    PlayIntroSound("Assets/Sounds/Enemies/GabrielWeaponSpawn.wav", 1f);
                }
                else if (def.style == Entrance.Form)
                {
                    // P-1: a blue soul falls to where he'll stand and shatters; (6-2: Gabriel's lights)
                    bool minos = def.level.StartsWith("P-1");
                    var col = minos ? new Color(0.35f, 0.65f, 1f, 0.85f) : new Color(1f, 0.85f, 0.4f, 0.7f);
                    orb = GameObject.CreatePrimitive(PrimitiveType.Sphere);
                    orb.name = "uc soul";
                    Destroy(orb.GetComponent<Collider>());
                    orb.transform.localScale = Vector3.one * 1.6f * K;
                    orb.transform.position = c + Vector3.up * 14f * K;
                    orb.GetComponent<MeshRenderer>().sharedMaterial = Glow(col);
                    light = Lamp(orb.transform.position, col, 24f * K, 5f);
                    light.transform.SetParent(orb.transform, true);
                    // unseen until the soul has shattered
                    hidden = HideAll(parts);
                    PlayIntroSound(Whoosh, 0.8f);
                }
            }

            List<Renderer> hidden;

            Vector3 Center()
            {
                foreach (var h in parts) if (h.go != null) return h.ground;
                return Vector3.zero;
            }

            public void Step(float e, float time)
            {
                foreach (var h in parts)
                {
                    if (h.go == null) continue;
                    switch (def.style)
                    {
                        case Entrance.Drop:
                        case Entrance.Sky:
                        {
                            float height = (def.style == Entrance.Sky ? 26f : 12f) * K;
                            float f = Mathf.Clamp01(e / fall);
                            h.go.transform.position = h.ground + Vector3.up * height * (1f - f * f);
                            break;
                        }
                        case Entrance.Rise:
                        {
                            var b = EnemyBounds(h.eid);
                            float f = Mathf.SmoothStep(0f, 1f, Mathf.Clamp01(e / 2.6f));
                            var shake = UnityEngine.Random.insideUnitSphere * 0.15f * (1f - f);
                            h.go.transform.position = h.ground + Vector3.down * Mathf.Max(3f, b.size.y) * (1f - f) + shake;
                            if (Time.frameCount % 4 == 0 && f < 1f) MonoSingleton<CameraController>.Instance?.CameraShake(0.25f);
                            break;
                        }
                        case Entrance.Portal:
                        {
                            float f = Mathf.SmoothStep(0f, 1f, Mathf.Clamp01(e / 1.4f));
                            h.go.transform.position = h.ground + Vector3.down * K * (1f - f);
                            break;
                        }
                        case Entrance.Statue:
                            if (Time.frameCount % 6 == 0)
                            {
                                var b = EnemyBounds(h.eid);
                                Zap(b.center + Vector3.up * b.extents.y * 0.5f, b.center + UnityEngine.Random.onUnitSphere * b.extents.magnitude, new Color(1f, 0.85f, 0.3f, 0.8f));
                            }
                            break;
                    }
                }
                if (!landed && (def.style == Entrance.Drop || def.style == Entrance.Sky) && e >= fall)
                {
                    landed = true;
                    foreach (var h in parts)
                    {
                        if (h.go == null) continue;
                        h.go.transform.position = h.ground;
                        Shockwave(h.ground);
                        // P-2: he lands in a spray of the prison's blood
                        if (def.level.StartsWith("P-2")) for (int i = 0; i < 3; i++) BloodBurst(h.ground + Vector3.up * (1f + i) * K, 3f);
                    }
                    PlayIntroSound("Assets/Sounds/Movement/LandingHeavy.wav", 1f);
                    if (def.style == Entrance.Sky) PlayIntroSound("Assets/Sounds/Impacts/Explosion Wave.ogg", 0.8f);
                    MonoSingleton<CameraController>.Instance?.CameraShake(def.style == Entrance.Sky ? 1.5f : 0.8f);
                }
                if (pillar != null)
                {
                    // the pillar narrows away over its last second
                    float left = time - e, w = Mathf.Clamp01(left / 1f) * PillarWidth;
                    // he hangs in the light, and comes down as it goes
                    foreach (var h in parts) if (h.go != null) h.go.transform.position = h.ground + Vector3.up * 3f * K * Mathf.Clamp01(left / 1f);
                    pillar.transform.localScale = new Vector3(w, 60f * K, w);
                    if (light != null) light.intensity = 6f * Mathf.Clamp01(left);
                }
                if (orb != null)
                {
                    // the soul comes down over a second and a half, then shatters
                    float f = Mathf.Clamp01(e / 1.5f);
                    orb.transform.position = Center() + Vector3.up * Mathf.Lerp(14f * K, 1.5f * K, f * f);
                    if (f >= 1f)
                    {
                        var at = orb.transform.position;
                        Destroy(orb);
                        orb = null;
                        light = null;
                        PlayIntroSound("Assets/Sounds/Impacts/Minos Prime Explosion.wav", 0.8f);
                        var blast = SpawnBlast(at, 1.2f, true, false, null, 0);
                        if (blast != null) foreach (var ex in blast.GetComponentsInChildren<Explosion>(true)) ex.harmless = true;
                        MonoSingleton<CameraController>.Instance?.CameraShake(0.7f);
                        if (hidden != null) foreach (var r in hidden) if (r != null) r.forceRenderingOff = false;
                        hidden = null;
                    }
                }
            }

            public void End()
            {
                if (pillar != null) Destroy(pillar);
                if (orb != null) Destroy(orb);
                if (light != null) Destroy(light.gameObject);
                if (hidden != null) foreach (var r in hidden) if (r != null) r.forceRenderingOff = false;
                if (def.style == Entrance.Statue || def.style == Entrance.Beam) MonoSingleton<CameraController>.Instance?.CameraShake(0.6f);
            }
        }

        static Material glowMat;

        /// <summary>A bright see-through colour (the pillar, the soul).</summary>
        static Material Glow(Color c)
        {
            var sh = Shader.Find("Sprites/Default");
            var m = new Material(sh) { color = c, renderQueue = 3000 };
            return m;
        }

        static Light Lamp(Vector3 at, Color c, float range, float intensity)
        {
            var l = new GameObject("uc intro light").AddComponent<Light>();
            l.transform.position = at;
            l.type = LightType.Point;
            l.color = c;
            l.range = range;
            l.intensity = intensity;
            return l;
        }

        static GameObject shockwavePrefab;
        static bool shockwaveLooked;

        /// <summary>ULTRAKILL's own landing shockwave, harmless.</summary>
        static void Shockwave(Vector3 at)
        {
            if (!shockwaveLooked)
            {
                shockwaveLooked = true;
                try { shockwavePrefab = AssetHelper.LoadPrefab("Assets/Prefabs/Attacks and Projectiles/PhysicalShockwaveHarmless.prefab"); }
                catch (Exception e) { Plugin.Log.LogDebug("shockwave: " + e.Message); }
            }
            if (shockwavePrefab != null) Instantiate(shockwavePrefab, at, Quaternion.identity);
        }

        /// <summary>Let go: where it should stand, its own animator back, brain and bodies as they were, hurtable.</summary>
        static void Release(Held h)
        {
            if (h.eid != null) introHeld.Remove(h.eid);
            if (h.go == null) return;
            try
            {
                h.go.transform.position = h.ground;
                // as it was before its entrance: the same parts shown (one Gabriel, not two)
                foreach (var (part, active) in h.parts)
                    if (part != null && part.activeSelf != active) part.SetActive(active);
                if (h.animator != null && h.animWas != null)
                {
                    h.animator.runtimeAnimatorController = h.animWas;
                    h.animator.applyRootMotion = h.rootMotionWas;
                    h.animator.speed = h.animSpeedWas;
                    h.animator.Rebind();
                }
                foreach (var (rb, kinematic) in h.bodies) if (rb != null) rb.isKinematic = kinematic;
                if (h.agent != null && h.agentWas)
                {
                    h.agent.enabled = true;
                    if (h.agent.isOnNavMesh) h.agent.Warp(h.ground);
                }
                foreach (var b in h.brains) if (b != null) b.enabled = true;
                Physics.SyncTransforms();
                Trails(h, true);
            }
            catch (Exception e) { Plugin.Log.LogWarning("boss intro release: " + e.Message); }
        }

        /// <summary>A boss's name as its bar shows it.</summary>
        static string BossDisplayName(string key)
        {
            switch (key)
            {
                case "swordsmachine": return "SWORDSMACHINE";
                case "cerberus": return "CERBERUS";
                case "hideousmass": return "HIDEOUS MASS";
                case "mindflayer": return "MINDFLAYER";
                case "ferryman": return "FERRYMAN";
                case "insurrectionist": return "SISYPHEAN INSURRECTIONIST";
                case "v2": case "v2green": return "V2";
                case "gabriel": return "GABRIEL, JUDGE OF HELL";
                case "gabriel2": return "GABRIEL, APOSTATE OF HATE";
                case "minosprime": return "MINOS PRIME";
                case "sisyphusprime": return "SISYPHUS PRIME";
            }
            return key.ToUpperInvariant();
        }

        /// <summary>ULTRAKILL's level title card (layer line small, name big, typed out), or its HUD message where the
        /// level has no title card.</summary>
        static void ShowTitle(string small, string big)
        {
            try
            {
                var popup = MonoSingleton<LevelNamePopup>.Instance;
                if (popup != null)
                {
                    popup.CustomNameAppear(small, big);
                    return;
                }
            }
            catch (Exception e) { Plugin.Log.LogDebug("title card: " + e.Message); }
            MonoSingleton<HudMessageReceiver>.Instance?.SendHudMessage("<size=48><color=#FF4343>" + big + "</color></size>\n" + small, "", "", 0, true);
        }

        /// <summary>A voice line from tail seconds before its end (0: all of it), its subtitles at ULTRAKILL's times.</summary>
        IEnumerator Say(Voice v, float tail, float _)
        {
            var clip = LoadIntroClip(v.clip);
            float from = clip != null && tail > 0f ? Mathf.Max(0f, clip.length - tail) : 0f;
            PlayIntroSound(v.clip, 1f, from);
            float start = Time.time - from;
            // the line that was being said where it starts, then each as its time comes
            int next = 0;
            for (int i = 0; i < v.lines.Length; i++) if (v.lines[i].at <= from + 0.05f) next = i;
            if (from > 0f && next < v.lines.Length && v.lines[next].at < from - 1.5f) next++;
            float end = clip != null ? clip.length : (v.lines.Length > 0 ? v.lines[v.lines.Length - 1].at + 2f : 2f);
            while (Time.time - start < end)
            {
                float t = Time.time - start;
                while (next < v.lines.Length && v.lines[next].at <= t)
                {
                    Subtitle(v.lines[next].text);
                    next++;
                }
                yield return null;
            }
        }

        static void Subtitle(string text)
        {
            // (ULTRAKILL's own subtitles, shown even with its subtitle setting off: a boss's entrance is its words)
            try { MonoSingleton<SubtitleController>.Instance?.DisplaySubtitle(text, null, true); }
            catch (Exception e) { Plugin.Log.LogDebug("subtitle: " + e.Message); }
        }

        static readonly Dictionary<string, AudioClip> introClips = new Dictionary<string, AudioClip>();

        static AudioClip LoadIntroClip(string address)
        {
            if (introClips.TryGetValue(address, out var clip)) return clip;
            try { clip = Addressables.LoadAssetAsync<AudioClip>(address).WaitForCompletion(); }
            catch (Exception e)
            {
                Plugin.Log.LogDebug("sound " + address + ": " + e.Message);
                clip = null;
            }
            introClips[address] = clip;
            return clip;
        }

        /// <summary>One of ULTRAKILL's own sounds (from seconds in), heard as V1 hears its HUD (through ULTRAKILL's
        /// sound volume).</summary>
        static AudioClip PlayIntroSound(string address, float volume, float from = 0f)
        {
            var clip = LoadIntroClip(address);
            if (clip == null) return null;
            var go = new GameObject("uc boss intro sound");
            var src = go.AddComponent<AudioSource>();
            src.clip = clip;
            src.volume = volume;
            src.spatialBlend = 0f;
            src.ignoreListenerPause = false;
            var mixer = MonoSingleton<AudioMixerController>.Instance;
            if (mixer != null && mixer.allGroup != null) src.outputAudioMixerGroup = mixer.allGroup;
            src.time = Mathf.Clamp(from, 0f, Mathf.Max(0f, clip.length - 0.05f));
            src.Play();
            Destroy(go, clip.length - src.time + 0.5f);
            return clip;
        }
    }
}

namespace UltraBridge
{
    public partial class Bridge
    {
        // ------------------------------------------------------------ -ucbosstest: every boss's entrance, unattended

        internal static readonly string BossTestArg = TestArg("-ucbosstest");
        bool bossTestStarted;

        static string TestArg(string name)
        {
            var args = Environment.GetCommandLineArgs();
            int i = Array.IndexOf(args, name);
            return i < 0 ? null : i + 1 < args.Length && !args[i + 1].StartsWith("-") ? args[i + 1] : "all";
        }

        /// <summary>Debug, ULTRAKILL alone: a floor under V1, and each boss (or the one named) brought in with its
        /// entrance, then checked: held while it lasts, let go after, alive, on the ground, its brain on.</summary>
        void MaybeBossTest()
        {
            if (BossTestArg == null || bossTestStarted || !levelPrepared || Time.timeSinceLevelLoad < 4f) return;
            bossTestStarted = true;
            StartCoroutine(BossTest());
        }

        IEnumerator Photos(string dir, string key)
        {
            float last = 0f;
            foreach (var t in new[] { 0.6f, 1.6f, 3f, 5f, 8f, 20f, 38f, 50f })
            {
                yield return new WaitForSeconds(t - last);
                last = t;
                ScreenCapture.CaptureScreenshot(System.IO.Path.Combine(dir, key + "_" + t.ToString("0.0") + ".png"));
            }
        }

        /// <summary>-ucsetup hp=2,phase2,stagger: a Boss Setup for each boss of the test.</summary>
        static Dictionary<string, string> TestSetup()
        {
            var d = new Dictionary<string, string>();
            var arg = TestArg("-ucsetup");
            if (arg == null || arg == "all") return d;
            foreach (var fl in arg.Split(','))
            {
                int eq = fl.IndexOf('=');
                if (eq > 0) d[fl.Substring(0, eq)] = fl.Substring(eq + 1);
                else if (fl.Length > 0) d[fl] = "1";
            }
            return d;
        }

        IEnumerator SongCheck()
        {
            for (int i = 0; i < 4; i++)
            {
                yield return new WaitForSeconds(3f);
                Plugin.Log.LogInfo("bosstest song: " + MusicInfo());
            }
        }

        IEnumerator BossTest()
        {
            var nm = MonoSingleton<NewMovement>.Instance;
            if (!originSet)
            {
                origin = Vector3.zero;
                originSet = true;
            }
            var floor = GameObject.CreatePrimitive(PrimitiveType.Cube);
            floor.name = "uc test floor";
            floor.layer = 8;
            floor.tag = "Floor";
            floor.transform.position = nm.transform.position + Vector3.down * 2.5f;
            floor.transform.localScale = new Vector3(400f, 1f, 400f);
            Physics.SyncTransforms();
            // the voice lines' lengths, to tell which is which
            foreach (var clipKey in new[] {
                "Minos Prime/mp_intro.wav", "Minos Prime/mp_intro2.wav", "Minos Prime/mp_prepare.wav", "Minos Prime/mp_thyend.wav", "Minos Prime/mp_judgement.wav",
                "Minos Prime/mp_useless.wav", "Minos Prime/mp_die.wav", "Minos Prime/mp_crush.wav", "Minos Prime/mp_outro.wav",
                "Sisyphus Prime/sp_intro.ogg", "Sisyphus Prime/sp_thisprison.ogg", "Sisyphus Prime/sp_outro.ogg",
                "Gabriel/gab_Intro1.ogg", "Gabriel/gab_Intro1b.ogg", "Gabriel/gab_Intro1c.ogg", "Gabriel/gab_Intro1d.ogg", "Gabriel/gab_Intro1e.ogg",
                "Gabriel/gab_Intro2.ogg", "Gabriel/gab_Intro2b.ogg", "Gabriel/gab_Behold.ogg",
                "Gabriel/gab2nd_intro1.ogg", "Gabriel/gab2nd_intro1b.ogg", "Gabriel/gab2nd_intro2.ogg" })
            {
                AudioClip c = null;
                try { c = Addressables.LoadAssetAsync<AudioClip>("Assets/Sounds/Voices/" + clipKey).WaitForCompletion(); }
                catch (Exception) { }
                Plugin.Log.LogInfo("bosstest clip " + clipKey + ": " + (c != null ? c.length.ToString("0.00") + " s" : "missing"));
            }
            // the intro animators, and what's in them
            foreach (var ck in new[] {
                "Assets/Models/Enemies/Minos Prime/MinosPrimeIntro.controller", "Assets/Models/Enemies/SisyphusPrime/SisyphusPrimeIntro.controller",
                "Assets/Models/Enemies/SisyphusPrime/SisyphusPrimeIntroBreak.controller", "Assets/Models/Enemies/Gabriel/Gabriel2ndIntro.controller",
                "Assets/Models/Enemies/Gabriel/GabrielFake.controller", "Assets/Models/Enemies/Ferryman/FerrymanIntro.controller",
                "Assets/Models/Enemies/Ferryman/FerrymanIntro2.controller", "Assets/Models/Enemies/Sisyphus/Sisyphus Intro.controller",
                "Assets/Models/V1/V2 GreenArm Intro.controller", "Assets/Animations/MassFake.controller", "Assets/Animations/StatueFake.controller",
                "Assets/Animations/SwordsMachineFake.controller", "Assets/Models/Enemies/Cerberus/CerberusStatic.controller" })
            {
                RuntimeAnimatorController rac = null;
                try { rac = Addressables.LoadAssetAsync<RuntimeAnimatorController>(ck).WaitForCompletion(); }
                catch (Exception e) { Plugin.Log.LogInfo("bosstest controller " + ck + ": " + e.Message); }
                if (rac == null) continue;
                var sbc = new System.Text.StringBuilder();
                foreach (var c in rac.animationClips) sbc.Append(c.name).Append('=').Append(c.length.ToString("0.0")).Append(' ');
                Plugin.Log.LogInfo("bosstest controller " + ck.Substring(ck.LastIndexOf('/') + 1) + ": " + sbc);
            }
            bool withIntro = Array.IndexOf(Environment.GetCommandLineArgs(), "-ucnointro") < 0;
            bool testOutro = Array.IndexOf(Environment.GetCommandLineArgs(), "-ucoutro") >= 0;
            // -ucsong <file>: one of the player's own songs, as a boss's theme
            if (TestArg("-ucsong") is string songFile && songFile != "all")
            {
                HandleMusic("THEME", "file:" + songFile);
                StartCoroutine(SongCheck());
            }
            var keys = BossTestArg == "all" ? new List<string>(Intros.Keys) : new List<string>(BossTestArg.Split(','));
            int id = 9000;
            foreach (var key in keys)
            {
                var spot = nm.transform.position + nm.transform.forward * 11f * K + Vector3.down * 1.5f;
                spot.y = floor.transform.position.y + 0.5f;
                Plugin.Log.LogInfo("bosstest " + key + ": spawning");
                SpawnBoss(++id, key, UkToMc(spot), 1, (TestArg("-ucmods") is string mm && mm != "all" ? mm.Split(',') : new string[0]), withIntro, Array.IndexOf(Environment.GetCommandLineArgs(), "-ucfullintro") >= 0,
                    TestArg("-ucalt") is string altArg && altArg != "all" ? altArg : null, testOutro, TestSetup());
                if (!bosses.TryGetValue(id, out var fight))
                {
                    Plugin.Log.LogWarning("bosstest " + key + ": FAILED to spawn");
                    continue;
                }
                foreach (var part in fight.parts)
                    if (part.go != null)
                        foreach (var an in part.go.GetComponentsInChildren<Animator>(true))
                            Plugin.Log.LogInfo("bosstest " + key + " animator " + an.name + " controller=" + (an.runtimeAnimatorController != null ? an.runtimeAnimatorController.name : "-")
                                + " avatar=" + (an.avatar != null ? an.avatar.name : "-"));
                // look at it, and photograph the entrance as it goes
                var cc = MonoSingleton<CameraController>.Instance;
                if (cc != null)
                {
                    var look = Quaternion.LookRotation(spot + Vector3.up * 2f * K - cc.transform.position).eulerAngles;
                    cc.rotationY = look.y;
                    cc.rotationX = -(look.x > 180f ? look.x - 360f : look.x);
                    cc.ApplyRotations();
                }
                var shots = System.IO.Path.Combine(Environment.GetEnvironmentVariable("TEMP") ?? ".", "uc_bosstest");
                System.IO.Directory.CreateDirectory(shots);
                StartCoroutine(Photos(shots, key));
                yield return new WaitForSeconds(0.5f);
                bool heldAtStart = false;
                foreach (var part in fight.parts) heldAtStart |= IntroHeld(part.eid) || (part.go != null && part.go.GetComponent<V2>() is V2 v && v.inIntro);
                // until the entrance is over (a full one can last a minute), and a moment more
                float until = Time.time + 80f;
                while (Time.time < until)
                {
                    bool any = false;
                    foreach (var part in fight.parts) any |= IntroHeld(part.eid) || (part.go != null && part.go.GetComponent<V2>() is V2 vv && vv.inIntro);
                    if (!any) break;
                    yield return null;
                }
                yield return new WaitForSeconds(2.5f);
                // -ucdecoys: let it fight a while, counting its afterimages (Gabriel's decoys) and how long each lasts
                if (Array.IndexOf(Environment.GetCommandLineArgs(), "-ucdecoys") >= 0)
                {
                    var born = new Dictionary<int, float>();
                    float longest = 0f;
                    for (int sec = 0; sec < 20; sec++)
                    {
                        if (sec % 4 == 0) ScreenCapture.CaptureScreenshot(System.IO.Path.Combine(shots, key + "_decoys_" + sec + ".png"));
                        var all = UnityEngine.Object.FindObjectsOfType<MindflayerDecoy>();
                        var ids = new HashSet<int>();
                        int bodies = 0;
                        foreach (var d in all)
                        {
                            int i = d.transform.root.GetInstanceID();
                            if (!ids.Add(i)) continue;
                            if (!born.ContainsKey(i)) born[i] = Time.time;
                            longest = Mathf.Max(longest, Time.time - born[i]);
                        }
                        foreach (var gb in UnityEngine.Object.FindObjectsOfType<GabrielBase>()) bodies++;
                        int rigs = 0;
                        foreach (var part in fight.parts)
                            if (part.go != null)
                                foreach (var smr in part.go.GetComponentsInChildren<SkinnedMeshRenderer>())
                                    if (smr.name == "body" && smr.gameObject.activeInHierarchy && smr.enabled && !smr.forceRenderingOff) rigs++;
                        Plugin.Log.LogInfo("bosstest " + key + " decoys: t=" + sec + " afterimages=" + ids.Count + " longestLived=" + longest.ToString("0.0")
                            + " s gabriels=" + bodies + " visibleBodies=" + rigs);
                        yield return new WaitForSeconds(1f);
                    }
                }
                foreach (var part in fight.parts)
                {
                    if (part.eid == null)
                    {
                        Plugin.Log.LogWarning("bosstest " + key + ": part gone");
                        continue;
                    }
                    bool brain = true;
                    foreach (var b in part.go.GetComponentsInChildren<EnemyScript>(true)) brain &= b.enabled;
                    var v2 = part.go.GetComponent<V2>();
                    float above = part.eid.transform.position.y - floor.transform.position.y - 0.5f;
                    Plugin.Log.LogInfo("bosstest " + key + ": heldAtStart=" + heldAtStart + " stillHeld=" + IntroHeld(part.eid) + " v2InIntro=" + (v2 != null && v2.inIntro)
                        + " dead=" + part.eid.dead + " health=" + part.eid.health.ToString("0.0") + " brainOn=" + brain + " heightOverFloor=" + (above / K).ToString("0.0") + " blocks");
                }
                // -ucsetup with stagger/retreat: the first part down to 30%, and what happens
                var ts = TestSetup();
                if ((ts.ContainsKey("stagger") || ts.ContainsKey("retreat")) && bosses.ContainsKey(id) && fight.parts.Count > 0 && fight.parts[0].eid != null)
                {
                    var e0 = fight.parts[0].eid;
                    e0.DeliverDamage(e0.gameObject, Vector3.zero, e0.transform.position, e0.health * (ts.ContainsKey("retreat") ? 0.9f : 0.7f), false);
                    StartCoroutine(Photos(shots, key + "_setup"));
                    yield return new WaitForSeconds(5f);
                    foreach (var part in fight.parts)
                        Plugin.Log.LogInfo("bosstest " + key + " setup: part " + (part.go == null ? "gone" : "there") + " held=" + IntroHeld(part.eid) + " retreated=" + part.retreated
                            + " health=" + (part.eid != null ? part.eid.health.ToString("0.0") : "-") + " fight=" + bosses.ContainsKey(id));
                }
                if (fight.parts.Count > 0 && fight.parts[0].eid != null)
                    Plugin.Log.LogInfo("bosstest " + key + " modifiers: health x" + fight.parts[0].eid.totalHealthModifier.ToString("0.00") + " speed x"
                        + fight.parts[0].eid.totalSpeedModifier.ToString("0.00") + " damage x" + fight.parts[0].eid.totalDamageModifier.ToString("0.00"));
                if (testOutro && bosses.ContainsKey(id))
                {
                    // beaten: its death scene, photographed, until Minecraft would hear it's over
                    StartCoroutine(Photos(shots, key + "_death"));
                    float killedAt = Time.unscaledTime;
                    foreach (var part in fight.parts)
                        if (part.eid != null && !part.eid.dead)
                        {
                            // as a real fight ends: its health gone to a hit (its own death runs)
                            part.eid.DeliverDamage(part.eid.gameObject, Vector3.zero, part.eid.transform.position, 999999f, false);
                            if (!part.eid.dead) part.eid.InstaKill();
                        }
                    while (bosses.ContainsKey(id) && Time.unscaledTime - killedAt < 60f)
                    {
                        UpdateBosses(nm);
                        yield return null;
                    }
                    var anyLeft = new List<string>();
                    foreach (var part in fight.parts)
                        anyLeft.Add(part.go == null ? "destroyed" : part.go.activeInHierarchy ? "still there" : "gone (inactive)");
                    Plugin.Log.LogInfo("bosstest " + key + ": death scene " + (Time.unscaledTime - killedAt).ToString("0.0") + " s, fight over=" + !bosses.ContainsKey(id)
                        + ", body " + string.Join(",", anyLeft.ToArray()));
                    yield return new WaitForSeconds(3f);
                }
                ClearBoss(id);
                yield return new WaitForSeconds(1f);
            }
            Plugin.Log.LogInfo("bosstest done");
        }
    }
}
