package dev.ultracraft;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;

/**
 * Minecraft mobs soaked in the Firestarter's gasoline (OILED id amount, from ULTRAKILL): ULTRAKILL would darken its own
 * enemies with it, but Minecraft draws these mobs, so the gasoline drips off them here until it burns or wears off.
 */
final class OilDrips {
	private static final Map<Integer, Integer> SOAKED = new HashMap<>();
	private static final DustParticleOptions OIL = new DustParticleOptions(0x2A1E0C, 1.1f);
	private static long nextDrip;

	private OilDrips() {}

	static void set(int id, int amount) {
		if (amount <= 0) SOAKED.remove(id);
		else SOAKED.put(id, amount);
	}

	/** A few drops a tick from each soaked mob, more the more gasoline it carries. */
	static void tick(Minecraft mc) {
		if (SOAKED.isEmpty() || mc.level == null) return;
		long now = System.currentTimeMillis();
		if (now < nextDrip) return;
		nextDrip = now + 50;
		var r = mc.level.getRandom();
		SOAKED.entrySet().removeIf(en -> {
			Entity e = mc.level.getEntity(en.getKey());
			if (e == null || !e.isAlive()) return true;
			int drops = 1 + en.getValue() / 3;
			for (int k = 0; k < drops; k++) {
				double x = e.getX() + (r.nextDouble() - 0.5) * e.getBbWidth();
				double y = e.getY() + r.nextDouble() * e.getBbHeight();
				double z = e.getZ() + (r.nextDouble() - 0.5) * e.getBbWidth();
				mc.level.addParticle(OIL, x, y, z, 0, -0.08, 0);
			}
			return false;
		});
	}
}
