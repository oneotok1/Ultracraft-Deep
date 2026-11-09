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
        /// <summary>The choice the last MUSIC carried, so a resend of the same one is not taken for a new pick.</summary>
        string musicSent;
        /// <summary>Between fights the song's calm version plays (if ULTRAKILL has one); otherwise it's quiet. Only in
        /// the Cyber Grind: in Minecraft's world a lull between fights is Minecraft's own.</summary>
        bool musicCalm;
        /// <summary>ULTRAKILL's fight music in Minecraft's world too, for its enemies that come out there (MUSICOPTS
        /// world=). Off: the Cyber Grind and bosses keep theirs.</summary>
        bool worldMusic = true;
        /// <summary>Songs the random pick leaves out (MUSICMUTE), by their key without the soundtrack root.</summary>
        readonly HashSet<string> mutedSongs = new HashSet<string>();
        /// <summary>The calm version is the one loaded into the clean theme, so it has to be let go of outside the Grind.</summary>
        bool calmLoaded;
        /// <summary>The load under way is only to fetch the calm version: when it lands, the song itself stays put.</summary>
        bool calmPending;
        /// <summary>A run is on (GRINDMUSIC 1/0): its music is up from the moment the grid changes, and the calm
        /// version plays in the pauses between its waves.</summary>
        bool grindMusic;
        /// <summary>A boss's or an arena's own song while it lasts (null: none).</summary>
        string themeSong;
        /// <summary>The player's own songs (YOURS): Minecraft's name for each, and the file to play it from. They are
        /// part of the random draw with the soundtrack, so a fight can land on one.</summary>
        readonly List<KeyValuePair<string, string>> ownSongs = new List<KeyValuePair<string, string>>();
        static List<string> songKeys;
        string songInPlayer, songLoading;
        // the song in the player is a boss's or an arena's (not one Random should keep)
        bool playingTheme;
        AsyncOperationHandle<SoundtrackSong> songHandle;
        AsyncOperationHandle<AudioClip> calmHandle;
        bool fightNow;
        float fightEndedAt = -999f;
        float nextMusicCheck;
        /// <summary>Where the song was when something took it away (a boss's entrance, its death scene): it comes back
        /// there, rather than from the top, as ULTRAKILL's own music would have gone on.</summary>
        static float songAt;
        static bool songAtSaved;

        void HandleMusic(string cmd, string rest)
        {
            switch (cmd)
            {
                case "MUSIC":
                    // MUSIC off|random|<song key>: what plays in fights
                    // Minecraft resends all of these with every setting changed, not only with the music ones
                    // (sendOpts carries them), so a repeat has to change nothing: taken for a fresh pick it drew a
                    // new song, which the player heard as the fight's music changing when a slider moved
                    musicChoice = rest.Trim();
                    if (musicChoice.Length == 0) musicChoice = "off";
                    if (musicChoice == musicSent) break;
                    musicSent = musicChoice;
                    // the pick was made by hand just now: a random draw starts over, rather than keeping the song it
                    // already has for the rest of the fight and its breather
                    fightEndedAt = -999f;
                    if (levelPrepared) MusicChanged();
                    break;
                case "MUSICOPTS":
                    // MUSICOPTS calm=0/1 world=0/1
                    bool calmBefore = musicCalm;
                    foreach (var kv in rest.Split(' '))
                    {
                        if (kv.StartsWith("calm=")) musicCalm = kv.EndsWith("1");
                        if (kv.StartsWith("world=")) worldMusic = kv.EndsWith("1");
                    }
                    // only the calm version is loaded into the clean theme: the song itself is already right, and
                    // reloading it would take it back to the top
                    if (levelPrepared && songInPlayer != null && musicCalm != calmBefore) LoadSong(songInPlayer, true);
                    break;
                case "MUSICMUTE":
                    // MUSICMUTE song;song...: left out of the random pick (keys without the soundtrack root)
                    var muteNow = new HashSet<string>();
                    foreach (var k in rest.Split(';'))
                    {
                        string song = k.Trim();
                        if (song.Length > 0) muteNow.Add(song);
                    }
                    if (muteNow.SetEquals(mutedSongs)) break;
                    mutedSongs.Clear();
                    foreach (var song in muteNow) mutedSongs.Add(song);
                    if (levelPrepared) MusicChanged();
                    break;
                case "YOURS":
                    // YOURS name\u0001file;name\u0001file;...: the player's own songs (Minecraft's ultracraft/music),
                    // in the random draw with the soundtrack. Sent as a whole list whenever it changes.
                    var ownNow = new List<KeyValuePair<string, string>>();
                    foreach (var e in rest.Split(';'))
                    {
                        if (e.Length == 0) continue;
                        int at = e.IndexOf('\u0001');
                        if (at <= 0) continue;
                        ownNow.Add(new KeyValuePair<string, string>(e.Substring(0, at), e.Substring(at + 1)));
                    }
                    if (ownNow.Count == ownSongs.Count)
                    {
                        bool same = true;
                        for (int i = 0; i < ownNow.Count && same; i++)
                            same = ownNow[i].Key == ownSongs[i].Key && ownNow[i].Value == ownSongs[i].Value;
                        if (same) break;
                    }
                    ownSongs.Clear();
                    ownSongs.AddRange(ownNow);
                    Plugin.Log.LogInfo("music: " + ownSongs.Count + " of the player's own songs");
                    if (levelPrepared) MusicChanged();
                    break;
                case "GRINDMUSIC":
                    // GRINDMUSIC 1/0: the Cyber Grind's grid is up (its music from the moment the terrain changes,
                    // not from the first enemy), or the run is over. A run later, rethemed arena sends 1 again:
                    // the song keeps on from where it is, it does not start over.
                    if (rest.Trim() == "1")
                    {
                        if (grindMusic) return;
                        StartGrindMusic();
                    }
                    else
                    {
                        grindMusic = false;
                        StopUkMusic();
                    }
                    break;
                case "THEME":
                    // THEME <song key>|-: a boss's or an arena's own song, until it's over.
                    // A "-" ends that song, and the music with it. Asking WantedSong() for the next one
                    // here would draw a fresh song the moment the boss dies, and nothing would stop it
                    // afterwards: it plays on into the next world. Only a Cyber Grind run has a song of
                    // its own to fall back to, so only that case asks for one.
                    string theme = rest.Trim();
                    themeSong = theme == "-" || theme.Length == 0 ? null : theme;
                    if (!levelPrepared) break;
                    if (themeSong == null && !grindMusic)
                    {
                        StopUkMusic();
                        Plugin.Log.LogInfo("music: the fight's song is over, nothing takes its place");
                        break;
                    }
                    MusicChanged();
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
                // the soundtrack and the player's own songs (YOURS) are one pool: a song is a song
                var keys = new List<string>();
                foreach (var k in SongKeys())
                {
                    string s = k.Substring(SoundtrackRoot.Length);
                    if (Calm(s) || mutedSongs.Contains(s)) continue;
                    keys.Add(s);
                }
                foreach (var own in ownSongs) if (!mutedSongs.Contains(own.Key)) keys.Add(own.Key);
                if (keys.Count == 0) return null;
                Plugin.Log.LogInfo("music: drawing from " + keys.Count + " songs (" + ownSongs.Count + " of them the player's, "
                    + mutedSongs.Count + " left out)");
                // the same song through a fight and a short breather; a new one after a real lull
                if (songInPlayer != null && !playingTheme && Time.time - fightEndedAt < 30f) return songInPlayer;
                // a draw that lands on the song already playing would be no change at all
                if (keys.Count > 1) keys.RemoveAll(k => k == songInPlayer);
                return keys[UnityEngine.Random.Range(0, keys.Count)];
            }
            return musicChoice;
        }

        /// <summary>Whether any of our music belongs in the air right now: a song has been asked for by name (a boss,
        /// an arena), the Grind is running, or ULTRAKILL's enemies are out in Minecraft's world with its music on.</summary>
        bool WantsMusic()
        {
            if (themeSong != null) return true;
            if (grindMusic) return true;
            if (musicChoice == "off") return false;
            return worldMusic && mcInWorld && LiveEnemies() > 0;
        }

        void MusicChanged()
        {
            // a setting changed, or the level finished loading - not a fight starting. Loading the pick there and
            // then started a song in an empty world, and "fight music off" in Minecraft's world could not stop it
            if (!WantsMusic())
            {
                StopUkMusic();
                return;
            }
            string want = WantedSong();
            playingTheme = themeSong != null;
            if (want == null)
            {
                StopUkMusic();
                return;
            }
            if (want != songInPlayer && want != songLoading) LoadSong(want, false);
        }

        /// <summary>The Cyber Grind's rank screen left the music mixer at -80 dB (silence): FinalCyberRank writes
        /// allVolume/allPitch on the mixer itself as its time scale falls (FinalCyberRank.cs:242-244, :429-430) and
        /// never puts them back; AudioMixerController.Update only restores when musicVolume is above the options
        /// volume, which those writes leave untouched. ULTRAKILL gets away with it because a level reload builds a
        /// fresh controller - the Cyber Grind here never reloads the level, so the silence stays for good.</summary>
        static void RestoreMixer()
        {
            var am = MonoSingleton<AudioMixerController>.Instance;
            if (am == null || am.musicSound == null) return;
            if (!am.musicSound.GetFloat("allVolume", out float db)) return;
            if (db > -79.5f) return; // still audible: not something we set
            float want = am.optionsMusicVolume > 0f ? am.optionsMusicVolume : MonoSingleton<PrefsManager>.Instance.GetFloat("musicVolume");
            if (want <= 0f) return; // the player turned ULTRAKILL's music down themselves
            am.forceOff = false;
            am.SetMusicVolume(want);
            am.musicSound.SetFloat("allPitch", 1f);
            Plugin.Log.LogInfo("music: mixer back up to " + want.ToString("0.00"));
        }

        /// <summary>A fight starts: hand the song that is already loaded to the battle theme. RestartMusic goes through
        /// MusicManager.StartMusic, which picks cleanTheme when no enemy has requested a theme yet, and PlayClip left
        /// cleanTheme with no clip: the song would sit there with nothing to play.</summary>
        void PlayForFight(MusicManager mm)
        {
            if (mm == null || mm.battleTheme == null || mm.battleTheme.clip == null) return;
            // nothing of ours to hand over: the pick is off and no boss has asked for its own song. MusicManager
            // keeps the last clip loaded, so StartMusic below played it again a quarter second after the pick was
            // set to off - the song came back with no enemy standing
            if (musicChoice == "off" && themeSong == null) return;
            try
            {
                RestoreMixer();
                // stopped outright (a boss's entrance, a level's quiet): StartMusic would pick the theme from
                // requestedThemes, which says nothing about our fights - the lines below take over from it
                if (mm.off) mm.ForceStartMusic();
                // a boss's own song outranks the fight's, but only while one has been asked for: PlayClip copies
                // every song onto the boss theme, so the theme alone means nothing, and the manager keeps pointing
                // at it after the song has ended - which would have kept every fight after it silent
                if (mm.targetTheme == mm.bossTheme && themeSong != null) return;
                // between the Grind's waves the calm version has the floor (as a level's does after a fight);
                // a battle theme with no calm counterpart keeps playing instead
                bool lull = grindMusic && grindIn && LiveEnemies() == 0 && mm.cleanTheme != null && mm.cleanTheme.clip != null;
                AudioSource want = lull ? mm.cleanTheme : mm.battleTheme;
                if (mm.targetTheme != want)
                {
                    // the theme has been playing silently since it was loaded: keep its place
                    mm.targetTheme = want;
                    // from silence, as ULTRAKILL's own themes come in; MusicManager fades the target up to its volume
                    want.volume = 0f;
                    Plugin.Log.LogInfo("music: " + want.clip.name + (lull ? " comes in for the lull" : " takes over for the fight"));
                }
                // only the themes that are not playing are held silent, and only they: zeroing the one that is would
                // cut its fade-in short on every check
                if (mm.battleTheme != want) mm.battleTheme.volume = 0f;
                if (mm.cleanTheme != null && mm.cleanTheme != want) mm.cleanTheme.volume = 0f;
                if (mm.bossTheme != null && mm.bossTheme != want) mm.bossTheme.volume = 0f;
            }
            catch (Exception e) { Plugin.Log.LogDebug("music for fight: " + e.Message); }
        }

        /// <summary>The Grind's grid has just gone up (its terrain changed): the music starts there, not with the first
        /// enemy of the wave - as ULTRAKILL opens a level with its song before anything comes at V1.</summary>
        void StartGrindMusic()
        {
            if (!levelPrepared) return;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null) return;
            grindMusic = true;
            // a run gets its own song, not the one the last run ended on
            fightEndedAt = -999f;
            MusicChanged();
            // the song was already the one playing, so MusicChanged had nothing to load and the calm version was
            // never looked for: go through the load again, now that this is the Grind
            if (musicCalm && songInPlayer != null && songLoading == null) LoadSong(songInPlayer, true);
        }

        /// <summary>Whether a fight is on. ULTRAKILL's own answer is requestedThemes, raised by an enemy's Start
        /// (Enemy.cs:573) - and the enemies UltraBridge puts down itself (the Cyber Grind's waves, the arenas') never
        /// run that, so they would fight in silence. Their presence counts as a fight too.</summary>
        bool InFight(MusicManager mm)
        {
            // off means off: not even a stuck requestedThemes (an enemy of ULTRAKILL's that died without lowering it)
            // makes a fight, and a boss's own song is the one thing that still gets to play
            if (musicChoice == "off" && themeSong == null) return false;
            // a song of its own has been asked for (a boss, an arena): that is a fight whatever ULTRAKILL's own
            // counter says, and it plays even with the fight music off
            if (themeSong != null) return true;
            // the Grind keeps its own music wherever it is, but out in Minecraft's world the player decides whether
            // ULTRAKILL's music comes along. Asked before ULTRAKILL's own answer: the enemies UltraBridge puts down
            // there run Enemy.Start, which raises requestedThemes, so IsInBattle stays true for as long as any of
            // them stands - and the setting was being overruled by the very enemies it is about
            if (mcInWorld && !worldMusic && !(grindRunning && grindIn)) return false;
            try { if (mm.IsInBattle()) return true; }
            catch (Exception) { }
            // a run is a fight from its first wave to its last, the pauses between waves included
            if (grindRunning && grindIn) return true;
            // in Minecraft's world: only if the player wants ULTRAKILL's music out there. Not in the Sandbox with no
            // world behind it, though - ULTRAKILL's menu scene keeps its own enemies standing, and they are nobody's
            // fight once Minecraft has gone back to its title screen
            if (!mcInWorld) return false;
            return worldMusic && LiveEnemies() > 0;
        }

        /// <summary>Enemies standing, ours and ULTRAKILL's: the same ones the fight music is for.</summary>
        int LiveEnemies()
        {
            int n = 0;
            foreach (var kv in ukEnemies) if ((object)kv.Value != null && !kv.Value.dead) n++;
            foreach (var kv in grindEnemies)
                if ((object)kv.Key != null && !kv.Key.dead && !ukEnemies.ContainsKey(kv.Key.GetInstanceID())) n++;
            return n;
        }

        void StopUkMusic()
        {
            songInPlayer = null;
            songLoading = null;
            calmPending = false;
            if (calmLoaded)
            {
                calmLoaded = false;
                if (calmHandle.IsValid()) Addressables.Release(calmHandle);
                calmHandle = default;
            }
            // nothing is resumed from where it was: the song that comes next starts at its top
            songAtSaved = false;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm != null)
            {
                try
                {
                    mm.ForceStopMusic();
                    // ForceStopMusic only silences: the clips stay on the themes, and the next StartMusic (a song
                    // finishing its load, an entrance ending) plays them again from the top. A stop is meant to be
                    // the end of the music, so the themes are emptied with it - otherwise "off" kept coming back
                    if (mm.battleTheme != null) mm.battleTheme.clip = null;
                    if (mm.bossTheme != null) mm.bossTheme.clip = null;
                    if (mm.cleanTheme != null) mm.cleanTheme.clip = null;
                    // a boss's song holding the floor would outrank every fight after it (PlayForFight leaves a
                    // boss theme alone), and nothing else ever hands the themes back
                    if (mm.bossTheme != null && mm.targetTheme == mm.bossTheme) mm.targetTheme = mm.cleanTheme;
                }
                catch (Exception e) { Plugin.Log.LogDebug("music stop: " + e.Message); }
            }
        }

        void LoadSong(string key, bool reload)
        {
            // one of the player's own songs (Minecraft's ultracraft/music folder): file:<path>
            if (key.StartsWith("file:"))
            {
                songLoading = key;
                StartCoroutine(LoadFileSong(key, key));
                return;
            }
            // a drawn own song is known by Minecraft's name for it (Your Music/<file>): the file comes from YOURS
            foreach (var own in ownSongs)
                if (own.Key == key)
                {
                    // the song is known by Minecraft's name for it, the file by its path: the loading has to be
                    // known by the name, or the finished load is thrown away as belonging to another song
                    songLoading = key;
                    StartCoroutine(LoadFileSong(own.Value, key));
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
        System.Collections.IEnumerator LoadFileSong(string file, string key)
        {
            string path = file.StartsWith("file:") ? file.Substring(5) : file;
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
            // the same song again (the calm version joining a run already under way, a rethemed arena): keep it
            // playing where it is instead of starting over
            if (songInPlayer == key && !calmPending)
            {
                Plugin.Log.LogInfo("music: " + songName + " already on, left as it is");
                return;
            }
            calmPending = false;
            songInPlayer = key;
            // a hand-picked song stays on; a random one runs out, and the end is what moves the draw along
            bool loop = musicChoice != "random" || themeSong != null;
            mm.battleTheme.clip = clip;
            mm.battleTheme.loop = loop;
            if (mm.bossTheme != null)
            {
                mm.bossTheme.clip = clip;
                mm.bossTheme.loop = loop;
            }
            mm.cleanTheme.clip = null;
            mm.cleanTheme.loop = true;
            if (calmHandle.IsValid()) Addressables.Release(calmHandle);
            calmHandle = default;
            calmLoaded = false;
            // its calm version: ULTRAKILL keeps one beside most level songs ("1-1" and "1-1 Clean"). The Cyber Grind
            // only: it is what plays in the pauses between its waves; in Minecraft's world a lull is Minecraft's.
            if (musicCalm && grindMusic && !key.StartsWith("file:"))
            {
                calmPending = true;
                string calmKey = "Assets/Music/" + clip.name + " Clean.wav";
                Plugin.Log.LogInfo("calm music: looking for " + calmKey + (HasKey(calmKey) ? "" : " (not there)"));
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
                            calmLoaded = true;
                            // the restart has to get past PlayClip's "already on" check: this load never meant
                            // to start the song over, only to fetch its calm version
                            calmPending = false;
                            RestartMusic(mm);
                        };
                    }
                    catch (Exception e) { Plugin.Log.LogDebug("calm music: " + e.Message); }
                }
                if (!calmHandle.IsValid()) calmPending = false;
            }
            RestartMusic(mm);
            Plugin.Log.LogInfo("music: " + songName + " (" + key + ")" + (calmLoaded ? ", calm between waves" : ""));
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
                ResumeAt(mm);
            }
            catch (Exception e) { Plugin.Log.LogDebug("music start: " + e.Message); }
        }

        /// <summary>Remembers where the song is, before it is taken away.</summary>
        static void SaveAt(MusicManager mm)
        {
            if (mm == null || mm.battleTheme == null || mm.battleTheme.clip == null || !mm.battleTheme.isPlaying) return;
            songAt = mm.battleTheme.time;
            songAtSaved = true;
        }

        /// <summary>Puts the song back where SaveAt left it: the sources were started over, but the song goes on.</summary>
        static void ResumeAt(MusicManager mm)
        {
            if (!songAtSaved || mm == null || mm.battleTheme == null || mm.battleTheme.clip == null) return;
            songAtSaved = false;
            float at = songAt;
            // a clip that is not looping and is already past its end starts over; nothing to seek to
            if (at > 0f && at < mm.battleTheme.clip.length) mm.battleTheme.time = at;
            if (mm.bossTheme != null && mm.bossTheme.clip == mm.battleTheme.clip && mm.bossTheme.isPlaying) mm.bossTheme.time = at;
        }

        /// <summary>Boss entrances going on: the music waits, as in ULTRAKILL, and starts the moment the entrance ends.</summary>
        static int introMusicHolds;

        /// <summary>An entrance starts: whatever was playing stops; the boss's song (when it comes) waits.</summary>
        static void HoldMusicForIntro()
        {
            introMusicHolds++;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null) return;
            // where the song was when the entrance began: it goes on from there once the entrance is over
            SaveAt(mm);
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
            // the song is not over: only the death scene is. It comes back where it was
            SaveAt(mm);
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
                // the song was cut off by the entrance: it goes on from where it was
                ResumeAt(mm);
                mm.battleTheme.volume = 0f;
                if (mm.cleanTheme != null) mm.cleanTheme.volume = 0f;
                if (mm.bossTheme != null) mm.bossTheme.volume = 0f;
                mm.targetTheme = mm.battleTheme;
                StartCoroutine(FadeInBattle(mm));
                Plugin.Log.LogInfo("music: " + mm.battleTheme.clip.name + " goes on as the entrance ends");
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
            // a pause stops every source, so isPlaying reads false for the song that is merely held: the "random song
            // has run out" check below would take that for an ending and draw a new song four times a second
            if (mcPaused) return;
            var mm = MonoSingleton<MusicManager>.Instance;
            if (mm == null) return;
            // the rank screen leaves the effects bus silent (it writes allPitch on allSound as its time scale falls
            // and never puts it back): a run that went far enough comes back with every sound muffled
            AudioTune.RestoreEffects();
            // the calm version belongs to the Grind's pauses between waves: out of it, the song goes back to itself
            if (calmLoaded && !(grindRunning && grindIn))
            {
                calmLoaded = false;
                if (mm.cleanTheme != null) mm.cleanTheme.clip = null;
                if (calmHandle.IsValid()) Addressables.Release(calmHandle);
                calmHandle = default;
                // the song itself stays put: only the calm version goes
            }
            // a boss's death scene over: its music back (unless Minecraft has already changed the song)
            if (musicOutroStopped && outroPlaying.Count == 0)
            {
                musicOutroStopped = false;
                if (songInPlayer != null) RestartMusic(mm);
            }
            // a random song that has run out: the next draw comes in, so the fight's music goes on. Only during a
            // fight, though: out of one the song is meant to play out and stop, and without this the draw kept the
            // music going for as long as the game ran - the world left, its enemies gone, the songs still cycling
            if (fightNow && musicChoice == "random" && themeSong == null && songInPlayer != null && !calmPending
                && mm.battleTheme != null && mm.battleTheme.clip != null && mm.targetTheme == mm.battleTheme
                && !mm.battleTheme.isPlaying)
            {
                string next = WantedSong();
                if (next != null && next != songInPlayer) LoadSong(next, false);
            }
            bool fight;
            try { fight = InFight(mm); }
            catch (Exception) { fight = false; }
            // every check, not only the first: a song loaded mid-fight goes through StartMusic again, which would
            // leave it on the silent clean theme
            if (fight) PlayForFight(mm);
            if (fight == fightNow) return;
            fightNow = fight;
            if (Net.Connected) Net.Send("FIGHT " + (fight ? 1 : 0));
            if (fight) MusicChanged();
            else
            {
                fightEndedAt = Time.time;
                // the fight is over and the Grind is not the one playing: the song ends here. It used to be left to
                // play out, which meant a song started by a fight went on for as long as the game ran - "fight music
                // off" in Minecraft's world only stopped the next one from starting
                if (!grindRunning && !grindIn) StopUkMusic();
            }
        }

        /// <summary>Debug: MUSICINFO.</summary>
        string MusicInfo()
        {
            var mm = MonoSingleton<MusicManager>.Instance;
            var sb = new StringBuilder("MUSICINFO choice=" + musicChoice + " theme=" + (themeSong ?? "-") + " playing=" + (songInPlayer ?? "-") + " calm=" + musicCalm
                                       + " fight=" + fightNow + " songs=" + SongKeys().Count + " world=" + worldMusic + " muted=" + mutedSongs.Count);
            if (mm != null)
            {
                sb.Append(" off=").Append(mm.off).Append(" forcedOff=").Append(mm.forcedOff).Append(" requested=").Append(mm.requestedThemes)
                  .Append(" battle=").Append(mm.battleTheme != null && mm.battleTheme.clip != null ? mm.battleTheme.clip.name + "@" + mm.battleTheme.volume.ToString("0.00") + (mm.battleTheme.isPlaying ? "+" : "") : "-")
                  .Append(" clean=").Append(mm.cleanTheme != null && mm.cleanTheme.clip != null ? mm.cleanTheme.clip.name + "@" + mm.cleanTheme.volume.ToString("0.00") : "-")
                  .Append(" target=").Append(mm.targetTheme != null ? mm.targetTheme.name : "-");
            }
            else sb.Append(" no music manager");
            var am = MonoSingleton<AudioMixerController>.Instance;
            if (am != null && am.musicSound != null)
            {
                sb.Append(" mix=").Append(am.musicSound.GetFloat("allVolume", out float db) ? db.ToString("0.0") + "dB" : "?")
                  .Append(" pitch=").Append(am.musicSound.GetFloat("allPitch", out float pt) ? pt.ToString("0.00") : "?")
                  .Append(" want=").Append(am.optionsMusicVolume.ToString("0.00")).Append(" forceOff=").Append(am.forceOff);
            }
            return sb.ToString();
        }
    }
}
