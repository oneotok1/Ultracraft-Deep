package dev.ultracraft;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft moving V1. ULTRAKILL moves V1 by its own rules; these are the times Minecraft's rules take over:
 * <ul>
 * <li>Carried: an elytra glide (jump while falling with one worn, as Steve does), a horse, pig, strider, camel, boat or
 * minecart, a bed. Minecraft's physics move the player and V1 follows (DRIVE), still looking, shooting and punching;
 * when it ends V1 flies on with Minecraft's speed (UNDRIVE).</li>
 * <li>Put somewhere: an ender pearl, chorus fruit, /tp, waking up, getting off a mount (TP).</li>
 * </ul>
 */
public final class Movement {
	/** Minecraft is carrying V1 this tick. */
	public static volatile boolean carried;
	/** V1 is asleep in a bed: Minecraft's own camera and screen. */
	public static volatile boolean sleeping;
	private static boolean jumpWas;
	// after a teleport ULTRAKILL's poses from before it are still on the way: they're ignored until V1 is there
	private static Vec3 tpTarget;
	private static long tpAt;

	private Movement() {}

	static void reset() {
		carried = false;
		sleeping = false;
		tpTarget = null;
	}

	/**
	 * Every client tick while V1 is active. Returns whether ULTRAKILL's pose moves the player this tick (false while
	 * Minecraft carries V1, or a teleport hasn't reached ULTRAKILL yet).
	 */
	static boolean tick(Minecraft mc, LocalPlayer p, UkLink.Pose pose) {
		tryGlide(mc, p, pose);
		sleeping = p.isSleeping();
		if (p.isFallFlying() || p.isPassenger() || sleeping) {
			// Minecraft's own physics: no creative flight, its velocity kept
			p.getAbilities().flying = false;
			carried = true;
			drive(p, p.position());
			return false;
		}
		if (carried) {
			carried = false;
			Vec3 v = p.getDeltaMovement();
			UkLink.send(String.format(Locale.ROOT, "UNDRIVE %.4f %.4f %.4f", v.x, v.y, v.z));
		}
		if (tpTarget != null) {
			boolean there = pose != null && new Vec3(pose.fx, pose.fy, pose.fz).distanceToSqr(tpTarget) < 4.0;
			if (there || System.currentTimeMillis() - tpAt > 1000) tpTarget = null;
			else return false;
		}
		return true;
	}

	/** Every frame: where Minecraft draws the carried player, so V1 (and the camera) glide smoothly between ticks. */
	static void frame(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!carried || p == null) return;
		drive(p, p.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true)));
	}

	private static void drive(LocalPlayer p, Vec3 at) {
		Vec3 v = p.getDeltaMovement();
		UkLink.send(String.format(Locale.ROOT, "DRIVE %.3f %.3f %.3f %.4f %.4f %.4f", at.x, at.y, at.z, v.x, v.y, v.z));
	}

	/**
	 * Jump while falling with an elytra on: V1 opens it, as Steve would (Minecraft's own check: something to glide
	 * with, off the ground, out of water, no Levitation). ULTRAKILL's fall speed carries into the glide.
	 */
	private static void tryGlide(Minecraft mc, LocalPlayer p, UkLink.Pose pose) {
		boolean jump = mc.options.keyJump.isDown() && mc.screen == null;
		boolean pressed = jump && !jumpWas;
		jumpWas = jump;
		if (!pressed || pose == null || pose.onGround || pose.vy > 0.0 || p.isFallFlying() || p.isPassenger() || p.isInWater()) return;
		p.getAbilities().flying = false;
		if (p.tryToStartFallFlying()) {
			// the server checks the same: it must not think V1 is flying
			p.onUpdateAbilities();
			p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
			p.setDeltaMovement(pose.vx / 20.0, pose.vy / 20.0, pose.vz / 20.0);
		} else {
			p.getAbilities().flying = true;
		}
	}

	/** The server put the player somewhere (ClientPacketListenerMixin): V1 goes there. */
	public static void serverMoved(LocalPlayer p) {
		if (!Ultracraft.active || !UkLink.connected || p.isPassenger()) return;
		Ultracraft.teleportV1(p);
		tpTarget = p.position();
		tpAt = System.currentTimeMillis();
	}
}
