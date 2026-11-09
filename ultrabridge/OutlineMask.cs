using System;
using System.Collections.Generic;
using HarmonyLib;
using Unity.Collections;
using Unity.Collections.LowLevel.Unsafe;
using UnityEngine;
using UnityEngine.Rendering;

namespace UltraBridge
{
    /// <summary>
    /// ULTRAKILL draws its enemy outline by multiplying the frame with a dilated silhouette mask, which leaves the
    /// ring at alpha 0. Minecraft only shows pixels whose mask alpha is not 0, so the ring never reaches it. This
    /// keeps a copy of the raw silhouette (the second MRT target, before the jump-flood pass consumes it) so the
    /// bridge can turn the ring into alpha itself.
    /// </summary>
    static class OutlineMask
    {
        /// <summary>The target ULTRAKILL's enemy shaders write their silhouette into. Captured once, because
        /// SetupOutlines swaps its two ping-pong fields around every frame and only the original object is the
        /// MRT target.</summary>
        static RenderTexture raw;
        static RenderTexture copy;
        static CommandBuffer cb;
        static Camera boundCam;
        static RenderTexture boundSrc;
        static bool warned;

        [HarmonyPatch(typeof(PostProcessV2_Handler), nameof(PostProcessV2_Handler.SetupOutlines))]
        static class CaptureRaw
        {
            static void Prefix(PostProcessV2_Handler __instance)
            {
                try
                {
                    var rt = __instance.reusableBufferA;
                    var main = __instance.mainTex;
                    if (rt == null || main == null) return;
                    // The MRT writes into whichever buffer the pair started with, so the first frame of a render-target
                    // generation is the only one where the field still names it. Later frames have swapped A and B, so
                    // the value is kept as long as it is still one of the two; SetupRTs() builds a fresh pair, which is
                    // how a genuinely stale capture is recognised.
                    bool stale = raw == null || !raw.IsCreated() || raw.width != main.width || raw.height != main.height;
                    if (!stale) stale = raw != rt && raw != __instance.reusableBufferB;
                    if (stale) raw = rt;
                }
                catch (Exception e)
                {
                    if (!warned) { warned = true; Plugin.Log.LogWarning("outline mask capture failed: " + e); }
                }
            }
        }

        /// <summary>Thickness the game would use for its own outline, or 0 when outlines are off.</summary>
        internal static int Radius()
        {
            try
            {
                var oman = MonoSingleton<OptionsManager>.Instance;
                if (oman == null || !oman.simplifyEnemies) return 0;
                var prefs = MonoSingleton<PrefsManager>.Instance;
                if (prefs == null) return 0;
                int r = prefs.GetInt("outlineThickness");
                // 0 is off, not "as thin as the slider goes": a recoloured enemy has no need of a ring at all
                return r < 1 ? 0 : r > 16 ? 16 : r;
            }
            catch { return 0; }
        }

        /// <summary>Arms the copy for this frame and returns the texture to read back, or null when there is nothing
        /// to copy. The copy has to be taken before the jump-flood pass, which overwrites the silhouette.</summary>
        internal static RenderTexture Begin(PostProcessV2_Handler pp)
        {
            try
            {
                if (pp == null || raw == null || !raw.IsCreated()) return null;
                var cam = pp.mainCam;
                if (cam == null || raw.width <= 0 || raw.height <= 0) return null;

                if (copy == null || copy.width != raw.width || copy.height != raw.height)
                {
                    if (copy != null) { copy.Release(); UnityEngine.Object.Destroy(copy); }
                    copy = new RenderTexture(raw.width, raw.height, 0, RenderTextureFormat.ARGB32) { name = "UltraBridge outline" };
                    copy.Create();
                    boundCam = null;
                }

                if (boundCam != cam || boundSrc != raw)
                {
                    if (boundCam != null && cb != null) boundCam.RemoveCommandBuffer(CameraEvent.AfterForwardAlpha, cb);
                    cb = new CommandBuffer { name = "UltraBridge outline copy" };
                    cb.Blit(raw, copy);
                    boundCam = cam;
                    boundSrc = raw;
                }
                // SetupRTs() drops every AfterForwardAlpha buffer when it rebuilds its targets, so the copy is
                // re-armed each frame rather than once per target pair.
                if (boundCam != null && cb != null)
                {
                    boundCam.RemoveCommandBuffer(CameraEvent.AfterForwardAlpha, cb);
                    boundCam.AddCommandBuffer(CameraEvent.AfterForwardAlpha, cb);
                }
                int radius = Radius();
                if (radius != boxRadius || Time.unscaledTime - boxAt > 0.25f)
                {
                    boxRadius = radius;
                    boxAt = Time.unscaledTime;
                    CollectBoxes(cam, radius);
                    FillCells(raw.width, raw.height, radius);
                }
                return copy;
            }
            catch (Exception e)
            {
                if (!warned) { warned = true; Plugin.Log.LogWarning("outline mask begin failed: " + e); }
                return null;
            }
        }

