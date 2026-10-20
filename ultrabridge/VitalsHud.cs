// UltraBridge, part nine: the health bar, for a V1 whose maximum moved. Vanilla's bar reads 0-100 and prints "/100"
// (or "/200" on Harmless) in its hard-damage readout; both are the same fixed full health the transpilers in
// Vitals.cs replace. This does the same for the widgets, and draws Steve's absorption hearts beside them the way
// GpVCTweaks draws its leech pool: a "+N" in light blue and a dark blue stretch of bar right after the fill.

using HarmonyLib;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace UltraBridge
{
    static class VitalsHud
    {
        [HarmonyPatch(typeof(HealthBar), "Update")]
        static class HealthBarUpdate
        {
            [HarmonyPostfix]
            static void Postfix(HealthBar __instance)
            {
                try { Draw(__instance); }
                catch (System.Exception) { } // one frame's HUD trouble is not worth a broken frame
            }
        }

        static void Draw(HealthBar hb)
        {
            if (hb == null || hb.hpText == null) return;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (nm == null) return;
            int e = Mathf.Max(1, Bridge.UkMax);
            SetMax(hb.hpSliders, e);
            SetMax(hb.afterImageSliders, e);
            if (hb.antiHpSlider != null && !Mathf.Approximately(hb.antiHpSlider.maxValue, e)) hb.antiHpSlider.maxValue = e;

            if (hb.antiHpText)
            {
                // "/N" is full health, less the hard damage that cannot be healed back
                string want = Difficulty() == 0 ? "/" + e : "/" + (e - Mathf.RoundToInt(nm.antiHp));
                if (hb.hpText.text != want) hb.hpText.text = want;
                return;
            }

            Transform block = hb.transform.parent;
            if (block == null) return;
            var marker = block.GetComponent<AbsorbMarker>();
            if (marker == null) marker = block.gameObject.AddComponent<AbsorbMarker>();
            marker.UpdateAbsorb(hb, nm.hp, Bridge.AbsorbHp, e);
        }

        static void SetMax(Slider[] sliders, int max)
        {
            if (sliders == null) return;
            for (int i = 0; i < sliders.Length; i++)
                if (sliders[i] != null && !Mathf.Approximately(sliders[i].maxValue, max)) sliders[i].maxValue = max;
        }

        static int Difficulty()
        {
            var pm = MonoSingleton<PrefsManager>.Instance;
            return pm != null ? pm.GetInt("difficulty") : 2;
        }

        internal static bool Classic()
        {
            var pm = MonoSingleton<PrefsManager>.Instance;
            if (pm == null) return false;
            int t = pm.GetInt("hudType");
            return t == 2 || t == 3;
        }
    }

    /// <summary>Steve's absorption hearts, drawn beside the health bar.</summary>
    public class AbsorbMarker : MonoBehaviour
    {
        TMP_Text label;
        RectTransform labelRect;
        Image bar;
        RectTransform barRect;
        string shown = "";

        public void UpdateAbsorb(HealthBar hb, float hp, float absorb, int max)
        {
            TMP_Text refText = hb.hpText;
            if (refText == null) return;
            Ensure(refText);
            if (label == null) return;
            int n = Mathf.RoundToInt(absorb);
            if (n > 0)
            {
                string want = "+" + n;
                if (shown != want)
                {
                    label.text = want;
                    shown = want;
                }
                float size = VitalsHud.Classic() ? 24f : refText.fontSize;
                if (!Mathf.Approximately(label.fontSize, size)) label.fontSize = size;
                if (label.fontSharedMaterial != refText.fontSharedMaterial) label.fontSharedMaterial = refText.fontSharedMaterial;
                if (!label.gameObject.activeSelf) label.gameObject.SetActive(true);
                Place(refText);
            }
            else if (label.gameObject.activeSelf)
            {
                label.gameObject.SetActive(false);
            }
            Bar(hb, refText, hp, absorb, max);
        }

        void Ensure(TMP_Text refText)
        {
            if (label != null) return;
            Transform parent = refText.transform.parent;
            if (parent == null) return;
            var go = new GameObject("UltraBridge AbsorbHp", typeof(RectTransform), typeof(TextMeshProUGUI));
            go.transform.SetParent(parent, false);
            go.transform.SetAsLastSibling();
            labelRect = (RectTransform)go.transform;
            var r = (RectTransform)refText.transform;
            // anchored to the block's bottom left and pivoted on its own left edge, so Place() can put the number
            // straight after the rendered digits wherever they end
            labelRect.anchorMin = Vector2.zero;
            labelRect.anchorMax = Vector2.zero;
            labelRect.pivot = new Vector2(0f, 0.5f);
            labelRect.sizeDelta = new Vector2(200f, r.rect.height);
            labelRect.anchoredPosition = Vector2.zero;
            label = go.GetComponent<TextMeshProUGUI>();
            label.font = refText.font;
            label.fontSize = VitalsHud.Classic() ? 24f : refText.fontSize;
            label.alignment = TextAlignmentOptions.Left;
            label.color = new Color(0.4f, 0.8f, 1f, 1f); // light blue, so it reads as a number and not as the bar
            label.raycastTarget = false;
            label.text = "";
            label.fontSharedMaterial = refText.fontSharedMaterial;
        }

        /// <summary>
        /// Puts "+N" just past the digits Steve's health is drawn in. Their measured width decides where, not a
        /// fixed offset: a wider number, another resolution or either HUD layout all end up beside the number
        /// instead of on top of it.
        /// </summary>
        void Place(TMP_Text refText)
        {
            var r = (RectTransform)refText.transform;
            var parent = r.parent as RectTransform;
            if (parent == null) return;
            bool classic = VitalsHud.Classic();
            Bounds b = refText.textBounds;
            // where the digits actually end, in the health text's own space: measured, so a wider number, another
            // resolution or either HUD layout all put the pool beside the digits instead of on top of them
            float edge = b.extents.x > 0.01f ? b.max.x : refText.preferredWidth * 0.5f;
            float mid = b.extents.y > 0.01f ? b.center.y : 0f;
            Vector3 local = parent.InverseTransformPoint(r.TransformPoint(new Vector3(edge + label.fontSize * 0.15f, mid, 0f)));
            // the classic panel draws its digits low in the box, with the "/N" readout tucked under the number: the
            // pool has to clear that second line, so it sits a third of a digit higher than the measured centre
            float lift = classic ? refText.fontSize * 0.33f : 1f;
            labelRect.anchoredPosition = new Vector2(local.x - parent.rect.xMin, local.y - parent.rect.yMin + lift);
        }

        void Bar(HealthBar hb, TMP_Text refText, float hp, float absorb, int max)
        {
            Transform filler = refText.transform.parent;
            RectTransform area = null;
            if (filler != null && hb.hpSliders != null && hb.hpSliders.Length > 0 && hb.hpSliders[0] != null)
            {
                Transform t = hb.hpSliders[0].transform.Find("Fill Area");
                area = (t != null ? t : hb.hpSliders[0].transform) as RectTransform;
            }
            if (area == null || absorb < 0.5f)
            {
                HideBar();
                return;
            }
            var fr = (RectTransform)filler;
            if (fr == null) return;
            var c = new Vector3[4];
            area.GetWorldCorners(c);
            Vector3 bl = fr.InverseTransformPoint(c[0]), tr = fr.InverseTransformPoint(c[2]);
            float w = tr.x - bl.x, h = tr.y - bl.y;
            if (w <= 0f || h <= 0f)
            {
                HideBar();
                return;
            }
            if (bar == null && !MakeBar(area, fr)) return;

            float xAt(float v) => bl.x + w * Mathf.Clamp01(v / max);
            float x0 = xAt(hp), x1 = xAt(hp + Mathf.Min(absorb, max));
            if (x0 >= bl.x + w - 0.5f)
            {
                // V1 is full: the blue starts over at the left, as GpVCTweaks draws its pool on a full bar
                x0 = bl.x;
                x1 = xAt(Mathf.Min(absorb, max));
            }
            if (x1 - x0 < 0.5f) x1 = Mathf.Min(x0 + 0.5f, bl.x + w);
            barRect.sizeDelta = new Vector2(x1 - x0, h);
            barRect.anchoredPosition = new Vector2(x0 - fr.rect.xMin, (bl.y + tr.y) * 0.5f - fr.rect.yMin);

            try
            {
                var pm = MonoSingleton<PrefsManager>.Instance;
                float zt = pm != null && pm.GetBool("hudAlwaysOnTop") ? 8f : 4f;
                if (bar.material != null && Mathf.Abs(bar.material.GetFloat("_ZTest") - zt) > 0.01f) bar.material.SetFloat("_ZTest", zt);
            }
            catch (System.Exception) { }
            if (!bar.gameObject.activeSelf) bar.gameObject.SetActive(true);
        }

        bool MakeBar(RectTransform area, RectTransform filler)
        {
            Transform fill = area.Find("Fill");
            Image src = (fill != null ? fill.GetComponent<Image>() : null) ?? area.GetComponent<Image>();
            if (src == null) return false;
            var go = new GameObject("UltraBridge AbsorbBar", typeof(RectTransform), typeof(Image));
            go.transform.SetParent(filler, false);
            barRect = (RectTransform)go.transform;
            barRect.anchorMin = Vector2.zero;
            barRect.anchorMax = Vector2.zero;
            barRect.pivot = new Vector2(0f, 0.5f);
            bar = go.GetComponent<Image>();
            bar.color = new Color(0f, 0.4f, 0.8f, 1f); // dark blue, against the light blue "+N"
            bar.raycastTarget = false;
            bar.sprite = src.sprite;
            bar.type = src.type;
            bar.fillCenter = src.fillCenter;
            bar.pixelsPerUnitMultiplier = src.pixelsPerUnitMultiplier;
            bar.material = src.material;
            // the HUD's own material sits on a queue above the text; 3000 keeps the bar under the numbers
            if (bar.material != null) bar.material.renderQueue = 3000;
            return true;
        }

        void HideBar()
        {
            if (bar != null && bar.gameObject.activeSelf) bar.gameObject.SetActive(false);
        }

        void OnDestroy()
        {
            if (label != null) Destroy(label.gameObject);
            if (bar != null) Destroy(bar.gameObject);
        }
    }
}
