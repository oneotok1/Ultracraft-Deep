// UltraBridge: ULTRAKILL's music for Minecraft's fights. Minecraft picks a song from ULTRAKILL's soundtrack (MUSIC
// key, random, or off); ULTRAKILL's own music manager plays it the way its levels do: the battle version while enemies
// are on V1 (they ask for it themselves), the song's calm version (or quiet) otherwise, crossfading between them. A boss
// or an arena can bring its own song (THEME key, - when it's over). Minecraft hears when a fight starts and ends
// (FIGHT 1/0), to hush its own music meanwhile.

using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using UnityEngine.AddressableAssets;
using UnityEngine.ResourceManagement.AsyncOperations;

namespace UltraBridge
{
    public partial class Bridge
    {
        const string SoundtrackRoot = "Assets/Data/Soundtrack/";

        /// <summary>Minecraft's choice: "off", "random", or a song's key.</summary>
        string musicChoice = "off";
        /// <summary>Between fights the song's calm version plays (if ULTRAKILL has one); otherwise it's quiet.</summary>
        bool musicCalm;
        /// <summary>A boss's or an arena's own song while it lasts (null: none).</summary>
        string themeSong;
        static List<string> songKeys;
        string songInPlayer, songLoading;
        // the song in the player is a boss's or an arena's (not one Random should keep)
        bool playingTheme;
        AsyncOperationHandle<SoundtrackSong> songHandle;
        AsyncOperationHandle<AudioClip> calmHandle;
        bool fightNow;
        float fightEndedAt = -999f;
        float nextMusicCheck;

        void HandleMusic(string cmd, string rest)
        {
            switch (cmd)
            {
                case "MUSIC":
                    // MUSIC off|random|<song key>: what plays in fights
                    musicChoice = rest.Trim();
                    if (musicChoice.Length == 0) musicChoice = "off";
                    if (levelPrepared) MusicChanged();
                    break;
                case "MUSICOPTS":
                    // MUSICOPTS calm=0/1
                    foreach (var kv in rest.Split(' '))
                        if (kv.StartsWith("calm=")) musicCalm = kv.EndsWith("1");
                    if (levelPrepared && songInPlayer != null) LoadSong(songInPlayer, true);
                    break;
                case "THEME":
                    // THEME <song key>|-: a boss's or an arena's own song, until it's over
                    themeSong = rest.Trim() == "-" || rest.Trim().Length == 0 ? null : rest.Trim();
                    if (levelPrepared) MusicChanged();
                    break;
            }
        }

        /// <summary>Every song ULTRAKILL's soundtrack has (its Cyber Grind's jukebox list), from its asset catalog.</summary>
        static List<string> SongKeys()
        {
            if (songKeys != null) return songKeys;
            var found = new SortedSet<string>(StringComparer.Ordinal);
            try
            {
                foreach (var loc in Addressables.ResourceLocators)
                    foreach (var key in loc.Keys)
                        if (key is string s && s.StartsWith(SoundtrackRoot) && s.EndsWith(".asset")) found.Add(s);
            }
            catch (Exception e) { Plugin.Log.LogWarning("songs: " + e.Message); }
            songKeys = new List<string>(found);
            return songKeys;
        }

        /// <summary>SONGS key;key;...: the soundtrack, for Minecraft's music picker.</summary>
        void SendSongs()
        {
            var sb = new StringBuilder("SONGS ");
            foreach (var k in SongKeys()) sb.Append(k.Substring(SoundtrackRoot.Length)).Append(';');
            Net.Send(sb.ToString());
        }

        /// <summary>Not for a random fight: ULTRAKILL's calm pieces (interludes, secret levels, the museum).</summary>
        static bool Calm(string key) =>
            key.StartsWith("Misc/") || key.StartsWith("Secret Levels/") || key.Contains("Clair de Lune") || key.Contains("Bach BWV") || key.Contains("Beethoven")
            || key.Contains("Weihnachten") || key.Contains("Silence. Introspection") || key.Contains("Disgrace. Humiliation") || key.Contains("An Absence")
            || key.Contains("Aftermath") || key.Contains("Shopping Ver") || key.Contains("The World Looks White");

