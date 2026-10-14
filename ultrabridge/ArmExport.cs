// UltraBridge: V1's real arm for Minecraft's hands. When V1 holds Minecraft things (Minecraft hands, V), Minecraft
// draws the arm holding them; this writes out ULTRAKILL's own Feedbacker arm for it, posed as ULTRAKILL shows it in
// first person: its meshes in the camera's space, their textures, where the hand is, and the view's field of view
// (%TEMP%/ultracraft_arm.*). Nothing of ULTRAKILL's ships with the mod; until this has run, Minecraft draws a stand-in.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        static readonly AccessTools.FieldRef<FistControl, List<GameObject>> SpawnedArms = AccessTools.FieldRefAccess<FistControl, List<GameObject>>("spawnedArms");

        bool armExported;
        float nextArmTry;
        string armInfo = "not yet";

        string ArmInfo() => armInfo;

        void UpdateArmExport()
        {
            if (armExported || !levelPrepared || !v1Landed || Time.unscaledTime < nextArmTry) return;
            nextArmTry = Time.unscaledTime + 5f;
            try
            {
                if (ExportArm()) armExported = true;
            }
            catch (Exception e)
            {
                armInfo = "failed: " + e.Message;
                Plugin.Log.LogWarning("V1 arm export: " + e);
                armExported = true;
            }
        }

        class ArmPart
        {
            public readonly List<Vector3> pos = new List<Vector3>();
            public readonly List<Vector3> nrm = new List<Vector3>();
            public readonly List<Vector2> uv = new List<Vector2>();
            public readonly List<int> idx = new List<int>();
            public Texture tex;
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
            // best of all the arm itself, out and posed by ULTRAKILL right now
            if (arm.activeInHierarchy) return WriteArm(arm, arm.transform.worldToLocalMatrix, toCam, arm.name + " (live)");
            // a copy, posed by its own animator in its resting pose, far from everything
            var holder = new GameObject("Ultracraft arm export");
            holder.SetActive(false);
            holder.transform.position = new Vector3(0f, -6000f, 0f);
            var copy = Instantiate(arm, holder.transform);
            copy.transform.localPosition = Vector3.zero;
            copy.transform.localRotation = Quaternion.identity;
            copy.transform.localScale = Vector3.one;
            foreach (var mb in copy.GetComponentsInChildren<MonoBehaviour>(true)) DestroyImmediate(mb);
            foreach (var au in copy.GetComponentsInChildren<AudioSource>(true)) DestroyImmediate(au);
            foreach (var lt in copy.GetComponentsInChildren<Light>(true)) DestroyImmediate(lt);
            copy.SetActive(true);
            holder.SetActive(true);
            var anim = copy.GetComponentInChildren<Animator>();
            if (anim != null)
            {
                anim.cullingMode = AnimatorCullingMode.AlwaysAnimate;
                anim.Update(0f);
            }
            bool ok = WriteArm(copy, copy.transform.worldToLocalMatrix, toCam, arm.name + " (copy)");
            Destroy(holder);
            return ok;
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
                var mats = r.sharedMaterials;
                for (int sub = 0; sub < mesh.subMeshCount && sub < mats.Length; sub++)
                {
                    var mat = mats[sub];
                    var tex = mat != null && mat.HasProperty("_MainTex") ? mat.mainTexture : null;
                    if (tex == null) continue;
                    var part = new ArmPart { tex = tex };
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
            foreach (var part in parts) if (!texIndex.Contains(part.tex)) texIndex.Add(part.tex);
            for (int i = 0; i < texIndex.Count; i++) SaveTexture(texIndex[i], Path.Combine(dir, "ultracraft_arm_" + i + ".png"));
            File.WriteAllText(Path.Combine(dir, "ultracraft_arm.txt"), string.Format(CultureInfo.InvariantCulture, "{0} {1} {2} {3}", fov, hand.x, hand.y, hand.z));
            using (var w = new BinaryWriter(File.Create(Path.Combine(dir, "ultracraft_arm.bin"))))
            {
                w.Write(parts.Count);
                foreach (var part in parts)
                {
                    w.Write(part.pos.Count);
                    w.Write(part.idx.Count);
                    w.Write(texIndex.IndexOf(part.tex));
                    foreach (var v in part.pos) { w.Write(v.x); w.Write(v.y); w.Write(v.z); }
                    foreach (var v in part.nrm) { w.Write(v.x); w.Write(v.y); w.Write(v.z); }
                    foreach (var v in part.uv) { w.Write(v.x); w.Write(v.y); }
                    foreach (var i in part.idx) w.Write(i);
                }
            }
            int verts2 = 0;
            foreach (var part in parts) verts2 += part.pos.Count;
            armInfo = label + " box " + lo.ToString("F2") + ".." + hi.ToString("F2") + ": " + parts.Count + " parts, " + verts2 + " vertices, " + texIndex.Count + " textures, hand " + hand.ToString("F3") + ", z "
                      + minZ.ToString("F2") + ".." + maxZ.ToString("F2") + ", fov " + fov.ToString("F0");
            Plugin.Log.LogInfo("V1 arm written for Minecraft: " + armInfo);
            return true;
        }
    }
}
