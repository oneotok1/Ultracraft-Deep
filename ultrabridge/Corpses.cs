// UltraBridge: their corpses go the way ULTRAKILL's own Cyber Grind takes them.
//
// Two of ULTRAKILL's enemies die differently inside its own arena, and both differences hang on things that only
// exist there, so in our grind neither happens: a Malicious Face's corpse shatters the moment it hits the ground
// (in its arena the floor moves, which is what breaks the body), and a Gutterman blows itself up as it falls instead
// of lying there (its death asks whether the Cyber Grind is what it died in). A wave's enemy of either kind is
// marked when it is spawned; its corpse then takes that same path - the Face breaks on touching anything solid, the
// Gutterman explodes where ULTRAKILL would have it explode. Nothing else is modelled: the rest is ULTRAKILL's own
// code doing what it already does.

using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    /// <summary>A Cyber Grind wave's Malicious Face or Gutterman: its corpse goes ULTRAKILL's grind way.</summary>
    public class GrindCorpse : MonoBehaviour { }

    /// <summary>A Malicious Face's corpse shatters on the ground. In ULTRAKILL's own Cyber Grind it lands on a
    /// moving floor, and landing on one of those is what breaks the body; Minecraft's ground is still, so ours would
    /// lie there with its splat sprite. Anything solid breaks it here instead.</summary>
    [HarmonyPatch(typeof(MaliciousFace), "HandleCollision")]
    static class GrindFaceShatters
    {
        static bool Prefix(MaliciousFace __instance, Collision other)
        {
            if (__instance == null || other == null || other.gameObject == null) return true;
            if (__instance.GetComponentInParent<GrindCorpse>() == null) return true;
            if (!LayerMaskDefaults.IsMatchingLayer(other.gameObject.layer, LMD.Environment)) return true;
            __instance.BreakCorpse();
            return false;
        }
    }

    /// <summary>A Gutterman explodes as it falls dead. ULTRAKILL itself blows the body up at the end of the death
    /// animation when the Cyber Grind is where it died, and leaves it lying anywhere else; FallOver is where that
    /// happens, and inside our grind it takes that same branch.</summary>
    [HarmonyPatch(typeof(Gutterman), "FallOver")]
    static class GrindGutterExplodes
    {
        static readonly AccessTools.FieldRef<Gutterman, GameObject> FallEffect = AccessTools.FieldRefAccess<Gutterman, GameObject>("fallEffect");

        static bool Prefix(Gutterman __instance)
        {
            if (__instance == null || __instance.GetComponentInParent<GrindCorpse>() == null) return true;
            // the corpse effect is what ULTRAKILL checks before doing anything at all
            if (FallEffect(__instance) == null) return false;
            __instance.Explode();
            return false;
        }
    }
}
