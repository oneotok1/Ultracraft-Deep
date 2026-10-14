package dev.ultracraft;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Ultracraft in multiplayer (a LAN world or a server running the mod): every player runs their own ULTRAKILL, and
 * what one ULTRAKILL does to the shared world goes to the server as a line of text (ServerOps), and what the world
 * does to one V1 comes back to that player's ULTRAKILL the same way. In singleplayer, and for the player hosting a
 * LAN world, both directions stay in this process (no packets), exactly as before multiplayer.
 */
public final class UcNet {
	/** A line from a player's ULTRAKILL (or Minecraft side) for the server. */
	public record ToServer(String msg) implements CustomPacketPayload {
		static final Type<ToServer> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ultracraft", "to_server"));
		static final StreamCodec<FriendlyByteBuf, ToServer> CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(ToServer::new, ToServer::msg).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A line for one player: for their ULTRAKILL, or ("C:...") for their Minecraft side. */
	public record ToClient(String msg) implements CustomPacketPayload {
		static final Type<ToClient> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ultracraft", "to_client"));
		static final StreamCodec<FriendlyByteBuf, ToClient> CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(ToClient::new, ToClient::msg).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Players who are V1 right now, as the server knows them. */
	private static final Set<UUID> SERVER_V1 = ConcurrentHashMap.newKeySet();
	/** The same, as this client last heard it (other players drawn as V1). */
	private static final Set<UUID> CLIENT_V1 = ConcurrentHashMap.newKeySet();

	private UcNet() {}

	/** Both sides' registration (from the common initializer). */
	static void registerCommon() {
		PayloadTypeRegistry.playC2S().registerLarge(ToServer.TYPE, ToServer.CODEC, 1 << 20);
		PayloadTypeRegistry.playS2C().registerLarge(ToClient.TYPE, ToClient.CODEC, 1 << 20);
		ServerPlayNetworking.registerGlobalReceiver(ToServer.TYPE, (payload, ctx) -> ServerOps.handle(ctx.player(), payload.msg()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> ServerOps.left(handler.player)));
		// back in after leaving mid-fight or mid-run (or the world closing): not stuck as a spectator or in the void
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
			BossParty.joined(handler.player);
			CyberGrind.joined(handler.player);
		}));
	}

	/** The client half (from the client initializer). */
	static void registerClient() {
		ClientPlayNetworking.registerGlobalReceiver(ToClient.TYPE, (payload, ctx) -> fromServer(payload.msg()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			CLIENT_V1.clear();
			Teammates.downs("");
		});
	}

	// ------------------------------------------------------------------ who is V1

	/** Whether this entity is a player who is V1 (on whichever side the entity lives). */
	public static boolean isV1(Entity e) {
		if (!(e instanceof Player p)) return false;
		return (p.level().isClientSide() ? CLIENT_V1 : SERVER_V1).contains(p.getUUID());
	}

	static boolean anyV1() {
		return !SERVER_V1.isEmpty();
	}

	static void setServerV1(MinecraftServer server, UUID id, boolean on) {
		boolean changed = on ? SERVER_V1.add(id) : SERVER_V1.remove(id);
		if (!changed) return;
		// everyone hears who is V1 now (they draw those players as V1, not as Steve)
		StringBuilder sb = new StringBuilder("C:V1S ");
		for (UUID u : SERVER_V1) sb.append(u).append(',');
		for (ServerPlayer sp : server.getPlayerList().getPlayers()) send(sp, sb.toString());
	}

	static void clearServer() {
		SERVER_V1.clear();
	}

	// ------------------------------------------------------------------ client -> server

	/** From this client's ULTRAKILL (or its Minecraft side) to the server, as this player. */
	static void toServer(String msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null) {
			// singleplayer or hosting: straight onto the server thread, as before multiplayer
			UUID id = mc.player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(id);
				if (sp != null) ServerOps.handle(sp, msg);
			});
		} else if (ClientPlayNetworking.canSend(ToServer.TYPE)) {
			ClientPlayNetworking.send(new ToServer(msg));
		}
	}

	// ------------------------------------------------------------------ server -> client

	/** Whether a server-side player is the one playing in this very process (singleplayer, or the LAN host). */
	static boolean isLocal(ServerPlayer sp) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.getSingleplayerServer() != null && mc.player.getUUID().equals(sp.getUUID());
	}

	/** A line for one player's ULTRAKILL ("C:..." for their Minecraft side instead). */
	public static void send(ServerPlayer sp, String msg) {
		if (sp == null) return;
		if (isLocal(sp)) {
			// the same process: no packet (and no tick's delay) for the host
			if (msg.startsWith("C:")) Minecraft.getInstance().execute(() -> fromServer(msg));
			else {
				observe(msg);
				UkLink.send(msg);
			}
			return;
		}
		if (ServerPlayNetworking.canSend(sp, ToClient.TYPE)) ServerPlayNetworking.send(sp, new ToClient(msg));
	}

	/** To every V1 within range of a spot (blood, deaths: each ULTRAKILL near it shows them). */
	static void sendNear(Entity at, double range, String msg) {
		if (at.level().getServer() == null) return;
		for (ServerPlayer sp : at.level().getServer().getPlayerList().getPlayers()) {
			if (sp.level() == at.level() && SERVER_V1.contains(sp.getUUID()) && sp.distanceToSqr(at) < range * range) send(sp, msg);
		}
	}

	/** To every V1 on the server. */
	static void sendAll(MinecraftServer server, String msg) {
		for (ServerPlayer sp : server.getPlayerList().getPlayers()) if (SERVER_V1.contains(sp.getUUID())) send(sp, msg);
	}

	/** On this client's thread: a line for our ULTRAKILL, or for our Minecraft side. */
	private static void fromServer(String msg) {
		if (msg.startsWith("C:")) {
			Ultracraft.fromServer(msg.substring(2));
			return;
		}
		observe(msg);
		UkLink.send(msg);
	}

	/** Our P as the server last said it (MONEY m, GEAR m ...), for the HUD and the inventory. */
	private static void observe(String msg) {
		try {
			if (msg.startsWith("MONEY ")) UkProgress.shownMoney = Integer.parseInt(msg.substring(6).trim());
			else if (msg.startsWith("GEAR ")) UkProgress.shownMoney = Integer.parseInt(msg.split(" ")[1]);
		} catch (RuntimeException ignored) {
		}
	}

	/** Our ULTRAKILL sends what its V1 and its game do only while other players are V1 (PEERS n). */
	private static void sendPeers(String list) {
		var me = Minecraft.getInstance().player;
		int others = 0;
		for (String u : list.split(",")) if (!u.isBlank() && (me == null || !me.getUUID().toString().equals(u.trim()))) others++;
		UkLink.send("PEERS " + others);
	}

	/** C:V1S uuid,uuid,...: who is V1 now. */
	static void clientV1s(String list) {
		CLIENT_V1.clear();
		try {
			sendPeers(list);
		} catch (RuntimeException ignored) {
		}
		for (String s : list.split(",")) {
			if (s.isEmpty()) continue;
			try {
				CLIENT_V1.add(UUID.fromString(s.trim()));
			} catch (IllegalArgumentException ignored) {
			}
		}
	}
}
