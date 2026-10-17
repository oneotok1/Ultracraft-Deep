// UltraBridge: V1's style rank for Minecraft's style rewards (STYLE rank, whenever it changes): kills at the top ranks
// count toward a streak there, and pay out in experience and loot.

using System;

namespace UltraBridge
{
    public partial class Bridge
    {
        int rankSent = -1;

        void UpdateStyleRank()
        {
            if (!levelPrepared || !Net.Connected) return;
            int rank;
            try
            {
                var hud = MonoSingleton<StyleHUD>.Instance;
                if (hud == null) return;
                rank = hud.rankIndex;
            }
            catch (Exception) { return; }
            if (rank == rankSent) return;
            rankSent = rank;
            Net.Send("STYLE " + rank);
        }

        /// <summary>
        /// Count a Minecraft kill toward ULTRAKILL's multikill combo. Vanilla does that at the tail of
        /// StyleCalculator.HitCalculator, which only runs for ULTRAKILL's own enemies; a Minecraft mob dies in
        /// Minecraft, so its kill never reaches that tail and two mobs dying together never read as a double kill.
        /// </summary>
        void AddMultiKill()
        {
            try
            {
                var calc = MonoSingleton<StyleCalculator>.Instance;
                if (calc != null) calc.AddToMultiKill(null);
            }
            catch (Exception) { }
        }
    }
}
