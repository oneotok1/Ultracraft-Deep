// UltraBridge: ULTRAKILL's own skies over the Cyber Grind's arenas (SKY <material address> | SKY -).
//
// V1's camera normally clears to nothing, so Minecraft's sky shows through. With a sky set it clears to one of
// ULTRAKILL's skybox materials (loaded from its Addressables, nothing of it shipped). The terrain occluders are drawn
// first and fill the depth buffer, so the skybox only lands where there is no Minecraft block: the sky.

using System;
using UnityEngine;
using UnityEngine.AddressableAssets;

namespace UltraBridge
{
    public partial class Bridge
    {
        string skyAddress;

        void HandleSky(string rest)
        {
            var addr = rest.Trim();
            skyAddress = addr == "-" || addr.Length == 0 ? null : addr;
            ApplySky();
        }

        /// <summary>The sky as Minecraft last asked (again once the level is ready).</summary>
        void ApplySky()
        {
            if (!levelPrepared) return;
            var cc = MonoSingleton<CameraController>.Instance;
            if (cc == null || cc.cam == null) return;
            Material mat = null;
            if (skyAddress != null)
            {
                try { mat = Addressables.LoadAssetAsync<Material>(skyAddress).WaitForCompletion(); }
                catch (Exception e) { Plugin.Log.LogWarning("sky " + skyAddress + ": " + e.Message); }
            }
            if (mat != null)
            {
                RenderSettings.skybox = mat;
                cc.cam.clearFlags = CameraClearFlags.Skybox;
                Plugin.Log.LogInfo("sky: " + skyAddress);
            }
            else
            {
                RenderSettings.skybox = null;
                cc.cam.clearFlags = CameraClearFlags.SolidColor;
                cc.cam.backgroundColor = new Color(0, 0, 0, 0);
                if (skyAddress != null) Plugin.Log.LogWarning("sky " + skyAddress + " didn't load");
            }
        }
    }
}
