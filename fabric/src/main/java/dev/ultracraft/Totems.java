package dev.ultracraft;

import dev.ultracraft.mixin.LivingEntityAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Totem of Undying, for V1.
 *
 * <p>Minecraft's own death protection never runs while V1's health lives in ULTRAKILL: Steve's bar never reaches zero,
 * so there is no death to ward off. What it does is asked for directly instead ({@code checkTotemDeathProtection},
 * through {@link LivingEntityAccessor}): the item is found the way the game finds it, in the two hands and by its
 * {@code DEATH_PROTECTION} component rather than by its name, one is spent, and the sound, the burst of green and the
 * effects all come from there - so another mod's totem works too, and so does holding one in either hand.
 *
 * <p>ULTRAKILL's own enemies never pass through Minecraft, so a blow from them is warded off on the track side, where
 * the count of totems V1 holds is kept ({@code TOTEM n}, only when it changes); that side asks for one back
 * ({@code TOTEMUSE}) and this side runs the same protection.
 */
public final class Totems {
	/** The count ULTRAKILL last heard, so the line only goes when it moved. */
	private static final Map<UUID, Integer> SENT = new HashMap<>();

	private Totems() {}

	/** Totems in the two hands: what Minecraft itself looks at, and each stack of them is that many uses. */
	static int count(ServerPlayer sp) {
		int n = 0;
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = sp.getItemInHand(hand);
			if (stack.has(DataComponents.DEATH_PROTECTION)) n += stack.getCount();
		}
		return n;
	}

	/** Tells ULTRAKILL how many totems V1 carries, when that changes. */
	static void sync(ServerPlayer sp) {
		int n = count(sp);
		Integer was = SENT.get(sp.getUUID());
		if (was != null && was == n) return;
		SENT.put(sp.getUUID(), n);
		UcNet.send(sp, "TOTEM " + n);
	}

	/**
	 * A blow that would have been V1's last, to be warded off the way Minecraft does it: the item is in a hand, the
	 * component grants whatever it grants, the sound and the green burst happen, and Steve is left at one health.
	 */
	static boolean trySave(ServerPlayer sp, DamageSource source) {
		if (!((LivingEntityAccessor) sp).ultracraft$checkTotemDeathProtection(source)) return false;
		// vanilla left Steve on half a heart: that bar is only a shadow of V1's, whose real health is on the other
		// side, so it is put back where it was rather than left as a nearly dead player the rest of Minecraft would act on
		sp.setHealth(sp.getMaxHealth());
		sync(sp);
		return true;
	}

	/** TOTEMUSE: ULTRAKILL warded off a blow of its own. One is spent here exactly as it would be there. */
	static void use(ServerPlayer sp) {
		if (trySave(sp, sp.damageSources().generic())) return;
		// nothing in a hand to spend: ULTRAKILL is told the count that is really left
		sync(sp);
	}

	static void reset(ServerPlayer sp) {
		SENT.remove(sp.getUUID());
	}
}
