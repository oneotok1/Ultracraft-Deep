package dev.ultracraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * PvP: one V1 challenges another (/uc duel &lt;player&gt;), the other accepts (a click in chat, or /uc duel accept).
 * Both are healed, a countdown runs, and then their shots, punches and blasts hurt each other (half as hard as they hurt
 * a mob, through Minecraft's armour and on to ULTRAKILL's health, like any other hit). The first V1 to die loses: nobody
 * actually dies, both get back up at full health and the server hears who won. Leaving, going far apart, turning back
 * into Steve or /uc duel forfeit ends it. Everyone else stays friendly as ever. Server thread.
 */
final class Duels {
	/** How hard a V1 hits another in a duel, against how hard it hits a mob. */
	static final float DAMAGE = 0.5f;
	private static final int CHALLENGE_TICKS = 20 * 30, COUNTDOWN_TICKS = 20 * 3;
	private static final double MAX_APART = 96.0;

	private record Challenge(UUID from, long until) {}

	private static final class Duel {
		final UUID a, b;
		int countdown = COUNTDOWN_TICKS;

		Duel(UUID a, UUID b) {
			this.a = a;
			this.b = b;
		}

		UUID other(UUID id) {
			return id.equals(a) ? b : a;
		}
	}

	/** Challenges waiting for an answer, by whom they're for. */
	private static final Map<UUID, Challenge> pending = new HashMap<>();
	/** Duels going on, by each of the two. */
	private static final Map<UUID, Duel> duels = new HashMap<>();

	private Duels() {}

	static boolean inDuel(ServerPlayer sp) {
		return duels.containsKey(sp.getUUID());
	}

	/** Whether a's hits hurt b now: they're dueling each other and the countdown is over. */
	static boolean hits(ServerPlayer a, ServerPlayer b) {
		Duel d = duels.get(a.getUUID());
		return d != null && d.countdown <= 0 && d.other(a.getUUID()).equals(b.getUUID());
	}

