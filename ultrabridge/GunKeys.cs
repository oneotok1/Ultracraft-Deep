using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>A gun key hands V1 one exact weapon; the first switch away from it puts the shop's own choice back.
    ///
    /// <para>Every way of changing weapon - the number keys, the wheel, last weapon, next variation, the shop - ends in
    /// GunControl.SwitchWeapon, so that is where the temporary version is dropped: the shop's equipment was never
    /// touched (the key only ever wrote a version of its own), so clearing it and rebuilding is all it takes.</para>
    /// </summary>
    [HarmonyPatch(typeof(GunControl), nameof(GunControl.SwitchWeapon))]
    static class GunKeySwitch
    {
        /// <summary>Set while the bridge itself is switching (a gun key), which must not undo its own version.</summary>
        internal static bool Switching;

        static void Prefix()
        {
            if (Switching) return;
            if (Bridge.I == null || !Bridge.I.ClearTempVersions()) return;
            // the version went into the prefab ULTRAKILL builds, so the guns have to be built again to go back
            MonoSingleton<GunSetter>.Instance?.ResetWeapons();
        }
    }

    /// <summary>The same for the paths that skip SwitchWeapon (a forced weapon, a shop handing one over).</summary>
    [HarmonyPatch(typeof(GunControl), nameof(GunControl.ForceWeapon))]
    static class GunKeyForce
    {
        static void Prefix()
        {
            if (GunKeySwitch.Switching) return;
            Bridge.I?.ClearTempVersions();
        }
    }
}
