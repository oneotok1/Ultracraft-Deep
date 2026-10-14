package dev.ultracraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Damage that got through Minecraft's own rules (difficulty, shields, armour and its enchantments, Resistance,
 * absorption hearts, invulnerability frames) lands on V1 in ULTRAKILL instead of on Steve's health: half a heart is
 * 5 HP. A mob's melee swing goes over as a parryable hit. Burning (fire, lava, magma, campfires) counts for less: at
 * 5 HP a half heart, a few seconds alight took half of V1's health.
 */
public final class V1Damage {
	// the mob whose swing was last sent as MELEE, and on which tick: its knockback follows in the same tick
	private static int meleeMob = -1;
	private static long meleeTick = -1;

	/** How much of a burn's damage V1 takes. */
	private static final float FIRE = 0.35f;

	private V1Damage() {}

	/** Returns true if V1 took it (Minecraft's health stays as it is). */
	public static boolean take(Player player, DamageSource source, float dealt) {
		if (!(player instanceof ServerPlayer sp) || !UcNet.isV1(sp) || dealt <= 0f) return false;
		// ULTRAKILL already said V1 died (or /kill): Minecraft's player dies its own way
		if (source.is(DamageTypes.GENERIC_KILL)) return false;
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) dealt *= FIRE;
		int uk = Math.max(1, Math.round(dealt * 5f));
		if ((source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)) && source.getEntity() instanceof Mob mob && source.getDirectEntity() == mob) {
			// ULTRAKILL flashes the mob and gives V1 the parry window before the hit lands
			UcNet.send(sp, "MELEE " + mob.getId() + " " + uk);
			meleeMob = mob.getId();
			meleeTick = sp.level().getGameTime();
		} else {
			UcNet.send(sp, "HURT " + uk);
		}
		return true;
	}

	/** The mob whose swing hit V1 on this tick (its knockback waits out the parry window with it), else -1. */
	public static int meleeThisTick(ServerPlayer sp) {
		return meleeTick == sp.level().getGameTime() ? meleeMob : -1;
	}
}
