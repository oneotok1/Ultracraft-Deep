package dev.ultracraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Fighting together. A V1 who dies in a boss fight, or in a Cyber Grind run with others, while another V1 is still
 * fighting isn't dead, only down: they watch a teammate (the camera goes round them; Spectate) and a marker stands
 * where they fell. A teammate who stands on that marker for a few seconds brings them back up there. Otherwise they're
 * back beside a teammate when the boss is beaten (or the Grind's wave is cleared), in a burst of light. Only when every
 * one of them is down is it lost: a boss leaves and they all die; a Grind run ends and everyone goes back where they
 * started, alive. Server thread.
 */
final class BossParty {
	private static final class Down {
		final GameType mode;
		UUID watching;
		/** Where they fell: standing here brings them back. */
		final Vec3 spot;
		final ResourceKey<Level> level;
		/** Ticks a teammate has stood on it so far. */
		int progress;
		/** In the Cyber Grind (else a boss fight). */
		final boolean grind;

		Down(GameType mode, UUID watching, Vec3 spot, ResourceKey<Level> level, boolean grind) {
			this.mode = mode;
			this.watching = watching;
			this.spot = spot;
			this.level = level;
			this.grind = grind;
		}
	}

	private static final Map<UUID, Down> downed = new HashMap<>();
	/** Marks a player who is down, with the game mode to go back to (a tag, so it's saved with them). */
	private static final String TAG = "ultracraft.down.";
	/** How long a teammate stands on the marker to bring someone back, and how near counts as on it. */
	private static final int REVIVE_TICKS = 80;
	private static final double REVIVE_REACH = 2.0;
	private static MinecraftServer server;

	private BossParty() {}

	static boolean isDown(ServerPlayer sp) {
		return downed.containsKey(sp.getUUID());
	}

	/** A V1 still standing in the fight, other than this one, nearest first. */
	private static ServerPlayer mate(ServerPlayer sp) {
		ServerPlayer best = null;
		double bestD = 160.0 * 160.0;
		for (ServerPlayer o : sp.level().getServer().getPlayerList().getPlayers()) {
			if (o == sp || !up(o) || o.level() != sp.level()) continue;
			double d = o.distanceToSqr(sp);
			if (d < bestD) {
				bestD = d;
				best = o;
			}
		}
		return best;
	}

	/** A V1 on their feet: alive, not down, not a spectator. */
	private static boolean up(ServerPlayer o) {
		return o.isAlive() && !o.isSpectator() && !downed.containsKey(o.getUUID()) && UcNet.isV1(o);
	}

	/**
	 * DEAD from this V1's ULTRAKILL in a boss fight or a Cyber Grind run with others: down instead of dead, if a teammate
	 * fights on.
	 */
	static boolean tryDown(ServerPlayer sp) {
		boolean grind = CyberGrind.inRun(sp);
		if ((!UkBosses.fighting() && !grind) || downed.containsKey(sp.getUUID())) return false;
		ServerPlayer mate = mate(sp);
		if (mate == null) return false;
		server = sp.level().getServer();
		downed.put(sp.getUUID(), new Down(sp.gameMode.getGameModeForPlayer(), mate.getUUID(), fallSpot(sp, mate), sp.level().dimension(), grind));
		// (saved with the player: whoever leaves or loses the world while down comes back in their own game mode)
		sp.addTag(TAG + sp.gameMode.getGameModeForPlayer().getName());
		sp.setHealth(sp.getMaxHealth());
		sp.setGameMode(GameType.SPECTATOR);
		UcNet.send(sp, "C:DOWNED 1");
		watch(sp, mate);
		sp.displayClientMessage(Component.literal("You're down: watching " + mate.getName().getString() + ". A teammate standing where you fell brings you back.")
			.withStyle(ChatFormatting.RED), false);
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			if (o != sp) o.displayClientMessage(Component.literal(sp.getName().getString() + " is down! Stand where they fell to bring them back.").withStyle(ChatFormatting.RED), false);
		}
		sendDowns();
		return true;
	}

	/** Where a revive marker goes: where they fell, if a teammate can stand there; else by the teammate. */
	private static Vec3 fallSpot(ServerPlayer sp, ServerPlayer mate) {
		ServerLevel level = sp.level();
		BlockPos feet = sp.blockPosition();
		for (int dy = 0; dy <= 3; dy++) {
			BlockPos below = feet.below(dy + 1);
			if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && level.getFluidState(below.above()).isEmpty()
				&& level.noCollision(sp.getType().getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(below.above())))) {
				return Vec3.atBottomCenterOf(below.above());
			}
		}
		return mate.position();
	}

	/**
	 * Every 10 ticks: each downed player keeps watching someone still up (and is kept near them, so they see the
	 * fight), teammates standing on their marker bring them back; with nobody left standing, it's lost.
	 */
	static void tick(MinecraftServer s) {
		if (downed.isEmpty()) return;
		server = s;
		for (var e : new ArrayList<>(downed.entrySet())) {
			ServerPlayer d = s.getPlayerList().getPlayer(e.getKey());
			Down down = e.getValue();
			if (d == null) {
				downed.remove(e.getKey());
				sendDowns();
				continue;
			}
			ServerPlayer watching = s.getPlayerList().getPlayer(down.watching);
			if (watching == null || !up(watching)) {
				watching = mate(d);
				if (watching == null) {
					everyoneDown(s);
					return;
				}
				down.watching = watching.getUUID();
				watch(d, watching);
			}
			if (d.getCamera() != watching) d.setCamera(watching);
			if (d.level() != watching.level() || d.distanceToSqr(watching) > 16.0) {
				d.teleportTo(watching.level(), watching.getX(), watching.getY(), watching.getZ(), Set.of(), d.getYRot(), d.getXRot(), false);
			}
			reviving(s, d, down);
		}
		if (!downed.isEmpty()) sendDowns();
	}

	/** Teammates on a downed player's marker: the revive fills while they stay, and empties slowly when they step off. */
	private static void reviving(MinecraftServer s, ServerPlayer d, Down down) {
		ServerLevel level = s.getLevel(down.level);
		if (level == null) return;
		// the marker: a ring of soul fire with a beam of light, seen from afar
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, true, true, down.spot.x, down.spot.y + 0.1, down.spot.z, 6, 0.5, 0.02, 0.5, 0.01);
		DustParticleOptions beam = new DustParticleOptions(0x40FF60, 1.5f);
		for (int i = 0; i < 5; i++) level.sendParticles(beam, true, true, down.spot.x, down.spot.y + 0.5 + i * 0.9, down.spot.z, 1, 0.05, 0.3, 0.05, 0.0);
		List<ServerPlayer> on = new ArrayList<>();
		for (ServerPlayer o : s.getPlayerList().getPlayers()) {
			if (o == d || !up(o) || o.level() != level) continue;
			Vec3 p = o.position();
			double dx = p.x - down.spot.x, dz = p.z - down.spot.z;
			if (dx * dx + dz * dz <= REVIVE_REACH * REVIVE_REACH && Math.abs(p.y - down.spot.y) < 3.0) on.add(o);
		}
		if (on.isEmpty()) {
			down.progress = Math.max(0, down.progress - 5);
			return;
		}
		// two on it are quicker
		down.progress += 10 * Math.min(2, on.size());
		int pct = Math.min(100, down.progress * 100 / REVIVE_TICKS);
		String bar = "Reviving " + d.getName().getString() + "  " + "|".repeat(pct / 5) + ".".repeat(20 - pct / 5) + "  " + pct + "%";
		for (ServerPlayer o : on) o.displayClientMessage(Component.literal(bar).withStyle(ChatFormatting.GREEN), true);
		d.displayClientMessage(Component.literal(on.get(0).getName().getString() + " is bringing you back  " + pct + "%").withStyle(ChatFormatting.GREEN), true);
		if (down.progress >= REVIVE_TICKS) {
			downed.remove(d.getUUID());
			stand(d, down, level, down.spot, on.get(0).getYRot());
			for (ServerPlayer o : s.getPlayerList().getPlayers()) {
				o.displayClientMessage(Component.literal(on.get(0).getName().getString() + " brought " + d.getName().getString() + " back!").withStyle(ChatFormatting.GOLD), false);
			}
			sendDowns();
		}
	}

	/** Back on their feet, here: their own game mode and camera, whole, in a burst of light. */
	private static void stand(ServerPlayer d, Down down, ServerLevel level, Vec3 at, float yaw) {
		d.setCamera(d);
		d.setGameMode(down.mode);
		untag(d);
		d.teleportTo(level, at.x, at.y, at.z, Set.of(), yaw, 0f, false);
		d.setHealth(d.getMaxHealth());
		d.fallDistance = 0;
		UcNet.send(d, "C:DOWNED 0");
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, d.getX(), d.getY() + 1.0, d.getZ(), 120, 0.6, 1.0, 0.6, 0.6);
		level.sendParticles(ParticleTypes.END_ROD, d.getX(), d.getY() + 1.0, d.getZ(), 40, 0.4, 1.2, 0.4, 0.15);
		level.playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);
		d.displayClientMessage(Component.literal("You're back!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
	}

	/** Every player's markers for the downed (C:DOWNS name,x,y,z,percent;...), for the HUD (Teammates). */
	private static void sendDowns() {
		if (server == null) return;
		StringBuilder sb = new StringBuilder("C:DOWNS ");
		for (var e : downed.entrySet()) {
			ServerPlayer d = server.getPlayerList().getPlayer(e.getKey());
			if (d == null) continue;
			Down down = e.getValue();
			sb.append(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%d,%s;", d.getName().getString().replace(',', ' ').replace(';', ' '),
				down.spot.x, down.spot.y, down.spot.z, Math.min(100, down.progress * 100 / REVIVE_TICKS), down.level.identifier()));
		}
		for (ServerPlayer o : server.getPlayerList().getPlayers()) UcNet.send(o, sb.toString());
	}

	/** Whom a downed player's camera follows (their Minecraft goes round them, and shows who it is). */
	private static void watch(ServerPlayer d, ServerPlayer mate) {
		d.setCamera(mate);
		UcNet.send(d, "C:WATCH " + mate.getId() + " " + mate.getName().getString());
	}

	/** SPECTATE dir: a downed player switches to the next teammate still standing (dir 1) or the one before (-1). */
	static void cycle(ServerPlayer sp, int dir) {
		Down down = downed.get(sp.getUUID());
		if (down == null) return;
		List<ServerPlayer> standing = new ArrayList<>();
		for (ServerPlayer o : sp.level().getServer().getPlayerList().getPlayers()) if (o != sp && up(o)) standing.add(o);
		if (standing.isEmpty()) return;
		standing.sort(java.util.Comparator.comparing(o -> o.getName().getString()));
		int at = 0;
		for (int i = 0; i < standing.size(); i++) if (standing.get(i).getUUID().equals(down.watching)) at = i;
		ServerPlayer next = standing.get(Math.floorMod(at + (dir < 0 ? -1 : 1), standing.size()));
		down.watching = next.getUUID();
		if (sp.level() != next.level() || sp.distanceToSqr(next) > 16.0) {
			sp.teleportTo(next.level(), next.getX(), next.getY(), next.getZ(), Set.of(), sp.getYRot(), sp.getXRot(), false);
		}
		watch(sp, next);
	}

	/**
	 * Nobody's left standing. A boss fight: the boss leaves and the downed die with the last one. A Cyber Grind run:
	 * it's over, and everyone goes back where they came from, alive.
	 */
	private static void everyoneDown(MinecraftServer s) {
		Map<UUID, Down> was = new HashMap<>(downed);
		downed.clear();
		sendDowns();
		boolean grind = false;
		for (Down d : was.values()) grind |= d.grind;
		if (grind && CyberGrind.running) {
			standAll(s, was);
			CyberGrind.stop(null, "everyone went down");
			return;
		}
		UkBosses.forceEnd(s, "everyone is dead");
		for (UUID id : was.keySet()) {
			ServerPlayer d = s.getPlayerList().getPlayer(id);
			if (d == null) continue;
			d.setCamera(d);
			d.setGameMode(was.get(id).mode);
			untag(d);
			UcNet.send(d, "C:DOWNED 0 dead");
			d.kill(d.level());
		}
	}

	/** Everyone down gets up where they are (a Grind run that's over takes them back from there). */
	static void standAll(MinecraftServer s) {
		Map<UUID, Down> was = new HashMap<>(downed);
		downed.clear();
		server = s;
		standAll(s, was);
		sendDowns();
	}

	private static void standAll(MinecraftServer s, Map<UUID, Down> was) {
		for (var e : was.entrySet()) {
			ServerPlayer d = s.getPlayerList().getPlayer(e.getKey());
			if (d == null) continue;
			ServerLevel level = s.getLevel(e.getValue().level);
			stand(d, e.getValue(), level != null ? level : d.level(), d.position(), d.getYRot());
		}
	}

	/** This one gets up where they are (leaving the Grind run they went down in). */
	static void standUp(ServerPlayer sp) {
		Down d = downed.remove(sp.getUUID());
		if (d == null) return;
		stand(sp, d, sp.level(), sp.position(), sp.getYRot());
		sendDowns();
	}

	/** The fight's over and somebody won it (the boss beaten or gone, a Grind wave cleared): everyone down gets up. */
	static void reviveAll() {
		if (downed.isEmpty() || server == null) return;
		Map<UUID, Down> was = new HashMap<>(downed);
		downed.clear();
		for (var e : was.entrySet()) {
			ServerPlayer d = server.getPlayerList().getPlayer(e.getKey());
			if (d == null) continue;
			ServerPlayer mate = server.getPlayerList().getPlayer(e.getValue().watching);
			if (mate == null) mate = mate(d);
			Vec3 at = mate != null ? mate.position().add(1.0, 0.0, 0.0) : d.position();
			stand(d, e.getValue(), mate != null ? mate.level() : d.level(), at, mate != null ? mate.getYRot() : d.getYRot());
		}
		sendDowns();
	}

	private static void untag(ServerPlayer sp) {
		for (String t : new ArrayList<>(sp.getTags())) if (t.startsWith(TAG)) sp.removeTag(t);
	}

	/**
	 * A player joining who was down when they left (or when the world closed): no longer watching anyone, in their own
	 * game mode again.
	 */
	static void joined(ServerPlayer sp) {
		for (String t : new ArrayList<>(sp.getTags())) {
			if (!t.startsWith(TAG) || downed.containsKey(sp.getUUID())) continue;
			sp.removeTag(t);
			sp.setCamera(sp);
			sp.setGameMode(GameType.byName(t.substring(TAG.length()), GameType.SURVIVAL));
		}
	}

	/** The world closed. */
	static void reset() {
		downed.clear();
		server = null;
	}
}
