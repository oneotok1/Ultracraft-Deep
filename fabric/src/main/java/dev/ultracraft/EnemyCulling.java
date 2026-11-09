package dev.ultracraft;

import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL's enemies hidden behind Minecraft's blocks. ULTRAKILL draws Minecraft's terrain into its depth as it has
 * it (WorldMesh), but some of it it may not have yet (a section on its way, beyond the range sent, the edge of a cave),
 * and an enemy there was drawn over the blocks in front of it. Here every few ticks each enemy is looked for from V1's
 * eye: its middle, top, feet and both sides. Seen by none (only blocks that hide what's behind them count: not glass or
 * leaves), ULTRAKILL doesn't draw it (HIDE id,...) until one of them is clear again. It still fights all the same.
 */
final class EnemyCulling {
	/** Close enemies are left to ULTRAKILL's own depth (always there for them): only these blocks out are checked. */
	private static final double NEAR = 6.0, FAR = 200.0;
	private static String sent = "";
	private static int ticks;

	// kept between runs: this goes over every enemy every 4 ticks, and a fresh set, array and string per enemy was
	// the whole cost of the pass. The tree is what makes the message ordered the same way twice, so it stays a tree.
	private static final Set<String> hidden = new TreeSet<>();
	private static final StringBuilder line = new StringBuilder("HIDE ");
	private static final Vec3[] points = new Vec3[5];

	private EnemyCulling() {}

	static void tick(Minecraft mc, LocalPlayer p) {
		ClientLevel level = mc.level;
		if (level == null || ++ticks % 4 != 0) return;
		Vec3 eye = p.getEyePosition();
		hidden.clear();
		for (Lighting.Spot s : Lighting.enemySpots()) {
			Vec3 c = new Vec3(s.x(), s.y(), s.z());
			double d = c.distanceTo(eye);
			if (d < NEAR || d > FAR) continue;
			if (!seen(level, p, eye, c, s.w(), s.h())) hidden.add(s.id());
		}
		line.setLength(5);
		boolean first = true;
		for (String id : hidden) {
			if (!first) line.append(',');
			first = false;
			line.append(id);
		}
		String msg = line.toString();
		// when it changes, and now and then (ULTRAKILL may have missed one, or reconnected)
		if (!msg.equals(sent) || ticks % 40 == 0) {
			sent = msg;
			UkLink.send(msg.trim());
		}
	}

	static void reset() {
		sent = "";
	}

	/** Any of its middle, top, feet and sides visible from the eye. */
	private static boolean seen(ClientLevel level, LocalPlayer p, Vec3 eye, Vec3 c, double w, double h) {
		Vec3 to = c.subtract(eye);
		Vec3 side = new Vec3(-to.z, 0, to.x);
		side = side.lengthSqr() > 1e-6 ? side.normalize().scale(w * 0.5) : Vec3.ZERO;
		points[0] = c;
		points[1] = c.add(0, h * 0.45, 0);
		points[2] = c.add(0, -h * 0.4, 0);
		points[3] = c.add(side);
		points[4] = c.subtract(side);
		for (Vec3 pt : points) if (clear(level, p, eye, pt)) return true;
		return false;
	}

	/** Nothing that blocks sight between the two (see-through blocks are looked past). */
	private static boolean clear(ClientLevel level, LocalPlayer p, Vec3 from, Vec3 to) {
		Vec3 dir = to.subtract(from);
		double len = dir.length();
		if (len < 1e-3) return true;
		dir = dir.scale(1.0 / len);
		Vec3 at = from;
		for (int i = 0; i < 8; i++) {
			BlockHitResult hit = level.clip(new ClipContext(at, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
			if (hit.getType() != HitResult.Type.BLOCK) return true;
			if (level.getBlockState(hit.getBlockPos()).canOcclude()) return false;
			at = hit.getLocation().add(dir.scale(0.05));
			if (at.distanceToSqr(from) >= len * len) return true;
		}
		return true;
	}
}
