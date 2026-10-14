// UltraBridge, part six: what a boss brings with it. Minecraft picks (UkBosses.java) and sends them with the boss
// (BOSS id key x y z difficulty mods):
// - its difficulty, which rises as V1 beats bosses: EASY, MEDIUM, HARD, NIGHTMARE, V1 MUST DIE. ULTRAKILL's own
//   difficulty for that one enemy (Lenient to ULTRAKILL MUST DIE: how it thinks, which attacks it uses, how fast it
//   picks them), with more health, speed and damage on top;
// - up to two modifiers, each with its own look and a title on the boss bar:
//   RADIANT (ULTRAKILL's radiance), UNDYING (gets back up once, at half health), REGENERATING (heals when left
//   alone), ENRAGED (enraged from the start), ESCORTED (calls in enemies), TWIN (a second one), BLINKING (teleports
//   next to V1), DEMOLISHER (smashes the blocks around it), STORMCALLER and ECLIPSE (Minecraft's side: lightning,
//   night), VOLATILE (leaves mines, blows up when it dies), SANDED (its blood doesn't heal), GLASS CANNON (half the
//   health, twice the damage), VAMPIRIC (heals from hurting V1), GIANT / TINY.

using System.Collections;
using System.Collections.Generic;
using System.Text;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>A boss's difficulty and modifiers, on the boss itself (its multipliers and what it does each frame).</summary>
    public class UcBoss : MonoBehaviour
    {
        public readonly HashSet<string> mods = new HashSet<string>();
        /// <summary>0 EASY .. 4 V1 MUST DIE.</summary>
        public int tier;
        public bool twin;
        public float hpMult = 1f, speedMult = 1f, dmgMult = 1f;
        public EnemyIdentifier eid;
        public UcBoss tether;
        string baseName;
        float nextBar, nextBlink, nextEscort, nextMine, nextCrush, nextRage, nextGlint, nextRegenFx;
        float lastHealth = -1f, lastHurt;
        LineRenderer tetherLine;
        Light glow;
        readonly List<GameObject> escorts = new List<GameObject>();

        public bool Has(string mod) => mods.Contains(mod);

        void Start()
        {
            float now = Time.time;
            nextBlink = now + Random.Range(6f, 9f);
            nextEscort = now + 6f;
            nextMine = now + 3f;
            nextGlint = now + 1f;
            lastHurt = now;
            var drm = MonoSingleton<DefaultReferenceManager>.Instance;
            if (eid == null) return;
            var b = Bridge.EnemyBounds(eid);
            // a light of its own for the modifiers that glow
            Color? c = Has("regen") ? new Color(0.35f, 1f, 0.45f) : Has("vampiric") ? new Color(0.8f, 0f, 0.1f) : Has("volatile") ? new Color(1f, 0.45f, 0.1f)
                : Has("glass") ? new Color(0.7f, 0.95f, 1f) : Has("secondwind") ? new Color(1f, 0.95f, 0.6f) : (Color?)null;
            if (c.HasValue)
            {
                glow = new GameObject("uc boss glow").AddComponent<Light>();
                glow.transform.SetParent(eid.transform, false);
                glow.transform.position = b.center;
                glow.color = c.Value;
                glow.range = Mathf.Max(6f, b.extents.magnitude * 3f);
                glow.intensity = 2f;
            }
            if (Has("secondwind") && drm != null && drm.blessingGlow != null)
            {
                // a halo while it still has its second life
                var halo = Instantiate(drm.blessingGlow, b.center, Quaternion.identity, eid.transform);
                halo.name = "uc undying halo";
                halo.transform.localScale *= Mathf.Max(1f, b.extents.y);
            }
            if (Has("enraged")) Rage(true);
            if (tether != null)
            {
                tetherLine = new GameObject("uc twin tether").AddComponent<LineRenderer>();
                tetherLine.transform.SetParent(transform, false);
                tetherLine.positionCount = 2;
                tetherLine.widthMultiplier = 0.12f;
                var shader = Shader.Find("Sprites/Default");
                if (shader != null) tetherLine.material = new Material(shader);
                tetherLine.startColor = tetherLine.endColor = new Color(0.7f, 0.4f, 1f, 0.8f);
            }
        }

        /// <summary>Enraged: its own enrage (V2, the Swordsmachine, Cerberus, Mindflayer, the Primes...), and the
        /// red look for those without one.</summary>
        void Rage(bool first)
        {
            if (eid == null || eid.dead) return;
            if (first)
            {
                gameObject.BroadcastMessage("Enrage", SendMessageOptions.DontRequireReceiver);
                var drm = MonoSingleton<DefaultReferenceManager>.Instance;
                if (drm != null && drm.enrageEffect != null)
                {
                    var fx = Instantiate(drm.enrageEffect, Bridge.EnemyBounds(eid).center, Quaternion.identity, eid.transform);
                    fx.name = "uc enrage";
                }
            }
            foreach (var sim in GetComponentsInChildren<EnemySimplifier>(true)) sim.enraged = true;
        }

        void OnDestroy()
        {
            if (eid != null) Bridge.ForgetBoss(eid);
        }

        void Update()
        {
            if (eid == null || eid.dead)
            {
                if (tetherLine != null) tetherLine.enabled = false;
                return;
            }
            float now = Time.time;
            var bridge = Bridge.I;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (bridge == null || nm == null) return;
            // making its entrance: its traits wait (and their timers start when it's let go)
            if (Bridge.IntroHeld(eid))
            {
                nextBlink = now + Random.Range(6f, 9f);
                nextEscort = now + 6f;
                nextMine = now + 3f;
                lastHurt = now;
                return;
            }
            var b = Bridge.EnemyBounds(eid);
            var enemy = eid.GetComponent<Enemy>();
            float health = enemy != null ? enemy.health : eid.health;
            float max = enemy != null && enemy.originalHealth > 0f ? enemy.originalHealth : Mathf.Max(health, 1f);
            if (lastHealth >= 0f && health < lastHealth - 0.001f) lastHurt = now;
            lastHealth = health;

            // its titles next to its name on the boss bar
            if (now >= nextBar)
            {
                nextBar = now + 0.5f;
                var bar = eid.GetComponent<BossHealthBar>();
                if (bar != null)
                {
                    if (baseName == null || !bar.bossName.StartsWith(baseName)) baseName = bar.bossName;
                    var want = baseName + Bridge.BossTitles(this);
                    if (bar.bossName != want) bar.ChangeName(want);
                }
            }
            if (glow != null) glow.intensity = 1.5f + Mathf.Sin(now * 4f) * 0.75f;
            if (tetherLine != null)
            {
                bool on = tether != null && tether.eid != null && !tether.eid.dead;
                tetherLine.enabled = on;
                if (on)
                {
                    tetherLine.SetPosition(0, b.center);
                    tetherLine.SetPosition(1, Bridge.EnemyBounds(tether.eid).center);
                }
            }
            if (Has("enraged") && now >= nextRage)
            {
                nextRage = now + 5f;
                Rage(false);
            }
            // REGENERATING: left alone for 3 s, it heals 2% a second
            if (Has("regen") && now - lastHurt > 3f && health < max && enemy != null)
            {
                enemy.health = Mathf.Min(max, health + max * 0.02f * Time.deltaTime);
                eid.health = enemy.health;
                lastHealth = enemy.health;
                if (now >= nextRegenFx)
                {
                    nextRegenFx = now + 0.3f;
                    var from = b.center + new Vector3(Random.Range(-1f, 1f) * b.extents.x, -b.extents.y, Random.Range(-1f, 1f) * b.extents.z);
                    Bridge.Zap(from, from + Vector3.up * b.size.y, new Color(0.35f, 1f, 0.45f, 0.9f));
                }
            }
            // BLINKING: every 7 to 11 s it's suddenly beside V1
            if (Has("blinker") && now >= nextBlink)
            {
                nextBlink = now + Random.Range(7f, 11f);
                bridge.Blink(this, nm);
            }
            // ESCORTED: every 30 s, three more enemies (at most eight about)
            if (Has("escorted") && now >= nextEscort)
            {
                nextEscort = now + 30f;
                escorts.RemoveAll(g => g == null || g.GetComponentInChildren<EnemyIdentifier>() == null || g.GetComponentInChildren<EnemyIdentifier>().dead);
                if (escorts.Count < 8) bridge.Escort(this, escorts);
            }
            // VOLATILE: a mine where it stood, every 3.5 s
            if (Has("volatile") && now >= nextMine)
            {
                nextMine = now + 3.5f;
                bridge.StartCoroutine(Bridge.Mine(new Vector3(b.center.x, b.min.y + 0.3f, b.center.z), eid));
            }
            // DEMOLISHER: the blocks around it give (never the ones it stands on)
            if (Has("demolisher") && now >= nextCrush)
            {
                nextCrush = now + 0.4f;
                bridge.Crush(b);
            }
            // GLASS CANNON: it glints
            if (Has("glass") && now >= nextGlint)
            {
                nextGlint = now + Random.Range(0.4f, 0.9f);
                var p = b.center + Vector3.Scale(Random.insideUnitSphere, b.extents);
                Bridge.Zap(p, p + Random.onUnitSphere * b.extents.magnitude * 0.4f, new Color(0.85f, 1f, 1f, 0.9f));
            }
        }
    }

    public partial class Bridge
    {
        static readonly string[] TierNames = { "EASY", "MEDIUM", "HARD", "NIGHTMARE", "V1 MUST DIE" };
        static readonly string[] TierColors = { "#7CFC00", "#FFD700", "#FF8C00", "#FF2020", "#C000FF" };
        // health, speed, damage on top of ULTRAKILL's own difficulty (Lenient .. ULTRAKILL MUST DIE)
        static readonly float[] TierHp = { 1f, 1.15f, 1.35f, 1.7f, 2.2f };
        static readonly float[] TierSpeed = { 1f, 1.05f, 1.1f, 1.2f, 1.3f };
        static readonly float[] TierDamage = { 1f, 1.1f, 1.25f, 1.5f, 2f };

        static readonly Dictionary<string, (string title, string color)> ModTitles = new Dictionary<string, (string, string)>
        {
            { "radiant", ("RADIANT", "#FFD54A") },
            { "secondwind", ("UNDYING", "#FFF2B0") },
            { "regen", ("REGENERATING", "#5CFF7A") },
            { "enraged", ("ENRAGED", "#FF3030") },
            { "escorted", ("ESCORTED", "#C8C8C8") },
            { "twin", ("TWIN", "#B070FF") },
            { "blinker", ("BLINKING", "#8A5CFF") },
            { "demolisher", ("DEMOLISHER", "#C08040") },
            { "stormcaller", ("STORMCALLER", "#7FD4FF") },
            { "eclipse", ("ECLIPSE", "#7070B0") },
            { "volatile", ("VOLATILE", "#FF7A1A") },
            { "sanded", ("SANDED", "#E0C080") },
            { "glass", ("GLASS CANNON", "#BFF6FF") },
            { "vampiric", ("VAMPIRIC", "#C00020") },
            { "giant", ("GIANT", "#FF9090") },
            { "tiny", ("TINY", "#90FF90") },
        };

        /// <summary>" RADIANT VOLATILE [NIGHTMARE]", coloured, for the boss bar.</summary>
        public static string BossTitles(UcBoss boss)
        {
            var sb = new StringBuilder();
            if (boss.twin) sb.Append(" <color=#B070FF>(TWIN)</color>");
            foreach (var m in boss.mods)
            {
                if (m == "twin" && boss.twin) continue;
                if (ModTitles.TryGetValue(m, out var t)) sb.Append(" <color=").Append(t.color).Append('>').Append(t.title).Append("</color>");
            }
            int tier = Mathf.Clamp(boss.tier, 0, TierNames.Length - 1);
            sb.Append(" <color=").Append(TierColors[tier]).Append(">[").Append(TierNames[tier]).Append("]</color>");
            return sb.ToString();
        }

        /// <summary>Set up before it wakes (its Awake and Start see this): its difficulty, radiance, sand, size, and
        /// the multipliers ULTRAKILL applies to its health, speed and damage.</summary>
        static UcBoss PrepareBoss(GameObject go, int tier, IEnumerable<string> mods, bool twin)
        {
            var boss = go.AddComponent<UcBoss>();
            boss.tier = Mathf.Clamp(tier, 0, 4);
            boss.twin = twin;
            if (mods != null) foreach (var m in mods) if (!string.IsNullOrEmpty(m)) boss.mods.Add(m);
            boss.hpMult = TierHp[boss.tier];
            boss.speedMult = TierSpeed[boss.tier];
            boss.dmgMult = TierDamage[boss.tier];
            if (boss.Has("glass"))
            {
                boss.hpMult *= 0.5f;
                boss.dmgMult *= 2f;
            }
            if (boss.Has("enraged")) boss.speedMult *= 1.2f;
            if (boss.Has("giant"))
            {
                go.transform.localScale *= 1.5f;
                boss.hpMult *= 1.75f;
                boss.dmgMult *= 1.25f;
                boss.speedMult *= 0.9f;
            }
            if (boss.Has("tiny"))
            {
                go.transform.localScale *= 0.6f;
                boss.hpMult *= 0.6f;
                boss.speedMult *= 1.25f;
            }
            if (twin) boss.hpMult *= 0.6f;
            foreach (var eid in go.GetComponentsInChildren<EnemyIdentifier>(true))
            {
                // ULTRAKILL's own difficulty, for this enemy alone: Lenient (EASY) up to ULTRAKILL MUST DIE
                eid.difficultyOverride = boss.tier + 1;
                if (boss.Has("radiant"))
                {
                    eid.healthBuff = eid.speedBuff = eid.damageBuff = true;
                    eid.radianceTier = 1.5f;
                }
                if (boss.Has("sanded")) eid.sandified = true;
                if (boss.eid == null) boss.eid = eid;
                BossOf[eid] = boss;
            }
            return boss;
        }

        /// <summary>The bosses' enemies, and whose difficulty and modifiers they carry.</summary>
        static readonly Dictionary<EnemyIdentifier, UcBoss> BossOf = new Dictionary<EnemyIdentifier, UcBoss>();

        /// <summary>A boss's own multipliers, on top of whatever ULTRAKILL's buffs make them (every enemy, every
        /// frame: kept cheap).</summary>
        public static void BossModifiers(EnemyIdentifier eid)
        {
            if (BossOf.Count == 0 || !BossOf.TryGetValue(eid, out var boss)) return;
            if (boss == null)
            {
                BossOf.Remove(eid);
                return;
            }
            eid.totalHealthModifier *= boss.hpMult;
            eid.totalSpeedModifier *= boss.speedMult;
            eid.totalDamageModifier *= boss.dmgMult;
        }

        public static void ForgetBoss(EnemyIdentifier eid) => BossOf.Remove(eid);

        /// <summary>BLINKING: it's gone, and beside V1 (five to eight blocks off, on the ground).</summary>
        public void Blink(UcBoss boss, NewMovement nm)
        {
            var eid = boss.eid;
            var from = EnemyBounds(eid).center;
            var drm = MonoSingleton<DefaultReferenceManager>.Instance;
            for (int tries = 0; tries < 8; tries++)
            {
                var dir = Quaternion.Euler(0f, Random.Range(0f, 360f), 0f) * Vector3.forward;
                var p = nm.transform.position + dir * Random.Range(5f, 8f) * K;
                if (!Physics.Raycast(p + Vector3.up * 4f * K, Vector3.down, out var hit, 10f * K, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)) continue;
                // room to stand there
                if (Physics.CheckSphere(hit.point + Vector3.up * 1.5f * K, 0.9f * K, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)) continue;
                var to = hit.point + Vector3.up * 0.1f;
                var root = eid.transform;
                var agent = eid.GetComponent<UnityEngine.AI.NavMeshAgent>();
                if (agent != null && agent.enabled && agent.isOnNavMesh) agent.Warp(to);
                else root.position = to;
                var rb = eid.GetComponent<Rigidbody>();
                if (rb != null)
                {
                    rb.position = to;
                    rb.velocity = Vector3.zero;
                }
                var look = nm.transform.position - to;
                look.y = 0f;
                if (look.sqrMagnitude > 0.01f) root.rotation = Quaternion.LookRotation(look);
                var purple = new Color(0.6f, 0.35f, 1f, 1f);
                Zap(from, EnemyBounds(eid).center, purple);
                if (drm != null && drm.unparryableFlash != null)
                {
                    Destroy(Instantiate(drm.unparryableFlash, from, Quaternion.identity), 1f);
                    Destroy(Instantiate(drm.unparryableFlash, EnemyBounds(eid).center, Quaternion.identity), 1f);
                }
                return;
            }
        }

        static readonly string[] EscortKinds = { "Filth", "Filth", "Stray", "Schism", "Soldier", "Drone" };

        /// <summary>ESCORTED: three of ULTRAKILL's regulars come out of thin air around it.</summary>
        public void Escort(UcBoss boss, List<GameObject> escorts)
        {
            var c = EnemyBounds(boss.eid).center;
            for (int i = 0; i < 3; i++)
            {
                var dir = Quaternion.Euler(0f, i * 120f + Random.Range(-30f, 30f), 0f) * Vector3.forward;
                var p = c + dir * Random.Range(2.5f, 4f) * K;
                if (Physics.Raycast(p + Vector3.up * 3f * K, Vector3.down, out var hit, 8f * K, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore))
                    p = hit.point;
                var go = SpawnAt(EscortKinds[Random.Range(0, EscortKinds.Length)], UkToMc(p), 0);
                if (go != null) escorts.Add(go);
            }
        }

        /// <summary>VOLATILE: a glowing mine, then a blast that hurts V1 and Minecraft's mobs (never the boss).</summary>
        public static IEnumerator Mine(Vector3 at, EnemyIdentifier origin)
        {
            var orb = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            orb.name = "uc volatile mine";
            Destroy(orb.GetComponent<Collider>());
            orb.transform.position = at;
            orb.transform.localScale = Vector3.one * 0.5f * K;
            var r = orb.GetComponent<Renderer>();
            var shader = Shader.Find("Sprites/Default");
            if (r != null && shader != null) r.material = new Material(shader) { color = new Color(1f, 0.4f, 0.05f, 0.9f) };
            var l = orb.AddComponent<Light>();
            l.color = new Color(1f, 0.45f, 0.1f);
            l.range = 4f * K;
            float t = 0f;
            while (t < 1.4f)
            {
                t += Time.deltaTime;
                float pulse = 0.5f + 0.5f * Mathf.Sin(t * (8f + t * 20f));
                l.intensity = 1f + pulse * 4f;
                orb.transform.localScale = Vector3.one * (0.4f + pulse * 0.25f) * K;
                yield return null;
            }
            Destroy(orb);
            SpawnBlast(at, 1f, true, true, origin, 20);
        }

        /// <summary>DEMOLISHER: blocks within its reach break, from its feet up.</summary>
        public void Crush(Bounds b)
        {
            float radius = Mathf.Clamp(Mathf.Max(b.extents.x, b.extents.z) / K + 0.8f, 1.2f, 3.5f);
            var feet = new Vector3(b.center.x, b.min.y, b.center.z);
            var center = feet + Vector3.up * (radius + 0.6f) * K;
            ReportBlockHit(center, Vector3.down, 4f, radius, Vector3.down, true);
        }

        /// <summary>UNDYING: a boss part just died for the first time: it rises again at half health.</summary>
        GameObject Revive(BossFight fight, BossPart part, NewMovement nm)
        {
            var boss = part.go != null ? part.go.GetComponent<UcBoss>() : null;
            var at = part.lastAt;
            var drm = MonoSingleton<DefaultReferenceManager>.Instance;
            if (drm != null && drm.radianceEffect != null) Destroy(Instantiate(drm.radianceEffect, at, Quaternion.identity), 4f);
            for (int i = 0; i < 6; i++) Zap(at, at + Random.onUnitSphere * 4f * K, new Color(1f, 0.95f, 0.6f, 1f));
            var feet = at;
            if (Physics.Raycast(at + Vector3.up * K, Vector3.down, out var hit, 8f * K, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore))
                feet = hit.point;
            if (part.go != null) Destroy(part.go);
            var mods = new List<string>(part.mods);
            mods.Remove("secondwind");
            var look = nm.transform.position - feet;
            look.y = 0f;
            var go = SpawnBossPart(fight, feet + Vector3.up * part.lift, Quaternion.LookRotation(look.sqrMagnitude > 0.01f ? look : Vector3.forward), part.tier, mods, part.twin);
            if (go == null) return null;
            var eid = go.GetComponentInChildren<EnemyIdentifier>(true);
            Plugin.Log.LogInfo("boss " + fight.id + ": " + fight.key + " rises again (undying) at " + UkToMc(feet) + " eid=" + (eid != null) + " dead=" + (eid != null && eid.dead)
                + " hp=" + (eid != null ? S(eid.health) : "-"));
            if (eid != null) StartCoroutine(HalfHealth(eid));
            HudMessage("<color=#FFF2B0>UNDYING</color>: it gets back up.");
            return go;
        }

        static IEnumerator HalfHealth(EnemyIdentifier eid)
        {
            yield return null;
            yield return null;
            if (eid == null) yield break;
            var enemy = eid.GetComponent<Enemy>();
            if (enemy == null) yield break;
            float max = enemy.originalHealth > 0f ? enemy.originalHealth : enemy.health;
            Plugin.Log.LogInfo("undying: hp " + S(enemy.health) + " of " + S(enemy.originalHealth) + " dead=" + eid.dead + " -> " + S(max * 0.5f));
            enemy.health = max * 0.5f;
            eid.health = enemy.health;
        }

        void HudMessage(string text)
        {
            var hud = levelPrepared ? MonoSingleton<HudMessageReceiver>.Instance : null;
            if (hud != null) hud.SendHudMessage(text, "", "", 0, false);
        }

        /// <summary>VAMPIRIC: V1 was hurt; every vampiric boss about drinks it in.</summary>
        public void VampireFeed(NewMovement nm, int lost)
        {
            if (lost <= 0) return;
            foreach (var f in bosses.Values)
            {
                foreach (var part in f.parts)
                {
                    if (part.eid == null || part.eid.dead || !part.mods.Contains("vampiric")) continue;
                    var b = EnemyBounds(part.eid);
                    if ((b.center - nm.transform.position).magnitude > 60f * K) continue;
                    var enemy = part.eid.GetComponent<Enemy>();
                    if (enemy == null) continue;
                    float max = enemy.originalHealth > 0f ? enemy.originalHealth : enemy.health;
                    enemy.health = Mathf.Min(max, enemy.health + max * lost / 100f * 0.35f);
                    part.eid.health = enemy.health;
                    Zap(nm.transform.position + Vector3.up * K, b.center, new Color(0.8f, 0f, 0.1f, 1f));
                }
            }
        }
    }

    /// <summary>A boss's difficulty and modifiers in ULTRAKILL's own health, speed and damage multipliers.</summary>
    [HarmonyPatch(typeof(EnemyIdentifier), "UpdateModifiers")]
    static class BossMultipliers
    {
        static void Postfix(EnemyIdentifier __instance) => Bridge.BossModifiers(__instance);
    }

    /// <summary>VAMPIRIC: whatever V1 loses feeds the boss.</summary>
    [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHurt))]
    static class BossVampire
    {
        static void Prefix(NewMovement __instance, out int __state) => __state = __instance.hp;

        static void Postfix(NewMovement __instance, int __state)
        {
            if (__state > __instance.hp) Bridge.I?.VampireFeed(__instance, __state - __instance.hp);
        }
    }
}
