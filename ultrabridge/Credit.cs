// UltraBridge: whose kill it was. A teammate's blow on one of our ULTRAKILL's enemies is replayed here as real
// damage (EHIT from Minecraft), so the enemy dies in our game and our style would pay for it. The last teammate to
// land a blow is remembered per enemy; the kill's experience and style then go to them instead.

using System.Collections.Generic;
using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        struct MateCredit
        {
            public int mate;
            public float at;
        }

        // ULTRAKILL enemy instance id -> the teammate (their Minecraft entity id) whose blow landed last
        static readonly Dictionary<int, MateCredit> mateHit = new Dictionary<int, MateCredit>();

        /// <summary>A teammate hurt this enemy: from now until our V1 (or a mob) hits it too, the kill is theirs.</summary>
        public void NoteMateHit(EnemyIdentifier eid, int mate)
        {
            if (eid == null || mate == 0 || mpPeers <= 0) return;
            if (mateHit.Count > 400) SweepMateHits();
            mateHit[eid.GetInstanceID()] = new MateCredit { mate = mate, at = Time.unscaledTime };
        }

        /// <summary>Our own V1 (or a mob) hit it: the teammate's earlier blow no longer claims the kill.</summary>
        public void ClearMateHit(EnemyIdentifier eid)
        {
            if (eid == null || mateHit.Count == 0) return;
            mateHit.Remove(eid.GetInstanceID());
        }

        /// <summary>Whose kill it is, without forgetting it (one death can pay several style lines).</summary>
        public int PeekMateHit(EnemyIdentifier eid)
        {
            if (eid == null || mateHit.Count == 0) return 0;
            return mateHit.TryGetValue(eid.GetInstanceID(), out var c) ? c.mate : 0;
        }

        // a style line that was just sent on a teammate's behalf: the multikill that follows it is theirs too
        static float creditAt = -99f;
        static int creditMate;

        /// <summary>A style line went to a teammate: the multikill chain that follows that same kill is theirs too.</summary>
        public void CreditMultikill(int mate)
        {
            creditAt = Time.unscaledTime;
            creditMate = mate;
        }

        /// <summary>One multikill right after a style line we sent elsewhere is that kill's, not ours (the flag is
        /// spent on the first question, so our own chain is never swallowed twice).</summary>
        public bool TakeMultikill()
        {
            if (creditMate == 0 || Time.unscaledTime - creditAt > 0.5f) return false;
            int mate = creditMate;
            creditMate = 0;
            SendMultiKill(mate);
            return true;
        }

        /// <summary>0 if the kill is ours, the teammate's Minecraft id if it is theirs (and forgets the claim).</summary>
        public int TakeKillCredit(EnemyIdentifier eid)
        {
            if (eid == null || mateHit.Count == 0) return 0;
            int id = eid.GetInstanceID();
            if (!mateHit.TryGetValue(id, out var c)) return 0;
            mateHit.Remove(id);
            return c.mate;
        }

        /// <summary>An enemy that died or vanished long ago can't be killed again: its claim would only leak.</summary>
        void SweepMateHits()
        {
            float now = Time.unscaledTime;
            var stale = new List<int>();
            foreach (var kv in mateHit) if (now - kv.Value.at > 120f) stale.Add(kv.Key);
            foreach (int k in stale) mateHit.Remove(k);
        }
    }

    /// <summary>Style for a kill a teammate made: the points are worked out here (ULTRAKILL only awards them where
    /// the enemy died) but paid in that teammate's game, which is where their style meter, rank and streak live.</summary>
    [HarmonyPatch(typeof(StyleHUD), nameof(StyleHUD.AddPoints))]
    static class StyleToMate
    {
        static bool Prefix(StyleHUD __instance, int points, string pointID, EnemyIdentifier eid)
        {
            if (eid == null || Bridge.I == null) return true;
            int mate = Bridge.I.PeekMateHit(eid);
            if (mate == 0) return true;
            Bridge.I.SendStyleCredit(mate, points, pointID);
            Bridge.I.CreditMultikill(mate);
            return false;
        }
    }

    /// <summary>A multikill that belongs to a teammate's kill is counted in their game, not ours.</summary>
    [HarmonyPatch(typeof(StyleCalculator), nameof(StyleCalculator.AddToMultiKill))]
    static class MultiKillToMate
    {
        static bool Prefix() => Bridge.I == null || !Bridge.I.TakeMultikill();
    }
}
