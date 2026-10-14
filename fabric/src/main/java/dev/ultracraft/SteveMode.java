package dev.ultracraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Steve's world acting on V1, every tick while V1 is active:
 * <ul>
 * <li>Minecraft's hearts mirror V1's health, so its own rules (regeneration from a full stomach, starving, poison
 * stopping at half a heart) know where V1 stands; its heals come back to V1 (LivingEntityMixin).</li>
 * <li>V1's running makes Steve hungry, a little gentler than Steve's sprint.</li>
 * <li>Potion effects that move a player (Speed, Slowness, Jump Boost, Slow Falling, Levitation) move V1.</li>
 * <li>Minecraft's lens (a drawn bow, a spyglass, Speed's wider view) zooms ULTRAKILL's camera, so the two keep
 * looking through the same one.</li>
 * </ul>
 */
final class SteveMode {
	private static float lastZoom = 1f;
	private static String lastEffects = "";
	private static long lastEffectsAt;
	private static Vec3 lastPos;

	private SteveMode() {}

	static void tick(Minecraft mc, LocalPlayer p) {
		zoom(mc, p);
		effects(p);
		Vec3 pos = p.position();
		double moved = lastPos == null ? 0 : Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
		lastPos = pos;
		boolean carried = Movement.carried;
		int hp = UkLink.hp, max = UkLink.maxHp;
		boolean dead = UkLink.dead;
		// the server mirrors V1's health in Minecraft's hearts and makes walking hungry (teleports, respawns and being
		// carried aside)
		UcNet.toServer(String.format(java.util.Locale.ROOT, "VITALS %d %d %d %.3f %d", hp, max, dead ? 1 : 0, moved, carried ? 1 : 0));
	}

	static void reset() {
		lastZoom = 1f;
		lastEffects = "";
		lastPos = null;
	}

	/** Minecraft's own field-of-view modifier, without the 10% it adds for flying (V1 only counts as flying). */
	private static void zoom(Minecraft mc, LocalPlayer p) {
		float m;
		if (p.isUsingItem() && !p.getUseItem().is(Items.BOW) && p.isScoping()) {
			m = 0.1f;
		} else {
			float g = 1f;
			float walk = p.getAbilities().getWalkingSpeed();
			if (walk != 0f) g *= ((float) p.getAttributeValue(Attributes.MOVEMENT_SPEED) / walk + 1f) / 2f;
			if (p.isUsingItem() && p.getUseItem().is(Items.BOW)) {
				float pull = Math.min(p.getTicksUsingItem() / 20f, 1f);
				g *= 1f - pull * pull * 0.15f;
			}
			m = Mth.lerp(mc.options.fovEffectScale().get().floatValue(), 1f, g);
		}
		if (Math.abs(m - lastZoom) > 0.01f) {
			lastZoom = m;
			UkLink.send(String.format(java.util.Locale.ROOT, "ZOOM %.3f", m));
		}
	}

	private static void effects(LocalPlayer p) {
		String fx = amp(p, MobEffects.SPEED) + " " + amp(p, MobEffects.SLOWNESS) + " " + amp(p, MobEffects.JUMP_BOOST) + " "
			+ amp(p, MobEffects.SLOW_FALLING) + " " + amp(p, MobEffects.LEVITATION);
		long now = System.currentTimeMillis();
		if (!fx.equals(lastEffects) || now - lastEffectsAt > 5000) {
			lastEffects = fx;
			lastEffectsAt = now;
			UkLink.send("EFFECTS " + fx);
		}
	}

	private static int amp(LocalPlayer p, Holder<MobEffect> effect) {
		MobEffectInstance i = p.getEffect(effect);
		return i == null ? -1 : i.getAmplifier();
	}
}
