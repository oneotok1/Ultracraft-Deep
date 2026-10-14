// UltraBridge: Minecraft's third-person views (F5) as V1. ULTRAKILL's camera moves back behind V1 (or round in front
// of it), V1's own body stands where V1 is, and the first-person guns and arms are hidden. Minecraft keeps drawing its
// world from the view each frame was drawn from, so the two still line up.

using System;
using HarmonyLib;
using UnityEngine;
using UnityEngine.Rendering;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>0: first person; 1: behind V1; 2: in front of V1, looking back (Minecraft's F5 cycle).</summary>
        public static int ViewMode;
        /// <summary>Which way V1 looks (ULTRAKILL's yaw), taken before the camera moves away.</summary>
        public static float SelfYaw;
        GameObject selfBody;
        Animator selfAnim;
        int hudMaskBefore = -1;

        void SetView(int view)
        {
            view = Mathf.Clamp(view, 0, 2);
            if (view == ViewMode) return;
            ViewMode = view;
            // the first-person guns and arms (ULTRAKILL's AlwaysOnTop layer, drawn by the HUD camera) go in third person
            var pp = levelPrepared ? MonoSingleton<PostProcessV2_Handler>.Instance : null;
            if (pp != null && pp.hudCam != null)
            {
                if (view != 0)
                {
                    if (hudMaskBefore < 0) hudMaskBefore = pp.hudCam.cullingMask;
                    pp.hudCam.cullingMask = hudMaskBefore & ~(1 << 13);
                }
                else if (hudMaskBefore >= 0)
                {
                    pp.hudCam.cullingMask = hudMaskBefore;
                    hudMaskBefore = -1;
                }
            }
            if (view == 0 && selfBody != null) selfBody.SetActive(false);
            Plugin.Log.LogInfo("view " + view);
        }

        /// <summary>V1's own body where V1 is, seen in third person: the platformer V1 of 4-S, as for other players.</summary>
        void UpdateSelfBody()
        {
            if (ViewMode == 0 || SteveView || !originSet) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm == null) return;
            if (selfBody == null && !BuildSelfBody()) return;
            if (!selfBody.activeSelf) selfBody.SetActive(true);
            var col = nm.GetComponent<CapsuleCollider>();
            var b = col != null ? col.bounds : new Bounds(nm.transform.position, Vector3.one);
            selfBody.transform.position = new Vector3(b.center.x, b.min.y, b.center.z);
            selfBody.transform.rotation = Quaternion.Euler(0f, SelfYaw, 0f);
            if (selfAnim != null && nm.rb != null)
            {
                var v = nm.rb.velocity;
                selfAnim.SetBool("Running", new Vector2(v.x, v.z).magnitude > 2f * K);
                selfAnim.SetBool("InAir", nm.gc != null && !nm.gc.touchingGround);
            }
            if (Time.frameCount % 10 == 0) LightUp(selfBody, LightNear(selfBody.transform.position), 0.5f);
        }

        bool BuildSelfBody()
        {
            try
            {
                var pt = MonoSingleton<PlayerTracker>.Instance;
                var prefab = pt != null ? pt.platformerPlayerPrefab : null;
                var pm = prefab != null ? prefab.GetComponentInChildren<PlatformerMovement>(true) : null;
                if (pm == null) return false;
                selfBody = new GameObject("Ultracraft V1 body (third person)");
                var holder = new GameObject("holder");
                holder.SetActive(false);
                holder.transform.SetParent(selfBody.transform, false);
                var body = Instantiate(pm.gameObject, holder.transform);
                body.transform.localPosition = Vector3.zero;
                body.transform.localRotation = Quaternion.identity;
                foreach (var c in body.GetComponentsInChildren<MonoBehaviour>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Joint>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Collider>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Rigidbody>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<AudioSource>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Camera>(true)) DestroyImmediate(c);
                foreach (var c in body.GetComponentsInChildren<Light>(true)) DestroyImmediate(c);
                foreach (var r in body.GetComponentsInChildren<Renderer>(true))
                {
                    if (!(r is SkinnedMeshRenderer) && !(r is MeshRenderer)) r.enabled = false;
                    r.shadowCastingMode = ShadowCastingMode.Off;
                }
                foreach (var tr in body.GetComponentsInChildren<Transform>(true)) tr.gameObject.layer = 0;
                holder.SetActive(true);
                // Minecraft's height for a player (1.8 blocks)
                var bb = new Bounds();
                bool any = false;
                foreach (var r in body.GetComponentsInChildren<Renderer>())
                {
                    if (!r.enabled) continue;
                    if (!any) { bb = r.bounds; any = true; }
                    else bb.Encapsulate(r.bounds);
                }
                if (any && bb.size.y > 0.1f) body.transform.localScale *= 1.8f * K / bb.size.y;
                selfAnim = body.GetComponent<Animator>();
                if (selfAnim != null) selfAnim.cullingMode = AnimatorCullingMode.AlwaysAnimate;
                return true;
            }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("V1 third-person body: " + e.Message);
                return false;
            }
        }
    }

    /// <summary>After ULTRAKILL's camera has placed itself at V1's eye: back four blocks (or round to the front), stopped
    /// short of walls, as Minecraft's own third-person camera. Put back before it runs again, so its head bob and
    /// smoothing carry on from where it really was.</summary>
    [HarmonyPatch(typeof(CameraController), "LateUpdate")]
    static class ThirdPersonCamera
    {
        static Vector3 savedPos;
        static Quaternion savedRot;
        static bool moved;

        static void Prefix(CameraController __instance)
        {
            if (!moved) return;
            moved = false;
            __instance.transform.localPosition = savedPos;
            __instance.transform.localRotation = savedRot;
        }

        static void Postfix(CameraController __instance)
        {
            var t = __instance.transform;
            Bridge.SelfYaw = t.eulerAngles.y;
            if (Bridge.ViewMode == 0 || Bridge.SteveView || !__instance.enabled) return;
            savedPos = t.localPosition;
            savedRot = t.localRotation;
            moved = true;
            var eye = t.position;
            var fwd = t.forward;
            var dir = Bridge.ViewMode == 1 ? -fwd : fwd;
            float dist = 4f * Bridge.K;
            if (Physics.SphereCast(eye, 0.1f * Bridge.K, dir, out var hit, dist, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore))
                dist = Mathf.Max(0.2f * Bridge.K, hit.distance - 0.05f * Bridge.K);
            t.position = eye + dir * dist;
            if (Bridge.ViewMode == 2) t.rotation = Quaternion.LookRotation(-fwd, t.up);
        }
    }
}
