// UltraBridge: V1's real arm for Minecraft's hands. When V1 holds Minecraft things (Minecraft hands, V), Minecraft
// draws the arm holding them; this writes out ULTRAKILL's own Feedbacker arm for it, posed as ULTRAKILL shows it in
// first person: its meshes in the camera's space, their textures, where the hand is, and the view's field of view
// (%TEMP%/ultracraft_arm.*). Nothing of ULTRAKILL's ships with the mod; until this has run, Minecraft draws a stand-in.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        static readonly AccessTools.FieldRef<FistControl, List<GameObject>> SpawnedArms = AccessTools.FieldRefAccess<FistControl, List<GameObject>>("spawnedArms");

        /// <summary>Sits where the mesh file's part list would start, so Minecraft can tell this layout from the older
        /// one that had no glow maps (and keeps reading a file ULTRAKILL wrote before an update).</summary>
        const int ArmMagic = 0x474C4F57;

        bool texNoted;

        /// <summary>A skin pack is a folder of PNGs named after the textures ULTRAKILL uses (T_MainArm, T_GreenArm and
        /// their _Emissive twins), so a re-skin can be read straight off disk by texture name. That is how the arm
        /// carries the look even when the skin mod never got its own chance to swap any material.</summary>
        static Dictionary<string, string> skinFiles;
        static readonly Dictionary<string, Texture2D> skinLoaded = new Dictionary<string, Texture2D>();

        /// <summary>A new scene brings new arm objects with ULTRAKILL's own materials: ask again, and let the export
        /// that follows see whatever the skin then puts on them.</summary>
        void ResetArmExport()
        {
            texNoted = false;
            matNames.Clear();
            armSig = null;
            nextArmTry = 0f;
            skinFiles = null;
            skinLoaded.Clear();
        }


        float nextArmTry;
        string armInfo = "not yet";
        string armSig;

        string ArmInfo() => armInfo;

        /// <summary>What the arm's materials point at right now. A re-skin swaps those textures after the scene has
        /// loaded, so an export taken once at landing can catch the arm before the skin is on it: the glow map then
        /// comes out empty and the arm stays black in Minecraft until the next launch happens to land the other way.
        /// Re-exporting whenever this changes keeps up with the skin instead of depending on that ordering.</summary>
        string ArmSignature()
        {
            var fc = MonoSingleton<FistControl>.Instance;
            var arms = fc != null ? SpawnedArms(fc) : null;
            if (arms == null) return null;
            GameObject arm = null;
            foreach (var a in arms)
            {
                var punch = a != null ? a.GetComponent<Punch>() : null;
                if (punch != null && punch.type == FistType.Standard) { arm = a; break; }
            }
            if (arm == null) arm = arms.Count > 0 ? arms[0] : null;
            if (arm == null) return null;
            var sig = new System.Text.StringBuilder();
            foreach (var r in arm.GetComponentsInChildren<Renderer>(true))
            {
                if (!r.enabled || !r.gameObject.activeInHierarchy) continue;
                foreach (var m in r.materials)
                {
                    if (m == null) { sig.Append("-;"); continue; }
                    var tex = m.HasProperty("_MainTex") ? m.mainTexture : null;
                    var glow = GlowOf(m);
                    sig.Append(tex != null ? tex.GetInstanceID().ToString() : "0").Append('/')
                       .Append(glow != null ? glow.GetInstanceID().ToString() : "0").Append(';');
                }
            }
            return sig.ToString();
        }

        /// <summary>True while the arm is mid-punch or holding something: the mesh would be baked in that pose, and
        /// Minecraft keeps it for good. Only the arm's resting pose is worth writing.</summary>
        static bool ArmBusy()
        {
            var fc = MonoSingleton<FistControl>.Instance;
            var arms = fc != null ? SpawnedArms(fc) : null;
            if (arms == null) return false;
            foreach (var a in arms)
            {
                var punch = a != null ? a.GetComponent<Punch>() : null;
                if (punch == null) continue;
                if (!punch.ready || punch.holding) return true;
                if (punch.anim != null && punch.anim.GetCurrentAnimatorStateInfo(0).IsName("Jab")) return true;
            }
            return false;
        }

        void UpdateArmExport()
        {
            if (!levelPrepared || !v1Landed || Time.unscaledTime < nextArmTry) return;
            nextArmTry = Time.unscaledTime + 5f;
            // a busy arm keeps the mesh it already wrote and is tried again on the next look
            if (ArmBusy()) return;
            string sig;
            try { sig = ArmSignature(); }
            catch (Exception e) { armInfo = "signature failed: " + e.Message; return; }
            if (sig == null || sig == armSig) return;
            try
            {
                if (ExportArm()) armSig = sig;
            }
            catch (Exception e)
            {
                armInfo = "failed: " + e.Message;
                Plugin.Log.LogWarning("V1 arm export: " + e);
            }
        }

        static readonly List<string> matNames = new List<string>();

        class ArmPart
        {
            public readonly List<Vector3> pos = new List<Vector3>();
            public readonly List<Vector3> nrm = new List<Vector3>();
            public readonly List<Vector2> uv = new List<Vector2>();
            public readonly List<int> idx = new List<int>();
            public Texture tex;
            public Texture glow;
        }

        /// <summary>A re-skin's looks live almost entirely in its glow map (the diffuse is left black), so the arm has
        /// to carry that along or it comes out a black shape.</summary>
        /// <summary>Where the applied skin pack keeps its PNGs. The skin mod records the chosen pack in its own save
        /// file; when that cannot be read, every folder under its GlobalSkins is treated as a pack.</summary>
        static string[] SkinPackFolders()
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var root = Path.Combine(appData, "bobthecorn2000", "ULTRAKILL");
            var dirs = new List<string>();
            foreach (var home in new[] { "ultraskinsGC-V2", Path.Combine("ultraskinsGC-V2", "GlobalSkins"), "ultraskinsGC" })
            {
                var baseDir = Path.Combine(root, home);
                if (!Directory.Exists(baseDir)) continue;
                foreach (var save in Directory.GetFiles(baseDir, "Data.USGC", SearchOption.AllDirectories))
                {
                    try
                    {
                        var text = File.ReadAllText(save);
                        int at = 0;
                        while ((at = text.IndexOf('"', at)) >= 0)
                        {
                            int close = text.IndexOf('"', at + 1);
                            if (close < 0) break;
                            var dir = text.Substring(at + 1, close - at - 1).Replace("\\\\", "\\");
                            at = close + 1;
                            if (dir.Length > 2 && dir[1] == ':' && Directory.Exists(dir)) dirs.Add(dir);
                        }
                    }
                    catch (Exception) { }
                }
                // no readable save: use every pack folder at once
                if (dirs.Count == 0)
                    foreach (var pack in Directory.GetDirectories(baseDir))
                        if (Directory.GetFiles(pack, "*.png").Length > 0) dirs.Add(pack);
            }
            return dirs.ToArray();
        }

        static void BuildSkinFiles()
        {
            skinFiles = new Dictionary<string, string>();
            foreach (var dir in SkinPackFolders())
            {
                foreach (var png in Directory.GetFiles(dir, "*.png"))
                    skinFiles[Path.GetFileNameWithoutExtension(png).ToLowerInvariant()] = png;
            }
        }

        /// <summary>The pack's own PNG for this texture name, or null when the pack does not re-skin it.</summary>
        static Texture2D SkinTexture(string name)
        {
            if (string.IsNullOrEmpty(name)) return null;
            if (skinFiles == null) BuildSkinFiles();
            if (!skinFiles.TryGetValue(name.ToLowerInvariant(), out var path)) return null;
            if (skinLoaded.TryGetValue(path, out var done)) return done;
            try
            {
                var tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                if (!tex.LoadImage(File.ReadAllBytes(path))) { Destroy(tex); return null; }
                skinLoaded[path] = tex;
                return tex;
            }
            catch (Exception e) { Plugin.Log.LogWarning("skin pack texture " + path + ": " + e.Message); return null; }
        }

        /// <summary>A skin pack leaves the diffuse black and puts its looks in the _Emissive twin.</summary>
        static Texture SkinGlow(string name)
        {
            return (Texture)SkinTexture(name + "_Emissive") ?? SkinTexture(name + "_Glow");
        }

        static Texture GlowOf(Material mat)
        {
            if (mat == null) return null;
            foreach (var prop in mat.GetTexturePropertyNames())
            {
                if (prop.IndexOf("Emiss", StringComparison.OrdinalIgnoreCase) < 0) continue;
                var t = mat.GetTexture(prop);
                if (t != null) return t;
            }
            return null;
        }

        bool ExportArm()
        {
            var fc = MonoSingleton<FistControl>.Instance;
            var cc = MonoSingleton<CameraController>.Instance;
            if (fc == null || cc == null) return false;
            var arms = SpawnedArms(fc);
            if (arms == null || arms.Count == 0) return false;
            // the Feedbacker: V1's own blue arm
            GameObject arm = null;
            foreach (var a in arms)
            {
                var punch = a != null ? a.GetComponent<Punch>() : null;
                if (punch != null && punch.type == FistType.Standard) { arm = a; break; }
            }
            if (arm == null) arm = arms[0];
            if (arm == null) return false;
            // where ULTRAKILL holds it in the view
            var toCam = cc.transform.worldToLocalMatrix * arm.transform.localToWorldMatrix;
            // only the arm ULTRAKILL has out and posed right now: a detached copy loses the bones its skin is bound to
            // and bakes to a single point, which is worse than no export at all (Minecraft then keeps the last good one)
            if (!arm.activeInHierarchy) return false;
            return WriteArm(arm, arm.transform.worldToLocalMatrix, toCam, arm.name + " (live)");
        }

        bool WriteArm(GameObject copy, Matrix4x4 rootInv, Matrix4x4 toCam, string label)
        {
            var cc = MonoSingleton<CameraController>.Instance;
            var arm = copy;
            var parts = new List<ArmPart>();
            var baked = new Mesh();
            foreach (var r in copy.GetComponentsInChildren<Renderer>(true))
            {
                if (!r.enabled || !r.gameObject.activeInHierarchy) continue;
                Mesh mesh;
                Matrix4x4 toRoot;
                if (r is SkinnedMeshRenderer smr)
                {
                    if (smr.sharedMesh == null) continue;
                    smr.BakeMesh(baked, true);
                    mesh = baked;
                    toRoot = rootInv * Matrix4x4.TRS(smr.transform.position, smr.transform.rotation, Vector3.one);
                }
                else if (r is MeshRenderer)
                {
                    var mf = r.GetComponent<MeshFilter>();
                    if (mf == null || mf.sharedMesh == null || !mf.sharedMesh.isReadable) continue;
                    mesh = mf.sharedMesh;
                    toRoot = rootInv * r.transform.localToWorldMatrix;
                }
                else continue;
                if (!mesh.isReadable && !(r is SkinnedMeshRenderer)) continue;
                var m = toCam * toRoot;
                var verts = mesh.vertices;
                var norms = mesh.normals;
                var uvs = mesh.uv;
                var mats = r.materials;
                for (int sub = 0; sub < mesh.subMeshCount && sub < mats.Length; sub++)
                {
                    var mat = mats[sub];
                    var tex = mat != null && mat.HasProperty("_MainTex") ? mat.mainTexture : null;
                    if (tex == null) continue;
                    // the pack re-skins by texture name; its files win over whatever ULTRAKILL put on the material
                    var skinName = tex.name ?? "";
                    var skin = SkinTexture(skinName);
                    var glow = GlowOf(mat);
                    var skinGlow = SkinGlow(skinName);
                    if (skin != null) tex = skin;
                    if (skinGlow != null) glow = skinGlow;
                    matNames.Add((mat.name ?? "?") + "/" + (tex.name ?? "") + "/" + tex.width + "x" + tex.height);
                    var part = new ArmPart { tex = tex, glow = glow };
                    var map = new Dictionary<int, int>();
                    foreach (int i in mesh.GetTriangles(sub))
                    {
                        if (!map.TryGetValue(i, out var j))
                        {
                            j = part.pos.Count;
                            map[i] = j;
                            part.pos.Add(m.MultiplyPoint3x4(verts[i]));
                            part.nrm.Add(norms.Length > i ? m.MultiplyVector(norms[i]).normalized : Vector3.up);
                            part.uv.Add(uvs.Length > i ? uvs[i] : Vector2.zero);
                        }
                        part.idx.Add(j);
                    }
                    if (part.idx.Count > 0) parts.Add(part);
                }
            }
            Destroy(baked);
            if (parts.Count == 0)
            {
                armInfo = "no meshes on " + arm.name;
                return true;
            }
            // the hand: the front of the arm (it reaches into the view), its middle
            float minZ = float.MaxValue, maxZ = float.MinValue;
            foreach (var part in parts) foreach (var v in part.pos) { minZ = Mathf.Min(minZ, v.z); maxZ = Mathf.Max(maxZ, v.z); }
            var lo = Vector3.one * float.MaxValue; var hi = Vector3.one * float.MinValue;
            foreach (var part in parts) foreach (var v in part.pos) { lo = Vector3.Min(lo, v); hi = Vector3.Max(hi, v); }
            // a collapsed bake (every vertex in one spot) would make Minecraft scale the arm to its minimum extent and
            // draw it enormous: keep the mesh it already has rather than write that
            var span = hi - lo;
            if (Mathf.Max(span.x, Mathf.Max(span.y, span.z)) < 0.01f)
            {
                armInfo = "collapsed bake (" + lo.ToString("F3") + "), kept the previous arm";
                Plugin.Log.LogWarning("V1 arm export skipped: " + armInfo);
                return false;
            }
            float cut = maxZ - (maxZ - minZ) * 0.2f;
            var hand = Vector3.zero;
            int n = 0;
            foreach (var part in parts) foreach (var v in part.pos) if (v.z >= cut) { hand += v; n++; }
            hand /= Mathf.Max(1, n);
            var pp = MonoSingleton<PostProcessV2_Handler>.Instance;
            float fov = pp != null && pp.hudCam != null ? pp.hudCam.fieldOfView : cc.cam != null ? cc.cam.fieldOfView : 90f;
            var dir = Path.GetTempPath();
            // the textures first: Minecraft reloads when the mesh file changes
            var texIndex = new List<Texture>();
            var glowIndex = new List<Texture>();
            foreach (var part in parts) if (!texIndex.Contains(part.tex)) texIndex.Add(part.tex);
            for (int i = 0; i < texIndex.Count; i++) SaveTexture(texIndex[i], Path.Combine(dir, "ultracraft_arm_" + i + ".png"));
            for (int i = 0; i < parts.Count; i++)
            {
                var g = parts[i].glow;
                if (g != null && !glowIndex.Contains(g)) glowIndex.Add(g);
            }
            for (int i = 0; i < glowIndex.Count; i++) SaveTexture(glowIndex[i], Path.Combine(dir, "ultracraft_arm_glow_" + i + ".png"));
            File.WriteAllText(Path.Combine(dir, "ultracraft_arm.txt"), string.Format(CultureInfo.InvariantCulture, "{0} {1} {2} {3}", fov, hand.x, hand.y, hand.z));
            using (var w = new BinaryWriter(File.Create(Path.Combine(dir, "ultracraft_arm.bin"))))
            {
                w.Write(parts.Count);
                // files written before skins carried a glow map go straight to the part count; this marks the newer
                // layout, where each part's diffuse index is followed by its glow index (-1 for none)
                w.Write(ArmMagic);
                foreach (var part in parts)
                {
                    w.Write(part.pos.Count);
                    w.Write(part.idx.Count);
                    w.Write(texIndex.IndexOf(part.tex));
                    w.Write(part.glow != null ? glowIndex.IndexOf(part.glow) : -1);
                    foreach (var v in part.pos) { w.Write(v.x); w.Write(v.y); w.Write(v.z); }
                    foreach (var v in part.nrm) { w.Write(v.x); w.Write(v.y); w.Write(v.z); }
                    foreach (var v in part.uv) { w.Write(v.x); w.Write(v.y); }
                    foreach (var i in part.idx) w.Write(i);
                }
            }
            int verts2 = 0;
            foreach (var part in parts) verts2 += part.pos.Count;
            if (!texNoted)
            {
                texNoted = true;
                var seen = new List<string>(matNames);
                if (skinFiles == null) BuildSkinFiles();
                Plugin.Log.LogInfo("arm textures: " + string.Join(" | ", seen.ToArray()) + " ;; skin files: " + skinFiles.Count
                    + " ;; packs: " + string.Join(" | ", SkinPackFolders()));
            }
            armInfo = label + " box " + lo.ToString("F2") + ".." + hi.ToString("F2") + ": " + parts.Count + " parts, " + verts2 + " vertices, " + texIndex.Count + " textures, " + glowIndex.Count + " glow, hand " + hand.ToString("F3") + ", z "
                      + minZ.ToString("F2") + ".." + maxZ.ToString("F2") + ", fov " + fov.ToString("F0");
            Plugin.Log.LogInfo("V1 arm written for Minecraft: " + armInfo);
            return true;
        }
    }
}
