using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>General's Hard Damage Recovery: hard damage (the health a hit puts out of reach for a while) clears
    /// faster, and the top three levels clear it outright from SSS, SS and S up.
    ///
    /// <para>ULTRAKILL runs the decay inline in NewMovement.Update (the cooldown first, then the hard damage itself,
    /// at a rate that depends on the style rank), so there is no method to patch: this runs after Update and takes a
    /// second bite out of whatever is left, at (multiplier - 1) times the same rate ULTRAKILL just used. The effect is
    /// the same as if the whole thing had run faster, without touching ULTRAKILL's own numbers.</para></summary>
    [HarmonyPatch(typeof(NewMovement), "Update")]
    static class HardDamageRecovery
    {
        static readonly AccessTools.FieldRef<NewMovement, float> Cooldown = AccessTools.FieldRefAccess<NewMovement, float>("antiHpCooldown");

        static void Postfix(NewMovement __instance)
        {
            if (!Bridge.AllWeapons || __instance == null || __instance.dead) return;
            int level = Bridge.UpLevel("gen.recovery");
            if (level <= 1) return;
            var hud = MonoSingleton<StyleHUD>.Instance;
            if (hud == null) return;
            int rank = hud.rankIndex;

            // 13, 14 and 15 clear it outright from SSS (6), SS (5) and S (4) up. ULTRAKILL already does this at U (7),
            // and a hard-damage forcer (a scene's own, never in Minecraft) can hold it back.
            if (Bridge.HardDamageClears(__instance))
            {
                __instance.antiHp = 0f;
                Cooldown(__instance) = 0f;
                // the red screen is ULTRAKILL's own way of saying hard damage went up: with none left to show, it has
                // no reason to stay lit (the hook's feed calls it every frame, so it would never fade on its own)
                if (__instance.antiHpFlash != null) __instance.antiHpFlash.Flash(0f);
                return;
            }

            float extra = Bridge.RecoverySpeed(level) - 1f;
            if (extra <= 0f) return;
            float dt = Time.deltaTime;
            if (Cooldown(__instance) > 0f)
                Cooldown(__instance) = Mathf.MoveTowards(Cooldown(__instance), 0f, dt * extra * (rank >= 4 ? (float)(rank / 2) : 1f));
            else if (__instance.antiHp > 0f)
                __instance.antiHp = Mathf.MoveTowards(__instance.antiHp, 0f, dt * extra * (rank >= 4 ? (float)rank * 10f : 15f));
        }
    }

    /// <summary>The same levels, one step earlier: while hard damage is being cleared outright, whatever tries to put
    /// it back does nothing at all, so the red screen never lights up in the first place (the Whiplash hook adds a
    /// little every frame it holds something, and each of those flashed the screen).</summary>
    [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.ForceAntiHP))]
    static class HardDamageBlocked
    {
        static bool Prefix(NewMovement __instance) => !__instance || !Bridge.HardDamageClears(__instance);
    }
}
