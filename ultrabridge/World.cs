// UltraBridge, part two: Minecraft's world acting on V1 and ULTRAKILL's things beyond the basics in Plugin.cs.
//
// - Knockback and blasts: Minecraft's hits shove V1 the way they shove Steve (KNOCK, PUSH).
// - Being carried: an elytra glide, a horse, a boat or a bed moves V1 by Minecraft's physics (DRIVE, UNDRIVE).
// - ULTRAKILL's enemies turning up in the dark like Minecraft's monsters (SPAWNAT), and dropping experience (UKDEAD).
// - Minecraft's sun, moon and weather on ULTRAKILL's light (SUN), and rain soaking ULTRAKILL's enemies (WET).
// - The ULTRAKILL shop as a block Minecraft players craft and place (SHOPS, SHOPZONE), with the Cyber Grind run
//   around it (GRIND, GRINDSTATE, GRINDHUD, GRINDCLEAR).
// - Leaves and glass hiding what's behind them pixel for pixel, like Minecraft draws them (TEX, textured SEC faces).
// - ULTRAKILL's enemies wrecking blocks with their beams, flames and pillars of light.
// - ULTRAKILL's blood stains painted onto Minecraft's blocks (STAINS).

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using HarmonyLib;
using TMPro;
using UnityEngine;
using UnityEngine.AddressableAssets;
using UnityEngine.AI;
using UnityEngine.Rendering;
using UnityEngine.UI;

