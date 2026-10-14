// UltraBridge: fire. A Minecraft mob (or another V1) on fire in Minecraft burns with ULTRAKILL's own fire here (ENTS
// says which are burning); Minecraft doesn't draw its flames over them while ULTRAKILL draws the view.

using UnityEngine;

namespace UltraBridge
{
    public partial class Bridge
    {
        const int MaxProxyFires = 16;
        int proxyFires;

        /// <summary>A Minecraft mob (or another V1) burning: ULTRAKILL's own fire on it, the kind its burning enemies have
        /// (the simple one, quieter: zombies burn in daylight by the dozen), instead of Minecraft's flames.</summary>
        void ProxyFire(McProxy p, bool burning)
        {
            if (burning == (p.fire != null)) return;
            var pool = MonoSingleton<FireObjectPool>.Instance;
            if (!burning)
            {
                if (p.fire != null && pool != null) pool.ReturnFire(p.fire, true);
                else if (p.fire != null) Destroy(p.fire);
                p.fire = null;
                proxyFires = Mathf.Max(0, proxyFires - 1);
                return;
            }
            if (pool == null || p.body == null) return;
            if (proxyFires >= MaxProxyFires)
            {
                // (recounted: a stand-in that went away with its fire took it along)
                proxyFires = 0;
                foreach (var q in proxies.Values) if (q != null && q.fire != null) proxyFires++;
                if (proxyFires >= MaxProxyFires) return;
            }
            var fire = pool.GetFire(true);
            if (fire == null) return;
            var size = p.body.bounds.size;
            fire.transform.SetParent(p.transform, false);
            fire.transform.position = p.body.bounds.center;
            fire.transform.localScale = Vector3.one * Mathf.Min(size.magnitude, 25f);
            foreach (var aud in fire.GetComponentsInChildren<AudioSource>()) aud.volume *= 0.4f;
            p.fire = fire;
            proxyFires++;
        }
    }
}
