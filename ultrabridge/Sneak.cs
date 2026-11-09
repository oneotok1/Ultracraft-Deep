// UltraBridge: Minecraft's sneak, for a player who is V1. Minecraft cannot do it itself - in V1 mode its own
// position is overwritten from ULTRAKILL's pose every tick, so Shift never reaches a crouch and the world never
// holds anybody back from a ledge. So the key comes down as a flag and both halves of the crouch are done here.
//
// The two halves have to agree on one thing: where the floor is. Minecraft's ground is a grid of blocks and its
// crouch asks the grid; ULTRAKILL's ground is a MeshCollider per chunk section, so the same question is asked with
// downward rays from the footprint of a Minecraft player's box (0.6 blocks square, the same box whose corners the
// vanilla crouch uses). With that, the rule is Minecraft's own: a step is allowed while any part of the footprint
// would still have floor under it, which is what lets the body stand with its middle out past the edge - far
// enough to look down at the block's side and place a block against it - while a corner stays on the block and the
// body can still walk along that edge.
//
// The move is refused rather than the speed zeroed. ULTRAKILL's walk lerps the velocity towards its target every
// physics step (NewMovement's Vector3.Lerp(rb.velocity, targetVel, ...)), so a zeroed velocity is back to a
// quarter of a walk one step later and the body creeps off the edge a hair at a time. Minecraft refuses the move.

using HarmonyLib;
using UnityEngine;

namespace UltraBridge
{
    [HarmonyPatch(typeof(NewMovement), "FixedUpdate")]
    static class Sneak
    {
        /// <summary>Minecraft's sneak key is down.</summary>
        internal static bool On;

        /// <summary>How much of a walk a sneak is. Minecraft's own crouch keeps 30%, but V1 is faster than a
        /// Minecraft player and that much of a cut read as being held rather than sneaking, so it keeps 70%.</summary>
        const float SneakSpeed = 0.7f;

        /// <summary>How far down a probe looks, in blocks, before it calls what is under it open air.</summary>
        const float ProbeDepth = 3f;

        /// <summary>How far a drop may be and still count as floor, in blocks: Minecraft's own step height, so a
        /// slab or a stair is walked off and a whole block's drop is not. Minecraft's crouch stops at exactly this
        /// height (its ledge test drops the player's box by maxUpStep, 0.6), which is why it matches.</summary>
        const float StepDown = 0.6f;

        /// <summary>Where the probe starts above the feet, in blocks: clear of the floor V1 stands on, so the test
        /// never hits the ground it is standing on and calls every step an edge.</summary>
        const float ProbeLift = 0.1f;

        /// <summary>How far below the transform's origin the feet are, in ULTRAKILL units. The same 1.5 that
        /// Plugin's own world position uses for them - and already ULTRAKILL's, so it is not scaled by K.</summary>
        const float FeetDrop = 1.5f;

        /// <summary>A fall faster than this is a real one: the hang holds a body that has just leaned out over an
        /// edge, not one that is already dropping past the block.</summary>
        const float FallSpeed = 6f;

        /// <summary>How far out the footprint reaches, in blocks. Minecraft's box is 0.3 from the centre; the extra
        /// is because the question is asked of V1's feet, which are not a flat 0.6 square, and because a body held
        /// a hair further out can look down at the block's side and place against it - the whole point of the
        /// crouch - without any corner losing its hold on the block.</summary>
        const float Reach = 0.36f;

        /// <summary>How far below the height it stood at a body may sink and still be held there, in blocks. A body
        /// that has come down further than this has really left that height - a step down, or a fall it was let
        /// into - and the hold has to let go of it: holding on pulled a body that had already dropped a whole block
        /// back up onto the ledge, which read as a teleport up the first time the key was pressed again.</summary>
        const float HoldSlack = 0.35f;

        /// <summary>A Minecraft player's footprint, in blocks from the centre: the corners of its 0.6 box and the
        /// middle of each side. A step is let through while any of these still has floor under it.</summary>
        static readonly Vector2[] Body =
        {
            new Vector2(1f, 1f), new Vector2(-1f, 1f), new Vector2(1f, -1f), new Vector2(-1f, -1f),
            new Vector2(1f, 0f), new Vector2(-1f, 0f), new Vector2(0f, 1f), new Vector2(0f, -1f),
            // the corners are further apart than a block is wide enough to matter, and a diagonal step can slip
            // between two of them over a gap whose sheets are a block apart - the half-way points close that
            new Vector2(0.5f, 0.5f), new Vector2(-0.5f, 0.5f), new Vector2(0.5f, -0.5f), new Vector2(-0.5f, -0.5f),
            new Vector2(0.5f, 0f), new Vector2(-0.5f, 0f), new Vector2(0f, 0.5f), new Vector2(0f, -0.5f),
        };