        /// <summary>Screen rectangles the outline may come from: the enemies themselves. The silhouette buffer is
        /// written by every shader that has an outline pass, which is not the same set as "an enemy": a projectile, a
        /// flame or an explosion writes there too, and those came out outlined as well. A coarse grid is filled from
        /// the enemies' own bounds (widened by the ring) and the ring is only laid down inside it.</summary>
        static bool[] cells;
        static bool[] burned;
        static int cellsW, cellsH;
        const int Cell = 16;
        static int boxRadius = -1;
        static float boxAt = -999f;
        static readonly List<Rect> boxes = new List<Rect>();
        static readonly List<Rect> fires = new List<Rect>();

        /// <summary>The union of the screen rectangles the last collect found, in pixels, or an empty rect when
        /// there were none. Merge walks only this instead of scanning the whole frame for the silhouette.</summary>
        static Rect boxBounds;
        /// <summary>Frame size the current cells/boxBounds were worked out for, so a resize re-collects at once.</summary>
        static int cellsFrameW, cellsFrameH;

        /// <summary>Union of a list of screen rectangles, grown by <paramref name="grow"/> and clamped to the frame.</summary>
        static Rect BoundsOf(List<Rect> from, float grow, int w, int h)
        {
            if (from == null || from.Count == 0) return new Rect(0, 0, 0, 0);
            float x0 = float.MaxValue, y0 = float.MaxValue, x1 = float.MinValue, y1 = float.MinValue;
            foreach (var r in from)
            {
                if (r.xMin < x0) x0 = r.xMin;
                if (r.yMin < y0) y0 = r.yMin;
                if (r.xMax > x1) x1 = r.xMax;
                if (r.yMax > y1) y1 = r.yMax;
            }
            x0 = Mathf.Max(0f, x0 - grow);
            y0 = Mathf.Max(0f, y0 - grow);
            x1 = Mathf.Min(w, x1 + grow);
            y1 = Mathf.Min(h, y1 + grow);
            if (x1 <= x0 || y1 <= y0) return new Rect(0, 0, 0, 0);
            return Rect.MinMaxRect(x0, y0, x1, y1);
        }

        static void CollectBoxes(Camera cam, int radius)
        {
            boxes.Clear();
            fires.Clear();
            if (cam == null) return;
            var eids = UnityEngine.Object.FindObjectsOfType<EnemyIdentifier>();
            foreach (var eid in eids)
            {
                if (eid == null || eid.dead) continue;
                foreach (var r in eid.GetComponentsInChildren<Renderer>())
                {
                    if (r == null || !r.enabled || !r.gameObject.activeInHierarchy) continue;
                    AddBox(cam, r.bounds, boxes, radius);
                }
            }

            // a burning enemy's flames are not its children (Flammable sets the fire loose as soon as it is got),
            // so the bounds above never cover them, and the fire writes to the silhouette buffer itself: it came
            // out with a ring around it too. Its own cells are kept clear instead, so no ring is laid there.
            // The fire is particles, and particles reach past the renderer's bounds by more than the ring does, so
            // the clear area is grown by the ring twice over: a ring laid in the glow just outside the bounds was
            // what came back as "the flames are outlined again".
            float fireGrow = radius * 2f + 12f;
            foreach (var fl in UnityEngine.Object.FindObjectsOfType<Flammable>())
            {
                if (fl == null || fl.currentFire == null) continue;
                if (!fl.currentFire.activeInHierarchy) continue;
                foreach (var r in fl.currentFire.GetComponentsInChildren<Renderer>())
                {
                    if (r == null || !r.enabled) continue;
                    AddBox(cam, r.bounds, fires, fireGrow);
                }
            }

            // a Minecraft mob burning in daylight does not have a Flammable at all: UltraBridge puts a pooled fire of
            // ULTRAKILL's on it and keeps it on the stand-in (Fire.cs). The sweep above never sees that one, and the
            // flames came back outlined. The fire is sized from the mob's body and centred on it, so the body is
            // what gets cleared - not the particle renderer's bounds, which say nothing about where the flames are.
            var bridge = Bridge.I;
            if (bridge != null)
            {
                foreach (var p in bridge.ProxyList())
                {
                    if (p == null || p.fire == null || !p.fire.activeInHierarchy) continue;
                    // the fire is scaled from the mob's body, so its own renderers reach well past that body: clearing
                    // only the body left the outer half of the flames taking a ring. Its renderers are what is on
                    // screen, so they are what gets cleared - the body is the fallback when none report bounds.
                    bool any = false;
                    foreach (var r in p.fire.GetComponentsInChildren<Renderer>())
                    {
                        if (r == null || !r.enabled) continue;
                        AddBox(cam, r.bounds, fires, fireGrow);
                        any = true;
                    }
                    if (!any && p.body != null) AddBox(cam, p.body.bounds, fires, fireGrow);
                }
            }
        }

