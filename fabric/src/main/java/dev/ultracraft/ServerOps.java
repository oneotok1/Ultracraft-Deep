package dev.ultracraft;

import dev.ultracraft.mixin.AbstractArrowAccessor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The server's side of Ultracraft: what one player's ULTRAKILL did to the shared world (block hits, blasts, damage
 * to mobs, parries, P earned, things bought, enemies' positions...), as a line of text from that player (UcNet), and
 * what the server runs for every V1 each tick (spawns in the dark, bosses, the Cyber Grind). Server thread only.
 */
public final class ServerOps {
	/** What the server knows of each V1's player beyond their entity. */
	static final class State {
		boolean playing, shopTouch;
		/** The absorption value ULTRAKILL last heard of (Steve's yellow hearts: only this side can spend them). */
		int sentAbsorb = -1;
		/** ULTRAKILL's style rank (STYLE) and the streak of kills at S and up (StyleRewards). */
		int rank, streak;
		/** Cheats on the server's side: Never Hungry, Infinite P (the Upgrades page takes nothing). */
		boolean neverHungry, infiniteP;
		/** When an arena last told this player about itself (game time). */
		long arenaNoticeAt;
		/** The stand-ins of this player's ULTRAKILL's enemies, by that ULTRAKILL's id. */
		final Map<Integer, UkEnemyEntity> enemies = new HashMap<>();
	}

	private static final Map<UUID, State> STATES = new HashMap<>();
	private static int ticks;
	/** How long a mob stays without goals after the reel lets go of it. Long enough to land and be looked at,
	 * short enough that it is a stagger rather than a stun. */
	private static final int STAGGER_TICKS = 25;

	private ServerOps() {}

	/**
	 * Whether V1's (or, enemy, ULTRAKILL's enemies') attacks break blocks here: as the settings say, but never in the
	 * Cyber Grind's arenas.
	 */
	static boolean breaks(Level level, boolean enemy) {
		if (level.dimension() == GrindArenas.DIMENSION) return false;
		return enemy ? UltracraftConfig.enemyBlockDamage : UltracraftConfig.playerBlockDamage;
	}

	/**
	 * A blast of V1's puts out the fire around it: the flames it lands on, and the ones it would have started. Fire is
	 * a block, but neither "Breaks Blocks" setting has anything to do with it, so this works with them off, and in the
	 * Cyber Grind's arenas (which no blast may touch otherwise). The nuke's fire is huge, hence the cap on the reach.
	 */
	private static void putOut(ServerLevel level, double x, double y, double z, float power) {
		if (!UltracraftConfig.blastFireOut) return;
		BlockPos center = BlockPos.containing(x, y, z);
		int r = Mth.clamp(Mth.ceil(power), 1, 24);
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			if (level.getBlockState(p).is(BlockTags.FIRE)) level.removeBlock(p, false);
		}
		// Whoever the blast reached stops burning too: a block of fire can be put out, but an entity on fire cannot.
		double reach = r + 1.0;
		for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(x - reach, y - reach, z - reach, x + reach, y + reach, z + reach))) {
			if (e.isSpectator() || !(e instanceof LivingEntity le)) continue;
			if (le.getRemainingFireTicks() > 0) le.clearFire();
		}
	}

	static State state(ServerPlayer sp) {
		return STATES.computeIfAbsent(sp.getUUID(), k -> new State());
	}

	static java.util.Collection<State> statesView() {
		return STATES.values();
	}

	static boolean shopping(ServerPlayer sp) {
		State s = STATES.get(sp.getUUID());
		return s != null && s.shopTouch;
	}

	/** Mobs ULTRAKILL's enemies sent flying, and the tick they were: no fall damage for them until they land. */
	private static final Map<LivingEntity, Integer> FLUNG = new HashMap<>();
	/** Mobs V1 is reeling in, which V1 holds each and since when: while it holds one, the mob does nothing of its own.
	 * ULTRAKILL's reel leaves a light enemy no way to act (its ground check is off and its speed is rewritten every
	 * frame), so a reeled-in mob stops walking, jumping and steering instead of fighting the pull. */
	private record Whip(ServerPlayer v1, int at, boolean noGravity) {}

	private static final Map<LivingEntity, Whip> WHIPPED = new HashMap<>();
	/** Mobs the hook has let go of, and the tick their AI comes back: a reeled-in mob is dropped right in V1's face,
	 * so handing its goals back on the let-go tick makes it swing at V1 the instant it lands. ULTRAKILL's own reel
	 * leaves its enemy reeling for a moment after the pull ends, and that is what this grace period reproduces. */
	private static final Map<LivingEntity, Integer> RELEASED_WHIP = new HashMap<>();
	/** Players just in, and the tick their join is dealt with: not while their client is still loading the world. */
	private static final Map<UUID, Integer> justJoined = new HashMap<>();

	static void fling(LivingEntity le) {
		le.fallDistance = 0;
		FLUNG.put(le, ticks);
	}

	private static void landFlung() {
		if (FLUNG.isEmpty()) return;
		FLUNG.entrySet().removeIf(en -> {
			LivingEntity le = en.getKey();
			if (le.isRemoved() || !le.isAlive()) return true;
			le.fallDistance = 0;
			int age = ticks - en.getValue();
			return (age > 5 && le.onGround()) || le.isInWater() || age > 600;
		});
	}

	/** Let go of a mob V1 was reeling in: it stands where the reel left it and its weight comes back. */
	private static void releaseWhip(LivingEntity le) {
		stopWhip(le);
	}

	/** Whip a held mob toward V1 at ULTRAKILL's 60 units a second, and only that: the reel is its whole motion, so it
	 * does not also fall or coast. One message holds it; the aiming is done here, each tick, from where V1 is. */
	private static void landWhipped() {
		if (WHIPPED.isEmpty()) return;
		WHIPPED.entrySet().removeIf(en -> {
			LivingEntity le = en.getKey();
			if (le.isRemoved() || !le.isAlive()) return true;
			Whip w = en.getValue();
			ServerPlayer v1 = w.v1();
			if (v1 == null || v1.isRemoved() || !v1.isAlive() || ticks - w.at() > 100) {
				halt(le, w);
				RELEASED_WHIP.put(le, ticks + STAGGER_TICKS);
				return true;
			}
			// it does not steer while it is reeled in; only its own AI is suppressed, its movement is not. Cleared
			// every tick because the goal selectors can pick a new target the moment the old one is dropped.
			if (le instanceof net.minecraft.world.entity.Mob mob) {
				mob.getNavigation().stop();
				mob.setTarget(null);
			}
			// any hit taken mid-reel starts the 20-tick hurt flash again; ULTRAKILL has no such frames
			le.invulnerableTime = 0;
			Vec3 to = v1.getEyePosition();
			Vec3 from = le.position().add(0, le.getBbHeight() * 0.5, 0);
			Vec3 dir = to.subtract(from);
			double len = dir.length();
			// no arrival test here: whether the mob is close enough to let go is the hook's call, and ULTRAKILL decides
			// it off the stand-in it can see. Two thresholds a few centimetres apart argued, and the late one let the
			// mob float into V1 before either fired. This one is only a backstop for a release that never arrives -
			// far inside the hook's own distance, so it cannot fire first and start arguing again.
			if (len < 0.3) {
				halt(le, w);
				RELEASED_WHIP.put(le, ticks + STAGGER_TICKS);
				return true;
			}
			// 60 units a second, in blocks a tick (ULTRAKILL's unit is 3.5/1.8 of a block)
			double speed = Math.min(3.0, 60.0 / (3.5 / 1.8) / 20.0);
			// never overshoot V1: a mob further out than one tick of travel closes the gap exactly and stops on the
			// next tick, instead of sailing past and being pulled back from the far side. Half of what is left as
			// well, so the last stride in cannot carry it past the hook's own release distance in one tick - at a
			// full stride it crossed that distance between two ticks and the hook let go only after the bodies met.
			double step = Math.min(speed, len * 0.5);
			Vec3 v = dir.scale(step / len);
			le.setDeltaMovement(v.x, v.y, v.z);
			le.fallDistance = 0;
			le.hurtMarked = true;
			// only the velocity is told to the mob's own client; everyone else, V1's client included,
			// would wait for the ordinary 20-tick entity update and see the mob hang still for the
			// first fifth of a second, then jump. Send where it actually is on every reel tick.
			syncNow((ServerLevel) v1.level(), le);
			return false;
		});
	}

	/** True while the hook has this mob, and so while MobAiMixin keeps its AI switched off: a reeled mob decides
	 * nothing, the way ULTRAKILL's reel leaves its enemies helpless. A mob just let go of counts as reeled for a
	 * moment longer, or it would swing at V1 on the very tick it was dropped in front of him. */
	public static boolean isWhipped(LivingEntity le) {
		if (WHIPPED.containsKey(le)) return true;
		Integer until = RELEASED_WHIP.get(le);
		return until != null && ticks < until;
	}

	/** Stop reeling a mob: it stands where the reel left it, with its weight back. The speed is cleared as well, or it
	 * carries the last tick's reel and keeps going in the direction it was being pulled. Its goals stay off for a
	 * short grace period - see RELEASED_WHIP. */
	private static void stopWhip(LivingEntity le) {
		Whip w = WHIPPED.remove(le);
		if (w == null) return;
		halt(le, w);
		RELEASED_WHIP.put(le, ticks + STAGGER_TICKS);
	}

	/** The reversing half of stopWhip, for the callers that are already walking WHIPPED and must not touch it: which
	 * entry is dropped is their business (removeIf), the mob's state is restored here. */
	private static void halt(LivingEntity le, Whip w) {
		le.setNoGravity(w.noGravity());
		le.setDeltaMovement(Vec3.ZERO);
		le.hurtMarked = true;
		le.fallDistance = 0;
	}

	/** A player joined: dealt with a second from now, once their client has the world (joining is what puts a
	 * Cyber Grind runner back at the spawn, and moving them mid-load left them stuck on "Loading terrain"). */
	static void joinLater(ServerPlayer sp) {
		justJoined.put(sp.getUUID(), ticks + 20);
	}

	/** Every server tick: each V1's spawns, bosses and Cyber Grind. */
	static void tick(MinecraftServer server) {
		ticks++;
		justJoined.entrySet().removeIf(e -> {
			if (e.getValue() > ticks) return false;
			ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
			// still loading, and not so long ago that they are gone: wait for them (600 ticks at the most)
			if (sp != null && !sp.connection.hasClientLoaded() && e.getValue() > ticks - 600) return false;
			if (sp != null) {
				guard("joined", () -> BossParty.joined(sp));
				guard("joined", () -> CyberGrind.joined(sp));
			}
			return true;
		});
		landFlung();
		landWhipped();
		if (!RELEASED_WHIP.isEmpty()) RELEASED_WHIP.entrySet().removeIf(en -> ticks >= en.getValue() || en.getKey().isRemoved());
		if (ticks % 10 == 0) guard("boss party", () -> BossParty.tick(server));
		guard("duels", () -> Duels.tick(server));
		for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
			State s = state(sp);
			// the Never Hungry cheat (as Steve too)
			if (s.neverHungry && ticks % 20 == 0) {
				sp.getFoodData().setFoodLevel(20);
				sp.getFoodData().setSaturation(5f);
			}
			if (!UcNet.isV1(sp)) continue;
			if (ticks % 40 == 0 && !UkBosses.busy()) guard("spawns", () -> UkSpawns.tick(sp));
			if (ticks % 10 == 0) guard("bosses", () -> UkBosses.tick(sp, s.playing));
			if (ticks % 20 == 5) guard("arenas", () -> Arenas.tick(sp));
			if (ticks % 10 == 0 && CyberGrind.running && CyberGrind.isRunner(sp)) guard("cyber grind", () -> CyberGrind.tick(sp));
		}
		if (ticks % 20 == 0) {
			for (ServerLevel level : server.getAllLevels()) MobRules.sweep(level);
		}
	}

	private static void guard(String what, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("ultracraft").error(what, e);
		}
	}

	/** A player left: nothing of theirs stays (their enemies, a boss coming for them, their Cyber Grind run). */
	static void left(ServerPlayer sp) {
		State s = STATES.remove(sp.getUUID());
		if (s != null) for (UkEnemyEntity e : s.enemies.values()) e.discard();
		UkBosses.stop(sp, "they left");
		CyberGrind.runnerLeft(sp);
		UcNet.setServerV1(sp.level().getServer(), sp.getUUID(), false);
	}

	/** The world closed. */
	static void reset() {
		STATES.clear();
		FLUNG.clear();
		UcNet.clearServer();
		Duels.reset();
	}

	/** Cheats are allowed for this player: in their own world (singleplayer, or the one they host), or as an operator. */
	static boolean cheatsAllowed(ServerPlayer sp) {
		MinecraftServer server = sp.level().getServer();
		return server.isSingleplayerOwner(sp.nameAndId()) || sp.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	/** One line from a player (their ULTRAKILL's, or their Minecraft side's). */
	static void handle(ServerPlayer sp, String msg) {
		try {
			dispatch(sp, msg);
		} catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger("ultracraft").warn("op from {}: {} ({})", sp.getName().getString(), msg.length() > 80 ? msg.substring(0, 80) : msg, e.toString());
		}
	}

	private static void dispatch(ServerPlayer sp, String msg) {
		int space = msg.indexOf(' ');
		String cmd = space < 0 ? msg : msg.substring(0, space);
		String rest = space < 0 ? "" : msg.substring(space + 1);
		String[] a = msg.split(" ");
		ServerLevel level = sp.level();
		MinecraftServer server = level.getServer();
		switch (cmd) {
			case "V1" -> {
				// V1 1|0 [keep]: this player became V1, or Steve again (keep: ULTRAKILL's enemies stay)
				boolean on = rest.trim().equals("1");
				boolean keep = rest.contains("keep");
				UcNet.setServerV1(server, sp.getUUID(), on);
				if (on) {
					var src = server.createCommandSourceStack().withSuppressedOutput();
					for (String c : new String[] {"gamerule player_movement_check false", "gamerule elytra_movement_check false", "gamerule fall_damage false", "gamerule natural_regeneration true"}) {
						server.getCommands().performPrefixedCommand(src, c);
					}
					sp.getAbilities().mayfly = true;
					sp.onUpdateAbilities();
					UkProgress.get(sp).send();
					CyberGrind.sendState(sp);
				} else {
					CyberGrind.stopIfRunner(sp, "back to Steve");
					UkBosses.stop(sp, "V1 is gone");
					State s = state(sp);
					if (!keep) {
						for (UkEnemyEntity e : s.enemies.values()) e.discard();
						s.enemies.clear();
					}
				}
			}
			case "SHURT" -> {
				// SHURT damage: as Steve, an ULTRAKILL enemy's hit (ULTRAKILL's 100 HP -> Minecraft's 20)
				float dmg = Float.parseFloat(rest.trim()) / 5f;
				if (dmg > 0 && sp.isAlive() && !UcNet.isV1(sp)) sp.hurtServer(sp.level(), sp.damageSources().generic(), dmg);
			}
			case "PLAYING" -> state(sp).playing = rest.trim().equals("1");
			case "CHEATSRV" -> {
				// CHEATSRV id 0/1: a cheat with a server side (only in the player's own world, or for operators)
				boolean on = a.length > 2 && a[2].equals("1") && cheatsAllowed(sp);
				State st = state(sp);
				if (a[1].equals("ultracraft.never-hungry")) st.neverHungry = on;
				else if (a[1].equals("ultracraft.infinite-p")) st.infiniteP = on;
			}
			case "NOSPAWN" -> UkSpawns.missing(rest.trim());
			case "STYLE" -> StyleRewards.rank(sp, Integer.parseInt(rest.trim()));
			case "SHOP" -> state(sp).shopTouch = rest.trim().equals("1");
			case "PROGRESS" -> {
				UkProgress.get(sp).send();
				CyberGrind.sendState(sp);
			}
			case "DEAD" -> {
				// V1 died in ULTRAKILL: Minecraft's player dies too (respawn goes through Minecraft); in a boss fight
				// with a teammate still standing, only down (BossParty); in a duel, it's lost (and nobody dies)
				if (Duels.lost(sp)) return;
				if (BossParty.tryDown(sp)) return;
				// the last one standing in the Grind's arenas: the run is over, and nobody dies for it
				if (CyberGrind.inArenaRun(sp)) {
					CyberGrind.wipe(sp);
					return;
				}
				CyberGrind.stopIfRunner(sp, "V1 died");
				UkBosses.stop(sp, "V1 died");
				sp.kill(level);
			}
			case "VITALS" -> {
				// VITALS hp max dead moved carried: Minecraft's hearts mirror V1's health; walking makes V1 hungry
				int hp = Integer.parseInt(a[1]), max = Integer.parseInt(a[2]);
				boolean dead = a[3].equals("1"), carried = a[5].equals("1");
				double moved = Double.parseDouble(a[4]);
				if (!dead && max > 0 && !sp.isDeadOrDying()) {
					float h = Mth.clamp(hp * sp.getMaxHealth() / max, 1f, sp.getMaxHealth());
					if (Math.abs(sp.getHealth() - h) > 0.01f) sp.setHealth(h);
				}
				if (moved > 0.01 && moved < 8 && !carried) sp.causeFoodExhaustion((float) (moved * 0.02));
				// V1's absorption hearts are Steve's own, and this side is where they are kept: Minecraft's damage
				// spends them on its own, ULTRAKILL's hits spend them through POOL below, and its ULTRAKILL hears
				// about every change to them
				int absorb = Mth.floor(sp.getAbsorptionAmount());
				if (absorb != state(sp).sentAbsorb) {
					state(sp).sentAbsorb = absorb;
					UcNet.send(sp, "ABSORB " + absorb);
				}
			}
			case "POOL" -> {
				// POOL mc: ULTRAKILL soaked part of a hit with V1's absorption hearts; this is what is left of them
				sp.setAbsorptionAmount(Math.max(0f, Float.parseFloat(rest.trim())));
				state(sp).sentAbsorb = Mth.floor(sp.getAbsorptionAmount());
			}
			case "UKE" -> UltracraftCommon.updateUkEnemies(sp, rest);
			case "UKDIE" -> {
				// UKDIE id: that enemy died (its puppets in the other players' ULTRAKILLs die with it)
				UkEnemyEntity e = state(sp).enemies.get(Integer.parseInt(rest.trim()));
				if (e != null) e.died();
			}
			case "PHIT" -> {
				// PHIT entity damage head explosion: this player hit another player's enemy (its puppet in their
				// ULTRAKILL): the real one, in its owner's ULTRAKILL, takes it
				if (level.getEntity(Integer.parseInt(a[1])) instanceof UkEnemyEntity e && e.owner != null) {
					ServerPlayer owner = server.getPlayerList().getPlayer(e.owner);
					if (owner != null) UcNet.send(owner, "EHIT " + e.ukId + " " + a[2] + " " + a[3] + " " + a[4] + " " + sp.getId());
				}
			}
			case "SLAM" -> {
				// SLAM x y z drop: V1 slammed into the ground after falling `drop` blocks; from high up it leaves a crater
				double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]), z = Double.parseDouble(a[3]);
				float power = Math.min(16f, Float.parseFloat(a[4]) / 12.5f);
				slamHurt(sp, level, x, y, z, 1.5 + Math.max(0f, power) * 0.4);
				// the landing puts fire out even when it is too short a fall to leave a crater
				putOut(level, x, y - 0.5, z, Math.max(1f, power));
				if (power < 1f) return;
				// the sound, the smoke and the shove are Minecraft's own explosion; the crater is BlockDamage.blast's ball
				level.explode(sp, null, V1_BLAST, x, y - 0.5, z, power, false, Level.ExplosionInteraction.NONE, ParticleTypes.EXPLOSION,
					ParticleTypes.EXPLOSION_EMITTER, BLAST_DEBRIS, SoundEvents.GENERIC_EXPLODE);
				if (breaks(level, false)) BlockDamage.blast(level, sp, x, y - 0.5, z, power, false);
				putOut(level, x, y - 0.5, z, power);
			}
			case "RAIL" -> {
				// RAIL x y z dx dy dz length radius: the Electric railcannon bores through the terrain
				if (!breaks(level, false)) return;
				Vec3 point = new Vec3(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
				Vec3 dir = new Vec3(Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6])).normalize();
				BlockDamage.tunnel(level, sp, point, dir, Float.parseFloat(a[7]), Float.parseFloat(a[8]));
			}
			case "BOOM" -> {
				// an ULTRAKILL explosion: blow the same spot up in Minecraft (blocks, drops, mob knockback); "e": an
				// enemy's; with its side's "Breaks Blocks" setting off it hurts and throws mobs but leaves blocks alone
				double x = Double.parseDouble(a[1]), y = Double.parseDouble(a[2]), z = Double.parseDouble(a[3]);
				String kind = a.length > 5 ? a[5] : "";
				float size = Float.parseFloat(a[4]);
				if (kind.equals("f")) {
					// a blast that leaves the blocks alone (one that deals no damage, a Freezeframe rocket's, or one
					// right where V1 stands, the Knuckleblaster's shockwave): it puts the fire out and nothing else
					putOut(level, x, y, z, Math.max(1f, Math.min(UltracraftConfig.opShop ? 32f : 10f, size)));
					return;
				}
				if (kind.equals("2")) {
					// the mini nuke: a burning crater twice anything else's size, hurting every mob caught in it
					float power = Math.max(18f, Math.min(UltracraftConfig.opShop ? 48f : 24f, size * 2f));
					// the fire already there goes first: the nuke's own crater is still set alight below
					putOut(level, x, y, z, power);
					level.explode(sp, null, V1_SLAM, x, y, z, power, false, Level.ExplosionInteraction.NONE,
						ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER, BLAST_DEBRIS, SoundEvents.GENERIC_EXPLODE);
					if (breaks(level, kind.equals("e"))) BlockDamage.blast(level, sp, x, y, z, power, true);
					return;
				}
				// the OP Shop's blasts (up to 15 times the size) get a bigger cap
				float power = Math.max(1f, Math.min(UltracraftConfig.opShop ? 32f : kind.equals("1") ? 16f : 10f, size));
				// the shove and the smoke are Minecraft's own explosion (no damage: ULTRAKILL dealt its own), the crater
				// comes from BlockDamage.blast, which is a ball rather than Minecraft's rays
				level.explode(sp, null, V1_BLAST, x, y, z, power, false, Level.ExplosionInteraction.NONE, ParticleTypes.EXPLOSION,
					ParticleTypes.EXPLOSION_EMITTER, BLAST_DEBRIS, SILENT);
				if (breaks(level, kind.equals("e"))) BlockDamage.blast(level, sp, x, y, z, power, false);
				// V1's own blasts put fires out; an enemy's leave them be
				if (!kind.equals("e")) putOut(level, x, y, z, power);
				if (kind.equals("e")) {
					// an enemy's blast: the mobs it throws land without a fall
					double r = power * 2.0;
					for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(x - r, y - r, z - r, x + r, y + r, z + r),
						m -> m.isAlive() && !(m instanceof Player))) fling(le);
				}
			}
			case "HIT" -> {
				// HIT x y z dx dy dz damage radius ax ay az [e]: a weapon struck a block
				Vec3 point = new Vec3(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
				Vec3 dir = new Vec3(Double.parseDouble(a[4]), Double.parseDouble(a[5]), Double.parseDouble(a[6]));
				float damage = a.length > 7 ? Float.parseFloat(a[7]) : 1f;
				float radius = a.length > 8 ? Float.parseFloat(a[8]) : 0f;
				Vec3 aim = a.length > 11 ? new Vec3(Double.parseDouble(a[9]), Double.parseDouble(a[10]), Double.parseDouble(a[11])).normalize() : dir.normalize();
				boolean enemy = a.length > 12 && a[12].equals("e");
				if (!breaks(level, enemy)) return;
				BlockDamage.clearPlants(level, sp, point, aim, Math.min(64.0, point.distanceTo(sp.getEyePosition()) + 1.0));
				BlockDamage.hit(level, sp, point, dir.normalize(), damage, radius);
			}
			case "FIRE" -> {
				// burning gasoline, or a Streetcleaner's flames ("e"): Minecraft fire there
				if (!breaks(level, a.length > 4 && a[4].equals("e"))) return;
				BlockPos p = BlockPos.containing(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
				for (BlockPos c : new BlockPos[] {p, p.above(), p.below()}) {
					if (level.getBlockState(c).isAir() && BaseFireBlock.canBePlacedAt(level, c, Direction.UP)) {
						level.setBlockAndUpdate(c, BaseFireBlock.getState(level, c));
						break;
					}
				}
			}
			case "DMG" -> damage(sp, level, a);
			case "MKNOCK" -> {
				// MKNOCK id vx vy vz: one of ULTRAKILL's enemies launched this mob as it would launch V1 (its new speed,
				// blocks a tick); it lands without a fall
				if (level.getEntity(Integer.parseInt(a[1])) instanceof LivingEntity le && le.isAlive() && !(le instanceof Player)) {
					le.setDeltaMovement(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]));
					le.hurtMarked = true;
					fling(le);
				}
			}
			case "WHIP" -> {
				// WHIP id held: the reel is running - the mob loses its AI and is pulled to V1 until told otherwise
				// WHIP id: let go
				if (level.getEntity(Integer.parseInt(a[1])) instanceof LivingEntity le && le.isAlive() && !UcNet.isV1(le)) {
					boolean held = a.length > 2 && a[2].equals("held");
					if (held) {
						// the reel is the whole of its motion: ULTRAKILL pulls a light enemy with nothing else acting
						// on it, so its own gravity is taken away for the hold and put back on release
						if (le instanceof net.minecraft.world.entity.Mob mob) {
							mob.getNavigation().stop();
							mob.setTarget(null);
						}
						// every resend of the hold is also its heartbeat: the timeout below measures from the last one,
						// so a reel that takes longer than a few seconds is not mistaken for a lost hook. The saved
						// gravity is only read on the first one, while the mob still has its own.
						Whip prev = WHIPPED.get(le);
						WHIPPED.put(le, new Whip(sp, ticks, prev != null ? prev.noGravity() : le.isNoGravity()));
						le.setNoGravity(true);
						// the hook's own 0.2 damage starts Minecraft's 20-tick hurt flash on it; ULTRAKILL has no such
						// frames, and a reeled mob should not be flashing through the whole pull
						le.invulnerableTime = 0;
					} else {
						releaseWhip(le);
					}
					le.fallDistance = 0;
				}
			}
			case "PIMPACT" -> {
				// ULTRAKILL says this projectile hit V1: apply its own hit (arrow damage, fireball blast...) to V1
				if (level.getEntity(Integer.parseInt(a[1])) instanceof Projectile pr && pr.isAlive()) {
					release(pr);
					pr.setPos(sp.getX(), sp.getY() + sp.getBbHeight() * 0.6, sp.getZ());
					((Ultracraft.HitApplier) pr).ultracraft$applyHit(new EntityHitResult(sp));
				}
			}
			case "PHOLD" -> {
				// the projectile reached V1 and hangs there a moment (a punch now parries it)
				int id = Integer.parseInt(a[1]);
				if (level.getEntity(id) instanceof Projectile pr && pr.isAlive() && !HELD.containsKey(id)) {
					Vec3 came = pr.getDeltaMovement();
					HELD.put(id, new Held(came, pr.isNoGravity()));
					// where ULTRAKILL holds it: just in front of V1's eyes, on the side it came from (by now Minecraft
					// has often carried it on through V1, which it flies through)
					Vec3 back = came.lengthSqr() > 1e-6 ? came.normalize().scale(-1) : sp.getLookAngle();
					pr.setPos(sp.getEyePosition().add(back.scale(0.8)).subtract(0, pr.getBbHeight() * 0.5, 0));
					pr.setDeltaMovement(Vec3.ZERO);
					pr.setNoGravity(true);
					pr.hurtMarked = true;
					syncNow(level, pr);
				}
			}
			case "PRELEASE" -> {
				// V1 dodged it while it hung there: it flies on
				int id = Integer.parseInt(a[1]);
				if (level.getEntity(id) instanceof Projectile pr && pr.isAlive()) {
					Held h = HELD.get(id);
					release(pr);
					if (h != null) pr.setDeltaMovement(h.velocity);
					pr.hurtMarked = true;
					syncNow(level, pr);
				}
			}
			case "PARRY" -> parry(sp, level, a);
			case "PEARN" -> {
				// style V1 earned is its P
				UkProgress p = UkProgress.get(sp);
				p.earn(Integer.parseInt(a[1].trim()));
				p.sendMoney();
			}
			case "PADD" -> {
				UkProgress p = UkProgress.get(sp);
				p.add(Integer.parseInt(a[1].trim()));
				p.sendMoney();
			}
			case "V1STATE", "FX" -> {
				// how this V1 stands and what its game spawned: for every other player near enough to see it
				String out = (cmd.equals("FX") ? "RFX " : "RV1 ") + sp.getId() + " " + rest;
				for (ServerPlayer o : server.getPlayerList().getPlayers()) {
					if (o != sp && o.level() == sp.level() && o.distanceToSqr(sp) < 160 * 160) UcNet.send(o, out);
				}
			}
			case "GEARADD" -> {
				String gear = rest.trim();
				UkProgress.get(sp).addGear(gear);
				sp.sendSystemMessage(Component.translatable("[Ultracraft] Bought: %s", GearNames.of(gear)).withStyle(net.minecraft.ChatFormatting.GOLD));
			}
			case "EQUIP" -> {
				if (a.length >= 3) UkProgress.get(sp).setEquip(a[1], Integer.parseInt(a[2].trim()));
			}
			case "UPBUY" -> {
				// UPBUY key [free]: free with the Infinite P cheat
				String key = a[1];
				UkProgress p = UkProgress.get(sp);
				boolean free = a.length > 2 && a[2].equals("free") && state(sp).infiniteP && cheatsAllowed(sp);
				if (p.buyUpgrade(key, free)) {
					sp.sendSystemMessage(Component.translatable("[Ultracraft] %s upgraded to level %s of %s.", UkUpgrades.name(key), p.level(key), UkUpgrades.TRACKS.get(key).max()).withStyle(net.minecraft.ChatFormatting.GOLD));
				}
				p.sendMoney();
				p.sendUpgrades();
			}
			case "UPSET" -> {
				// UPSET key level: the Upgrades page's level buttons. A lowered level rests until raised again; a
				// downgrade refunds nothing and it can't be raised past the highest level paid for.
				if (a.length < 3 || !UkUpgrades.TRACKS.containsKey(a[1])) break;
				UkProgress p = UkProgress.get(sp);
				// only between 1 and what was paid for: setting it higher would hand out a level nobody bought
				p.setLevel(a[1], Math.min(Integer.parseInt(a[2].trim()), p.bought(a[1])));
				p.sendUpgrades();
			}
			case "BOSSPOS" -> UkBosses.moved(sp, Integer.parseInt(a[1]), new Vec3(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4])),
				a.length > 5 ? Float.parseFloat(a[5]) : -1f);
			// SPECTATE 1|-1: down in a boss fight, watch the next (or previous) teammate still standing
			case "SPECTATE" -> BossParty.cycle(sp, Integer.parseInt(a[1]));
			case "BOSSDEAD" -> UkBosses.beaten(sp, Integer.parseInt(a[1]), new Vec3(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4])));
			case "BOSSGONE" -> UkBosses.gone(sp, Integer.parseInt(a[1]), a.length > 2 ? a[2] : "?");
			case "GRIND" -> CyberGrind.toggle(sp, Long.parseLong(rest.trim()));
			case "SPAWNS" -> {
				// a shop's Sandbox page switched ULTRAKILL's enemies spawning in the dark (the world's setting)
				UltracraftConfig.ukSpawns = rest.trim().equals("1");
				UltracraftConfig.save();
				CyberGrind.sendStateAll(server);
				sp.displayClientMessage(Component.translatable(UltracraftConfig.ukSpawns ? "ULTRAKILL's enemies spawn in the dark again" : "ULTRAKILL's enemies no longer spawn in the dark"), true);
			}
			case "UKDEAD" -> {
				// UKDEAD type x y z rank grind [credit]: one of this player's ULTRAKILL's enemies died: its experience
				// drops there. credit (a teammate's Minecraft entity id) is who landed the killing blow: the experience,
				// the streak and the loot are theirs, since the enemy died to their hit (their ULTRAKILL paid the style)
				Vec3 at = new Vec3(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]));
				int rank = a.length > 5 ? Integer.parseInt(a[5]) : 0;
				ServerPlayer paid = sp;
				ServerPlayer mate = a.length > 7 ? playerOf(server, Integer.parseInt(a[7])) : null;
				if (mate != null && UcNet.isV1(mate)) paid = mate;
				// the rank that scales the experience is the killer's own, and only their game knows it
				if (paid != sp) rank = state(paid).rank;
				UkSpawns.died(paid, level, a[1], at, rank);
				// the wave still counts against the runner: it is their Cyber Grind
				if (a.length > 6 && a[6].equals("1")) CyberGrind.died(sp);
			}
			case "UKMULTI" -> {
				// UKMULTI entity: that kill also extended a multikill chain, which counts in the killer's game
				ServerPlayer mate = playerOf(server, Integer.parseInt(a[1]));
				if (mate != null && UcNet.isV1(mate)) UcNet.send(mate, "UKMULTI");
			}
			case "UKSTYLE" -> {
				// UKSTYLE entity points pointID: a teammate killed one of this player's ULTRAKILL enemies with the blow
				// that ended it: the style for that kill belongs to them, not to the enemy's owner
				ServerPlayer mate = playerOf(server, Integer.parseInt(a[1]));
				if (mate != null && UcNet.isV1(mate)) UcNet.send(mate, "UKCREDIT " + a[2] + " " + (a.length > 3 ? a[3] : ""));
			}
			// GRINDGONE: one of the wave's enemies never came, or fell out of the world: the wave doesn't wait for it
			case "GRINDGONE" -> CyberGrind.died(sp);
			default -> {
			}
		}
	}

	// a player by its Minecraft entity id: the level's entity map is for the world's creatures, not for players
	private static ServerPlayer playerOf(MinecraftServer server, int id) {
		if (server == null) return null;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.getId() == id) return p;
		return null;
	}

	// ------------------------------------------------------------------ damage to mobs

	/** DMG id amount head explosion parry by: V1 (or one of its ULTRAKILL's enemies, by) hurt a Minecraft entity. */
	/**
	 * Minecraft health per ULTRAKILL damage V1 deals a mob. A revolver shot is two hits of 1.25 (ULTRAKILL's own beam),
	 * a headshot twice that: at the start (40% Power) a headshot does 2, and at 10 a point that killed a spider (16) and
	 * left a zombie (20) on a sliver. At 6, either takes two headshots; fully upgraded weapons still kill in one.
	 * ULTRAKILL's enemies hitting mobs, and a parry, keep 10.
	 */
	private static final float V1_HIT = 6f;

	private static void damage(ServerPlayer sp, ServerLevel level, String[] a) {
		int id = Integer.parseInt(a[1]);
		float amount = Float.parseFloat(a[2]);
		boolean explosion = a[4].equals("1");
		boolean parry = a.length > 5 && a[5].equals("1");
		int by = a.length > 6 ? Integer.parseInt(a[6]) : 0;
		boolean fire = a.length > 7 && a[7].equals("1");
		Entity e = level.getEntity(id);
		if (e instanceof EndCrystal crystal) {
			// an end crystal V1 shot or blew up: it goes off, whatever the world-destruction setting
			if (crystal.isAlive()) crystal.hurtServer(level, explosion ? level.damageSources().explosion(sp, sp) : level.damageSources().playerAttack(sp), Math.max(1f, amount * 10f));
			return;
		}
		if (!(e instanceof LivingEntity le) || !le.isAlive()) return;
		// left out of the Mob Damage Filter: ULTRAKILL's side never touches it (its stand-in is gone, this is the
		// one frame where a hit that was already on its way could still land)
		if (!MobDamageRules.touchable(le)) return;
		UkEnemyEntity enemy = by != 0 ? state(sp).enemies.get(by) : null;
		// V1s don't hurt each other (their shots stop at a teammate), unless they're dueling (Duels); enemies do
		if (enemy == null && UcNet.isV1(le)) {
			if (le instanceof ServerPlayer other && Duels.hits(sp, other)) {
				le.invulnerableTime = 0;
				le.hurtServer(level, explosion ? level.damageSources().explosion(sp, sp) : level.damageSources().playerAttack(sp), amount * 10f * Duels.DAMAGE);
			}
			return;
		}
		// ULTRAKILL has no invulnerability frames
		le.invulnerableTime = 0;
		var src = enemy != null && !enemy.isRemoved() ? level.damageSources().mobAttack(enemy)
			: fire ? level.damageSources().onFire() : explosion ? level.damageSources().explosion(sp, sp) : level.damageSources().playerAttack(sp);
		// burning gasoline sets it alight in Minecraft too (fire resistance, and mobs that don't burn, shrug it off)
		if (fire && !le.fireImmune()) le.setRemainingFireTicks(Math.max(le.getRemainingFireTicks(), 60));
		// ULTRAKILL's enemies hitting a mob keep 10 (SwingCheck2 sent them the swing's damage/10); a hit on V1
		// carries the swing's own damage instead, and V1Damage turns that back into ULTRAKILL HP at 5 per point.
		// Scaling by 10 here as well is what took a full-health V1 from 100 to 0 in one ordinary swing.
		float scale = enemy != null && le instanceof ServerPlayer v1 && UcNet.isV1(v1) ? 2f
			: (enemy != null || parry ? 10f : V1_HIT);
		le.hurtServer(level, src, amount * scale);
		if (enemy != null) {
			// knocked about by one of ULTRAKILL's enemies: it lands without a fall
			if (le.isAlive() && !(le instanceof Player)) fling(le);
			return;
		}
		if (parry) {
			// a parried mob is sent flying
			Vec3 away = le.position().subtract(sp.position()).multiply(1, 0, 1).normalize();
			le.push(away.x * 1.6, 0.6, away.z * 1.6);
			le.hurtMarked = true;
		}
	}

	// ------------------------------------------------------------------ Minecraft's projectiles and parries

	/** A projectile held still in front of V1 (PHOLD): how it was flying, and whether it had gravity. */
	private record Held(Vec3 velocity, boolean noGravity) {}

	private static final Map<Integer, Held> HELD = new HashMap<>();
	/** Projectiles V1 parried: the extra damage they do to what they hit. */
	private static final Map<Integer, Float> PARRIED = new HashMap<>();

	private static void release(Projectile pr) {
		Held h = HELD.remove(pr.getId());
		if (h != null) pr.setNoGravity(h.noGravity);
		if (HELD.size() > 256) HELD.clear();
	}

	/** PARRY id dx dy dz: parried with the Feedbacker: the projectile is V1's now and flies back where V1 aimed. */
	private static void parry(ServerPlayer sp, ServerLevel level, String[] a) {
		int id = Integer.parseInt(a[1]);
		Vec3 dir = new Vec3(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4])).normalize();
		if (!(level.getEntity(id) instanceof Projectile pr) || !pr.isAlive()) return;
		// three times as fast as it came, and the Feedbacker's Return to Sender makes it faster and harder still
		Held h = HELD.get(id);
		release(pr);
		float sender = UkUpgrades.senderMult(UkProgress.get(sp));
		double came = h != null ? h.velocity.length() : pr.getDeltaMovement().length();
		double speed = Math.min(6.0, Math.max(came, 1.5) * 3.0 * (0.75 + 0.25 * sender));
		pr.setOwner(sp);
		pr.setPos(sp.getEyePosition().add(dir.scale(1.2)));
		pr.setDeltaMovement(dir.scale(speed));
		if (pr instanceof AbstractArrow arrow) {
			arrow.setBaseDamage(((AbstractArrowAccessor) arrow).ultracraft$getBaseDamage() * 3.0 * sender);
			arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		}
		if (pr instanceof net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball fb) {
			var acc = (dev.ultracraft.mixin.LargeFireballAccessor) fb;
			acc.ultracraft$setExplosionPower(Math.round(Math.max(1, acc.ultracraft$getExplosionPower()) * (1.5f + 0.5f * sender)));
		}
		// whatever it hits takes a parry's worth on top of its own damage (see parriedHit)
		PARRIED.put(id, 8f * sender);
		pr.hurtMarked = true;
		syncNow(level, pr);
	}

	/**
	 * A projectile moved by hand: everyone sees it there at once. Minecraft sends a flying projectile's position only every
	 * 10 ticks (fireballs) or 20 (arrows), its velocity at once: a parried one went the right way from where the client
	 * last had it, behind V1, so it looked like it flew straight through V1 unharmed instead of being sent back.
	 */
	private static void syncNow(ServerLevel level, Entity e) {
		level.getChunkSource().sendToTrackingPlayers(e, ClientboundEntityPositionSyncPacket.of(e));
	}

	/** From ProjectileMixin, after any projectile hit something: a parried one hurts it a parry's worth more. */
	public static void parriedHit(Projectile pr, HitResult hit) {
		Float bonus = PARRIED.remove(pr.getId());
		if (PARRIED.size() > 256) PARRIED.clear();
		if (bonus == null || !(hit instanceof EntityHitResult eh) || !(eh.getEntity() instanceof LivingEntity le) || UcNet.isV1(le) || !le.isAlive()) return;
		if (!MobDamageRules.touchable(le)) return;
		if (!(pr.level() instanceof ServerLevel sl)) return;
		le.invulnerableTime = 0;
		le.hurtServer(sl, sl.damageSources().thrown(pr, pr.getOwner()), bonus);
	}

	// ------------------------------------------------------------------ blasts

	/** A ground slam: ULTRAKILL's 2 damage to every mob (not player) close to where V1 came down. */
	private static void slamHurt(ServerPlayer sp, ServerLevel level, double x, double y, double z, double radius) {
		var box = new net.minecraft.world.phys.AABB(x - radius, y - 1.5, z - radius, x + radius, y + 1.0, z + radius);
		for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, box, m -> m.isAlive() && !(m instanceof Player))) {
			if (le.distanceToSqr(x, le.getY(), z) > radius * radius) continue;
			if (!MobDamageRules.touchable(le)) continue;
			le.invulnerableTime = 0;
			le.hurtServer(level, level.damageSources().playerAttack(sp), 20f);
		}
	}

	/** ULTRAKILL blasts break blocks and push mobs, but never hurt anything twice or shove a V1. */
	private static final ExplosionDamageCalculator V1_BLAST = new ExplosionDamageCalculator() {
		@Override
		public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
			return false;
		}

		/** Dropped items are thrown by the blast, never destroyed by it: what a mob drops as it dies survives the same blast. */
		@Override
		public float getEntityDamageAmount(Explosion explosion, Entity entity, float amount) {
			return 0f;
		}

		@Override
		public float getKnockbackMultiplier(Entity entity) {
			return entity instanceof Player ? 0f : 1f;
		}
	};
	/** A slam crater (or the nuke) hurts every mob in it, but never a player. */
	private static final ExplosionDamageCalculator V1_SLAM = new ExplosionDamageCalculator() {
		@Override
		public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
			return !(entity instanceof Player) && MobDamageRules.touchable(entity);
		}

		/** Dropped items are thrown by the nuke, never destroyed by it: what a mob drops as it dies has to survive. */
		@Override
		public float getEntityDamageAmount(Explosion explosion, Entity entity, float amount) {
			return entity instanceof ItemEntity ? 0f : super.getEntityDamageAmount(explosion, entity, amount);
		}

		@Override
		public float getKnockbackMultiplier(Entity entity) {
			return entity instanceof Player ? 0f : 1f;
		}
	};
	private static final WeightedList<ExplosionParticleInfo> BLAST_DEBRIS = WeightedList.<ExplosionParticleInfo>builder()
		.add(new ExplosionParticleInfo(ParticleTypes.POOF, 0.5F, 1.0F))
		.add(new ExplosionParticleInfo(ParticleTypes.SMOKE, 1.0F, 1.0F))
		.build();
	/** ULTRAKILL already plays its own explosion sound. */
	private static final Holder<SoundEvent> SILENT = Holder.direct(SoundEvents.EMPTY);

	static String format(String f, Object... args) {
		return String.format(Locale.ROOT, f, args);
	}
}