        const int EnvironmentMask = 1 << 8;

        /// <summary>walkSpeed as it was before the step scaled it, per V1. ULTRAKILL reads walkSpeed inside the
        /// step that computes the target velocity, so scaling it is the only way to slow the walk without also
        /// slowing a dash (which multiplies the same field) by a different amount than it should be.</summary>
        static readonly System.Collections.Generic.Dictionary<NewMovement, float> scaled =
            new System.Collections.Generic.Dictionary<NewMovement, float>();

        /// <summary>Only V1: other NewMovements are the game's own business.</summary>
        static NewMovement V1 => MonoSingleton<NewMovement>.Instance;

        /// <summary>The height the body stood at the last time it had ground under it: while it hangs over the
        /// edge that is the height it is held at. A number rather than a direction, so nothing about the hang
        /// depends on which way the body last walked.</summary>
        static float standY;
        static bool standYSet;

        /// <summary>Where the body ended up after the last physics step. FixedUpdate runs before the step that
        /// moves the body, so the position at the end of this one is still the position the previous step left -
        /// the step's own displacement is only readable at the start of the next one. Measured this way, the test
        /// below judges where the body actually went, which is what Minecraft's own crouch judges.</summary>
        static Vector3 lastEnd;
        static bool lastEndSet;

        /// <summary>Whether the footprint had floor under it at the end of the last step. The step rule asks whether
        /// the body came from floor, and this is that answer, carried from one step to the next. Asked of the last
        /// position instead - "here minus moved" - the y taken for it is the one the body has already sunk to, and a
        /// body held out over an edge reads as having stood on nothing, so the one step that walks off got no test
        /// at all.</summary>
        static bool hadFloor;

        /// <summary>Is there floor within a step's drop of this point, in ULTRAKILL units? The point is taken as
        /// standing height, so the probe starts just above it and a slab still counts as floor.</summary>
        static bool FloorAt(Vector3 feet)
        {
            var from = feet + Vector3.up * (ProbeLift * Bridge.K);
            return Physics.Raycast(from, Vector3.down, out var hit, ProbeDepth * Bridge.K, EnvironmentMask,
                       QueryTriggerInteraction.Ignore)
                && hit.distance <= (ProbeLift + StepDown) * Bridge.K;
        }

        /// <summary>Floor under any part of the body standing at this point: the whole footprint is tried, so a
        /// body whose middle is already past the edge still has the block behind it - and one walking along that
        /// edge keeps a corner on it, which is why the walk is not stopped there.</summary>
        static bool BodyFloor(Vector3 feet)
        {
            if (FloorAt(feet)) return true;
            for (int i = 0; i < Body.Length; i++)
            {
                var at = feet + new Vector3(Body[i].x * Reach * Bridge.K, 0f, Body[i].y * Reach * Bridge.K);
                if (FloorAt(at)) return true;
            }
            return false;
        }

        /// <summary>Floor under the whole footprint, not just under a corner of it: standing, as opposed to hanging
        /// with the heels on the block. Only this may move the height the body is held at - a body hanging over an
        /// edge has nothing under it to stand on, and taking its own sinking height for the ground it stands on is
        /// what let it drop a little more every step.</summary>
        static bool Resting(Vector3 feet)
        {
            if (!FloorAt(feet)) return false;
            for (int i = 0; i < Body.Length; i++)
            {
                var at = feet + new Vector3(Body[i].x * Reach * Bridge.K, 0f, Body[i].y * Reach * Bridge.K);
                if (!FloorAt(at)) return false;
            }
            return true;
        }

        /// <summary>The sneak key alone, before the question of what is underfoot.</summary>
        static bool Sneaking(NewMovement nm)
        {
            // a dash or a slide is not a walk and keeps its speed
            return On && nm != null && nm == V1 && !nm.dead && !nm.sliding && !nm.jumping && !nm.boost;
        }

        /// <summary>Where the body is standing. A refused step puts the body back to the last place it was held at,
        /// so that place - not the position the physics step has already carried one step outward - is the one the
        /// floor questions have to be asked about: asked at the outward one, a body that is being held would read
        /// as one that has already walked off.</summary>
        static Vector3 Held()
        {
            if (lastEndSet) return lastEnd;
            var rb = V1 != null ? V1.rb : null;
            return rb != null ? rb.position : lastEnd;
        }

