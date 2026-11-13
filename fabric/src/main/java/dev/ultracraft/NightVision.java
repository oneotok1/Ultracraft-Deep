package dev.ultracraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Night vision for whoever is standing in the arenas' void dimension.
 *
 * <p>The Cyber Grind and the twelve boss rooms share one dimension ({@link GrindArenas#DIMENSION}), and several of them
 * are lit well below what a fight needs - a boss room wrapped in blocks has no sky light at all, and the Grind's own
 * palette is dark by design. Rather than relighting every scene (which a saved world would not pick up anyway), the
 * player is given night vision for as long as they are in there.
 *
 * <p>It reaches both sides of the picture: Minecraft's own lightmap brightens, and {@link Lighting} - which sends the
 * light colours to ULTRAKILL, where the same world is drawn - scales those colours up when the player has the effect.
 *
 * <p>The effect is only ever added when none is present, and only the one this class added is taken away again, so a
 * potion of the player's own survives the trip.
 */
public final class NightVision {
	/** Who was given one by this class, so leaving takes away ours and not theirs. */
	private static final Set<UUID> GIVEN = new HashSet<>();

	private NightVision() {}

	static void tick(ServerPlayer sp) {
		UUID id = sp.getUUID();
		if (sp.level().dimension() != GrindArenas.DIMENSION) {
			if (GIVEN.remove(id)) sp.removeEffect(MobEffects.NIGHT_VISION);
			return;
		}
		MobEffectInstance on = sp.getEffect(MobEffects.NIGHT_VISION);
		if (on != null && !ours(on)) {
			// a potion of their own: leave it be, and remember not to take it away when they leave
			GIVEN.remove(id);
			return;
		}
		if (on != null) return;
		sp.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		GIVEN.add(id);
	}

	/** Whether the effect on the player is the one this class put there: never runs out, and at its lowest level. */
	private static boolean ours(MobEffectInstance on) {
		return on.isInfiniteDuration() && on.getAmplifier() == 0;
	}

	/** The world closed. */
	static void clear() {
		GIVEN.clear();
	}

	static void reset(ServerPlayer sp) {
		GIVEN.remove(sp.getUUID());
	}
}