        /// <summary>Adds the on-screen rectangle of <paramref name="b"/> to <paramref name="into"/>, widened by the
        /// ring so the cells the ring would be drawn in are covered too. Skips bounds with a corner behind the
        /// camera: those project to nonsense and would mark half the screen.</summary>
        static void AddBox(Camera cam, Bounds b, List<Rect> into, float grow)
        {
            Vector3 c = b.center, e = b.extents;
            float x0 = float.MaxValue, y0 = float.MaxValue, x1 = float.MinValue, y1 = float.MinValue;
            for (int i = 0; i < 8; i++)
            {
                var p = c + new Vector3((i & 1) == 0 ? -e.x : e.x, (i & 2) == 0 ? -e.y : e.y, (i & 4) == 0 ? -e.z : e.z);
                var sp = cam.WorldToScreenPoint(p);
                if (sp.z <= 0f) return;
                if (sp.x < x0) x0 = sp.x;
                if (sp.y < y0) y0 = sp.y;
                if (sp.x > x1) x1 = sp.x;
                if (sp.y > y1) y1 = sp.y;
            }
            into.Add(Rect.MinMaxRect(x0 - grow - 2, y0 - grow - 2, x1 + grow + 2, y1 + grow + 2));
        }

        static void FillCells(int w, int h, int radius)
        {
            int cw = (w + Cell - 1) / Cell + 1, ch = (h + Cell - 1) / Cell + 1;
            if (cells == null || cellsW != cw || cellsH != ch)
            {
                cells = new bool[cw * ch];
                burned = new bool[cw * ch];
                cellsW = cw;
                cellsH = ch;
            }
            else
            {
                Array.Clear(cells, 0, cells.Length);
                Array.Clear(burned, 0, burned.Length);
            }
            Fill(cells, boxes);
            Fill(burned, fires);
            // the ring only ever lands near an enemy, so Merge can start from this instead of scanning the frame.
            // The collect is a quarter of a second old by the time the next one runs, and a fast enemy covers a few
            // dozen pixels in that time: the margin keeps a ring that has run ahead of the grid from being clipped.
            boxBounds = BoundsOf(boxes, 64f, w, h);
            cellsFrameW = w;
            cellsFrameH = h;
        }

        static void Fill(bool[] grid, List<Rect> from)
        {
            int cw = cellsW, ch = cellsH;
            foreach (var box in from)
            {
                int cx0 = Mathf.Clamp(Mathf.FloorToInt(box.xMin / Cell), 0, cw - 1);
                int cx1 = Mathf.Clamp(Mathf.FloorToInt(box.xMax / Cell), 0, cw - 1);
                int cy0 = Mathf.Clamp(Mathf.FloorToInt(box.yMin / Cell), 0, ch - 1);
                int cy1 = Mathf.Clamp(Mathf.FloorToInt(box.yMax / Cell), 0, ch - 1);
                for (int cy = cy0; cy <= cy1; cy++)
                    for (int cx = cx0; cx <= cx1; cx++)
                        grid[cy * cw + cx] = true;
            }
        }

        static bool Allowed(int x, int y)
        {
            if (cells == null) return true;
            int cx = x / Cell, cy = y / Cell;
            if (cx < 0 || cy < 0 || cx >= cellsW || cy >= cellsH) return false;
            int at = cy * cellsW + cx;
            return cells[at] && !burned[at];
        }

        static NativeArray<byte> scratch;
        static int scratchLen;