        /// <summary>Held up by the block the body leans off: not grounded by ULTRAKILL's own check (that one wants
        /// the whole capsule overlapping something), but with floor under some part of the footprint still.</summary>
        static bool Hanging(NewMovement nm)
        {
            if (!Sneaking(nm) || !standYSet) return false;
            var rb = nm.rb;
            if (rb == null || rb.velocity.y > FallSpeed) return false;
            var held = Held();
            if (held.y < standY - HoldSlack * Bridge.K) return false;
            return BodyFloor(held + Vector3.down * FeetDrop);
        }

        internal static bool Slowing(NewMovement nm)
        {
            if (!Sneaking(nm)) return false;
            var rb = nm.rb;
            if (rb == null) return false;
            // asked of the footprint, not of ULTRAKILL's switch: the hang below tells that switch it is standing so
            // the walk keeps its feet, and a switch this file sets itself cannot be the one it reads back
            var feet = rb.position + Vector3.down * FeetDrop;
            return Resting(feet) || Hanging(nm);
        }

        /// <summary>GroundCheckGroup's own list of checks: the hang has to reach each of them.</summary>
        static readonly AccessTools.FieldRef<GroundCheckGroup, System.Collections.Generic.List<GroundCheck>> Checks =
            AccessTools.FieldRefAccess<GroundCheckGroup, System.Collections.Generic.List<GroundCheck>>("instances");

        /// <summary>Tell the game whether the body is standing. Its own check wants the whole capsule to overlap a
        /// collider and lets go the moment the middle passes the edge, and everything after that switch reads a hang
        /// as a fall: the walk animation stops (PlayerAnimations sets InAir from this), the ground branch of
        /// NewMovement - friction and the ramp up to speed - is skipped for air control, and the body slides out like
        /// a statue. The footprint test above already says there is floor here, so this hands that same answer to the
        /// rest of the game.
        /// <para>The switch is only ever set true by anyone, never back: nothing clears it but a trigger exit, and a
        /// check that never had a collider in the first place gets no exit. So the lie has to be taken back as well,
        /// and only while the check is holding nothing of its own - a check with real colliders left in <c>cols</c> is
        /// answering for itself.</para></summary>
        static void StandGround(NewMovement nm, bool standing)
        {
            var list = nm.gc != null ? Checks(nm.gc) : null;
            if (list == null) return;
            for (int i = 0; i < list.Count; i++)
            {
                var g = list[i];
                if (g == null || !g.isActiveAndEnabled) continue;
                if (standing)
                {
                    g.touchingGround = true;
                    g.onGround = true;
                }
                else if (g.cols.Count == 0)
                {
                    g.touchingGround = false;
                    g.onGround = false;
                }
            }
        }

        static void Prefix(NewMovement __instance)
        {
            // a step that threw before its postfix still has the old scale on the field; put it back before this
            // step scales it again, or the walk would ratchet down a little every time one did
            if (scaled.TryGetValue(__instance, out var stale))
            {
                __instance.walkSpeed = stale;
                scaled.Remove(__instance);
            }
            if (!Slowing(__instance)) return;
            scaled[__instance] = __instance.walkSpeed;
            __instance.walkSpeed *= SneakSpeed;
        }