        /// <summary>The song for the next fight: a theme first, then Minecraft's choice.</summary>
        string WantedSong()
        {
            if (themeSong != null) return themeSong;
            if (musicChoice == "off") return null;
            if (musicChoice == "random")
            {
                var keys = SongKeys().FindAll(k => !Calm(k.Substring(SoundtrackRoot.Length)));
                if (keys.Count == 0) return null;
                // the same song through a fight and a short breather; a new one after a real lull
                if (songInPlayer != null && !playingTheme && Time.time - fightEndedAt < 30f) return songInPlayer;
                return keys[UnityEngine.Random.Range(0, keys.Count)].Substring(SoundtrackRoot.Length);
            }
            return musicChoice;
        }

        void MusicChanged()
        {
            string want = WantedSong();
            playingTheme = themeSong != null;
            if (want == null)
            {
                StopUkMusic();
                return;
            }
            if (want != songInPlayer && want != songLoading) LoadSong(want, false);
        }

        void StopUkMusic()
        {
            songInPlayer = null;
            songLoading = null;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm != null)
            {
                try { mm.ForceStopMusic(); }
                catch (Exception e) { Plugin.Log.LogDebug("music stop: " + e.Message); }
            }
        }

        void LoadSong(string key, bool reload)
        {
            // one of the player's own songs (Minecraft's ultracraft/music folder): file:<path>
            if (key.StartsWith("file:"))
            {
                songLoading = key;
                StartCoroutine(LoadFileSong(key));
                return;
            }
            string full = SoundtrackRoot + key;
            songLoading = key;
            AsyncOperationHandle<SoundtrackSong> h;
            try { h = Addressables.LoadAssetAsync<SoundtrackSong>(full); }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("music " + key + ": " + e.Message);
                songLoading = null;
                return;
            }
            h.Completed += op =>
            {
                if (songLoading != key)
                {
                    Addressables.Release(op);
                    return;
                }
                songLoading = null;
                if (op.Status != AsyncOperationStatus.Succeeded || op.Result == null)
                {
                    Plugin.Log.LogWarning("music " + key + ": couldn't load");
                    Addressables.Release(op);
                    return;
                }
                if (songHandle.IsValid()) Addressables.Release(songHandle);
                songHandle = op;
                PlaySong(key, op.Result);
            };
        }

        AudioClip fileClip;

        /// <summary>A song file of the player's (mp3, ogg or wav), loaded the way Unity reads one off disk.</summary>
        System.Collections.IEnumerator LoadFileSong(string key)
        {
            string path = key.Substring(5);
            string ext = System.IO.Path.GetExtension(path).ToLowerInvariant();
            var type = ext == ".mp3" ? AudioType.MPEG : ext == ".ogg" ? AudioType.OGGVORBIS : ext == ".wav" ? AudioType.WAV : AudioType.UNKNOWN;
            if (!System.IO.File.Exists(path) || type == AudioType.UNKNOWN)
            {
                Plugin.Log.LogWarning("music " + path + ": no such song file (mp3, ogg or wav)");
                songLoading = null;
                yield break;
            }
            var req = UnityEngine.Networking.UnityWebRequestMultimedia.GetAudioClip(new Uri(path).AbsoluteUri, type);
            yield return req.SendWebRequest();
            if (songLoading != key)
            {
                req.Dispose();
                yield break;
            }
            songLoading = null;
            AudioClip clip = null;
            try { clip = UnityEngine.Networking.DownloadHandlerAudioClip.GetContent(req); }
            catch (Exception e) { Plugin.Log.LogWarning("music " + path + ": " + e.Message); }
            req.Dispose();
            if (clip == null) yield break;
            clip.name = System.IO.Path.GetFileNameWithoutExtension(path);
            if (fileClip != null && fileClip != clip) Destroy(fileClip);
            fileClip = clip;
            PlayClip(key, clip, clip.name);
        }

        void PlaySong(string key, SoundtrackSong song)
        {
            AudioClip clip = song.clips != null && song.clips.Count > 0 && song.clips[0] != null ? song.clips[0] : song.introClip;
            PlayClip(key, clip, song.songName);
        }

        void PlayClip(string key, AudioClip clip, string songName)
        {
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null || clip == null || mm.battleTheme == null || mm.cleanTheme == null) return;
            songInPlayer = key;
            mm.battleTheme.clip = clip;
            mm.battleTheme.loop = true;
            if (mm.bossTheme != null)
            {
                mm.bossTheme.clip = clip;
                mm.bossTheme.loop = true;
            }
            mm.cleanTheme.clip = null;
            mm.cleanTheme.loop = true;
            if (calmHandle.IsValid()) Addressables.Release(calmHandle);
            calmHandle = default;
            // its calm version: ULTRAKILL keeps one beside most level songs ("1-1" and "1-1 Clean")
            if (musicCalm && !key.StartsWith("file:"))
            {
                string calmKey = "Assets/Music/" + clip.name + " Clean.wav";
                if (HasKey(calmKey))
                {
                    try
                    {
                        var ch = Addressables.LoadAssetAsync<AudioClip>(calmKey);
                        ch.Completed += op =>
                        {
                            if (songInPlayer != key || op.Status != AsyncOperationStatus.Succeeded)
                            {
                                Addressables.Release(op);
                                return;
                            }
                            calmHandle = op;
                            mm.cleanTheme.clip = op.Result;
                            RestartMusic(mm);
                        };
                    }
                    catch (Exception e) { Plugin.Log.LogDebug("calm music: " + e.Message); }
                }
            }
            RestartMusic(mm);
            Plugin.Log.LogInfo("music: " + songName + " (" + key + ")" + (musicCalm ? ", calm between fights" : ""));
        }

        static bool HasKey(string key)
        {
            try
            {
                foreach (var loc in Addressables.ResourceLocators)
                    if (loc.Locate(key, typeof(AudioClip), out _)) return true;
            }
            catch (Exception) { }
            return false;
        }

        /// <summary>The music manager plays its themes from the top, with the new clips (battle if a fight is on).</summary>
        static void RestartMusic(MusicManager mm)
        {
            try
            {
                // a boss making its entrance: quiet until it's done (MusicAfterIntro)
                if (introMusicHolds > 0 || outroPlaying.Count > 0)
                {
                    mm.ForceStopMusic();
                    return;
                }
                mm.off = true;
                mm.ForceStartMusic();
            }
            catch (Exception e) { Plugin.Log.LogDebug("music start: " + e.Message); }
        }

        /// <summary>Boss entrances going on: the music waits, as in ULTRAKILL, and starts the moment the entrance ends.</summary>
        static int introMusicHolds;

        /// <summary>An entrance starts: whatever was playing stops; the boss's song (when it comes) waits.</summary>
        static void HoldMusicForIntro()
        {
            introMusicHolds++;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null) return;
            try { mm.ForceStopMusic(); }
            catch (Exception e) { Plugin.Log.LogDebug("music hold: " + e.Message); }
        }

        const float IntroMusicFade = 0.6f;
        const float OutroMusicFade = 0.8f;

        /// <summary>A boss is beaten and goes out (its death scene): the music fades away, as in ULTRAKILL, and stays quiet
        /// until the fight is over for Minecraft (then its world music comes back).</summary>
        void MusicForOutro()
        {
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm != null) StartCoroutine(FadeOutAll(mm));
        }

        /// <summary>The music was stopped for a boss's death: it comes back when that's over.</summary>
        static bool musicOutroStopped;

        static System.Collections.IEnumerator FadeOutAll(MusicManager mm)
        {
            var themes = new[] { mm.battleTheme, mm.cleanTheme, mm.bossTheme };
            var from = new float[themes.Length];
            for (int i = 0; i < themes.Length; i++) from[i] = themes[i] != null ? themes[i].volume : 0f;
            for (float t = 0f; t < OutroMusicFade; t += Time.unscaledDeltaTime)
            {
                if (mm == null) yield break;
                float f = 1f - t / OutroMusicFade;
                for (int i = 0; i < themes.Length; i++) if (themes[i] != null) themes[i].volume = from[i] * f;
                yield return null;
            }
            if (mm == null || outroPlaying.Count == 0) yield break;
            musicOutroStopped = true;
            try { mm.ForceStopMusic(); }
            catch (Exception e) { Plugin.Log.LogDebug("music outro: " + e.Message); }
            Plugin.Log.LogInfo("music: stopped for the boss's death");
        }

        /// <summary>An entrance is over ("...HERE AND NOW!"): its song starts from the top as the battle theme, fading in
        /// over a moment.</summary>
        void MusicAfterIntro()
        {
            if (introMusicHolds == 0) return;
            if (--introMusicHolds > 0) return;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null || mm.battleTheme == null || mm.battleTheme.clip == null) return;
            try
            {
                mm.off = true;
                mm.ForceStartMusic();
                mm.battleTheme.volume = 0f;
                if (mm.cleanTheme != null) mm.cleanTheme.volume = 0f;
                if (mm.bossTheme != null) mm.bossTheme.volume = 0f;
                mm.targetTheme = mm.battleTheme;
                StartCoroutine(FadeInBattle(mm));
                Plugin.Log.LogInfo("music: " + mm.battleTheme.clip.name + " starts as the entrance ends");
            }
            catch (Exception e) { Plugin.Log.LogDebug("music after intro: " + e.Message); }
        }

        static System.Collections.IEnumerator FadeInBattle(MusicManager mm)
        {
            for (float t = 0f; t < IntroMusicFade; t += Time.unscaledDeltaTime)
            {
                if (mm == null || mm.battleTheme == null || mm.targetTheme != mm.battleTheme) yield break;
                // eased: it swells in rather than ramping
                float f = t / IntroMusicFade;
                mm.battleTheme.volume = mm.volume * f * f;
                yield return null;
            }
            if (mm != null && mm.battleTheme != null && mm.targetTheme == mm.battleTheme) mm.battleTheme.volume = mm.volume;
        }

        /// <summary>A few times a second: whether a fight is on (ULTRAKILL's enemies asked for battle music), and the song
        /// for it.</summary>
        void UpdateMusic()
        {
            if (!levelPrepared || Time.unscaledTime < nextMusicCheck) return;
            nextMusicCheck = Time.unscaledTime + 0.25f;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null) return;
            // a boss's death scene over: its music back (unless Minecraft has already changed the song)
            if (musicOutroStopped && outroPlaying.Count == 0)
            {
                musicOutroStopped = false;
                if (songInPlayer != null) RestartMusic(mm);
            }
            bool fight;
            try { fight = mm.IsInBattle(); }
            catch (Exception) { fight = false; }
            if (fight == fightNow) return;
            fightNow = fight;
            if (Net.Connected) Net.Send("FIGHT " + (fight ? 1 : 0));
            if (fight) MusicChanged();
            else fightEndedAt = Time.time;
        }

        /// <summary>Debug: MUSICINFO.</summary>
        string MusicInfo()
        {
            var mm = MonoSingleton<MusicManager>.Instance;
            var sb = new StringBuilder("MUSICINFO choice=" + musicChoice + " theme=" + (themeSong ?? "-") + " playing=" + (songInPlayer ?? "-") + " calm=" + musicCalm
                                       + " fight=" + fightNow + " songs=" + SongKeys().Count);
            if (mm != null)
            {
                sb.Append(" off=").Append(mm.off).Append(" forcedOff=").Append(mm.forcedOff).Append(" requested=").Append(mm.requestedThemes)
                  .Append(" battle=").Append(mm.battleTheme != null && mm.battleTheme.clip != null ? mm.battleTheme.clip.name + "@" + mm.battleTheme.volume.ToString("0.00") + (mm.battleTheme.isPlaying ? "+" : "") : "-")
                  .Append(" clean=").Append(mm.cleanTheme != null && mm.cleanTheme.clip != null ? mm.cleanTheme.clip.name + "@" + mm.cleanTheme.volume.ToString("0.00") : "-")
                  .Append(" target=").Append(mm.targetTheme != null ? mm.targetTheme.name : "-");
            }
            else sb.Append(" no music manager");
            return sb.ToString();
        }
    }
}
