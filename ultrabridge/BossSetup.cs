// UltraBridge: Boss Setup (Minecraft's Cheats -> Boss Setup), the flags of a BOSS message:
// - hp=, spd=, dmg=: ULTRAKILL's own health, speed and damage modifiers, times that;
// - phase2: it starts in its second phase (Gabriel: "Enough!" and his juggle; the Primes: enraged, "WEAK");
// - retreat (V2): as in 1-4, at a third of its health it's knocked down and flees in a flash of light (a win);
// - stagger (Cerberus): as in 0-5, the second statue sleeps until the first is down to half its health;
// - bribe (Ferryman): as in 5-2, a Marksman coin thrown at him buys the way past (the fight ends, unpaid);
// - grind (Primes): as the Cyber Grind has them, with no boss bar at their start (a quick death, no outro).

using System;
using System.Collections;
using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>Bosses with their own health, speed and damage (the Setup's multipliers).</summary>
        static readonly Dictionary<EnemyIdentifier, (float hp, float spd, float dmg)> bossScale = new Dictionary<EnemyIdentifier, (float, float, float)>();

        static readonly System.Reflection.MethodInfo UpdateModifiersMethod = AccessTools.Method(typeof(EnemyIdentifier), "UpdateModifiers");

        [HarmonyPatch(typeof(EnemyIdentifier), "UpdateModifiers")]
        static class BossScaleModifiers
        {
            static void Postfix(EnemyIdentifier __instance)
            {
                if (bossScale.Count == 0 || !bossScale.TryGetValue(__instance, out var s)) return;
                __instance.totalHealthModifier *= s.hp;
                __instance.totalSpeedModifier *= s.spd;
                __instance.totalDamageModifier *= s.dmg;
            }
        }

        static float SetupNum(Dictionary<string, string> setup, string k)
        {
            if (setup == null || !setup.TryGetValue(k, out var v)) return 1f;
            return float.TryParse(v, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var f) && f > 0.05f ? f : 1f;
        }

        /// <summary>A fight's setup, once its bosses are in.</summary>
        void ApplySetup(BossFight f)
        {
            if (f.setup == null || f.setup.Count == 0) return;
            float hp = SetupNum(f.setup, "hp"), spd = SetupNum(f.setup, "spd"), dmg = SetupNum(f.setup, "dmg");
            if (hp != 1f || spd != 1f || dmg != 1f)
                foreach (var part in f.parts)
                {
                    if (part.eid == null) continue;
                    bossScale[part.eid] = (hp, spd, dmg);
                    try { UpdateModifiersMethod?.Invoke(part.eid, null); }
                    catch (Exception e) { Plugin.Log.LogDebug("boss scale: " + e.Message); }
                }
            Plugin.Log.LogInfo("boss " + f.id + ": setup " + string.Join(",", new List<string>(f.setup.Keys).ToArray()));
            StartCoroutine(SetupWatch(f));
        }

        static bool AnyHeld(BossFight f)
        {
            foreach (var p in f.parts) if (IntroHeld(p.eid) || (p.go != null && p.go.GetComponent<V2>() is V2 v && v.inIntro)) return true;
            return false;
        }

        IEnumerator SetupWatch(BossFight f)
        {
            // its entrance first
            float until = Time.time + 90f;
            while (AnyHeld(f) && Time.time < until) yield return null;
            yield return new WaitForSeconds(0.4f);
            if (!bosses.ContainsKey(f.id)) yield break;
            if (f.setup.ContainsKey("phase2")) SecondPhase(f);
            if (f.setup.ContainsKey("stagger") && f.parts.Count > 1) StartCoroutine(Stagger(f));
            var start = new Dictionary<BossPart, float>();
            foreach (var p in f.parts) if (p.eid != null) start[p] = p.eid.health;
            bool retreat = f.setup.ContainsKey("retreat"), bribe = f.setup.ContainsKey("bribe");
            if (!retreat && !bribe) yield break;
            while (bosses.ContainsKey(f.id))
            {
                foreach (var p in f.parts.ToArray())
                {
                    if (p.eid == null || p.eid.dead || p.go == null || p.retreated) continue;
                    if (retreat && start.TryGetValue(p, out var h0) && p.eid.health <= h0 / 3f && p.go.GetComponent<V2>() != null)
                        StartCoroutine(Retreat(f, p));
                    if (bribe && CoinNear(p)) StartCoroutine(Bribed(f, p));
                }
                yield return new WaitForSeconds(0.1f);
            }
        }

        /// <summary>Gabriel's "Enough!" or a Prime's enraged half, at once.</summary>
        void SecondPhase(BossFight f)
        {
            foreach (var p in f.parts)
            {
                if (p.go == null || p.eid == null || p.eid.dead) continue;
                try
                {
                    var mp = p.go.GetComponentInChildren<MinosPrime>();
                    var sp = p.go.GetComponentInChildren<SisyphusPrime>();
                    var gb = p.go.GetComponentInChildren<GabrielBase>();
                    if (mp != null) mp.Enrage();
                    else if (sp != null) sp.Enrage();
                    else if (gb != null && !gb.secondPhase)
                    {
                        gb.secondPhase = true;
                        var voice = Traverse.Create(gb).Field("voice").GetValue<GabrielVoice>();
                        if (voice != null) voice.secondPhase = true;
                        Traverse.Create(gb).Method("JuggleStart").GetValue();
                    }
                    if (mp != null || sp != null || gb != null) Plugin.Log.LogInfo("boss " + f.id + ": " + f.key + " starts in its second phase");
                }
                catch (Exception e) { Plugin.Log.LogWarning("second phase: " + e.Message); }
            }
        }

        /// <summary>1-4: V2 knocked down, then gone in a flash; the fight is won.</summary>
        IEnumerator Retreat(BossFight f, BossPart p)
        {
            p.retreated = true;
            introHeld.Add(p.eid);
            var v2 = p.go.GetComponent<V2>();
            try { v2.KnockedOut("KnockedDown"); }
            catch (Exception e) { Plugin.Log.LogDebug("v2 knocked out: " + e.Message); }
            MonoSingleton<HudMessageReceiver>.Instance?.SendHudMessage("<color=#FF4343>V2</color> RETREATS", "", "", 0, true);
            yield return new WaitForSeconds(2.5f);
            if (p.go != null)
            {
                var at = EnemyBounds(p.eid).center;
                var flash = Lamp(at, new Color(0.6f, 0.8f, 1f), 30f * K, 10f);
                Destroy(flash.gameObject, 0.4f);
                PlayIntroSound("Assets/Sounds/Impacts/Explosion Wave.ogg", 0.6f);
                MonoSingleton<CameraController>.Instance?.CameraShake(0.6f);
                Destroy(p.go);
            }
            introHeld.Remove(p.eid);
            Plugin.Log.LogInfo("boss " + f.id + ": V2 retreated");
        }

        /// <summary>0-5: the second Cerberus sleeps (a statue, unhurtable) until the first is down to half.</summary>
        IEnumerator Stagger(BossFight f)
        {
            var first = f.parts[0];
            var second = f.parts[1];
            if (second.go == null || first.eid == null) yield break;
            var h = HoldForIntro(second.go, "cerberus");
            if (h == null) yield break;
            introHeld.Add(second.eid);
            Intros.TryGetValue("cerberus", out var def);
            var rac = def != null && def.anim != null ? LoadIntroAnim(def.anim) : null;
            StartIntroAnim(h, rac, def, true);
            float half = first.eid.health / 2f;
            while (bosses.ContainsKey(f.id) && first.eid != null && !first.eid.dead && first.eid.health > half && second.go != null) yield return null;
            if (second.go == null) yield break;
            // it wakes
            PlayIntroAnim(h, def, false);
            PlayIntroSound("Assets/Sounds/Enemies/statuecharge2.wav", 1f);
            MonoSingleton<HudMessageReceiver>.Instance?.SendHudMessage("THE SECOND <color=#FF4343>CERBERUS</color> AWAKENS", "", "", 0, true);
            float animLen = 2f;
            if (rac != null) foreach (var c in rac.animationClips) if (c.name == def.animState) animLen = c.length / def.animSpeedShort;
            yield return new WaitForSeconds(animLen);
            Release(h);
        }

        /// <summary>A Marksman coin by the Ferryman.</summary>
        static bool CoinNear(BossPart p)
        {
            var c = EnemyBounds(p.eid).center;
            foreach (var coin in UnityEngine.Object.FindObjectsOfType<Coin>())
                if (coin != null && (coin.transform.position - c).sqrMagnitude < (3.5f * K) * (3.5f * K))
                {
                    Destroy(coin.gameObject);
                    return true;
                }
            return false;
        }

        /// <summary>5-2: he takes the coin and lets V1 pass.</summary>
        IEnumerator Bribed(BossFight f, BossPart p)
        {
            p.retreated = true;
            f.bribed = true;
            introHeld.Add(p.eid);
            foreach (var b in p.go.GetComponentsInChildren<EnemyScript>()) b.enabled = false;
            PlayIntroSound("Assets/Sounds/Weapons/Coin Flip.wav", 1f);
            MonoSingleton<HudMessageReceiver>.Instance?.SendHudMessage("THE <color=#5AC8FF>FERRYMAN</color> TAKES YOUR COIN", "", "", 0, true);
            yield return new WaitForSeconds(1.5f);
            if (p.go != null)
            {
                var flash = Lamp(EnemyBounds(p.eid).center, new Color(0.4f, 0.8f, 1f), 25f * K, 8f);
                Destroy(flash.gameObject, 0.4f);
                PlayIntroSound("Assets/Sounds/Enemies/Spawns/PortalFerryman.wav", 0.8f);
                Destroy(p.go);
            }
            introHeld.Remove(p.eid);
        }

        static void ForgetSetups()
        {
            bossScale.Clear();
        }
    }
}
