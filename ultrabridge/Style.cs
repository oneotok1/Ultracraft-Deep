// UltraBridge: V1's style rank for Minecraft's style rewards (STYLE rank, whenever it changes): kills at the top ranks
// count toward a streak there, and pay out in experience and loot.

using System;
using UnityEngine;

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

        /// <summary>
        /// ULTRAKILL's air bonus: the lines StyleCalculator pays itself are scaled by a multiplier that climbs from
        /// one to three while V1 is off the ground or sliding, and falls back once landed (StyleCalculator.Update).
        /// That is its private AddPoints(points, pointName, eid, sourceWeapon): it books points + num, with num
        /// rounded from (points * airTime - points), and this repeats that arithmetic rather than the tidier
        /// rounding of points * airTime so the two never disagree by a point. Lines that go straight to StyleHUD
        /// (AIR SLAM, AIRSHOT, FIREWORKS, SPLATTERED) never see the multiplier, so they must not come through here.
        /// </summary>
        static int AirScaled(int points)
        {
            try
            {
                var calc = MonoSingleton<StyleCalculator>.Instance;
                if (calc != null)
                {
                    float airTime = calc.airTime;
                    return points + Mathf.RoundToInt(points * airTime - points);
                }
            }
            catch (Exception) { }
            return points;
        }
    }
}