        static void Postfix(NewMovement __instance)
        {
            if (scaled.TryGetValue(__instance, out var was))
            {
                __instance.walkSpeed = was;
                scaled.Remove(__instance);
            }
            if (__instance != V1) return;

            var rb = __instance.rb;
            if (rb == null || rb.isKinematic)
            {
                lastEndSet = false;
                return;
            }

            var here = rb.position;
            if (!Sneaking(__instance))
            {
                // the height it stood at belongs to one press of the key. Carried over to the next one, a body that
                // had since stepped down was pulled back up to it - and pressing the key over and over repeated it
                standYSet = false;
                // but whether there is floor under the feet is still worth keeping: pressing the key while walking
                // towards an edge has to judge the very first step, and asked only from inside the sneak below, the
                // answer would be "no floor came before this one" and that step would go through untouched
                hadFloor = BodyFloor(here + Vector3.down * FeetDrop);
                lastEnd = here;
                lastEndSet = true;
                return;
            }

            var moved = lastEndSet ? new Vector3(here.x - lastEnd.x, 0f, here.z - lastEnd.z) : Vector3.zero;

            {
                var feet = here + Vector3.down * FeetDrop;
                bool cameFrom = hadFloor;

                // the key went down with the body already out past the edge, so there is no standing step to take the
                // height from: the height it is at now is the one to hold. Without this the very first press out on a
                // ledge found no height to hang from and let the body sink slowly down the block's face
                if (!standYSet && BodyFloor(feet))
                {
                    standY = here.y;
                    standYSet = true;
                }
                // whether the body is standing is taken from the footprint alone. ULTRAKILL's own switch cannot be
                // asked: the hang below sets it true on purpose, and a switch that is only ever set true answers
                // "standing" for a body that has long since walked off
                bool grounded = Resting(feet);

                // a step is refused only once no part of the body's footprint would have floor under it where the
                // step has put it. Minecraft resolves its moves one axis at a time for the same reason this does:
                // a body pressed against a wall that also has a drop beside it must still be able to slide along
                // the wall, and only the axis that leads off the block may be taken away.
                //
                // That alone also catches a body that is simply in the air: falling has no floor ahead of it
                // either, so the step read as one off a ledge and was undone with the walk's speed - a body that
                // jumped and then held the sneak key stopped dead in midair. A step off a ledge starts on the
                // block, so the move only counts as one when the body came from floor. (Bridge.AirSneakHold puts
                // the old reading back, for whoever wants to stop on nothing.)
                var kept = moved;
                if (moved.sqrMagnitude > 1e-6f && !BodyFloor(feet) && (Bridge.AirSneakHold || cameFrom))
                {
                    var alongX = new Vector3(moved.x, 0f, 0f);
                    var alongZ = new Vector3(0f, 0f, moved.z);
                    bool xOk = alongX.sqrMagnitude > 1e-6f && BodyFloor(feet - moved + alongX);
                    bool zOk = alongZ.sqrMagnitude > 1e-6f && BodyFloor(feet - moved + alongZ);
                    if (xOk && !zOk) kept = alongX;
                    else if (zOk && !xOk) kept = alongZ;
                    else if (!xOk && !zOk) kept = Vector3.zero;
                    // both axes are fine on their own but not together: that is the corner case, a diagonal step
                    // across a gap whose two sides each still have floor under them. Resolved one axis at a time the
                    // move stops at the first axis that would leave no floor, so only one of them is kept here -
                    // keeping both walked the body diagonally off a corner with nothing in its way at all
                    else kept = xOk ? alongX : alongZ;
                }

                if (kept != moved)
                {
                    // put the body back by the part of the step that was refused: the move is undone, not the
                    // speed, so the next step starts from the same place instead of from a quarter of a walk
                    var back = here + (kept - moved);
                    rb.position = new Vector3(back.x, rb.position.y, back.z);
                    __instance.transform.position = new Vector3(back.x, __instance.transform.position.y, back.z);
                    here = new Vector3(back.x, here.y, back.z);
                    feet = here + Vector3.down * FeetDrop;
                    var v = rb.velocity;
                    if (kept.x == 0f) v.x = 0f;
                    if (kept.z == 0f) v.z = 0f;
                    rb.velocity = v;
                }

                if (grounded)
                {
                    standY = here.y;
                    standYSet = true;
                    // standing on the whole footprint: whatever the hang said before, the game's own check is
                    // right again by itself here, and this only clears a lie left on a check holding nothing
                    StandGround(__instance, false);
                }
                else if (!standYSet || !BodyFloor(feet) || here.y <= standY - HoldSlack * Bridge.K)
                {
                    // nothing under any part of the footprint: this is a real fall, so give the switch back to the
                    // game and let it fall as it will
                    StandGround(__instance, false);
                }
                else
                {
                    // over the edge: nothing is under the whole footprint, but some of it still has floor. The
                    // hang holds the height the body last stood at, and it is the same test the step rule above
                    // uses - one notion of where the floor is
                    var v = rb.velocity;
                    if (v.y <= FallSpeed)
                    {
                        v.y = 0f;
                        rb.velocity = v;
                        // hold the height as well as the fall: the physics step after this one pulls the body down
                        // by gravity every time, so a body whose velocity is merely zeroed sinks a hair per step
                        // and slides slowly down the block's face until nothing holds it
                        if (here.y < standY - 0.002f)
                        {
                            rb.position = new Vector3(rb.position.x, standY, rb.position.z);
                            __instance.transform.position =
                                new Vector3(__instance.transform.position.x, standY, __instance.transform.position.z);
                        }

                        // and tell the rest of the game it is standing: the walk animation and the ground branch of
                        // NewMovement both key off the same switch, and a body the game reads as falling walks with
                        // its feet still and steers like it is in the air - which is what the slide down the edge was
                        StandGround(__instance, true);
                    }
                }

                hadFloor = BodyFloor(feet);
            }

            lastEnd = rb.position;
            lastEndSet = true;
        }
    }
}
