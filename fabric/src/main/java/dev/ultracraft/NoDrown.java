package dev.ultracraft;

import net.minecraft.server.level.ServerPlayer;

/**
 * V1 does not drown (the "V1 Never Drowns" setting, on by default).
 *
 * <p>Minecraft drowns a player by running down {@code airSupply} while the eyes are underwater; at zero it hurts every
 * second and the bubbles show. Refilling the supply each tick keeps it at its maximum, so the bubbles never appear and
 * the water never hurts, while everything else about being in water (swimming, the fog, ULTRAKILL's muffled sound) is
 * left as it is.
 *
 * <p>Only the V1 form is covered: back as Steve, Minecraft's own rule applies again, and the supply refills by itself
 * as soon as the head is out of the water.
 */
public final class NoDrown {
	private NoDrown() {}

	static void tick(ServerPlayer sp) {
		if (!UltracraftConfig.noDrown) return;
		int max = sp.getMaxAirSupply();
		// one short of full: Minecraft takes the last one away when the head goes under, and a full bar is what
		// keeps the bubbles hidden
		if (sp.getAirSupply() < max) sp.setAirSupply(max);
	}
}
