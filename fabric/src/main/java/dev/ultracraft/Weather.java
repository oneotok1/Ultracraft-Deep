package dev.ultracraft;

import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;

/**
 * Minecraft's sky on ULTRAKILL: where the sun stands (ULTRAKILL's sun follows it, the moon takes over at night),
 * how bright the day is, rain and thunder (SUN); and which of ULTRAKILL's enemies the rain falls on (WET).
 */
final class Weather {
	private static String lastSun = "", lastWet = "";
	private static long lastSent;

	private Weather() {}

	static void export(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) return;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Camera camera = mc.gameRenderer.getMainCamera();
		int sky = level.dimension() == Level.NETHER ? 1 : level.dimension() == Level.END ? 2 : 0;
		float angle = camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partial);
		float day = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partial);
		String sun = String.format(Locale.ROOT, "SUN %.1f %.2f %.2f %.2f %d", angle, day, level.getRainLevel(partial), level.getThunderLevel(partial), sky);
		long now = System.currentTimeMillis();
		if (!sun.equals(lastSun) || now - lastSent > 5000) {
			lastSun = sun;
			UkLink.send(sun);
		}
		// rain reaches what stands under the open sky (Minecraft's own test, as for a mob catching fire or not)
		StringBuilder wet = new StringBuilder("WET ");
		if (level.isRaining()) {
			for (Lighting.Spot s : Lighting.enemySpots()) {
				if (level.isRainingAt(BlockPos.containing(s.x(), s.y(), s.z())) || level.isRainingAt(BlockPos.containing(s.x(), s.y() + 1.0, s.z()))) {
					wet.append(s.id()).append(',');
				}
			}
		}
		String w = wet.toString();
		if (!w.equals(lastWet) || now - lastSent > 5000) {
			lastWet = w;
			UkLink.send(w);
		}
		if (now - lastSent > 5000) lastSent = now;
	}

	static void reset() {
		lastSun = "";
		lastWet = "";
	}
}