	/** /uc duel &lt;player&gt;: a challenge, answered with a click. Returns what's wrong, or null. */
	static String challenge(ServerPlayer from, ServerPlayer to) {
		if (from == to) return "You can't duel yourself.";
		String why = problem(from, to);
		if (why != null) return why;
		pending.put(to.getUUID(), new Challenge(from.getUUID(), from.level().getGameTime() + CHALLENGE_TICKS));
		Component accept = Component.literal("[ACCEPT]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand("/uc duel accept"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Fight " + from.getName().getString()))));
		Component decline = Component.literal("[DECLINE]").withStyle(s -> s.withColor(ChatFormatting.GRAY)
			.withClickEvent(new ClickEvent.RunCommand("/uc duel decline")));
		to.sendSystemMessage(Component.literal("[Ultracraft] ").withStyle(ChatFormatting.RED)
			.append(Component.literal(from.getName().getString() + " challenges you to a duel!  ").withStyle(ChatFormatting.WHITE))
			.append(accept).append(Component.literal(" ")).append(decline));
		UcNet.send(to, "HUD <color=red>" + from.getName().getString() + "</color> challenges you to a duel: accept in chat.");
		return null;
	}

	/** /uc duel accept|decline. Returns what's wrong, or null. */
	static String answer(ServerPlayer to, boolean yes) {
		Challenge c = pending.remove(to.getUUID());
		MinecraftServer server = to.level().getServer();
		ServerPlayer from = c == null ? null : server.getPlayerList().getPlayer(c.from());
		if (c == null || from == null || to.level().getGameTime() > c.until()) return "Nobody has challenged you (or the challenge ran out).";
		if (!yes) {
			from.sendSystemMessage(Component.literal("[Ultracraft] " + to.getName().getString() + " declined the duel.").withStyle(ChatFormatting.GRAY));
			return null;
		}
		String why = problem(from, to);
		if (why != null) return why;
		Duel d = new Duel(from.getUUID(), to.getUUID());
		duels.put(d.a, d);
		duels.put(d.b, d);
		for (ServerPlayer p : List.of(from, to)) {
			ServerPlayer other = p == from ? to : from;
			p.setHealth(p.getMaxHealth());
			UcNet.send(p, "FULLHEAL");
			// our ULTRAKILL's shots now hurt that player's V1 body
			UcNet.send(p, "C:DUEL " + other.getId());
		}
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			o.sendSystemMessage(Component.literal("[Ultracraft] DUEL: " + from.getName().getString() + " vs " + to.getName().getString()).withStyle(ChatFormatting.GOLD));
		}
		return null;
	}

	private static String problem(ServerPlayer a, ServerPlayer b) {
		if (!UcNet.isV1(a) || !UcNet.isV1(b)) return "Both of you need to be V1.";
		if (inDuel(a) || inDuel(b)) return "One of you is already in a duel.";
		if (UkBosses.busy() || CyberGrind.running) return "Not now: a boss or the Cyber Grind is on.";
		if (a.level() != b.level() || a.distanceTo(b) > MAX_APART) return "You're too far apart.";
		return null;
	}

	/** Every tick: countdowns, and duels whose players went off. */
	static void tick(MinecraftServer server) {
		if (!pending.isEmpty()) {
			long now = server.overworld().getGameTime();
			pending.values().removeIf(c -> now > c.until());
		}
		if (duels.isEmpty()) return;
		for (Duel d : new ArrayList<>(new LinkedHashSet<>(duels.values()))) {
			ServerPlayer a = server.getPlayerList().getPlayer(d.a), b = server.getPlayerList().getPlayer(d.b);
			if (a == null || b == null) {
				end(server, d, a == null ? b : a, "the other player left");
				continue;
			}
			if (!UcNet.isV1(a) || !UcNet.isV1(b)) {
				ServerPlayer steve = !UcNet.isV1(a) ? a : b;
				end(server, d, steve == a ? b : a, steve.getName().getString() + " went back to Steve");
				continue;
			}
			if (a.level() != b.level() || a.distanceTo(b) > MAX_APART) {
				end(server, d, null, "too far apart");
				continue;
			}
			if (d.countdown <= 0) continue;
			if (d.countdown % 20 == 0) {
				int n = d.countdown / 20;
				for (ServerPlayer p : List.of(a, b)) {
					ServerPlayer other = p == a ? b : a;
					title(p, Component.literal(Integer.toString(n)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
						Component.literal("DUEL vs " + other.getName().getString()).withStyle(ChatFormatting.GRAY));
					p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.NOTE_BLOCK_BASEDRUM.value(), SoundSource.PLAYERS, 2f, 0.6f);
				}
			}
			if (--d.countdown == 0) {
				for (ServerPlayer p : List.of(a, b)) {
					title(p, Component.literal("FIGHT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.empty());
					p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 1.5f, 1.2f);
				}
			}
		}
	}

	/**
	 * DEAD from a dueling V1's ULTRAKILL: they lost. Nobody dies: both are back up at full health. Returns true if it
	 * ended a duel (Minecraft's player doesn't die then).
	 */
	static boolean lost(ServerPlayer loser) {
		Duel d = duels.get(loser.getUUID());
		if (d == null) return false;
		MinecraftServer server = loser.level().getServer();
		end(server, d, d.countdown > 0 ? null : server.getPlayerList().getPlayer(d.other(loser.getUUID())), d.countdown > 0 ? "died before it began" : null);
		return true;
	}

	/** /uc duel forfeit. */
	static boolean forfeit(ServerPlayer sp) {
		Duel d = duels.get(sp.getUUID());
		if (d == null) return false;
		MinecraftServer server = sp.level().getServer();
		end(server, d, server.getPlayerList().getPlayer(d.other(sp.getUUID())), sp.getName().getString() + " forfeited");
		return true;
	}

	/** The duel's over: its winner (or null: nobody), and why, if it wasn't fought to the end. */
	private static void end(MinecraftServer server, Duel d, ServerPlayer winner, String why) {
		duels.remove(d.a);
		duels.remove(d.b);
		for (UUID id : List.of(d.a, d.b)) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) continue;
			// both back up, whole (a V1 that died in ULTRAKILL gets up again: C:DUEL - respawns it)
			UcNet.send(p, "C:DUEL -");
			p.setHealth(p.getMaxHealth());
			p.fallDistance = 0;
			boolean won = winner != null && winner.getUUID().equals(id);
			String sub = why != null ? why : won ? "you won the duel" : winner != null ? winner.getName().getString() + " won the duel" : "";
			title(p, Component.literal(won ? "VICTORY" : winner == null ? "NO CONTEST" : "DEFEAT").withStyle(won ? ChatFormatting.GOLD : ChatFormatting.RED, ChatFormatting.BOLD),
				Component.literal(sub).withStyle(ChatFormatting.GRAY));
			if (won) {
				p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
				p.level().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.0, p.getZ(), 60, 0.5, 1.0, 0.5, 0.5);
			}
		}
		ServerPlayer a = server.getPlayerList().getPlayer(d.a), b = server.getPlayerList().getPlayer(d.b);
		String names = (a != null ? a.getName().getString() : "?") + " vs " + (b != null ? b.getName().getString() : "?");
		String result = winner != null ? winner.getName().getString() + " won" : "no winner";
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			o.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "[Ultracraft] DUEL %s: %s%s", names, result, why != null ? " (" + why + ")" : ""))
				.withStyle(ChatFormatting.GOLD));
		}
	}

	/** The world closed. */
	static void reset() {
		pending.clear();
		duels.clear();
	}

	private static void title(ServerPlayer sp, Component title, Component sub) {
		sp.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 10));
		sp.connection.send(new ClientboundSetSubtitleTextPacket(sub));
		sp.connection.send(new ClientboundSetTitleTextPacket(title));
	}
}
