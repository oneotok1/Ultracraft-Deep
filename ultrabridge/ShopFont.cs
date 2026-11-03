using System.IO;
using TMPro;
using UnityEngine;

namespace UltraBridge
{
    // Chinese glyphs for the shop's own text. ULTRAKILL's font has none, so on a Chinese Minecraft the upgrade
    // page borrows a CJK font from a fontpack.bundle somebody else installed - GpVCTweaks ships one, and so does
    // the older UltrakULL. The mod deliberately does not carry its own: a CJK font that renders here has to be a
    // static TMP asset with an 8192 atlas, which is 12 MB on its own - larger than everything else in the jar.
    //
    // The bundle is loaded once and reused if another mod already has it: Unity refuses a second load of
    // identical bytes ("another AssetBundle with the same files is already loaded"). If it cannot be found the
    // page keeps the English font, which is why English text always stays readable either way.
    public partial class Bridge
    {
        /// <summary>The assets worth taking. The WenQuanYi bitmap font is what GpVCTweaks and the older UltrakULL
        /// packs ship; the second name is the Fusion Pixel asset this mod's own experiments produced, kept so a
        /// pack built that way still works.</summary>
        static readonly string[] FontMarkers = { "wenquanyi.bitmap.song.12px", "ultracraft.chinese" };
        static TMP_FontAsset ShopFont;

        /// <summary>The Chinese font, or null when there is none. A hit is cached for good; a miss only for
        /// <see cref="FontMissTicks"/> seconds, because Zh asks every time the shop redraws and the answer can turn
        /// from no to yes the moment the pack is installed. Without the retry a player who adds the pack mid-session
        /// stays in English until ULTRAKILL restarts.</summary>
        static TMP_FontAsset ChineseFont()
        {
            if (ShopFont != null) return ShopFont;
            if (Time.realtimeSinceStartup < nextFontLook) return null;
            // Another mod's pack is tried first: it is the one that ships a font at all. A player who put a pack
            // next to this dll by hand still gets it, because that path is in the list too.
            foreach (var path in FontPackPaths())
            {
                if (!File.Exists(path)) continue;
                try
                {
                    var bundle = AssetBundle.LoadFromFile(path);
                    if (bundle == null) continue;
                    if (TryFindFontIn(bundle)) return ShopFont;
                }
                catch (System.Exception e)
                {
                    Plugin.Log.LogInfo("[UltraBridge] fontpack.bundle at " + path + " failed: " + e.Message);
                }
            }
            if (TryFindFontInLoaded()) return ShopFont;
            nextFontLook = Time.realtimeSinceStartup + FontMissTicks;
            Plugin.Log.LogInfo("[UltraBridge] no Chinese font pack found: the shop stays in its own font");
            return null;
        }

        /// <summary>How long a failed lookup is trusted before trying again.</summary>
        const float FontMissTicks = 10f;
        static float nextFontLook;

        // This dll sits in BepInEx/plugins/UltraBridge; GpVCTweaks keeps the pack in its own folder next to ours.
        // Its folder leads because that is where the pack actually lives; the other two cover a pack dropped in by
        // hand, which is what the README tells a player to do when they want Chinese in the shop.
        static string[] FontPackPaths()
        {
            var here = Path.GetDirectoryName(typeof(Bridge).Assembly.Location) ?? "";
            var plugins = Path.GetDirectoryName(here) ?? here;
            return new[]
            {
                Path.Combine(plugins, "GpVCTweaks", "fontpack.bundle"),
                Path.Combine(here, "fontpack.bundle"),
                Path.Combine(plugins, "fontpack.bundle"),
            };
        }

        static bool TryFindFontInLoaded()
        {
            try
            {
                foreach (var b in AssetBundle.GetAllLoadedAssetBundles())
                    if (b != null && TryFindFontIn(b)) return true;
            }
            catch (System.Exception e)
            {
                Plugin.Log.LogInfo("[UltraBridge] loaded-bundle scan failed: " + e.Message);
            }
            return false;
        }

        static bool TryFindFontIn(AssetBundle bundle)
        {
            if (bundle == null) return false;
            string[] names = bundle.GetAllAssetNames();
            foreach (var marker in FontMarkers)
            {
                foreach (var n in names)
                {
                    if (!n.Contains(marker) || n.Contains("fallback")) continue;
                    ShopFont = bundle.LoadAsset<TMP_FontAsset>(n);
                    if (ShopFont != null)
                    {
                        Plugin.Log.LogInfo("[UltraBridge] Chinese font: " + n);
                        return true;
                    }
                }
            }
            return false;
        }

        /// <summary>Puts the Chinese font on the upgrade page, when Minecraft is Chinese and a font was found.</summary>
        void ApplyShopFont(UpgradePage up)
        {
            if (up == null || up.page == null) return;
            ApplyShopFont(up.page.transform);
        }

        /// <summary>Puts the Chinese font on every text under <paramref name="root"/> that actually holds Chinese.
        /// Only those: a label still in English keeps ULTRAKILL's own font and so its own look. This covers the main
        /// menu's own button too, which sits outside the upgrade page and so was drawing tofu squares.</summary>
        void ApplyShopFont(Transform root)
        {
            if (!Zh || root == null) return;
            var font = ChineseFont();
            if (font == null) return;
            foreach (var t in root.GetComponentsInChildren<TMP_Text>(true))
            {
                if (t == null || t.font == font) continue;
                if (!HasChinese(t.text)) continue;
                t.font = font;
                t.fontSharedMaterial = font.material;
            }
        }

        static bool HasChinese(string s)
        {
            if (string.IsNullOrEmpty(s)) return false;
            for (int i = 0; i < s.Length; i++)
            {
                char c = s[i];
                // CJK unified ideographs, plus the punctuation ranges Chinese text uses
                if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF))
                    return true;
            }
            return false;
        }
    }
}
