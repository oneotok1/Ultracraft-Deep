package dev.ultracraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Steve's armour, for the hits that never reach him.
 *
 * <p>ULTRAKILL's own enemies do their damage on the other side of the bridge: {@code NewMovement.GetHurt} is called
 * directly, so Minecraft's pipeline - armour, toughness, Protection, Resistance - never runs for them, and a Stray's
 * beam lands on V1 with exactly the damage it lands on an unarmoured V1. Minecraft's own mobs are fine: their hits go
 * through {@code hurtServer} and arrive here already reduced.
 *
 * <p>What cannot be sent is a single reduction figure, because Minecraft's own is not one: {@code
 * CombatRules.getDamageAfterAbsorb} scales armour down as the blow gets heavier, so a breastplate takes a larger share
 * of a small hit than of a huge one. The four numbers it works from are sent instead ({@code ARMOR armour toughness
 * protection resistance}, only when one of them moves) and ULTRAKILL does the same arithmetic on its side.
 */
public final class Armour {
	/** The line ULTRAKILL last heard, so it only goes when something moved. */
	private static final Map<UUID, String> SENT = new HashMap<>();

	private Armour() {}

	static void sync(ServerPlayer sp) {
		int armour = sp.getArmorValue();
		float toughness = (float) sp.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
		// Protection and its kin. ULTRAKILL's enemies have no DamageSource of their own on this side, so a plain
		// one stands in: every protection enchantment in the game answers to it, where a typed one might not.
		float protection = EnchantmentHelper.getDamageProtection(sp.level(), sp, sp.damageSources().generic());
		int resistance = sp.hasEffect(MobEffects.RESISTANCE) ? sp.getEffect(MobEffects.RESISTANCE).getAmplifier() + 1 : 0;
		String line = String.format(Locale.ROOT, "ARMOR %d %.2f %.2f %d", armour, toughness, protection, resistance);
		if (line.equals(SENT.get(sp.getUUID()))) return;
		SENT.put(sp.getUUID(), line);
		UcNet.send(sp, line);
	}

	static void reset(ServerPlayer sp) {
		SENT.remove(sp.getUUID());
	}
}
