package dev.ultracraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Watching a teammate while down in a boss fight (BossParty). The camera goes round them, a few blocks back, wherever
 * the mouse turns it (it used to sit in their eyes, inside the V1 body ULTRAKILL draws there, and look only where they
 * looked); left and right click switch to the next or previous teammate still standing; the top of the screen says
 * whom we're watching, how they're doing, and what it takes to get back up.
 */
public final class Spectate {
	/** The teammate we're watching (their Minecraft id) and their name (C:WATCH from the server). */
	private static int watchId = -1;
	private static String watchName = "";
	private static boolean attackWas, useWas;
	private static final double DIST = 4.5;

	private Spectate() {}

	static void watch(String rest) {
		String[] a = rest.trim().split(" ", 2);
		watchId = Integer.parseInt(a[0]);
		watchName = a.length > 1 ? a[1] : "";
	}

	static void reset() {
		watchId = -1;
		watchName = "";
	}

	private static Entity target(Minecraft mc) {
		if (!Ultracraft.downed || mc.level == null) return null;
		Entity e = watchId >= 0 ? mc.level.getEntity(watchId) : null;
		return e != null ? e : mc.getCameraEntity() != mc.player ? mc.getCameraEntity() : null;
	}

	/** Each tick while down: a click switches teammate. */
	static void tick(Minecraft mc) {
		if (!Ultracraft.downed || mc.screen != null) {
			attackWas = useWas = false;
			return;
		}
		boolean attack = mc.options.keyAttack.isDown(), use = mc.options.keyUse.isDown();
		if (attack && !attackWas) UcNet.toServer("SPECTATE 1");
		if (use && !useWas) UcNet.toServer("SPECTATE -1");
		attackWas = attack;
		useWas = use;
	}

	/**
	 * The camera while down: behind the teammate as our own mouse look turns it, pulled in where a wall is in the way.
	 * Returns {x, y, z, yaw, pitch}, or null to leave Minecraft's camera as it is.
	 */
	public static double[] pose(Minecraft mc, float partial) {
		Entity t = target(mc);
		if (t == null || mc.player == null) return null;
		float yaw = mc.player.getViewYRot(partial), pitch = mc.player.getViewXRot(partial);
		Vec3 pivot = t.getPosition(partial).add(0.0, t.getBbHeight() * 0.85, 0.0);
		Vec3 look = Vec3.directionFromRotation(pitch, yaw);
		Vec3 want = pivot.subtract(look.scale(DIST));
		var hit = mc.level.clip(new ClipContext(pivot, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, t));
		Vec3 at = hit.getType() == HitResult.Type.MISS ? want : hit.getLocation().add(look.scale(0.25));
		return new double[] {at.x, at.y, at.z, yaw, pitch};
	}

	/** Who we're watching, how they're doing, and how to switch. */
	static void render(Minecraft mc, GuiGraphics ctx) {
		if (!Ultracraft.downed || mc.options.hideGui) return;
		int w = ctx.guiWidth();
		Entity t = target(mc);
		String who = !watchName.isEmpty() ? watchName : t != null ? t.getName().getString() : "a teammate";
		String line1 = "YOU'RE DOWN  -  WATCHING " + who.toUpperCase(java.util.Locale.ROOT);
		String line2 = "A teammate standing where you fell brings you back.  Left / right click: next / previous teammate";
		int y = 10;
		int tw = Math.max(mc.font.width(line1), mc.font.width(line2));
		ctx.fill(w / 2 - tw / 2 - 6, y - 4, w / 2 + tw / 2 + 6, y + 32, 0xA0000000);
		ctx.drawCenteredString(mc.font, line1, w / 2, y, 0xFFFF5050);
		if (t instanceof LivingEntity le) {
			float frac = Math.max(0f, Math.min(1f, le.getHealth() / Math.max(1f, le.getMaxHealth())));
			int bw = 120, bx = w / 2 - bw / 2;
			ctx.fill(bx, y + 12, bx + bw, y + 16, 0xB0200000);
			ctx.fill(bx, y + 12, bx + Math.round(bw * frac), y + 16, frac > 0.5f ? 0xFF40E040 : frac > 0.25f ? 0xFFE0C030 : 0xFFE03030);
		}
		ctx.drawCenteredString(mc.font, line2, w / 2, y + 20, 0xFFC0C0C0);
	}
}
