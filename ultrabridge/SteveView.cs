// UltraBridge, part seven: Steve's view. Back as Steve (F8), ULTRAKILL's enemies stay: ULTRAKILL keeps running and
// draws its world from Steve's camera (STEVECAM, sent by Minecraft every frame), with V1's guns, arms and HUD hidden.
// V1's body (what the enemies chase) follows Steve around (STEVEPOS), and what would have hurt V1 hurts Steve
// instead (SHURT, to Minecraft). STEVE 1 / STEVE 0 switch it on and off.

using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        /// <summary>Steve is playing: ULTRAKILL draws from his camera, and V1 is only a body that follows him.</summary>
        public static bool SteveView;
        Vector3 steveEye;
        float steveYaw, stevePitch, steveFov = 70f;
        bool steveCam, hudWasOn = true;

        void SetSteveView(bool on)
        {
            if (on == SteveView) return;
            SteveView = on;
            steveCam = false;
            var cc = levelPrepared ? MonoSingleton<CameraController>.Instance : null;
            var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
            var pp = levelPrepared ? MonoSingleton<PostProcessV2_Handler>.Instance : null;
            if (cc != null) cc.enabled = !on;
            if (pp != null && pp.hudCam != null)
            {
                if (on) hudWasOn = pp.hudCam.enabled;
                pp.hudCam.enabled = !on && hudWasOn;
            }
            if (nm != null && nm.rb != null)
            {
                nm.rb.isKinematic = on;
                if (!on) nm.rb.velocity = Vector3.zero;
            }
            Plugin.Log.LogInfo("Steve's view " + (on ? "on" : "off"));
        }

        /// <summary>STEVE, STEVECAM and STEVEPOS (from Settings' message handling).</summary>
        bool HandleSteve(string cmd, string rest)
        {
            switch (cmd)
            {
                case "STEVE":
                    SetSteveView(rest.Trim() == "1");
                    return true;
                case "STEVECAM":
                {
                    // STEVECAM x y z yaw pitch fov: Steve's eye (Minecraft coordinates) and view
                    var a = rest.Split(' ');
                    if (a.Length < 6) return true;
                    steveEye = new Vector3(F(a[0]), F(a[1]), F(a[2]));
                    steveYaw = F(a[3]);
                    stevePitch = F(a[4]);
                    steveFov = F(a[5]);
                    steveCam = true;
                    return true;
                }
                case "STEVEPOS":
                {
                    // STEVEPOS x y z: Steve's feet; V1's body (the enemies' target) stands there
                    var a = rest.Split(' ');
                    var nm = levelPrepared && originSet ? MonoSingleton<NewMovement>.Instance : null;
                    // down in a boss fight: V1's body lies where it fell, not with the camera watching a teammate
                    if (!SteveView || nm == null || a.Length < 3 || downed) return true;
                    var p = McToUk(new Vector3(F(a[0]), F(a[1]), F(a[2]))) + Vector3.up * 1.5f;
                    nm.transform.position = p;
                    nm.rb.position = p;
                    return true;
                }
            }
            return false;
        }

        /// <summary>Every frame while Steve plays: ULTRAKILL's camera where Minecraft's is.</summary>
        void UpdateSteveCamera()
        {
            if (!SteveView || !steveCam || !originSet) return;
            var cc = MonoSingleton<CameraController>.Instance;
            if (cc == null || cc.cam == null) return;
            if (cc.enabled) cc.enabled = false;
            var t = cc.cam.transform;
            t.position = McToUk(steveEye);
            t.rotation = Quaternion.Euler(stevePitch, steveYaw - 180f, 0f);
            cc.cam.fieldOfView = steveFov;
        }
    }

    /// <summary>Steve's view: what would hurt V1 hurts Steve (Minecraft scales it to hearts).</summary>
    [HarmonyPatch(typeof(NewMovement), nameof(NewMovement.GetHurt))]
    static class SteveTakesHits
    {
        [HarmonyPriority(Priority.First)]
        static bool Prefix(int damage)
        {
            if (!Bridge.SteveView) return true;
            if (damage > 0) Net.Send("SHURT " + damage);
            return false;
        }
    }
}