namespace UltraBridge
{
    public partial class Bridge
    {
        void HandleWorld(string cmd, string rest)
        {
            switch (cmd)
            {
                case "KNOCK":
                    HandleKnock(rest);
                    break;
                case "PUSH":
                {
                    // PUSH vx vy vz: a blast, a wind charge or a riptide throws V1 (blocks per tick)
                    var a = rest.Split(' ');
                    var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
                    if (nm == null || nm.dead || driving || a.Length < 3) break;
                    var v = new Vector3(F(a[0]), F(a[1]) * LiftScale, -F(a[2])) * (20f * K);
                    Shove(nm, nm.rb.velocity + v, v);
                    break;
                }
                case "DRIVE":
                {
                    // DRIVE x y z vx vy vz: Minecraft carries V1 (feet; velocity in blocks per tick)
                    var a = rest.Split(' ');
                    if (a.Length < 6) break;
                    driveTarget = McToUk(new Vector3(F(a[0]), F(a[1]), F(a[2]))) + Vector3.up * 1.5f;
                    driveVel = new Vector3(F(a[3]), F(a[4]), -F(a[5])) * (20f * K);
                    driveAt = Time.unscaledTime;
                    driving = true;
                    break;
                }
                case "UNDRIVE":
                {
                    // UNDRIVE vx vy vz: Minecraft lets go of V1, which flies on with that velocity
                    var a = rest.Split(' ');
                    undriveVel = a.Length >= 3 ? new Vector3(F(a[0]), F(a[1]), -F(a[2])) * (20f * K) : Vector3.zero;
                    driving = false;
                    break;
                }
                case "SPAWNAT":
                {
                    // SPAWNAT <enemy> x y z [kind]: one of ULTRAKILL's enemies appears there (feet, Minecraft coordinates);
                    // kind 1 came with the dark (and despawns far away), 2 is a Cyber Grind wave's
                    var a = rest.Split(' ');
                    int kind = a.Length > 4 ? int.Parse(a[4]) : 0;
                    // not while the handover holds the world: Minecraft answers LANDED only once V1 is stood there,
                    // and an enemy that turns up before that lands in the frame the player is being handed into
                    if (a.Length >= 4 && levelPrepared && originSet && !handoffPending) SpawnAt(a[0], new Vector3(F(a[1]), F(a[2]), F(a[3])), kind);
                    else if (kind == 2) Net.Send("GRINDGONE 1");
                    break;
                }
                case "GRINDSTATE":
                {
                    // GRINDSTATE running wave best spawns [in]: the Cyber Grind as Minecraft runs it, whether ULTRAKILL's
                    // enemies spawn in the dark (both shown on every shop's screen), and whether we're in the run (a run
                    // we're not in, the button joins)
                    var a = rest.Split(' ');
                    if (a.Length < 4) break;
                    grindRunning = a[0] == "1";
                    grindWave = int.Parse(a[1]);
                    grindBest = int.Parse(a[2]);
                    spawnsOn = a[3] == "1";
                    grindIn = a.Length > 4 ? a[4] == "1" : grindRunning;
                    foreach (var go in shops.Values) if (go != null) ShopTexts(go);
                    break;
                }
                case "GRINDHUD":
                {
                    // GRINDHUD text: a line in ULTRAKILL's own hint box (the wave starting, the run ending)
                    var hud = levelPrepared ? MonoSingleton<HudMessageReceiver>.Instance : null;
                    if (hud != null) hud.SendHudMessage(rest, "", "", 0, false);
                    break;
                }
                case "GRINDCLEAR":
                    ClearGrind();
                    break;
                case "SHOPPRESS":
                {
                    // debug: SHOPPRESS <name>: press that button on the first shop's screen, as V1's finger would
                    GameObject shop = null;
                    foreach (var g in shops.Values) if (g != null) { shop = g; break; }
                    Transform hit = null;
                    if (shop != null)
                        foreach (var t in shop.GetComponentsInChildren<Transform>(true))
                            if (t.name == rest.Trim() && t.gameObject.activeInHierarchy) { hit = t; break; }
                    if (hit == null)
                    {
                        Net.Send("SHOPPRESSED none " + rest);
                        break;
                    }
                    var button = hit.GetComponent<Button>();
                    if (button != null) button.onClick.Invoke();
                    var sbutton = hit.GetComponent<ShopButton>();
                    if (sbutton != null) AccessTools.Method(typeof(ShopButton), "OnPointerClick").Invoke(sbutton, null);
                    Net.Send("SHOPPRESSED " + hit.name);
                    break;
                }
                case "FXINFO":
                {
                    // debug: FXINFO <radius>: every non-terrain renderer that close to V1, with what draws it (shader, queue,
                    // blend factors, texture): what puts colour or coverage into ULTRAKILL's frame
                    float r = rest.Length > 0 ? F(rest.Trim()) : 20f;
                    var nm = MonoSingleton<NewMovement>.Instance;
                    if (nm == null) break;
                    var sb = new StringBuilder("FXINFO");
                    int n = 0;
                    foreach (var rd in FindObjectsOfType<Renderer>())
                    {
                        if (!rd.enabled || !rd.gameObject.activeInHierarchy || rd.transform.IsChildOf(sectionRoot.transform) || rd.transform.IsChildOf(blockRoot.transform)) continue;
                        if ((rd.bounds.center - nm.transform.position).sqrMagnitude > r * r) continue;
                        foreach (var m in rd.sharedMaterials)
                        {
                            if (m == null || m.renderQueue < 2450) continue;
                            sb.Append(" | ").Append(rd.GetType().Name).Append(':').Append(rd.name).Append(" sh=").Append(m.shader != null ? m.shader.name : "-").Append(" q=").Append(m.renderQueue)
                              .Append(" mat=").Append(m.name);
                            foreach (var prop in new[] { "_SrcBlend", "_DstBlend", "_SrcBlendAlpha", "_DstBlendAlpha", "_ZWrite", "_ColorMask", "_Mode" })
                                if (m.HasProperty(prop)) sb.Append(' ').Append(prop).Append('=').Append(m.GetFloat(prop));
                            if (m.mainTexture != null) sb.Append(" tex=").Append(m.mainTexture.name);
                            sb.Append(" kw=").Append(string.Join(",", m.shaderKeywords));
                            if (++n > 60) break;
                        }
                        if (n > 60) break;
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                case "PROJ":
                {
                    // debug: PROJ x y z (Minecraft's coordinates): where ULTRAKILL's camera puts that point on screen (-1..1)
                    var a = rest.Split(' ');
                    var cc = levelPrepared ? MonoSingleton<CameraController>.Instance : null;
                    if (cc == null || a.Length < 3) break;
                    var v = cc.cam.WorldToViewportPoint(McToUk(new Vector3(F(a[0]), F(a[1]), F(a[2]))));
                    Plugin.Log.LogInfo("PROJ " + rest.Trim() + " -> (" + S(v.x * 2f - 1f) + ", " + S(v.y * 2f - 1f) + ") roll " + S(cc.cam.transform.eulerAngles.z));
                    break;
                }
                case "LEAN":
                    // debug: LEAN deg: ULTRAKILL's camera leans that far on top of its own tilt (0: off)
                    DebugLean.degrees = F(rest.Trim());
                    break;
                case "ENEMIES":
                {
                    // debug: every one of ULTRAKILL's own enemies in the scene, switched off or not (alive, health, where)
                    var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
                    var sb = new StringBuilder("ENEMIES");
                    int n = 0;
                    foreach (var e in Resources.FindObjectsOfTypeAll<EnemyIdentifier>())
                    {
                        if (e == null || !e.gameObject.scene.IsValid() || IsProxy(e) || IsPuppet(e)) continue;
                        n++;
                        var mc = UkToMc(e.transform.position);
                        sb.Append(" | ").Append(e.enemyType).Append(" active=").Append(e.gameObject.activeInHierarchy).Append(" dead=").Append(e.dead)
                          .Append(" hp=").Append(S(e.health)).Append(" at ").Append(mc.ToString("F1"))
                          .Append(" from V1 ").Append(nm != null ? (Vector3.Distance(nm.transform.position, e.transform.position) / K).ToString("0") : "?");
                        var nma = e.GetComponent<UnityEngine.AI.NavMeshAgent>();
                        if (nma != null && nma.enabled) sb.Append(" nav=").Append(nma.isOnNavMesh ? (nma.hasPath ? nma.pathStatus.ToString() : "idle") : "off");
                    }
                    sb.Insert(7, " n=" + n);
                    Plugin.Log.LogInfo(sb.ToString());
                    break;
                }
                case "FPSINFO":
                {
                    // debug: ULTRAKILL's frame rate over its last frames (average, and the slowest one in twenty)
                    int c = Mathf.Min(frameCount, frameTimes.Length);
                    if (c == 0) break;
                    var t = new float[c];
                    System.Array.Copy(frameTimes, t, c);
                    System.Array.Sort(t);
                    float sum = 0f;
                    foreach (var f in t) sum += f;
                    Plugin.Log.LogInfo("FPSINFO " + rest.Trim() + " fps=" + (c / sum).ToString("0") + " avg=" + (sum / c * 1000f).ToString("0.0") + "ms p95="
                        + (t[(int)(c * 0.95f)] * 1000f).ToString("0.0") + "ms target=" + Application.targetFrameRate + " res=" + Screen.width + "x" + Screen.height);
                    break;
                }
                case "CUTINFO":
                {
                    // debug: the leaf/plant faces drawn with holes (sections with them, faces, textures known)
                    int secs = 0, verts = 0;
                    var texs = new HashSet<string>();
                    foreach (var go in sections.Values)
                    {
                        var t = go != null ? go.transform.Find("cutout") : null;
                        var mf = t != null ? t.GetComponent<MeshFilter>() : null;
                        if (mf == null || mf.sharedMesh == null) continue;
                        secs++;
                        verts += mf.sharedMesh.vertexCount;
                        foreach (var m in t.GetComponent<MeshRenderer>().sharedMaterials) if (m != null && m.mainTexture != null) texs.Add(m.mainTexture.name);
                    }
                    Net.Send("CUTINFO sections=" + secs + " verts=" + verts + " textures=" + cutoutTex.Count + " used=" + string.Join(",", texs) + " sec=" + sections.Count + " queue=" + sectionQueue.Count);
                    break;
                }
                case "HEADSHOT":
                {
                    // debug: a revolver headshot on the nearest of ULTRAKILL's own enemies, hard enough to kill (a crit kill)
                    var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
                    if (nm == null) break;
                    EnemyIdentifier best = null;
                    float bd = float.MaxValue;
                    foreach (var e in FindObjectsOfType<EnemyIdentifier>())
                    {
                        if (e == null || e.dead || e.GetComponent<McProxy>() != null || e.GetComponentInParent<UkPuppet>() != null) continue;
                        float d = (e.transform.position - nm.transform.position).sqrMagnitude;
                        if (d < bd) { bd = d; best = e; }
                    }
                    if (best == null) { Plugin.Log.LogInfo("HEADSHOT none"); break; }
                    GameObject head = null;
                    foreach (var c in best.GetComponentsInChildren<Collider>()) if (c.CompareTag("Head")) { head = c.gameObject; break; }
                    var gc = MonoSingleton<GunControl>.Instance;
                    best.hitter = "revolver";
                    best.DeliverDamage(head != null ? head : best.gameObject, (best.transform.position - nm.transform.position).normalized * 5000f,
                        head != null ? head.transform.position : best.transform.position, 50f, true, 1f, gc != null ? gc.currentWeapon : null);
                    Plugin.Log.LogInfo("HEADSHOT " + best.enemyType + " head=" + (head != null ? head.name : "none") + " dead=" + best.dead);
                    break;
                }
                case "BLOODINFO":
                {
                    // debug: ULTRAKILL blood on screen now (splatters and blood particle systems, where they are from V1)
                    var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
                    var from = nm != null ? nm.transform.position : Vector3.zero;
                    var sb = new StringBuilder("BLOODINFO");
                    int n = 0;
                    foreach (var ps in FindObjectsOfType<ParticleSystem>())
                    {
                        if (ps == null || !ps.isPlaying || ps.particleCount == 0) continue;
                        var root = ps.transform.root;
                        string nm2 = ps.gameObject.name;
                        bool blood = ps.GetComponentInParent<Bloodsplatter>() != null || nm2.IndexOf("blood", StringComparison.OrdinalIgnoreCase) >= 0
                                     || root.name.IndexOf("gore", StringComparison.OrdinalIgnoreCase) >= 0 || nm2.IndexOf("chest", StringComparison.OrdinalIgnoreCase) >= 0;
                        if (!blood) continue;
                        if (n++ < 25)
                            sb.Append(" | ").Append(nm2).Append(" in ").Append(root.name).Append(" n=").Append(ps.particleCount)
                              .Append(" loop=").Append(ps.main.loop).Append(" t=").Append(ps.time.ToString("0.0")).Append(" at ").Append(((ps.transform.position - from) / K).ToString("F1"))
                              .Append(" scale=").Append(ps.transform.lossyScale.x.ToString("0.0"));
                    }
                    sb.Insert(9, " playing=" + n);
                    // drops hanging in the air: barely moving, more than half a block over whatever is below
                    var buf = new ParticleSystem.Particle[512];
                    var hang = new StringBuilder();
                    int hanging = 0;
                    foreach (var ps in FindObjectsOfType<ParticleSystem>())
                    {
                        if (ps == null || ps.particleCount == 0) continue;
                        int c = ps.GetParticles(buf);
                        bool worldSpace = ps.main.simulationSpace == ParticleSystemSimulationSpace.World;
                        int here = 0;
                        Vector3 sample = Vector3.zero, vel = Vector3.zero;
                        for (int i = 0; i < c; i++)
                        {
                            var pos = worldSpace ? buf[i].position : ps.transform.TransformPoint(buf[i].position);
                            if (buf[i].velocity.magnitude > 1.5f) continue;
                            if (Physics.Raycast(pos, Vector3.down, 0.5f * K, 1 << 8, QueryTriggerInteraction.Ignore)) continue;
                            if (here++ == 0) { sample = pos; vel = buf[i].velocity; }
                        }
                        if (here == 0) continue;
                        hanging += here;
                        Transform t = ps.transform;
                        string path = t.name;
                        for (int k = 0; k < 3 && t.parent != null; k++) { t = t.parent; path = t.name + "/" + path; }
                        hang.Append(" || ").Append(path).Append(" x").Append(here).Append(" of ").Append(c).Append(" at ").Append(((sample - from) / K).ToString("F1"))
                            .Append(" v=").Append(vel.ToString("F2")).Append(" sim=").Append(ps.main.simulationSpace).Append(" grav=").Append(ps.main.gravityModifierMultiplier.ToString("0.0"))
                            .Append(" coll=").Append(ps.collision.enabled).Append(" playing=").Append(ps.isPlaying);
                    }
                    sb.Append(" ## hanging=").Append(hanging).Append(hang).Append(" ## v1 at ").Append(UkToMc(from).ToString("F1"));
                    Plugin.Log.LogInfo(sb.ToString());
                    break;
                }
                case "STAININFO":
                {
                    // debug: ULTRAKILL's stain system and what went to Minecraft
                    var bsm = levelPrepared ? MonoSingleton<BloodsplatterManager>.Instance : null;
                    var sb = new StringBuilder("STAININFO sent=" + stainsSent + " queued=" + stainPos.Count + " size=" + S(StainSize()));
                    if (bsm != null)
                    {
                        sb.Append(" compute=").Append(bsm.usedComputeShadersAtStart).Append(" chance=").Append(S(bsm.GetBloodstainChance()))
                          .Append(" gore=").Append(bsm.goreOn);
                        if (bsm.stainMesh != null) sb.Append(" mesh=").Append(bsm.stainMesh.bounds.size);
                        if (bsm.stainMat != null) sb.Append(" mat=").Append(bsm.stainMat.shader.name);
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                case "SUN":
                {
                    // SUN angle day rain thunder sky: Minecraft's sun angle (degrees, 0 = noon), its daylight (0..1),
                    // rain and thunder (0..1), and the dimension's sky (0 overworld, 1 nether, 2 end)
                    var a = rest.Split(' ');
                    if (a.Length < 5) break;
                    sunAngle = F(a[0]);
                    sunDay = F(a[1]);
                    sunRain = F(a[2]);
                    sunThunder = F(a[3]);
                    sunSky = int.Parse(a[4]);
                    sunKnown = true;
                    break;
                }
                case "WET":
                    ApplyWet(rest);
                    break;
                case "SHOPS":
                    ApplyShops(rest);
                    break;
                case "PADS":
                    ApplyPads(rest);
                    break;
                case "HIDE":
                    ApplyHidden(rest);
                    break;
                case "TEX":
                    ApplyTexture(rest);
                    break;
                case "SPAWNABLES":
                {
                    // debug: every enemy the Sandbox can spawn (its name, its type)
                    var sb = new StringBuilder("SPAWNABLES");
                    var seen = new HashSet<string>();
                    foreach (var db in Resources.FindObjectsOfTypeAll<SpawnableObjectsDatabase>())
                        if (db.enemies != null)
                            foreach (var e in db.enemies)
                                if (e != null && seen.Add(e.objectName)) sb.Append(" | ").Append(e.objectName).Append('=').Append(e.enemyType);
                    Net.Send(sb.ToString());
                    break;
                }
                case "SHOPRQ":
                {
                    // debug: how each part of a shop is drawn (queue, shader, depth test and write), to find what
                    // draws over Minecraft's blocks
                    var sb = new StringBuilder("SHOPRQ");
                    var seen = new HashSet<string>();
                    foreach (var go in shops.Values)
                    {
                        if (go == null) continue;
                        foreach (var r in go.GetComponentsInChildren<Renderer>(false))
                        {
                            foreach (var m in r.sharedMaterials)
                            {
                                if (m == null) continue;
                                string zt = m.HasProperty("_ZTest") ? m.GetInt("_ZTest").ToString() : "-";
                                string zw = m.HasProperty("_ZWrite") ? m.GetInt("_ZWrite").ToString() : "-";
                                var key = r.GetType().Name + ":" + r.name + " q=" + m.renderQueue + " sh=" + (m.shader != null ? m.shader.name : "?") + " zt=" + zt + " zw=" + zw
                                    + " layer=" + r.gameObject.layer + " y=" + S(r.bounds.center.y - go.transform.position.y);
                                if (seen.Add(key)) sb.Append(" | ").Append(key);
                            }
                        }
                        foreach (var cv in go.GetComponentsInChildren<Canvas>(false))
                            if (seen.Add("canvas" + cv.name)) sb.Append(" | Canvas:").Append(cv.name).Append(" mode=").Append(cv.renderMode).Append(" order=").Append(cv.sortingOrder);
                        break;
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                case "SHOPINFO":
                {
                    // debug: the shop template and the shops standing in the world
                    var sb = new StringBuilder("SHOPINFO prefab=" + (shopPrefab != null ? shopPrefab.name : "none") + " failed=" + shopFailed
                                               + " term=" + shopTerm.size + " front=" + shopFront + " scale=" + S(shopScale) + " touch=" + shopTouch + " near=" + shopNear
                                               + " screen=" + Screen.width + "x" + Screen.height);
                    foreach (var kv in shops)
                    {
                        if (kv.Value == null) continue;
                        var z = kv.Value.GetComponent<ScreenZone>();
                        var canvas = kv.Value.GetComponentInChildren<Canvas>(true);
                        sb.Append(" | ").Append(kv.Value.name).Append(" at ").Append(UkToMc(kv.Value.transform.position)).Append(" zone=").Append(ZoneIn(z))
                          .Append(" touch=").Append(z != null && ZoneTouch(z)).Append(" screen=").Append(canvas != null && canvas.gameObject.activeInHierarchy);
                        var panel = kv.Value.transform.Find("Canvas/Background/Main Panel");
                        if (panel != null)
                        {
                            sb.Append(" pages=");
                            foreach (Transform t in panel) if (t.gameObject.activeSelf) sb.Append(t.name.Replace(' ', '_')).Append(',');
                        }
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                case "SUNINFO":
                {
                    // debug: ULTRAKILL's sun as Minecraft's sky has set it
                    var sb = new StringBuilder("SUNINFO known=" + sunKnown + " angle=" + S(sunAngle) + " day=" + S(sunDay) + " rain=" + S(sunRain));
                    foreach (var l in suns) if (l != null) sb.Append(" | ").Append(l.name).Append(" i=").Append(S(l.intensity)).Append(" fwd=").Append(l.transform.forward).Append(" on=").Append(l.enabled);
                    Net.Send(sb.ToString());
                    break;
                }
                case "NUKETEST":
                {
                    // debug: NUKETEST x y z (Minecraft coordinates): ULTRAKILL's super explosion there, harmless; says
                    // whether it reads as the nuke and how big it is
                    var a = rest.Split(' ');
                    var drm = MonoSingleton<DefaultReferenceManager>.Instance;
                    if (drm == null || drm.superExplosion == null || !originSet || a.Length < 3) break;
                    var go = Instantiate(drm.superExplosion, McToUk(new Vector3(F(a[0]), F(a[1]), F(a[2]))), Quaternion.identity);
                    var exs = go.GetComponentsInChildren<Explosion>(true);
                    var sb = new StringBuilder("NUKETEST " + go.name + " spheres=" + exs.Length);
                    foreach (var ex in exs)
                    {
                        sb.Append(" nuke=").Append(IsNuke(ex)).Append(" max=").Append(S(ex.maxSize));
                        ex.harmless = true;
                    }
                    Net.Send(sb.ToString());
                    break;
                }
                case "PUSHINFO":
                {
                    // debug: V1's velocity and the part of it that is Minecraft's shove
                    var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
                    if (nm != null) Net.Send("PUSHINFO vel=" + nm.rb.velocity + " shove=" + shove + " driving=" + driving + " kinematic=" + nm.rb.isKinematic + " pos=" + UkToMc(nm.transform.position));
                    break;
                }
                default:
                    HandleProgress(cmd, rest);
                    break;
            }
        }

        void ResetWorldExtras()
        {
            // a handover in flight belonged to the scene that is going and its teleport will never be answered: say so
            // now, or Minecraft sits frozen until its own timeout notices nobody is coming
            tpHoldUntil = 0f;
            settleRun = 0;
            EndHandoff();
            ClearPuppets();
            driving = false;
            wasDriving = false;
            shove = Vector3.zero;
            naturals.Clear();
            lastUkEnemies.Clear();
            ukEnemyAt.Clear();
            suns.Clear();
            sunOrig.Clear();
            rained.Clear();
            rainWater = null;
            shops.Clear();
            shopPrefab = null;
            shopFailed = false;
            shopTouch = false;
            shopNear = false;
            cutoutMats.Clear();
            // the run's enemies go with the world they stood in: clearing the map alone left them in the scene, and
            // the next run (or a walk out to Minecraft and back) found the last one's enemies still standing there
            ClearGrind();
            stainPos.Clear();
            stainNorm.Clear();
            bosses.Clear();
            bossEids.Clear();
            ForgetOutros();
            altButtons.Clear();
            styleSeen = -1;
            // the pads stand in the scene, not in the list: clearing the list alone left them behind, and a run
            // that ended in the main menu (this path) came back to find ULTRAKILL's pads floating in Minecraft's
            foreach (var go in jumpPads) if (go != null) Destroy(go);
            jumpPads.Clear();
            padsWere = "";
            padPrefab = null;
            padLooked = false;
            enemyProgress.Clear();
            litCache.Clear();
            hiddenEnemies.Clear();
        }

        void WorldExtrasDisconnected()
        {
            ClearPuppets();
            driving = false;
            undriveVel = Vector3.zero;
            sunKnown = false;
            RestoreSun();
foreach (var go in jumpPads) if (go != null) Destroy(go);
            jumpPads.Clear();
            padsWere = "";
                        foreach (var go in shops.Values) if (go != null) Destroy(go);
            shops.Clear();
            shopTouch = false;
            shopNear = false;
            stainPos.Clear();
            stainNorm.Clear();
            stainSizeSent = false;
            ClearGrind();
            ClearBosses();
            altButtons.Clear();
        }

        /// <summary>Minecraft moved the origin (V1 again after Steve): what stood around the old one is rebuilt where
        /// Minecraft says next (the shops come back with the next SHOPS).</summary>
        void OriginMoved(bool clearBosses)
        {
            foreach (var go in shops.Values) if (go != null) Destroy(go);
            shops.Clear();
            stainPos.Clear();
            stainNorm.Clear();
            if (clearBosses)
            {
                ClearBosses();
                // another world or dimension: the last one's enemies, living or dead, stay behind with it
                ClearWorldEnemies();
            }
        }

        // ------------------------------------------------------------ bodies

        // when each body was first seen dead
        readonly Dictionary<EnemyIdentifier, float> deadSince = new Dictionary<EnemyIdentifier, float>();
        float nextSweep;
        const float CorpseSeconds = 45f, CorpseBlocks = 96f;

        /// <summary>ULTRAKILL keeps its dead where they fell for good (its levels are small); in Minecraft's world they
        /// piled up, and stayed behind after V1 died or left. A body goes after 45 s, or once V1 is 96 blocks off.</summary>
        void SweepCorpses(NewMovement nm)
        {
            if (Time.unscaledTime < nextSweep) return;
            nextSweep = Time.unscaledTime + 2f;
            var v1 = nm.transform.position;
            float now = Time.unscaledTime;
            foreach (var eid in FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid == null || !eid.dead || IsOurs(eid)) continue;
                if (!deadSince.TryGetValue(eid, out var since)) deadSince[eid] = since = now;
                if (now - since > CorpseSeconds || (eid.transform.position - v1).sqrMagnitude > CorpseBlocks * CorpseBlocks * K * K)
                {
                    deadSince.Remove(eid);
                    Destroy(eid.gameObject);
                }
            }
            if (deadSince.Count > 256)
            {
                var gone = new List<EnemyIdentifier>();
                foreach (var e in deadSince.Keys) if (e == null) gone.Add(e);
                foreach (var e in gone) deadSince.Remove(e);
            }
        }

        /// <summary>Not an enemy of this world: a Minecraft mob's stand-in, another player's V1 or a copy of their
        /// enemy (multiplayer), or a body in the doll's corner.</summary>
        static bool IsOurs(EnemyIdentifier eid)
            => eid.GetComponent<McProxy>() != null || eid.GetComponentInParent<UkPuppet>() != null;

        /// <summary>Every enemy of the world V1 left (its bodies too) goes.</summary>
        void ClearWorldEnemies()
        {
            int n = 0;
            foreach (var eid in FindObjectsOfType<EnemyIdentifier>())
            {
                if (eid == null || IsOurs(eid)) continue;
                Destroy(eid.gameObject);
                n++;
            }
            ukEnemies.Clear();
            ukEnemyAt.Clear();
            naturals.Clear();
            deadSince.Clear();
            hiddenEnemies.Clear();
            enemyProgress.Clear();
            if (n > 0) Plugin.Log.LogInfo("another world: " + n + " enemies and bodies of the last one cleared");
        }

        /// <summary>The origin moved by shift (ULTRAKILL units): ULTRAKILL's enemies and bosses stay where they are in
        /// Minecraft's world.</summary>
        void ShiftActors(Vector3 shift)
        {
            var moved = new HashSet<Transform>();
            void Move(Transform t)
            {
                if (t == null || !moved.Add(t)) return;
                t.position += shift;
                var rb = t.GetComponent<Rigidbody>();
                if (rb != null) rb.position = t.position;
                var agent = t.GetComponent<NavMeshAgent>();
                if (agent != null && agent.enabled && agent.isOnNavMesh) agent.Warp(t.position);
            }
            foreach (var f in bosses.Values)
                foreach (var part in f.parts)
                {
                    if (part.go != null) Move(part.go.transform);
                    part.lastAt += shift;
                    part.ground += shift;
                }
            foreach (var eid in ukEnemies.Values) if (eid != null && !eid.dead && !bossEids.Contains(eid)) Move(eid.transform);
            foreach (var k in new List<int>(ukEnemyAt.Keys)) ukEnemyAt[k] += shift;
            foreach (var pad in jumpPads) if (pad != null) pad.transform.position += shift;
            Physics.SyncTransforms();
            Plugin.Log.LogInfo("origin moved: actors shifted by " + shift);
        }

        float nextExtras;

        // after a teleport V1 waits where it was put until the handover is really over: Minecraft's blocks around a
        // new spot (or around a new origin) arrive over a few frames, and V1 must not drop through terrain that isn't
        // built yet and fall forever. The hold ends once V1 is landed AND there is ground under it - that is the
        // moment Minecraft is told its world may run again. It used to end on a jump-size test that a same-spot
        // handover never passed, so the answer went out on the teleport's own frame and nothing was held at all.
        const float HandoffMax = 5f;
        // the takeover swaps the camera, the HUD and the weapon arm over a few frames: answering before they are
        // done would unfreeze Minecraft into the middle of the very stall the freeze exists to hide
        const float HandoffMin = 0.4f;
        // and the level's first seconds carry one-off loads of its own (the shop prefab, a font pack) that stall
        // ULTRAKILL's main thread for a second or more: a handover answered into that stall is exactly the
        // "frozen for a second right after switching" the player reported, so the answer waits for a run of
        // healthy frames instead of a clock
        const float SettleFrame = 0.1f;
        const int SettleFrames = 3;
        float tpHoldUntil;
        float tpHoldFrom;
        float tpHoldFrameAt;
        int settleRun;
        Vector3 tpHoldAt;
        bool handoffPending;

        void HoldAfterTeleport(Vector3 at)
        {
            // the same handover is announced more than once (READY sends the position again): re-arming on each of
            // those pushed the deadline out forever, so only a spot away from the one already waited on is new
            if (handoffPending && (at - tpHoldAt).sqrMagnitude < 4f) return;
            tpHoldAt = at;
            tpHoldFrom = Time.unscaledTime;
            tpHoldFrameAt = Time.unscaledTime;
            settleRun = 0;
            tpHoldUntil = Time.unscaledTime + HandoffMax;
            // the hold is what Minecraft waits on: it is told when the handover is done (see KeepTeleportHold)
            handoffPending = true;
        }

        void KeepTeleportHold(NewMovement nm)
        {
            if (tpHoldUntil <= 0f) return;
            // both halves matter: ground under V1 while the takeover is still running means camera and HUD are still
            // coming, and a landed V1 with no ground under it drops through the world the moment it is let go. The
            // deadline is the backstop for a handover that never finishes, and it sits under Minecraft's own 5000 ms
            // one so the answer comes from here instead of from that timeout
            bool grounded = nm.gc != null && nm.gc.onGround;
            // the shop's prefab is a one-off load that used to land just after the answer, stalling the frame the
            // player was handed into: it is fetched here instead, while Minecraft is still waiting
            if (shopPrefab == null && !shopFailed && Time.timeSinceLevelLoad > 3f) PrepareShop();
            bool shopDone = shopPrefab != null || shopFailed;
            // healthy frames in a row: a stalled frame breaks the run, so the answer cannot go out in the middle of one
            float dt = Time.unscaledTime - tpHoldFrameAt;
            tpHoldFrameAt = Time.unscaledTime;
            if (dt > 0f && dt < SettleFrame) settleRun++;
            else settleRun = 0;
            bool settled = settleRun >= SettleFrames;
            bool ready = v1Landed && grounded && GroundBuilt(tpHoldAt) && shopDone && settled;
            bool held = Time.unscaledTime - tpHoldFrom >= HandoffMin;
            if ((ready && held) || driving || nm.dead || Time.unscaledTime > tpHoldUntil)
            {
                tpHoldUntil = 0f;
                EndHandoff();
                return;
            }
            nm.transform.position = tpHoldAt;
            nm.rb.position = tpHoldAt;
            nm.rb.velocity = Vector3.zero;
            fallPeak = tpHoldAt.y;
        }

        /// <summary>V1 is where Minecraft put it and the ground under it is there: the world it was holding may run
        /// again. Nothing is sent when no handover is outstanding, so the per-tick TP messages cost nothing.</summary>
        void EndHandoff()
        {
            if (!handoffPending) return;
            handoffPending = false;
            Net.Send("LANDED");
        }

        /// <summary>Whether something solid stands under V1's feet, rather than the ground it needs still being
        /// queued. Cast down instead of reading the section's bounding box: a chunk that carries a hill or a tree
        /// reaches far above the feet of someone standing on its flat part, so the box top says nothing about the
        /// ground here and that test was false even with the terrain fully built.</summary>
        bool GroundBuilt(Vector3 at)
        {
            Vector3 feet = at - Vector3.up * 1.5f;
            // from a little above the feet, so ground exactly at them still counts, far enough down to reach the
            // step below them; the terrain is built on the Environment layer
            return Physics.Raycast(feet + Vector3.up * 0.5f, Vector3.down, out _, 4f, 1 << 8, QueryTriggerInteraction.Ignore);
        }

        void UpdateWorldExtras(NewMovement nm)
        {
            KeepTeleportHold(nm);
            DecayShove(nm);
            UpdateShopZone();
            SendStains();
            FlushBooms();
            UpdateMultiplayer();
            PollStyle();
            UpdateBosses(nm);
            UnhideDead();
            // ready the shop early, so Minecraft gets its looks before anyone places one
            if (shopPrefab == null && !shopFailed && Time.timeSinceLevelLoad > 3f) PrepareShop();
            if (Time.unscaledTime < nextExtras) return;
            nextExtras = Time.unscaledTime + 0.25f;
            DespawnNaturals(nm);
            SweepCorpses(nm);
            ApplySun();
            KeepRain();
            EnemyFlames();
        }

        // ------------------------------------------------------------ knockback

        // the part of V1's velocity that is a Minecraft shove; it fades as Minecraft's air drag would fade it
        // (0.91 a tick), so a hit sends V1 about as far as it sends Steve
        Vector3 shove;
        float shovedAt;
        const float ShoveDrag = 1.886f; // -ln(0.91) * 20 ticks: per second
        // ULTRAKILL's V1 falls slower than Steve (about 34 units/s² against Minecraft's 62): an upward push this much
        // smaller lifts V1 as high as it lifts Steve
        const float LiftScale = 0.75f;

        /// <summary>KNOCK hx hz up mob: Minecraft knocked V1 back (blocks per tick; up &lt; 0 when V1 wasn't on the ground,
        /// so no lift). A mob's swing that's still waiting for its parry window carries its knockback with it.</summary>
        void HandleKnock(string rest)
        {
            var a = rest.Split(' ');
            var nm = levelPrepared ? MonoSingleton<NewMovement>.Instance : null;
            if (nm == null || nm.dead || driving || a.Length < 3) return;
            var push = new Vector3(F(a[0]), 0f, -F(a[1])) * (20f * K);
            float up = F(a[2]);
            float upUk = up < 0f ? -1f : up * 20f * K * LiftScale;
            int mob = a.Length > 3 ? int.Parse(a[3]) : -1;
            if (mob >= 0 && proxies.TryGetValue(mob, out var p) && p != null)
            {
                // punched just before the swing: parried, so nothing lands
                if (Time.time - p.parriedAt < 0.5f) return;
                if (p.pendingHurt > 0)
                {
                    p.pendingKnock = push;
                    p.pendingKnockUp = upUk;
                    p.hasPendingKnock = true;
                    return;
                }
            }
            Knock(nm, push, upUk);
        }

        /// <summary>Minecraft's knockback: half the old speed plus the push away from the hit, and a hop if on the
        /// ground.</summary>
        void Knock(NewMovement nm, Vector3 push, float up)
        {
            var v = nm.rb.velocity;
            var nv = new Vector3(v.x * 0.5f + push.x, up >= 0f ? Mathf.Max(v.y, up) : v.y, v.z * 0.5f + push.z);
            Shove(nm, nv, push);
        }

        void Shove(NewMovement nm, Vector3 velocity, Vector3 added)
        {
            if (nm.rb.isKinematic) return;
            // Launch leaves the ground properly (ULTRAKILL's own knockback): V1 doesn't stick to the floor this instant
            nm.Launch(velocity, 1f, ignoreMass: true);
            shove += new Vector3(added.x, 0f, added.z);
            shovedAt = Time.time;
        }

        void DecayShove(NewMovement nm)
        {
            if (shove == Vector3.zero) return;
            if (nm.dead || nm.rb.isKinematic || mcPaused)
            {
                if (!mcPaused) shove = Vector3.zero;
                return;
            }
            // Launch's push only reaches the velocity at the next physics step
            if (Time.time - shovedAt < 0.05f) return;
            var v = nm.rb.velocity;
            var dir = shove.normalized;
            // stopped by a wall (or V1 turned around): nothing left to slow
            if (Vector3.Dot(new Vector3(v.x, 0f, v.z), dir) <= 0f)
            {
                shove = Vector3.zero;
                return;
            }
            var lost = shove * (1f - Mathf.Exp(-ShoveDrag * Time.deltaTime));
            shove -= lost;
            nm.rb.velocity = v - lost;
            if (shove.sqrMagnitude < 0.25f) shove = Vector3.zero;
        }

        // ------------------------------------------------------------ carried by Minecraft

        // an elytra glide, a horse, a boat, a minecart, a bed: Minecraft moves V1, ULTRAKILL only follows (V1 still
        // looks, shoots and punches)
        bool driving, wasDriving;
        Vector3 driveTarget, driveVel, undriveVel;
        float driveAt;

        void UpdateDrive(NewMovement nm)
        {
            if (!driving)
            {
                if (wasDriving)
                {
                    wasDriving = false;
                    nm.rb.isKinematic = false;
                    nm.rb.velocity = undriveVel;
                    undriveVel = Vector3.zero;
                    fallPeak = nm.transform.position.y;
                }
                return;
            }
            if (Time.unscaledTime - driveAt > 0.5f)
            {
                // Minecraft stopped saying (it paused, or crashed): let go where V1 is
                driving = false;
                undriveVel = Vector3.zero;
                return;
            }
            wasDriving = true;
            shove = Vector3.zero;
            nm.rb.isKinematic = true;
            // between Minecraft's updates V1 carries on the way it was going
            var p = driveTarget + driveVel * Mathf.Min(Time.unscaledTime - driveAt, 0.1f);
            nm.transform.position = p;
            nm.rb.position = p;
            // being carried is no fall: landing from a glide isn't a slam from the height it started
            fallPeak = p.y;
        }

        /// <summary>A teleport ends any ride (Plugin's TP).</summary>
        void StopCarry()
        {
            driving = false;
            wasDriving = false;
            undriveVel = Vector3.zero;
            shove = Vector3.zero;
        }

        // ------------------------------------------------------------ ULTRAKILL's enemies in Minecraft's world

        // enemies Minecraft's spawning rules brought in, and when each was last near V1: far away for long, they go,
        // as Minecraft's monsters despawn
        readonly Dictionary<GameObject, float> naturals = new Dictionary<GameObject, float>();
        const float DespawnBlocks = 72f, DespawnAfter = 30f;

        SpawnableObject FindSpawnable(string name)
        {
            foreach (var db in Resources.FindObjectsOfTypeAll<SpawnableObjectsDatabase>())
            {
                if (db.enemies == null) continue;
                foreach (var e in db.enemies)
                {
                    if (e == null || e.gameObject == null) continue;
                    if (string.Equals(e.objectName, name, StringComparison.OrdinalIgnoreCase) || string.Equals(e.enemyType.ToString(), name, StringComparison.OrdinalIgnoreCase))
                        return e;
                }
            }
            return null;
        }

        // the Cyber Grind's enemies (each one's spawned object): their deaths count toward the wave, and they go when the
        // run ends
        readonly Dictionary<EnemyIdentifier, GameObject> grindEnemies = new Dictionary<EnemyIdentifier, GameObject>();

        /// <summary>The Cyber Grind's enemies that ended without being reported: died before Minecraft ever heard of them
        /// (a kill in their first moment), or gone without dying (fell out of the world). Either way the wave counts them
        /// (GRINDGONE: no experience for one that didn't die).</summary>
        void SweepGrind()
        {
            if (grindEnemies.Count == 0) return;
            List<EnemyIdentifier> ended = null;
            foreach (var kv in grindEnemies)
            {
                var eid = kv.Key;
                bool dead = (object)eid != null && eid.dead;
                if (dead && !ukEnemies.ContainsKey(eid.GetInstanceID()) && !lastUkEnemies.ContainsKey(eid.GetInstanceID())) (ended ??= new List<EnemyIdentifier>()).Add(eid);
                else if (!dead && (eid == null || kv.Value == null)) (ended ??= new List<EnemyIdentifier>()).Add(eid);
            }
            if (ended == null) return;
            foreach (var eid in ended)
            {
                if ((object)eid != null && eid.dead)
                {
                    var go = grindEnemies[eid];
                    var nm = MonoSingleton<NewMovement>.Instance;
                    ReportEnemyDeath(eid, go != null ? go.transform.position : nm != null ? nm.transform.position : Vector3.zero);
                    grindEnemies.Remove(eid);
                }
                else
                {
                    grindEnemies.Remove(eid);
                    Net.Send("GRINDGONE 1");
                    Plugin.Log.LogInfo("grind enemy gone without dying");
                }
            }
        }

        void ClearGrind()
        {
            foreach (var kv in grindEnemies) if (kv.Value != null) Destroy(kv.Value);
            grindEnemies.Clear();
        }

        GameObject SpawnAt(string name, Vector3 mcFeet, int kind)
        {
            var so = FindSpawnable(name);
            var nm = MonoSingleton<NewMovement>.Instance;
            if (so == null || nm == null)
            {
                Net.Send("SPAWNED none " + name);
                // Minecraft stops asking for an enemy this ULTRAKILL doesn't have
                if (so == null) Net.Send("NOSPAWN " + name);
                // a wave's enemy that never came isn't waited for
                if (kind == 2) Net.Send("GRINDGONE 1");
                return null;
            }
            var p = McToUk(mcFeet);
            // stand on the ground there (flyers keep the height Minecraft picked)
            if (Physics.Raycast(p + Vector3.up * 2f, Vector3.down, out var hit, 6f, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore))
                p = hit.point + Vector3.up * so.spawnOffset;
            var look = nm.transform.position - p;
            look.y = 0f;
            var go = Instantiate(so.gameObject, p, Quaternion.LookRotation(look.sqrMagnitude > 0.01f ? look : Vector3.forward));
            (go.GetComponent<Sandbox.EnemySpawnableInstance>() ?? go.AddComponent<Sandbox.EnemySpawnableInstance>()).sourceObject = so;
            // ULTRAKILL's arena entrance: the enemy materializes
            var eid = go.GetComponentInChildren<EnemyIdentifier>(true);
            if (eid != null) eid.spawnIn = true;
            go.SetActive(true);
            if (kind == 1) naturals[go] = Time.time;
            if (kind == 2 && eid != null)
            {
                grindEnemies[eid] = go;
                // its corpse goes the way ULTRAKILL's grind takes that enemy's (Corpses.cs)
                go.AddComponent<GrindCorpse>();
            }
            Net.Send("SPAWNED " + so.objectName + " " + UkToMc(p));
            return go;
        }

        void DespawnNaturals(NewMovement nm)
        {
            if (naturals.Count == 0) return;
            var v1 = nm.transform.position;
            List<GameObject> gone = null;
            foreach (var go in new List<GameObject>(naturals.Keys))
            {
                if (go == null)
                {
                    (gone ??= new List<GameObject>()).Add(go);
                    continue;
                }
                var eid = go.GetComponentInChildren<EnemyIdentifier>();
                if (eid == null || eid.dead)
                {
                    (gone ??= new List<GameObject>()).Add(go);
                    continue;
                }
                if (Vector3.Distance(eid.transform.position, v1) < DespawnBlocks * K) naturals[go] = Time.time;
                else if (Time.time - naturals[go] > DespawnAfter)
                {
                    (gone ??= new List<GameObject>()).Add(go);
                    Destroy(go);
                }
            }
            if (gone != null) foreach (var go in gone) naturals.Remove(go);
        }

        /// <summary>UKDEAD type x y z rank grind: one of ULTRAKILL's enemies died near V1; Minecraft drops its experience
        /// (more the higher V1's style rank). grind is 1 for a Cyber Grind wave's enemy.</summary>
        void ReportEnemyDeath(EnemyIdentifier eid, Vector3 lastAt)
        {
            if (!originSet || IsProxy(eid)) return;
            bool grind = grindEnemies.Remove(eid);
            // still here, or gone with its death (lastAt: its middle when last seen)
            var mid = eid != null ? EnemyBounds(eid).center : lastAt;
            var nm = MonoSingleton<NewMovement>.Instance;
            if (!grind && nm != null && Vector3.Distance(nm.transform.position, mid) > 80f * K) return;
            var at = UkToMc(mid);
            int rank = 0;
            try { rank = MonoSingleton<StyleHUD>.Instance != null ? MonoSingleton<StyleHUD>.Instance.rankIndex : 0; } catch { }
            // whose kill it was: a teammate's last blow takes the experience and the streak to them, not to us
            int credit = TakeKillCredit(eid);
            Net.Send("UKDEAD " + eid.enemyType + " " + S(at.x) + " " + S(at.y) + " " + S(at.z) + " " + rank + " " + (grind ? 1 : 0) + (credit != 0 ? " " + credit : ""));
        }

        // ------------------------------------------------------------ Minecraft's sun and sky

        // the Sandbox's sun follows Minecraft's: across the sky with the time of day, the moon at night, dimmed by rain
        // and thunder; in the Nether and the End a dim, steady light from above
        readonly List<Light> suns = new List<Light>();
        readonly Dictionary<Light, (float intensity, Color color, Quaternion rotation)> sunOrig = new Dictionary<Light, (float, Color, Quaternion)>();
        bool sunKnown;
        float sunAngle, sunDay = 1f, sunRain, sunThunder;
        int sunSky;

        void FindSuns()
        {
            var nm = MonoSingleton<NewMovement>.Instance;
            var player = nm != null ? nm.transform.root : null;
            var cam = MonoSingleton<CameraController>.Instance;
            var camRoot = cam != null ? cam.transform.root : null;
            foreach (var l in FindObjectsOfType<Light>())
            {
                if (l.type != LightType.Directional || l.transform.root == player || l.transform.root == camRoot) continue;
                // V1's portrait has its own lighting
                if (dollRoot != null && l.transform.IsChildOf(dollRoot.transform)) continue;
                if (l.name.StartsWith("doll")) continue;
                suns.Add(l);
                sunOrig[l] = (l.intensity, l.color, l.transform.rotation);
            }
        }

        void ApplySun()
        {
            if (!sunKnown) return;
            if (suns.Count == 0) FindSuns();
            float th = sunAngle * Mathf.Deg2Rad;
            // toward the sun, in Minecraft's space: it rises in the east, stands overhead at noon, sets in the west
            var toSun = new Vector3(-Mathf.Sin(th), Mathf.Cos(th), 0f);
            Vector3 toLight;
            float strength;
            Color tint;
            if (sunSky == 1)
            {
                // the Nether: no sky, the glow of lava from everywhere
                toLight = new Vector3(0.3f, 1f, 0.2f).normalized;
                strength = 0.35f;
                tint = new Color(1f, 0.7f, 0.55f);
            }
            else if (sunSky == 2)
            {
                toLight = new Vector3(-0.2f, 1f, 0.3f).normalized;
                strength = 0.3f;
                tint = new Color(0.8f, 0.7f, 1f);
            }
            else
            {
                // the sun while it's up (fading through dawn and dusk), the moon's paler light once it has set
                float sunUp = Mathf.Clamp01((toSun.y + 0.1f) / 0.3f);
                bool moon = sunUp <= 0f;
                toLight = moon ? -toSun : toSun;
                // never quite along the horizon: a light skimming the ground lights nothing
                if (toLight.y < 0.15f) toLight = new Vector3(toLight.x, 0.15f, toLight.z).normalized;
                strength = moon ? 0.25f : Mathf.Lerp(0.25f, 1f, sunUp) * Mathf.Lerp(0.5f, 1f, Mathf.Clamp01(sunDay));
                tint = moon ? new Color(0.62f, 0.7f, 1f) : Color.Lerp(new Color(1f, 0.75f, 0.55f), Color.white, Mathf.Clamp01(toSun.y * 3f));
                strength *= (1f - 0.5f * sunRain) * (1f - 0.3f * sunThunder);
            }
            var toLightUk = new Vector3(toLight.x, toLight.y, -toLight.z);
            var rot = Quaternion.LookRotation(-toLightUk, Vector3.up);
            foreach (var l in suns)
            {
                if (l == null || !sunOrig.TryGetValue(l, out var o)) continue;
                l.transform.rotation = rot;
                l.intensity = o.intensity * strength;
                l.color = o.color * tint;
            }
        }

        void RestoreSun()
        {
            foreach (var l in suns)
            {
                if (l == null || !sunOrig.TryGetValue(l, out var o)) continue;
                l.transform.rotation = o.rotation;
                l.intensity = o.intensity;
                l.color = o.color;
            }
        }

        // ------------------------------------------------------------ rain

        // everything Minecraft's rain falls on is wet as in water: its fires go out and won't catch, it drips as it
        // dries, and electricity arcs between everything in the same rain as through one pool
        GameObject rainWaterGo;
        Water rainWater;
        readonly HashSet<EnemyIdentifier> rained = new HashSet<EnemyIdentifier>();
        float nextResoak;

        Water RainWater()
        {
            if (rainWater != null) return rainWater;
            // a Water no one can enter: only a name for "the same water" (it never runs, so it needs no colliders)
            rainWaterGo = new GameObject("Minecraft rain");
            rainWaterGo.SetActive(false);
            rainWater = rainWaterGo.AddComponent<Water>();
            return rainWater;
        }

        /// <summary>WET id,id,...: ULTRAKILL's enemies the rain is falling on now.</summary>
        void ApplyWet(string rest)
        {
            if (!levelPrepared) return;
            var now = new HashSet<EnemyIdentifier>();
            foreach (var s in rest.Split(','))
            {
                if (s.Length == 0 || !int.TryParse(s, out int id)) continue;
                if (ukEnemies.TryGetValue(id, out var eid) && eid != null && !eid.dead) now.Add(eid);
            }
            foreach (var eid in now) if (!rained.Contains(eid)) Soak(eid);
            foreach (var eid in rained) if (eid != null && !now.Contains(eid)) DryOff(eid);
            rained.Clear();
            foreach (var eid in now) rained.Add(eid);
        }

        void Soak(EnemyIdentifier eid)
        {
            foreach (var f in eid.GetComponentsInChildren<Flammable>()) f.PutOut();
            var wet = eid.GetComponent<Wet>() ?? eid.gameObject.AddComponent<Wet>();
            wet.Refill();
            eid.touchingWaters.Add(RainWater());
        }

        void DryOff(EnemyIdentifier eid)
        {
            if (rainWater != null) eid.touchingWaters.Remove(rainWater);
            var wet = eid.GetComponent<Wet>();
            if (wet == null) return;
            if (FindObjectOfType<PooledWaterStore>() == null) new GameObject("Ultracraft Water Pools").AddComponent<PooledWaterStore>();
            wet.Dry(eid.transform.InverseTransformPoint(EnemyBounds(eid).center));
        }

        /// <summary>Still in the rain: anything that caught fire meanwhile (a Firestarter) is put out again.</summary>
        void KeepRain()
        {
            if (rained.Count == 0 || Time.unscaledTime < nextResoak) return;
            nextResoak = Time.unscaledTime + 1f;
            rained.RemoveWhere(e => e == null || e.dead);
            foreach (var eid in rained)
                foreach (var f in eid.GetComponentsInChildren<Flammable>())
                    if (f.burning) f.PutOut();
        }

        // ------------------------------------------------------------ the shop

        // ULTRAKILL's campaign shop as a block Minecraft players craft and place: SmileOS 2.0 with its main menu
        // (weapons, enemies, the Cyber Grind, the Sandbox), tip of the day, variations, colours, its music and its
        // drawer. It is loaded the way ULTRAKILL loads it into its own levels (Addressables) and stands at its real size
        // in the block's 2 x 3 x 1, facing where Minecraft's block faces.
        const string ShopAddress = "Assets/Prefabs/Levels/Shop.prefab";
        static readonly string[] TerminalParts = { "ShopTerminal/ShopTerminal", "ShopTerminal/ShopTerminal_Drawer" };
        GameObject shopPrefab;
        Bounds shopTerm;      // the terminal (body and drawer) in the prefab's upright space (see Upright)
        Vector3 shopFront;    // the way its screen faces, in that space
        float shopScale;      // the root's scale as placed (ULTRAKILL's own, unless that wouldn't fit)
        bool shopFailed, shopTouch, shopNear;
        bool grindRunning, grindIn, spawnsOn = true;
        // Minecraft's world is there: the Sandbox outlives it (it stays on its menu scene, its enemies still
        // standing), so "an enemy is alive" alone does not mean a fight is on. TP marks a world in, WORLDRESET out.
        bool mcInWorld;
        int grindWave, grindBest;
        readonly Dictionary<string, GameObject> shops = new Dictionary<string, GameObject>();
        static readonly AccessTools.FieldRef<ScreenZone, bool> ZoneTouch = AccessTools.FieldRefAccess<ScreenZone, bool>("touchMode");
        static readonly AccessTools.FieldRef<ScreenZone, bool> ZoneInside = AccessTools.FieldRefAccess<ScreenZone, bool>("inZone");

        static bool ZoneIn(ScreenZone z) => z != null && ZoneInside(z);

        /// <summary>The prefab as it stands, its place and size taken out but not its turn: its root is stored upside
        /// down (the terminal hangs below it), and only the prefab's own rotation stands it up.</summary>
        static Matrix4x4 Upright(Transform root) => Matrix4x4.Scale(Vector3.one / root.localScale.x) * Matrix4x4.Translate(-root.position);

        bool PrepareShop()
        {
            if (shopPrefab != null) return true;
            if (shopFailed) return false;
            try
            {
                var prefab = AssetHelper.LoadPrefab(ShopAddress);
                if (prefab == null) throw new Exception(ShopAddress + " didn't load");
                var root = prefab.transform;
                var toRoot = Upright(root);
                bool any = false;
                foreach (var path in TerminalParts)
                {
                    var t = root.Find(path);
                    var mf = t != null ? t.GetComponent<MeshFilter>() : null;
                    if (mf == null || mf.sharedMesh == null) continue;
                    var m = toRoot * t.localToWorldMatrix;
                    var b = mf.sharedMesh.bounds;
                    for (int i = 0; i < 8; i++)
                    {
                        var corner = b.center + Vector3.Scale(b.extents, new Vector3((i & 1) == 0 ? -1f : 1f, (i & 2) == 0 ? -1f : 1f, (i & 4) == 0 ? -1f : 1f));
                        var c = m.MultiplyPoint3x4(corner);
                        if (!any) shopTerm = new Bounds(c, Vector3.zero);
                        else shopTerm.Encapsulate(c);
                        any = true;
                    }
                }
                if (!any) throw new Exception("the shop has no terminal");
                // its screen faces the way its walk-up zone reaches out (square to the terminal: the nearest axis)
                var zone = prefab.GetComponent<BoxCollider>();
                var front = zone != null ? toRoot.MultiplyPoint3x4(root.TransformPoint(zone.center)) - shopTerm.center : Vector3.forward;
                shopFront = Mathf.Abs(front.x) > Mathf.Abs(front.z) ? new Vector3(Mathf.Sign(front.x), 0f, 0f) : new Vector3(0f, 0f, front.z < 0f ? -1f : 1f);
                // 2 x 3 x 1 blocks: ULTRAKILL's own size fits (about 1.8 x 3 x 0.9)
                var size = shopTerm.size;
                bool alongZ = Mathf.Abs(shopFront.z) >= Mathf.Abs(shopFront.x);
                float width = alongZ ? size.x : size.z, depth = alongZ ? size.z : size.x;
                float s = root.localScale.x;
                shopScale = s * Mathf.Min(1f, 2f * K / (width * s), 3f * K / (size.y * s), 1f * K / (depth * s));
                shopPrefab = prefab;
                Plugin.Log.LogInfo("shop: " + prefab.name + ", terminal " + (size * s) + " units, placed at " + S(shopScale / s) + "x");
                try { DumpShopForMinecraft(); }
                catch (Exception e) { Plugin.Log.LogWarning("shop looks for Minecraft: " + e.Message); }
                return true;
            }
            catch (Exception e)
            {
                shopFailed = true;
                Plugin.Log.LogWarning("shop: " + e.Message);
                return false;
            }
        }

        /// <summary>The terminal for Minecraft to draw itself where V1 isn't (%TEMP%/ultracraft_shop2.*): its mesh in
        /// blocks, standing on the middle of its floor with its screen to the north, and its textures.</summary>
        void DumpShopForMinecraft()
        {
            var root = shopPrefab.transform;
            var toRoot = Upright(root);
            // the prefab's space to Minecraft's blocks: from its floor's middle, scaled as placed, its screen turned to
            // ULTRAKILL's +z, which is Minecraft's north once z is flipped
            var foot = new Vector3(shopTerm.center.x, shopTerm.min.y, shopTerm.center.z);
            var turn = Quaternion.Inverse(Quaternion.LookRotation(shopFront, Vector3.up));
            var verts = new List<Vector3>();
            var norms = new List<Vector3>();
            var uvs = new List<Vector2>();
            var idx = new List<int>();
            Material mat = null;
            foreach (var path in TerminalParts)
            {
                var t = root.Find(path);
                var mf = t != null ? t.GetComponent<MeshFilter>() : null;
                var mesh = mf != null ? mf.sharedMesh : null;
                if (mesh == null || !mesh.isReadable) continue;
                if (mat == null)
                {
                    var r = t.GetComponent<MeshRenderer>();
                    if (r != null) mat = r.sharedMaterial;
                }
                var m = toRoot * t.localToWorldMatrix;
                int first = verts.Count;
                var v = mesh.vertices;
                var n = mesh.normals;
                var uv = mesh.uv;
                for (int i = 0; i < v.Length; i++)
                {
                    var p = turn * ((m.MultiplyPoint3x4(v[i]) - foot) * shopScale) / K;
                    verts.Add(new Vector3(p.x, p.y, -p.z));
                    var nn = i < n.Length ? (turn * m.MultiplyVector(n[i])).normalized : Vector3.up;
                    norms.Add(new Vector3(nn.x, nn.y, -nn.z));
                    uvs.Add(i < uv.Length ? uv[i] : Vector2.zero);
                }
                // flipping z turns the faces inside out: wind them the other way round
                var tris = mesh.triangles;
                for (int i = 0; i + 2 < tris.Length; i += 3)
                {
                    idx.Add(first + tris[i]);
                    idx.Add(first + tris[i + 2]);
                    idx.Add(first + tris[i + 1]);
                }
            }
            if (verts.Count == 0) throw new Exception("its meshes can't be read");
            var dir = Path.GetTempPath();
            using (var w = new BinaryWriter(File.Create(Path.Combine(dir, "ultracraft_shop2.bin"))))
            {
                w.Write(verts.Count);
                w.Write(idx.Count);
                foreach (var p in verts) { w.Write(p.x); w.Write(p.y); w.Write(p.z); }
                foreach (var p in norms) { w.Write(p.x); w.Write(p.y); w.Write(p.z); }
                foreach (var p in uvs) { w.Write(p.x); w.Write(p.y); }
                foreach (var i in idx) w.Write(i);
            }
            bool glow = false;
            if (mat != null)
            {
                SaveTexture(mat.mainTexture, Path.Combine(dir, "ultracraft_shop2.png"));
                foreach (var prop in mat.GetTexturePropertyNames())
                {
                    if (prop.IndexOf("Emiss", StringComparison.OrdinalIgnoreCase) < 0 || mat.GetTexture(prop) == null) continue;
                    SaveTexture(mat.GetTexture(prop), Path.Combine(dir, "ultracraft_shop2_glow.png"));
                    glow = true;
                    break;
                }
                var so = mat.mainTextureScale;
                var off = mat.mainTextureOffset;
                File.WriteAllText(Path.Combine(dir, "ultracraft_shop2.txt"), S(so.x) + " " + S(so.y) + " " + S(off.x) + " " + S(off.y));
            }
            Plugin.Log.LogInfo("shop: its looks written for Minecraft (" + verts.Count + " vertices, " + (mat != null ? mat.name : "no material") + (glow ? ", glowing" : "") + ")");
        }

        static void SaveTexture(Texture tex, string file)
        {
            if (tex == null) return;
            var rt = RenderTexture.GetTemporary(tex.width, tex.height, 0, RenderTextureFormat.ARGB32, RenderTextureReadWrite.sRGB);
            Graphics.Blit(tex, rt);
            var prev = RenderTexture.active;
            RenderTexture.active = rt;
            var t = new Texture2D(tex.width, tex.height, TextureFormat.RGBA32, false);
            t.ReadPixels(new Rect(0, 0, tex.width, tex.height), 0, 0);
            t.Apply();
            RenderTexture.active = prev;
            RenderTexture.ReleaseTemporary(rt);
            File.WriteAllBytes(file, t.EncodeToPNG());
            Destroy(t);
        }

        /// <summary>SHOPS id,x,y,z,fx,fz;...: the shops near V1: an id (its light comes as LIGHT s&lt;id&gt;), the middle of
        /// its floor (Minecraft coordinates) and the way its screen faces.</summary>
        void ApplyShops(string rest)
        {
            if (!levelPrepared || !originSet) return;
            var seen = new HashSet<string>();
            foreach (var entry in rest.Split(';'))
            {
                if (entry.Length == 0) continue;
                var a = entry.Split(',');
                if (a.Length < 6) continue;
                seen.Add(entry);
                if (shops.TryGetValue(entry, out var have) && have != null) continue;
                if (!PrepareShop()) return;
                shops[entry] = MakeShop(new Vector3(F(a[1]), F(a[2]), F(a[3])), new Vector3(F(a[4]), 0f, F(a[5])), a[0]);
            }
            foreach (var key in new List<string>(shops.Keys))
            {
                if (seen.Contains(key)) continue;
                if (shops[key] != null) Destroy(shops[key]);
                shops.Remove(key);
            }
        }

        // ------------------------------------------------------------ enemies behind Minecraft's blocks

        readonly Dictionary<int, EnemyIdentifier> hiddenEnemies = new Dictionary<int, EnemyIdentifier>();
        readonly List<Renderer> hideBuf = new List<Renderer>();

        /// <summary>HIDE id,...: ULTRAKILL's enemies Minecraft sees fully behind its blocks (EnemyCulling.java): not
        /// drawn until one of their edges is in sight again, for the terrain ULTRAKILL doesn't have to hide them with.
        /// They fight on all the same.</summary>
        void ApplyHidden(string rest)
        {
            var want = new HashSet<int>();
            foreach (var t in rest.Split(','))
                if (int.TryParse(t.Trim(), out var id)) want.Add(id);
            foreach (var kv in new List<KeyValuePair<int, EnemyIdentifier>>(hiddenEnemies))
            {
                if (want.Contains(kv.Key) && kv.Value != null && !kv.Value.dead) continue;
                hiddenEnemies.Remove(kv.Key);
                Draw(kv.Value, true);
            }
            foreach (var id in want)
            {
                if (hiddenEnemies.ContainsKey(id) || !ukEnemies.TryGetValue(id, out var eid) || eid == null || eid.dead) continue;
                hiddenEnemies[id] = eid;
                Draw(eid, false);
            }
        }

        void Draw(EnemyIdentifier eid, bool drawn)
        {
            if (eid == null) return;
            eid.GetComponentsInChildren(true, hideBuf);
            foreach (var r in hideBuf) if (r != null) r.forceRenderingOff = !drawn;
        }

        /// <summary>A hidden one that died is a body now: drawn again at once.</summary>
        void UnhideDead()
        {
            if (hiddenEnemies.Count == 0) return;
            List<int> dead = null;
            foreach (var kv in hiddenEnemies) if (kv.Value == null || kv.Value.dead) (dead ??= new List<int>()).Add(kv.Key);
            if (dead == null) return;
            foreach (var id in dead)
            {
                Draw(hiddenEnemies[id], true);
                hiddenEnemies.Remove(id);
            }
        }

        // ------------------------------------------------------------ the Cyber Grind's own patterns

        string grindPatterns;

        /// <summary>A pattern's rows joined with '/' (one line for the message).</summary>
        static string Rows(string text) => (text ?? "").Replace("\r", "").Trim().Replace('\n', '/');

        /// <summary>CGPATTERNS name|heights|prefabs;...: ULTRAKILL's own Cyber Grind patterns (its 01 to 18, in its
        /// order, and the community's it ships), for Minecraft's grid to shift through. Rows are joined with '/'.</summary>
        void SendGrindPatterns()
        {
            if (grindPatterns == null)
            {
                var keys = new SortedSet<string>();
                try
                {
                    foreach (var loc in Addressables.ResourceLocators)
                        foreach (var k in loc.Keys)
                            if (k is string s && s.StartsWith("Assets/Data/Cyber Grind Patterns/") && s.EndsWith(".asset") && !s.Contains("/Patterns/Data/"))
                                keys.Add(s);
                }
                catch (Exception e) { Plugin.Log.LogWarning("grind patterns: " + e.Message); }
                var sb = new StringBuilder("CGPATTERNS ");
                int n = 0;
                foreach (var key in keys)
                {
                    try
                    {
                        var ap = Addressables.LoadAssetAsync<ArenaPattern>(key).WaitForCompletion();
                        if (ap == null || string.IsNullOrEmpty(ap.heights)) continue;
                        string name = key.Substring(key.LastIndexOf('/') + 1).Replace(".asset", "");
                        sb.Append(name).Append('|').Append(Rows(ap.heights)).Append('|').Append(Rows(ap.prefabs)).Append(';');
                        n++;
                    }
                    catch (Exception e) { Plugin.Log.LogDebug("grind pattern " + key + ": " + e.Message); }
                }
                grindPatterns = sb.ToString();
                if (BossTestArg != null)
                    try { System.IO.File.WriteAllText(System.IO.Path.Combine(Environment.GetEnvironmentVariable("TEMP") ?? ".", "uc_cgpatterns.txt"), grindPatterns); }
                    catch (Exception) { }
                Plugin.Log.LogInfo("Cyber Grind: " + n + " of ULTRAKILL's own patterns");
            }
            Net.Send(grindPatterns);
        }

        // ------------------------------------------------------------ the Cyber Grind's jump pads

        readonly List<GameObject> jumpPads = new List<GameObject>();
        string padsWere = "";
        GameObject padPrefab;
        bool padLooked;

        /// <summary>PADS x,y,z;...: the Cyber Grind pattern's jump pads (Minecraft coordinates, on the tile tops), as
        /// ULTRAKILL's own: its real jump pad from the Sandbox's spawn menu, launching V1 and enemies alike. An empty
        /// PADS takes them away (the grid is moving, or the run is over).</summary>
        void ApplyPads(string rest)
        {
            rest = rest.Trim();
            if (!levelPrepared || !originSet || rest == padsWere) return;
            padsWere = rest;
            foreach (var go in jumpPads) if (go != null) Destroy(go);
            jumpPads.Clear();
            if (rest.Length == 0) return;
            var prefab = PadPrefab();
            if (prefab == null) return;
            foreach (var entry in rest.Split(';'))
            {
                var a = entry.Split(',');
                if (a.Length < 3) continue;
                var at = McToUk(new Vector3(F(a[0]), F(a[1]), F(a[2])));
                var go = Instantiate(prefab, at, Quaternion.identity);
                go.name = "Ultracraft jump pad";
                go.SetActive(true);
                jumpPads.Add(go);
            }
            Plugin.Log.LogInfo("Cyber Grind: " + jumpPads.Count + " jump pads");
        }

        /// <summary>ULTRAKILL's jump pad, from the Sandbox's spawn menu (whatever it's listed under).</summary>
        GameObject PadPrefab()
        {
            if (padPrefab != null || padLooked) return padPrefab;
            padLooked = true;
            foreach (var db in Resources.FindObjectsOfTypeAll<SpawnableObjectsDatabase>())
            {
                foreach (var list in new[] { db.objects, db.sandboxObjects, db.sandboxTools, db.specialSandbox, db.unlockables })
                {
                    if (list == null) continue;
                    foreach (var so in list)
                    {
                        if (so == null || so.gameObject == null) continue;
                        if (so.gameObject.GetComponentInChildren<JumpPad>(true) == null) continue;
                        padPrefab = so.gameObject;
                        Plugin.Log.LogInfo("jump pad: " + so.identifier + " (" + so.objectName + ")");
                        return padPrefab;
                    }
                }
            }
            Plugin.Log.LogWarning("no jump pad in ULTRAKILL's spawn menu: the Cyber Grind's J tiles stay flat");
            return null;
        }

        /// <summary>Each shop's terminal in Minecraft's light where it stands (LIGHT s&lt;id&gt;); its screen glows on.</summary>
        void LightShops()
        {
            foreach (var go in shops.Values)
            {
                if (go == null || !lit.TryGetValue(go.name, out var c)) continue;
                var term = go.transform.Find("ShopTerminal");
                // its sides and back are lit like a block's (never black where ULTRAKILL's sun doesn't reach)
                if (term != null) LightUp(term.gameObject, c, 0.6f);
            }
        }

        GameObject MakeShop(Vector3 mcFloor, Vector3 mcFacing, string id)
        {
            var face = new Vector3(mcFacing.x, 0f, -mcFacing.z);
            if (face.sqrMagnitude < 0.01f) face = Vector3.forward;
            var rot = Quaternion.LookRotation(face.normalized, Vector3.up) * Quaternion.Inverse(Quaternion.LookRotation(shopFront, Vector3.up));
            var foot = new Vector3(shopTerm.center.x, shopTerm.min.y, shopTerm.center.z);
            var go = Instantiate(shopPrefab, McToUk(mcFloor) - rot * (foot * shopScale), rot * shopPrefab.transform.rotation);
            go.name = "s" + id;
            go.transform.localScale = Vector3.one * shopScale;
            DressShop(go, id);
            return go;
        }

        /// <summary>The screen made this world's: a tip of the day (ULTRAKILL's own or one about Minecraft), the Cyber
        /// Grind run around this terminal instead of in its own level, and the Sandbox page about this sandbox.</summary>
        void DressShop(GameObject go, string id)
        {
            var zone = go.GetComponent<ShopZone>();
            if (zone != null && zone.tipOfTheDay != null)
            {
                // its Start would put the Sandbox's one tip in; ours goes in instead
                var tip = zone.tipOfTheDay;
                zone.tipOfTheDay = null;
                tip.text = NextTip();
                // the tip hangs outside Main Panel, so ShopTexts' own font pass never reaches it
                ApplyShopFont(tip.transform);
            }
            var main = go.transform.Find("Canvas/Background/Main Panel");
            if (main == null) return;
            Rewire(main.Find("The Cyber Grind/Cyber Grind Panel/Panel/Enter Button"), () => Net.Send("GRIND " + id));
            Rewire(main.Find("Sandbox/Sandbox Panel/Panel/Enter Button"), () => Net.Send("SPAWNS " + (spawnsOn ? 0 : 1)));
            ShopTexts(go);
            shopObject = go;
            DressShopGear(go);
        }

        /// <summary>A button that would load another level (the Cyber Grind's, the Sandbox's) does this instead.</summary>
        static void Rewire(Transform t, UnityEngine.Events.UnityAction act)
        {
            if (t == null) return;
            var changer = t.GetComponent<AbruptLevelChanger>();
            if (changer != null) Destroy(changer);
            var button = t.GetComponent<Button>();
            if (button != null)
            {
                button.onClick = new Button.ButtonClickedEvent();
                button.onClick.AddListener(act);
            }
            var sb = t.GetComponent<ShopButton>();
            if (sb != null)
            {
                sb.toActivate = new GameObject[0];
                sb.toDeactivate = new GameObject[0];
            }
        }

        void ShopTexts(GameObject go)
        {
            var main = go.transform.Find("Canvas/Background/Main Panel");
            if (main == null) return;
            SetText(main.Find("The Cyber Grind/Cyber Grind Panel/Panel/Text Inset/Text"), grindRunning && !grindIn
                ? (Zh
                    ? "<color=#FF4343>电子血宫</color>正在进行：另一位 V1 在第 <color=#FF4343>" + grindWave + "</color> 波。\n\n加入他们：每多一位 V1，波次都会更强。"
                    : "<color=#FF4343>The Cyber Grind</color> is on: another V1 is at wave <color=#FF4343>" + grindWave + "</color>.\n\nJoin them: the waves grow with every V1 in it.")
                : grindRunning
                ? (Zh
                    ? "<color=#FF4343>电子血宫</color>正在进行。\n\n第 <color=#FF4343>" + grindWave + "</color> 波，最佳 <color=#FF4343>" + grindBest
                      + "</color>。\n\n倒下后队友可以把你救起；无人站着就结束了。"
                    : "<color=#FF4343>The Cyber Grind</color> is running.\n\nWave <color=#FF4343>" + grindWave + "</color>, best <color=#FF4343>" + grindBest
                      + "</color>.\n\nGo down and a teammate can bring you back; with nobody standing, it's over.")
                : (Zh
                    ? "<color=#FF4343>电子血宫</color>是无尽生存模式。\n\n一波接一波的敌人<color=#FF4343>围绕这台终端</color>涌来，每一波都比上一波更强。"
                      + (grindBest > 0 ? "\n\n最佳：<color=#FF4343>第 " + grindBest + " 波</color>" : "")
                    : "<color=#FF4343>The Cyber Grind</color> is an endless survival mode.\n\nWave after wave of enemies <color=#FF4343>around this terminal</color>, each bigger than the last."
                      + (grindBest > 0 ? "\n\nBest: <color=#FF4343>wave " + grindBest + "</color>" : "")));
            SetText(main.Find("The Cyber Grind/Cyber Grind Panel/Panel/Enter Button/Text"), grindRunning
                ? (grindIn ? UiText("Leave The Cyber Grind") : UiText("Join The Cyber Grind"))
                : UiText("Enter The Cyber Grind"));
            SetText(main.Find("Sandbox/Sandbox Panel/Panel/Text Inset/Text"), Zh
                ? "<color=#FF4343>沙盒</color>是一个可以用来练习的空关卡。\n\n这一关是 <color=#FF4343>Minecraft</color>。\n\n黑暗中生成敌人：<color=#FF4343>"
                  + (spawnsOn ? "开" : "关") + "</color>"
                : "The <color=#FF4343>Sandbox</color> is an empty level that can be used for practicing.\n\nThis one is <color=#FF4343>Minecraft</color>.\n\nEnemies spawning in the dark: <color=#FF4343>"
                  + (spawnsOn ? "ON" : "OFF") + "</color>");
            SetText(main.Find("Sandbox/Sandbox Panel/Panel/Enter Button/Text"), UiText(spawnsOn ? "Stop Dark Spawns" : "Allow Dark Spawns"));
            // the Chinese text needs a font that has the glyphs
            ApplyShopFont(main);
        }

        /// <summary>Set in DressShop so the main panels can be redone when Minecraft's language changes.</summary>
        GameObject shopObject;

        /// <summary>Redoes the main menu's own text in Minecraft's language.</summary>
        internal void ShopTexts()
        {
            if (shopObject != null) ShopTexts(shopObject);
        }

        static void SetText(Transform t, string text)
        {
            var tmp = t != null ? t.GetComponent<TMP_Text>() : null;
            if (tmp != null) tmp.text = text;
        }

        // ULTRAKILL's own tips of the day, one per level
        static readonly string[] LevelTips =
        {
            "Level 0-2", "Level 0-3", "Level 0-4", "Level 0-5", "Level 0-E", "Level 1-1", "Level 1-2", "Level 1-3", "Level 1-4", "Level 1-E",
            "Level 2-1", "Level 2-2", "Level 2-3", "Level 2-4", "Level 3-1", "Level 3-2", "Level 4-1", "Level 4-2", "Level 4-3", "Level 4-4",
            "Level 5-1", "Level 5-2", "Level 5-3", "Level 5-4", "Level 6-1", "Level 6-2", "Level 7-1", "Level 7-2", "Level 7-3", "Level 7-4",
            "Level 8-1", "Level 8-2", "Level 8-3", "Level 8-4", "Level P-1", "Level P-2", "Endless", "uk_construct"
        };

        // and ones about where V1 is now
        static readonly string[] MinecraftTips =
        {
            "This terminal was made of <color=#FF4343>iron, copper, glass</color> and <color=#FF4343>cobblestone</color>.\n\nA pickaxe takes it with you.",
            "ULTRAKILL's enemies come out <color=#FF4343>in the dark</color>, like Minecraft's monsters.\n\nLight up what you want to keep.",
            "Press <color=#FF4343>V</color> to put the guns away and use <color=#FF4343>Minecraft's hands</color>: mine, build, eat.\n\nPress it again for the guns.",
            "<color=#FF4343>Rain</color> puts fires out and soaks whatever it falls on.\n\nSoaked enemies <color=#FF4343>conduct electricity</color> to each other.",
            "<color=#FF4343>Elytra, horses, boats, minecarts</color> and <color=#FF4343>beds</color> all carry V1.\n\nYou can still shoot while riding.",
            "Explosions, wind charges and <color=#FF4343>Riptide</color> throw V1 around.\n\nSo does a zombie's punch.",
            "The higher your <color=#FF4343>style rank</color>, the more <color=#FF4343>experience</color> a kill drops.",
            "Minecraft's mobs <color=#FF4343>bleed</color> like anything else.\n\nGet close: their blood <color=#FF4343>heals</color> you.",
            "A <color=#FF4343>rocket</color> or <color=#FF4343>core</color> struck by a charged shot goes off as a <color=#FF4343>nuke</color>.\n\nThe terrain does not survive it.",
            "Slam down from high enough and <color=#FF4343>the ground caves in</color>.",
            "<color=#FF4343>The Cyber Grind</color> runs right here, around this terminal.\n\nIt ends when V1 dies or runs."
        };

        string NextTip()
        {
            if (UnityEngine.Random.value < 0.5f)
            {
                int pick = UnityEngine.Random.Range(0, LevelTips.Length);
                string zh;
                if (Zh && TipTextsZh.Levels.TryGetValue(LevelTips[pick], out zh)) return zh;
                try
                {
                    var key = "Assets/Data/Level Tips/" + LevelTips[pick] + ".asset";
                    var tip = Addressables.LoadAssetAsync<ScriptableObjects.TipOfTheDay>(key).WaitForCompletion();
                    if (tip != null && !string.IsNullOrEmpty(tip.tip)) return tip.tip;
                }
                catch (Exception e) { Plugin.Log.LogDebug("tip: " + e.Message); }
            }
            int mine = UnityEngine.Random.Range(0, MinecraftTips.Length);
            return Zh ? TipTextsZh.Minecraft[mine] : MinecraftTips[mine];
        }

        /// <summary>SHOPZONE touch near: V1 is at a shop's screen (guns away, the pointing finger out: Minecraft lets the
        /// clicks through and hides its own hand), or close enough that its screen is on (Minecraft has ULTRAKILL draw
        /// at full resolution, so the small text is sharp).</summary>
        void UpdateShopZone()
        {
            bool touch = false, near = false;
            foreach (var go in shops.Values)
            {
                var z = go != null ? go.GetComponent<ScreenZone>() : null;
                if (z == null) continue;
                if (ZoneTouch(z)) touch = true;
                if (ZoneIn(z)) near = true;
            }
            if (touch == shopTouch && near == shopNear) return;
            shopTouch = touch;
            shopNear = near;
            Net.Send("SHOPZONE " + (touch ? 1 : 0) + " " + (near ? 1 : 0));
        }

        // ------------------------------------------------------------ blood on Minecraft's blocks

        // ULTRAKILL paints its blood onto whatever it lands on, but folds those stains into its picture by darkening
        // what's under them; Minecraft's blocks are see-through holes in that picture, so there they vanish. Each stain
        // goes to Minecraft instead (STAINS x,y,z,nx,ny,nz;...), which paints it onto its own block, lit and fogged
        // like the block.
        readonly List<Vector3> stainPos = new List<Vector3>();
        readonly List<Vector3> stainNorm = new List<Vector3>();
        float nextStainSend;
        int stainsSent;
        bool stainSizeSent;

        public void QueueStain(Vector3 pos, Vector3 normal)
        {
            if (!levelPrepared || !originSet || stainPos.Count >= 4096 || !StainsOn) return;
            stainPos.Add(pos);
            stainNorm.Add(normal);
        }

        public void StainsCleared()
        {
            stainPos.Clear();
            stainNorm.Clear();
            if (Net.Connected) Net.Send("STAINCLEAR");
        }

        /// <summary>How wide one of ULTRAKILL's stains is, in blocks.</summary>
        float StainSize()
        {
            var bsm = levelPrepared ? MonoSingleton<BloodsplatterManager>.Instance : null;
            var mesh = bsm != null ? bsm.stainMesh : null;
            if (mesh == null) return 0.5f;
            var s = mesh.bounds.size;
            // ULTRAKILL's stains overlap into pools; Minecraft's flat copies need a little more each to read the same
            return Mathf.Clamp(Mathf.Max(s.x, Mathf.Max(s.y, s.z)) / K * 1.8f, 0.2f, 1.5f);
        }

        void SendStains()
        {
            if (stainPos.Count == 0 || Time.unscaledTime < nextStainSend || !Net.Connected) return;
            nextStainSend = Time.unscaledTime + 0.1f;
            if (!stainSizeSent)
            {
                stainSizeSent = true;
                Net.Send("STAINSIZE " + S(StainSize()));
            }
            int n = Math.Min(stainPos.Count, 256);
            var sb = new StringBuilder("STAINS ", 32 + n * 48);
            for (int i = 0; i < n; i++)
            {
                var p = UkToMc(stainPos[i]);
                var d = stainNorm[i];
                sb.Append(p.x.ToString("0.###", CultureInfo.InvariantCulture)).Append(',')
                  .Append(p.y.ToString("0.###", CultureInfo.InvariantCulture)).Append(',')
                  .Append(p.z.ToString("0.###", CultureInfo.InvariantCulture)).Append(',')
                  .Append(d.x.ToString("0.##", CultureInfo.InvariantCulture)).Append(',')
                  .Append(d.y.ToString("0.##", CultureInfo.InvariantCulture)).Append(',')
                  .Append((-d.z).ToString("0.##", CultureInfo.InvariantCulture)).Append(';');
            }
            stainPos.RemoveRange(0, n);
            stainNorm.RemoveRange(0, n);
            stainsSent += n;
            Net.Send(sb.ToString());
        }

        // ------------------------------------------------------------ leaves and glass

        // Minecraft draws leaves and glass with holes (cutout textures). Their faces get the same texture here, drawn
        // only for its depth: what's behind a leaf's solid pixels hides, what's behind its holes shows.
        readonly Dictionary<int, Material> cutoutMats = new Dictionary<int, Material>();
        readonly Dictionary<int, Texture2D> cutoutTex = new Dictionary<int, Texture2D>();
        Material cutoutClear;
        static Texture2D emptyTex;

        /// <summary>TEX id name base64png: a texture Minecraft's section faces refer to by id.</summary>
        void ApplyTexture(string rest)
        {
            var a = rest.Split(' ');
            if (a.Length < 3) return;
            int id = int.Parse(a[0]);
            var t = new Texture2D(2, 2, TextureFormat.RGBA32, false);
            if (!t.LoadImage(Convert.FromBase64String(a[2])))
            {
                Plugin.Log.LogWarning("TEX " + a[1] + ": not an image");
                return;
            }
            t.filterMode = FilterMode.Point;
            t.wrapMode = TextureWrapMode.Repeat;
            t.name = a[1];
            if (cutoutTex.TryGetValue(id, out var old) && old != null) Destroy(old);
            cutoutTex[id] = t;
            if (CutoutShader() != null) CutoutMaterial(id).mainTexture = t;
        }

        static Shader cutoutShader;
        static bool cutoutShaderSearched;

        /// <summary>An alpha-tested shader: Unity's "Unlit/Transparent Cutout" if something loaded it, otherwise
        /// ULTRAKILL's own unlit alpha test (its foliage's), loaded through its Addressables. (Unity's sits in ULTRAKILL's
        /// built-in shader bundle, but only as a dependency: nothing there can be loaded by name.)</summary>
        static Shader CutoutShader()
        {
            if (cutoutShader != null || cutoutShaderSearched) return cutoutShader;
            cutoutShaderSearched = true;
            cutoutShader = Shader.Find("Unlit/Transparent Cutout");
            if (cutoutShader == null)
            {
                try { cutoutShader = Addressables.LoadAssetAsync<Shader>("Assets/Shaders/AlphaTest/ULTRAKILL-unlit-alphatest-nocull.shader").WaitForCompletion(); }
                catch (Exception e) { Plugin.Log.LogWarning("cutout shader: " + e.Message); }
            }
            Plugin.Log.LogInfo("cutout shader: " + (cutoutShader != null ? cutoutShader.name : "MISSING (leaves won't hide anything)"));
            return cutoutShader;
        }

        Material CutoutMaterial(int id)
        {
            if (cutoutMats.TryGetValue(id, out var m) && m != null) return m;
            var sh = CutoutShader();
            if (emptyTex == null)
            {
                emptyTex = new Texture2D(1, 1, TextureFormat.RGBA32, false);
                emptyTex.SetPixel(0, 0, new Color(0, 0, 0, 0));
                emptyTex.Apply();
            }
            // first its depth where the texture is solid (and some colour there)...
            m = new Material(sh) { renderQueue = 1991, name = "Ultracraft cutout " + id };
            m.SetFloat("_Cutoff", 0.5f);
            // ULTRAKILL's shader would wobble its vertices PSX-style: these must sit exactly on Minecraft's leaves
            if (m.HasProperty("_VertexWarpScale")) m.SetFloat("_VertexWarpScale", 0f);
            if (m.HasProperty("_Color")) m.SetColor("_Color", Color.white);
            m.mainTexture = cutoutTex.TryGetValue(id, out var t) && t != null ? t : emptyTex;
            cutoutMats[id] = m;
            return m;
        }

        /// <summary>...then the colour wiped back to nothing over every face, so Minecraft's own leaves show.</summary>
        Material CutoutClear()
        {
            if (cutoutClear != null) return cutoutClear;
            cutoutClear = new Material(Shader.Find("Hidden/Internal-Colored")) { renderQueue = 1992, name = "Ultracraft cutout clear" };
            cutoutClear.SetColor("_Color", new Color(0, 0, 0, 0));
            cutoutClear.SetInt("_SrcBlend", (int)BlendMode.One);
            cutoutClear.SetInt("_DstBlend", (int)BlendMode.Zero);
            cutoutClear.SetInt("_ZWrite", 0);
            // over the very faces that just wrote their depth, whatever the depth test would say (faces seen edge-on
            // fail even a nudged one, leaving thin lines of leaf): nothing with colour is drawn before it but them
            // (Minecraft's blocks are drawn see-through before, ULTRAKILL's own things after)
            cutoutClear.SetInt("_ZTest", (int)CompareFunction.Always);
            cutoutClear.SetInt("_Cull", (int)CullMode.Off);
            return cutoutClear;
        }

        /// <summary>Faces of textured see-through blocks, per texture.</summary>
        class CutoutFaces
        {
            public readonly List<Vector3> verts = new List<Vector3>();
            public readonly List<Vector2> uvs = new List<Vector2>();
            public readonly Dictionary<int, List<int>> tris = new Dictionary<int, List<int>>();
        }

        /// <summary>One textured face (d, plane, a0..b1 as in SEC). Its texture repeats once per block, laid on as
        /// Minecraft lays it on each side of a cube.</summary>
        void AddCutoutFace(CutoutFaces cf, int tex, int d, float p, float a0, float b0, float a1, float b1)
        {
            int axis = d >> 1;
            int i = cf.verts.Count;
            float[] aa = { a0, a1, a1, a0 }, bb = { b0, b0, b1, b1 };
            for (int k = 0; k < 4; k++)
            {
                cf.verts.Add(McToUk(FacePoint(axis, p, aa[k], bb[k])));
                cf.uvs.Add(CutoutUv(d, aa[k], bb[k]));
            }
            if (!cf.tris.TryGetValue(tex, out var list)) cf.tris[tex] = list = new List<int>();
            list.Add(i); list.Add(i + 1); list.Add(i + 2);
            list.Add(i); list.Add(i + 2); list.Add(i + 3);
        }

        /// <summary>One quad of a plant's model (12,tex,x,y,z,u,v x4: corners from the section's corner, texture
        /// coordinates from the texture's top left). An animated texture is a strip of frames: its first one.</summary>
        void AddCutoutQuad(CutoutFaces cf, int tex, Vector3 origin, string[] f)
        {
            float frame = 1f;
            if (cutoutTex.TryGetValue(tex, out var t) && t != null && t.height > t.width) frame = (float)t.width / t.height;
            int i = cf.verts.Count;
            for (int k = 0; k < 4; k++)
            {
                int o = 2 + k * 5;
                cf.verts.Add(McToUk(origin + new Vector3(F(f[o]), F(f[o + 1]), F(f[o + 2]))));
                cf.uvs.Add(new Vector2(F(f[o + 3]), 1f - F(f[o + 4]) * frame));
            }
            if (!cf.tris.TryGetValue(tex, out var list)) cf.tris[tex] = list = new List<int>();
            list.Add(i); list.Add(i + 1); list.Add(i + 2);
            list.Add(i); list.Add(i + 2); list.Add(i + 3);
        }

        /// <summary>Minecraft's cube UVs (FaceBakery), in blocks, with Unity's v running up: east and north faces run
        /// their texture the other way, the top's v runs north to south.</summary>
        static Vector2 CutoutUv(int d, float a, float b)
        {
            switch (d)
            {
                case 0: return new Vector2(-b, a);   // +x: u along -z, v up
                case 1: return new Vector2(b, a);    // -x: u along +z
                case 2: return new Vector2(a, -b);   // +y: u along x, v toward -z
                case 3: return new Vector2(a, b);    // -y
                case 4: return new Vector2(a, b);    // +z: u along x, v up (b is y)
                default: return new Vector2(-a, b);  // -z: u along -x
            }
        }

        void BuildCutout(GameObject section, CutoutFaces cf)
        {
            var t = section.transform.Find("cutout");
            // without the cutout shader they stay see-through, as before
            if (cf == null || cf.verts.Count == 0 || CutoutShader() == null)
            {
                if (t != null)
                {
                    var oldMf = t.GetComponent<MeshFilter>();
                    if (oldMf != null && oldMf.sharedMesh != null) Destroy(oldMf.sharedMesh);
                    Destroy(t.gameObject);
                }
                return;
            }
            GameObject go;
            if (t == null)
            {
                go = new GameObject("cutout");
                go.layer = section.layer;
                go.transform.SetParent(section.transform, false);
                go.AddComponent<MeshFilter>();
                var r = go.AddComponent<MeshRenderer>();
                r.shadowCastingMode = ShadowCastingMode.Off;
                r.receiveShadows = false;
                r.lightProbeUsage = LightProbeUsage.Off;
                r.reflectionProbeUsage = ReflectionProbeUsage.Off;
                r.allowOcclusionWhenDynamic = false;
            }
            else go = t.gameObject;
            var mf = go.GetComponent<MeshFilter>();
            if (mf.sharedMesh != null) Destroy(mf.sharedMesh);
            var mesh = new Mesh();
            if (cf.verts.Count > 65000) mesh.indexFormat = IndexFormat.UInt32;
            mesh.SetVertices(cf.verts);
            mesh.SetUVs(0, cf.uvs);
            // one submesh per texture, and a last one with every face for the colour wipe
            mesh.subMeshCount = cf.tris.Count + 1;
            var mats = new Material[cf.tris.Count + 1];
            var all = new List<int>();
            int s = 0;
            foreach (var kv in cf.tris)
            {
                mesh.SetTriangles(kv.Value, s);
                mats[s++] = CutoutMaterial(kv.Key);
                all.AddRange(kv.Value);
            }
            mesh.SetTriangles(all, s);
            mats[s] = CutoutClear();
            mesh.RecalculateBounds();
            mf.sharedMesh = mesh;
            go.GetComponent<MeshRenderer>().sharedMaterials = mats;
        }

        // ------------------------------------------------------------ enemies on the terrain

        readonly Dictionary<int, float> flameSent = new Dictionary<int, float>();

        /// <summary>A Streetcleaner's flamethrower sets the blocks it plays on alight.</summary>
        void EnemyFlames()
        {
            foreach (var eid in ukEnemies.Values)
            {
                if (eid == null || eid.dead || eid.enemyType != EnemyType.Streetcleaner) continue;
                var sc = eid.GetComponent<Streetcleaner>();
                if (sc == null || !sc.damaging || sc.firePoint == null) continue;
                var fp = sc.firePoint.transform;
                if (Physics.Raycast(fp.position, fp.forward, out var hit, 7f, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)
                    && IsMinecraftTerrain(hit.transform))
                {
                    ReportFire(hit.point + hit.normal * 0.5f, true);
                }
            }
        }

        readonly Dictionary<ContinuousBeam, float> beamHits = new Dictionary<ContinuousBeam, float>();

        /// <summary>An enemy's continuous beam (a Mindflayer's) burns through what it plays on, a few times a second.</summary>
        public void BeamOnTerrain(ContinuousBeam b)
        {
            if (!levelPrepared || !originSet || !Net.Connected || b == null || !b.enemy || b.off) return;
            if (beamHits.TryGetValue(b, out var t) && Time.time - t < 0.25f) return;
            Vector3 dir;
            float dist;
            if (b.endPoint != null)
            {
                dir = b.endPoint.position - b.transform.position;
                dist = dir.magnitude;
                dir = dist > 0f ? dir / dist : b.transform.forward;
            }
            else
            {
                dir = b.transform.forward;
                dist = b.maxDistance;
            }
            if (dist <= 0f) return;
            if (!Physics.Raycast(b.transform.position, dir, out var hit, dist, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)
                || !IsMinecraftTerrain(hit.transform)) return;
            beamHits[b] = Time.time;
            if (beamHits.Count > 64) beamHits.Clear();
            ReportBlockHit(hit.point, -hit.normal, 1.5f, 0f, dir, true);
        }

        /// <summary>A Virtue's pillar of light comes down: it craters the ground it strikes.</summary>
        public void PillarOnTerrain(Vector3 at)
        {
            if (!levelPrepared || !originSet || !Net.Connected) return;
            if (Physics.Raycast(at + Vector3.up * 2f, Vector3.down, out var hit, 8f, LayerMaskDefaults.Get(LMD.Environment), QueryTriggerInteraction.Ignore)
                && IsMinecraftTerrain(hit.transform))
                ReportBlockHit(hit.point, Vector3.down, 3f, 1.5f, Vector3.down, true);
        }

        // ------------------------------------------------------------ the nuke

        static readonly HashSet<string> nukeNames = new HashSet<string>();
        static bool nukeNamesFound;

        /// <summary>ULTRAKILL's "super explosion" (a rocket or core eject shot with a railcannon or charged revolver, a
        /// levelled-up grenade beam): the mini nuke.</summary>
        public static bool IsNuke(Explosion ex)
        {
            if (!nukeNamesFound)
            {
                nukeNamesFound = true;
                var drm = MonoSingleton<DefaultReferenceManager>.Instance;
                if (drm != null && drm.superExplosion != null) nukeNames.Add(drm.superExplosion.name);
                foreach (var g in Resources.FindObjectsOfTypeAll<Grenade>()) if (g != null && g.superExplosion != null) nukeNames.Add(g.superExplosion.name);
            }
            for (var t = ex.transform; t != null; t = t.parent)
            {
                var n = t.name.Replace("(Clone)", "").Trim();
                if (nukeNames.Contains(n)) return true;
            }
            return false;
        }
    }

    [HarmonyPatch(typeof(ContinuousBeam), "FixedUpdate")]
    static class BeamTerrain
    {
        static void Postfix(ContinuousBeam __instance) => Bridge.I?.BeamOnTerrain(__instance);
    }

    [HarmonyPatch(typeof(VirtueInsignia), "Explode")]
    static class PillarTerrain
    {
        static void Postfix(VirtueInsignia __instance) => Bridge.I?.PillarOnTerrain(__instance.transform.position);
    }

    /// <summary>Every blood stain ULTRAKILL paints, in world space (with compute shaders it keeps them in its parent's
    /// space), goes to Minecraft too.</summary>
    [HarmonyPatch(typeof(BloodsplatterManager), nameof(BloodsplatterManager.CreateBloodstain), new[] { typeof(Vector3), typeof(Vector3), typeof(bool), typeof(BloodstainParent) })]
    static class StainsToMinecraft
    {
        static void Postfix(BloodsplatterManager __instance, Vector3 pos, Vector3 norm, BloodstainParent parent)
        {
            var b = Bridge.I;
            if (b == null || parent == null) return;
            if (__instance.usedComputeShadersAtStart)
            {
                pos = parent.transform.TransformPoint(pos);
                norm = parent.transform.TransformDirection(norm);
            }
            b.QueueStain(pos, norm.normalized);
        }
    }

    [HarmonyPatch(typeof(BloodsplatterManager), nameof(BloodsplatterManager.ClearStains))]
    static class StainsCleared
    {
        static void Postfix() => Bridge.I?.StainsCleared();
    }

    /// <summary>ULTRAKILL's blood spray is drawn at full brightness (its levels are lit around it); in Minecraft it
    /// takes Minecraft's light where it sprays, so in a dark cave it's a dark red, not a glowing one.</summary>
    [HarmonyPatch(typeof(Bloodsplatter), "OnEnable")]
    static class BloodInTheLight
    {
        static readonly Dictionary<int, ParticleSystem.MinMaxGradient> original = new Dictionary<int, ParticleSystem.MinMaxGradient>();
        static readonly List<ParticleSystem> systems = new List<ParticleSystem>();

        static void Postfix(Bloodsplatter __instance)
        {
            var b = Bridge.I;
            if (b == null || !b.LevelReady) return;
            try
            {
                var light = b.LightNear(__instance.transform.position);
                // ULTRAKILL's own blood is as lit as its level; never brighter than it was made
                light.r = Mathf.Min(light.r, 1f);
                light.g = Mathf.Min(light.g, 1f);
                light.b = Mathf.Min(light.b, 1f);
                __instance.GetComponentsInChildren(true, systems);
                foreach (var ps in systems)
                {
                    var main = ps.main;
                    int id = ps.GetInstanceID();
                    if (!original.TryGetValue(id, out var o)) original[id] = o = main.startColor;
                    main.startColor = Tint(o, light);
                }
                if (original.Count > 4096) original.Clear();
            }
            catch (Exception e) { Plugin.Log.LogDebug("blood light: " + e.Message); }
        }

        static ParticleSystem.MinMaxGradient Tint(ParticleSystem.MinMaxGradient g, Color c)
        {
            switch (g.mode)
            {
                case ParticleSystemGradientMode.Color: return new ParticleSystem.MinMaxGradient(g.color * c);
                case ParticleSystemGradientMode.TwoColors: return new ParticleSystem.MinMaxGradient(g.colorMin * c, g.colorMax * c);
                case ParticleSystemGradientMode.Gradient: return new ParticleSystem.MinMaxGradient(Tint(g.gradient, c));
                case ParticleSystemGradientMode.TwoGradients: return new ParticleSystem.MinMaxGradient(Tint(g.gradientMin, c), Tint(g.gradientMax, c));
                default:
                {
                    var t = new ParticleSystem.MinMaxGradient(Tint(g.gradient, c));
                    t.mode = g.mode;
                    return t;
                }
            }
        }

        static Gradient Tint(Gradient g, Color c)
        {
            if (g == null) return null;
            var keys = g.colorKeys;
            for (int i = 0; i < keys.Length; i++) keys[i].color *= c;
            var t = new Gradient { mode = g.mode };
            t.SetKeys(keys, g.alphaKeys);
            return t;
        }
    }

    /// <summary>Debug (LEAN): the camera held leaning, as when V1 strafes, to line Minecraft's world up against.</summary>
    [HarmonyPatch(typeof(CameraController), nameof(CameraController.ApplyRotations))]
    static class DebugLean
    {
        public static float degrees;

        static void Postfix(CameraController __instance)
        {
            if (degrees != 0f) __instance.transform.localRotation *= Quaternion.AngleAxis(degrees, Vector3.forward);
        }
    }
}