        /// <summary>Adds the outline ring to the mask's alpha: every pixel within <paramref name="radius"/> of an
        /// enemy, but not on the enemy itself, becomes opaque so Minecraft stops discarding it.</summary>
        internal static unsafe void Merge(NativeArray<byte> mask, NativeArray<byte> sil, int w, int h, int radius)
        {
            if (radius < 1 || w <= 0 || h <= 0 || !sil.IsCreated || !mask.IsCreated) return;
            if (sil.Length < w * h * 4 || mask.Length < w * h * 4) return;

            byte* s = (byte*)sil.GetUnsafePtr();
            long stride = (long)w * 4;

            // enemies usually cover a small part of the screen, so the passes below only walk their bounds. Those
            // bounds are already known: the same rectangles that decided which cells may take a ring. Scanning the
            // whole frame for them cost a megapixel-and-a-half read per frame whatever the enemy count was.
            int x0 = 0, y0 = 0, x1 = -1, y1 = -1;
            if (cellsFrameW == w && cellsFrameH == h)
            {
                var bb = boxBounds;
                x0 = Mathf.Clamp(Mathf.FloorToInt(bb.xMin) - radius, 0, w - 1);
                y0 = Mathf.Clamp(Mathf.FloorToInt(bb.yMin) - radius, 0, h - 1);
                x1 = Mathf.Clamp(Mathf.CeilToInt(bb.xMax) + radius, 0, w - 1);
                y1 = Mathf.Clamp(Mathf.CeilToInt(bb.yMax) + radius, 0, h - 1);
                if (bb.width <= 0f || bb.height <= 0f) return;
            }
            else
            {
                // no collect has run for this frame size yet: fall back to finding the silhouette the slow way
                x0 = w; y0 = h;
                for (int y = 0; y < h; y++)
                {
                    byte* row = s + y * stride;
                    for (int x = 0; x < w; x++)
                    {
                        if (Math.Max(row[x * 4], row[x * 4 + 1]) < 8 || !Allowed(x, y)) continue;
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                }
                if (x1 < 0) return;
                x0 = Math.Max(0, x0 - radius); y0 = Math.Max(0, y0 - radius);
                x1 = Math.Min(w - 1, x1 + radius); y1 = Math.Min(h - 1, y1 + radius);
            }

            int len = w * h;
            if (!scratch.IsCreated || scratchLen != len)
            {
                if (scratch.IsCreated) scratch.Dispose();
                scratch = new NativeArray<byte>(len, Allocator.Persistent);
                scratchLen = len;
            }
            byte* t = (byte*)scratch.GetUnsafePtr();
            // only the rows this frame walks need clearing: the horizontal pass below fills every pixel it reads
            // back out of this band, and the vertical pass only reads the band
            for (int y = y0; y <= y1; y++)
            {
                byte* row = t + (long)y * w;
                for (int x = x0; x <= x1; x++) row[x] = 0;
            }

            // Horizontal reach: a pixel is marked when an enemy sits within `radius` along its row. Walking each row
            // both ways and remembering the last enemy seen does it in one pass instead of one pass per radius.
            for (int y = y0; y <= y1; y++)
            {
                byte* row = s + y * stride;
                byte* dst = t + (long)y * w;
                int last = int.MinValue / 2;
                for (int x = x0; x <= x1; x++)
                {
                    // only an enemy's own pixels seed a ring: the buffer also holds what is not an enemy
                    if (Math.Max(row[x * 4], row[x * 4 + 1]) >= 8 && Allowed(x, y)) last = x;
                    if (x - last <= radius) dst[x] = 1;
                }
                last = int.MaxValue / 2;
                for (int x = x1; x >= x0; x--)
                {
                    if (Math.Max(row[x * 4], row[x * 4 + 1]) >= 8 && Allowed(x, y)) last = x;
                    if (last - x <= radius) dst[x] = 1;
                }
            }

            // Vertical reach, written straight into the mask's alpha. The enemy itself is skipped: the game only
            // darkens the ring around it, and the enemy's own pixels already carry alpha.
            byte* m = (byte*)mask.GetUnsafePtr();
            for (int x = x0; x <= x1; x++)
            {
                int last = int.MinValue / 2;
                for (int y = y0; y <= y1; y++)
                {
                    if (t[(long)y * w + x] != 0) last = y;
                    if (y - last > radius) continue;
                    if (!Allowed(x, y)) continue;
                    long p = y * stride + x * 4;
                    if (Math.Max(s[p], s[p + 1]) >= 8) continue;
                    m[p + 3] = 255;
                }
                last = int.MaxValue / 2;
                for (int y = y1; y >= y0; y--)
                {
                    if (t[(long)y * w + x] != 0) last = y;
                    if (last - y > radius) continue;
                    if (!Allowed(x, y)) continue;
                    long p = y * stride + x * 4;
                    if (Math.Max(s[p], s[p + 1]) >= 8) continue;
                    m[p + 3] = 255;
                }
            }
        }
    }
}
