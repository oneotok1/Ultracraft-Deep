using System.Collections.Generic;
using UnityEngine;

namespace UltraBridge
{
    // The shop's text in Chinese, used when Minecraft runs in Chinese. Minecraft tells us its language
    // (LANG zh_cn) and we swap these in for the English in Upgrades.cs / Progress.cs.
    //
    // English stays the fallback: a key missing here falls back to the English text, so a partial table never
    // leaves a blank line. The wording is the proofread version in docs/译文校对-终端机与HUD.md.
    public partial class Bridge
    {
        /// <summary>Minecraft's selected language code ("zh_cn", "en_us"...). Set by LANG.</summary>
        internal static string McLang = "en_us";

        /// <summary>True when the shop's text is to come out in Chinese. ULTRAKILL's own font holds no CJK
        /// glyphs, so Chinese without the font pack would draw a page of tofu: a Chinese Minecraft with no
        /// font pack keeps the English instead, which is what the terminal showed before it was translated.</summary>
        internal static bool Zh => McLang != null && McLang.StartsWith("zh") && ChineseFont() != null;

        /// <summary>Upgrade display names (UpGroups' second column), in Chinese.</summary>
        static readonly Dictionary<string, string> ZhName = new Dictionary<string, string>
        {
            { "Power", "威力" },
            { "Hair Trigger", "速射扳机" },
            { "Capacitor", "电容器" },
            { "Payload", "高爆装药" },
            { "Overclock", "超频" },
            { "Heatsink", "散热器" },
            { "Autoloader", "自动装填" },
            { "Reflex", "快速反射" },
            { "Return to Sender", "原样反弹" },
            { "Bloodfist", "血拳" },
            { "Arc", "电弧连击" },
            { "Counterblast", "反击爆破" },
            { "Adrenaline", "肾上腺素" },
            { "Shockwave", "冲击波" },
            { "Demolition", "地面爆破" },
            { "Detonator", "命中引爆" },
            { "Seismic", "地脉震荡" },
            { "Ironclad", "装甲减伤" },
            { "Overcharge", "过载充能" },
            { "Reel", "快速收回" },
            { "Barbs", "倒刺" },
            { "Grapple", "方块抓取" },
            { "Live Wire", "通电勾爪" },
            { "Slingshot", "弹射" },
            { "Ripcord", "拉拽引爆" },
            // UpGroups' group names: the page's tabs and its title
            { "Revolver", "左轮" },
            { "Shotgun", "霰弹枪" },
            { "Nailgun", "钉枪" },
            { "Railcannon", "轨道炮" },
            { "Rocket Launcher", "火箭筒" },
            { "Feedbacker", "反弹/蓝拳" },
            { "Knuckleblaster", "爆破/红拳" },
            { "Whiplash", "勾爪" },
        };

        /// <summary>An upgrade's name as shown: Chinese on a Chinese Minecraft, English otherwise.</summary>
        internal static string UpName(string english)
        {
            if (Zh && english != null && ZhName.TryGetValue(english, out var zh)) return zh;
            return english;
        }

        /// <summary>UpEffect in Chinese: the short label after the number. Mirrors Upgrades.cs's switch.</summary>
        static string UpEffectZh(string key, int level)
        {
            string track = key.Substring(key.IndexOf('.') + 1);
            switch (track)
            {
                case "power": return Mathf.RoundToInt(At(PowerTable, level) * 100f) + "% 伤害";
                case "rate":
                case "charge":
                case "reel": return "x" + At(RateTable, level).ToString("0.##") + " 速度";
                case "payload":
                case "shockwave": return Mathf.RoundToInt(At(SizeTable, level) * 100f) + "% 范围";
                case "reflex": return "+" + Mathf.RoundToInt(At(ReflexTable, level) * 1000f) + " 毫秒";
                case "sender": return "x" + At(SenderTable, level).ToString("0.#") + " 反弹伤害与速度";
                case "demolition": return "挖掘范围 x" + At(DemolitionTable, level).ToString("0.#");
                case "barbs": return "x" + At(BarbsTable, level).ToString("0.#") + " 勾爪伤害";
                case "bloodfist": return level <= 1 ? "关闭" : "每次命中 +" + At(BloodfistTable, level) + " HP";
                case "arc": return level <= 1 ? "关闭" : At(ArcTable, level) + " 道电弧，拳伤害 x" + At(ArcDamageTable, level).ToString("0.##");
                case "counter": return level <= 1 ? "关闭" : Mathf.RoundToInt(At(CounterTable, level) * 100f) + "% 爆炸范围";
                case "adrenaline": return level <= 1 ? "关闭" : "+" + At(AdrenalineTable, level) + " 体力" + (level >= 5 ? "（额外 +25 HP）" : "");
                case "detonator": return level <= 1 ? "关闭" : Mathf.RoundToInt(At(DetonatorTable, level) * 100f) + "% 爆炸范围";
                case "seismic": return level <= 1 ? "关闭" : Mathf.RoundToInt(At(SeismicTable, level) * 100f) + "% 爆炸范围";
                case "ironclad": return level <= 1 ? "关闭" : "-" + Mathf.RoundToInt(At(IroncladTable, level) * 100f) + "% 受到伤害";
                case "overcharge": return "x" + At(RateTable, level).ToString("0.##") + " 充能速度";
                case "grapple": return level <= 1 ? "关闭" : "拉拽速度 " + At(GrappleTable, level);
                case "livewire": return level <= 1 ? "关闭" : "每秒 x" + At(LiveWireTable, level).ToString("0.#") + "勾爪伤害";
                case "slingshot": return level <= 1 ? "关闭" : "弹射速度 +" + At(SlingshotTable, level);
                case "ripcord": return level <= 1 ? "关闭" : Mathf.RoundToInt(At(RipcordTable, level) * 100f) + "% 爆炸范围";
            }
            return "";
        }

