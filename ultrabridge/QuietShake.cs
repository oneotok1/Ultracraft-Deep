// UltraBridge: the camera must not shake for the killing blow the void deals. ULTRAKILL sizes a hit's shake as
// damage / 20 and decays it at 3 a second, so a hit of hp * 10 + 999 leaves the screen shaking for a minute and
// more. Everywhere else the shake is ULTRAKILL's own and is left alone.

using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    [HarmonyPatch(typeof(CameraController), nameof(CameraController.CameraShake))]
    static class QuietShake
    {
        [HarmonyPriority(Priority.First)]
        static bool Prefix() => !Bridge.NoShake;
    }
}
