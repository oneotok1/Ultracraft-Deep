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
    }
}