        /// <summary>UpAbout in Chinese: the whole sentence at the bottom of the panel.</summary>
        static string UpAboutZh(string key)
        {
            switch (key)
            {
                case "rev.power": case "sho.power": case "nai.power": case "rai.power": case "rock.power":
                    return "对敌人、生物与方块造成更高伤害（全变体）。";
                case "arm0.power": return "反弹/蓝拳伤害更高、挖掘更深。";
                case "arm1.power": return "爆破(红)拳伤害更高、挖掘更深。";
                case "arm2.power": return "勾爪造成更高伤害。";
                case "rev.rate": return "射击间隔更短，Piercer 充能更快。";
                case "rev.charge": return "Marksman、Sharpshooter 充能更快。";
                case "sho.payload": return "核心抛射与过载爆炸范围更大。";
                case "sho.charge": return "核心、锯片与气锤充能更快。";
                case "nai.rate": return "射击速度更快。";
                case "nai.charge": return "冷却、补钉与磁铁充能更快。";
                case "rai.charge": return "充能速度更快。";
                case "rai.payload": return "恶意轨道炮(红轨)爆炸范围更大。";
                case "rock.payload": return "火箭爆炸范围更大。";
                case "rock.rate": return "射速更快；炮弹、凝固汽油与定格(蓝筒时停)充能更快。";
                case "arm0.reflex": return "招架箭矢、火球与挥击的判定时间更长。";
                case "arm0.sender": return "招架反弹的投射物速度更快、伤害更高。";
                case "arm0.bloodfist": return "每次蓝拳击中回复 V1 生命。";
                case "arm0.arc": return "蓝拳命中敌人向周围敌人传导电弧，电弧伤害受拳伤害加成。";
                case "arm0.counter": return "触发招架闪光时在身前引发爆炸，仅伤害敌人，范围相对默认爆炸。";
                case "arm0.adrenaline": return "触发招架闪光回复体力（满级额外回复生命）。";
                case "arm1.shockwave": return "红拳冲击波范围更大。";
                case "arm1.demolition": return "向地面拳击挖掘范围更大。";
                case "arm1.detonator": return "拳击命中目标引发爆炸。";
                case "arm1.seismic": return "下砸落地时在身周引发爆炸。";
                case "arm1.ironclad": return "手持爆破/红拳时受到伤害降低。";
                case "arm1.overcharge": return "手持爆破/红拳时，拳击冷却更快。";
                case "arm2.reel": return "勾爪重掷更快。";
                case "arm2.barbs": return "勾爪命中造成伤害。";
                case "arm2.grapple": return "勾爪抓取方块并将 V1 拉过去，拉拽速度是瞬间速度。";
                case "arm2.livewire": return "被勾中的敌人持续受到电击，伤害受勾爪伤害加成。";
                case "arm2.slingshot": return "拉近自身断钩时往朝向方向弹射飞出。";
                case "arm2.ripcord": return "拉近敌人引发爆炸（不伤害 V1）。";
            }
            return "";
        }

        /// <summary>The panel's own fixed words ("Next:", "Buy", "Maxed") in Chinese.</summary>
        internal static string UiText(string english)
        {
            if (!Zh) return english;
            switch (english)
            {
                case "Upgrades": return "升级";
                case "Next:": return "下一级:";
                case "Maxed": return "已满级";
                case "Buy": return "购买";
                case "Enter The Cyber Grind": return "进入电子血宫";
                case "Leave The Cyber Grind": return "离开电子血宫";
                case "Join The Cyber Grind": return "加入电子血宫";
                case "Stop Dark Spawns": return "停止黑暗生成";
                case "Allow Dark Spawns": return "允许黑暗生成";
                case "ALTERNATE": return "变种";
                case "ON": return "开";
                case "OFF": return "关";
            }
            return english;
        }
    }
}
