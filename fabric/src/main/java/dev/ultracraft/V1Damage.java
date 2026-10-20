package dev.ultracraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Damage that got through Minecraft's own rules (difficulty, shields, armour and its enchantments, Resistance,
 * absorption hearts, invulnerability frames) lands on V1 in ULTRAKILL instead of on Steve's health: half a heart is
 * 5 HP. A mob's melee swing goes over as a parryable hit (a spear's thrust too, though it lands from blocks away).
 * Burning (fire, lava, magma, campfires) counts for less: at
 * 5 HP a half heart, a few seconds alight took half of V1's health.
 */
public final class V1Damage {
	// the mob whose swing was last sent as MELEE, and on which tick: its knockback follows in the same tick
	private static int meleeMob = -1;
	private static long meleeTick = -1;
	// that swing was stopped dead, so it must not shove V1 either
	private static boolean meleeBlocked;
	// the swing Minecraft is resolving right now (armed in hurtServer, settled at its tail): a swing stopped dead
	// anywhere between armour, shields and absorption hearts never reaches take(), so it's reported from here
	private static long swingTick = -1;
	private static boolean swingSent;

	/** How much of a burn's damage V1 takes. */
	private static final float FIRE = 0.35f;

	private V1Damage() {}

	/** Returns true if V1 took it (Minecraft's health stays as it is). */
	public static boolean take(Player player, DamageSource source, float dealt) {
		if (!(player instanceof ServerPlayer sp) || !UcNet.isV1(sp)) return false;
		if (dealt <= 0f) {
			// armour, Resistance or absorption hearts ate the whole swing: Steve isn't hurt, but the swing still
			// happened, so ULTRAKILL gets the same flash a landed one would have had
			blocked(sp, source);
			return false;
		}
		// ULTRAKILL already said V1 died (or /kill): Minecraft's player dies its own way
		if (source.is(DamageTypes.GENERIC_KILL)) return false;
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) dealt *= FIRE;
		// V1's health is scaled to Steve's maximum (see Vitals on the bridge side): the same fraction of the bar
		// rather than a fixed 5 per half heart, so a health boost makes each hit cost the same share
		int uk = Math.max(1, Math.round(dealt * UkLink.maxHp / player.getMaxHealth()));
		Mob mob = swingMob(source);
		if (mob != null) {
			// ULTRAKILL flashes the mob and gives V1 the parry window before the hit lands
			sendMelee(sp, mob, uk);
			meleeMob = mob.getId();
			meleeTick = sp.level().getGameTime();
			meleeBlocked = false;
			swingSent = true;
		} else {
			UcNet.send(sp, "HURT " + uk);
		}
		return true;
	}

	/**
	 * A mob's swing that Minecraft stopped dead (a raised shield): no damage reaches V1, so {@link #take} never runs,
	 * but the swing itself still happened and ULTRAKILL should flash the mob so V1 can punch it back.
	 */
	public static void blocked(ServerPlayer sp, DamageSource source) {
		if (!UcNet.isV1(sp)) return;
		Mob mob = swingMob(source);
		if (mob == null) return;
		sendMelee(sp, mob, 0);
		meleeMob = mob.getId();
		meleeTick = sp.level().getGameTime();
		// only a shield plants V1's feet: armour or absorption hearts soaking a hit doesn't
		meleeBlocked = sp.isBlocking();
		swingSent = true;
	}

	/** A swing Minecraft is about to resolve: remembered so its tail can tell whether any of it reached V1. */
	public static void arm(ServerPlayer sp, DamageSource source) {
		Mob mob = swingMob(source);
		swingTick = mob == null ? -1 : sp.level().getGameTime();
		swingSent = false;
	}

	/**
	 * The swing is over. Nothing arrived at {@link #take}: absorption hearts, armour or Resistance ate all of it and
	 * Minecraft skipped the setHealth call the damage hook sits on. The swing still happened, so ULTRAKILL gets the
	 * same flash a landed one would have had.
	 */
	public static void settle(ServerPlayer sp, DamageSource source) {
		if (swingTick != sp.level().getGameTime()) return;
		swingTick = -1;
		if (swingSent) return;
		blocked(sp, source);
	}

	/**
	 * A mob's swing goes to ULTRAKILL along with how far apart the two were when it landed. A spear's thrust reaches
	 * several blocks past a fist, so the bridge sizes the parry's reach from what Minecraft measured here rather than
	 * from a fixed guess; a swing Minecraft never measured a distance for keeps to the bridge's own reach.
	 */
	private static void sendMelee(ServerPlayer sp, Mob mob, int uk) {
		UcNet.send(sp, "MELEE " + mob.getId() + " " + uk + " " + mob.distanceTo(sp));
	}

	/**
	 * The mob behind a swing that ULTRAKILL should flash, or null for anything else. A spear's thrust counts: Minecraft
	 * gives it its own SPEAR damage type instead of MOB_ATTACK (the item sets it), and it lands from much further out.
	 */
	private static Mob swingMob(DamageSource source) {
		if (!(source.getEntity() instanceof Mob mob) || source.getDirectEntity() != mob) return null;
		if (!source.is(DamageTypes.MOB_ATTACK) && !source.is(DamageTypes.MOB_ATTACK_NO_AGGRO) && !source.is(DamageTypes.SPEAR)) return null;
		return mob;
	}

	/** The mob whose swing hit V1 on this tick (its knockback waits out the parry window with it), else -1. */
	public static int meleeThisTick(ServerPlayer sp) {
		return meleeTick == sp.level().getGameTime() ? meleeMob : -1;
	}

	/** Whether the swing this tick was one Minecraft stopped dead: a blocked swing shoves nobody. */
	public static boolean meleeBlockedThisTick(ServerPlayer sp) {
		return meleeTick == sp.level().getGameTime() && meleeBlocked;
	}
}
