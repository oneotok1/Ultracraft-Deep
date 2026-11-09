using System;
using UnityEngine;
using UnityEngine.Audio;

namespace UltraBridge
{
    /// <summary>
    /// Two things about ULTRAKILL's sound that only show up once a Cyber Grind run gets far enough.
    ///
    /// The first is voice count. Unity mixes a limited number of voices at once (32 by default) and virtualises the
    /// rest: past that limit a source is stopped and restarted as others come and go, which is heard as sounds
    /// cutting in and out rather than as a drop in volume. A late wave has hundreds of enemies with a source each,
    /// so the limit is raised once at startup.
    ///
    /// The second is the mixer state the rank screen leaves behind. FinalCyberRank writes allPitch/allVolume on
    /// allSound itself as its time scale falls (FinalCyberRank.cs:237-244, :427-430) and nothing puts them back -
    /// ULTRAKILL gets away with it because a level reload builds a fresh controller, and the Cyber Grind here never
    /// reloads the level. Music.cs restores musicSound for the same reason; this restores the effects bus.
    /// </summary>
    static class AudioTune
    {
        /// <summary>Voices to mix at once. 32 is Unity's default; a late Cyber Grind wave has several times that
        /// many sources alive, and every one past the limit is a source that gets cut and restarted.</summary>
        const int RealVoices = 128;

        static bool tuned;

        /// <summary>Raise the voice limit. Safe to call more than once: it only ever raises, never lowers, so a
        /// player who set their own higher value keeps it.</summary>
        internal static void RaiseVoices()
        {
            if (tuned) return;
            try
            {
                var cfg = AudioSettings.GetConfiguration();
                if (cfg.numRealVoices >= RealVoices) { tuned = true; return; }
                int was = cfg.numRealVoices;
                cfg.numRealVoices = RealVoices;
                // virtual voices are the pool the mixer draws on when more sources are alive than it can mix; keeping
                // it at four times the real count is what Unity itself defaults to (32 real / 512 virtual is not,
                // but 4x leaves room for the hundreds of sources a late wave holds)
                if (cfg.numVirtualVoices < RealVoices * 4) cfg.numVirtualVoices = RealVoices * 4;
                // Reset() restarts the audio device, so it is done once, at startup, before anything is playing
                if (!AudioSettings.Reset(cfg))
                {
                    Plugin.Log.LogWarning("audio: could not raise the voice limit from " + was);
                    tuned = true;
                    return;
                }
                tuned = true;
                Plugin.Log.LogInfo("audio: voices " + was + " -> " + cfg.numRealVoices + " (virtual " + cfg.numVirtualVoices + ")");
            }
            catch (Exception e) { Plugin.Log.LogWarning("audio: voice limit: " + e.Message); }
        }

        /// <summary>Puts the effects bus back the way the player set it, when the rank screen has left it silent.
        /// Only touches a bus that is at the bottom of its range: anything still audible was set on purpose.</summary>
        internal static void RestoreEffects()
        {
            try
            {
                // a pause silences the effects bus on purpose (OptionsManager sets allPitch to 0 on allSound, and the
                // controller then reads that as a volume of 0): putting it back here would talk over the pause
                if (Time.timeScale <= 0f) return;
                var oman = MonoSingleton<OptionsManager>.Instance;
                if (oman != null && oman.paused) return;
                var am = MonoSingleton<AudioMixerController>.Instance;
                if (am == null || am.allSound == null) return;
                if (!am.allSound.GetFloat("allVolume", out float db)) return;
                if (db > -79.5f) return;
                float want = am.sfxVolume > 0f ? am.sfxVolume : MonoSingleton<PrefsManager>.Instance.GetFloat("sfxVolume");
                if (want <= 0f) return; // the player turned the effects down themselves
                am.forceOff = false;
                am.SetSFXVolume(want);
                am.allSound.SetFloat("allPitch", 1f);
                Plugin.Log.LogInfo("audio: effects back up to " + want.ToString("0.00"));
            }
            catch (Exception e) { Plugin.Log.LogDebug("audio restore: " + e.Message); }
        }
    }
}
