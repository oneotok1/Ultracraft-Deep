package dev.ultracraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/**
 * Development aid: lines written to %TEMP%/ultracraft_debug.txt are run once (the file is then deleted), so a test can
 * drive Minecraft while it runs: hands 0|1, give &lt;item&gt;, inv, close, swing, shot &lt;name&gt;, nopause,
 * arm hx hy hz tiltZ tiltX roll, effect &lt;id&gt; &lt;seconds&gt; &lt;amplifier&gt;, clear, cmd &lt;server command&gt;,
 * uk &lt;line for ULTRAKILL&gt; (its debug answers go to the log as [uk]), mark / return (come back to a spot), state,
 * boss next|&lt;key&gt; [seconds], bosskill, playing, p &lt;amount&gt;.
 */
final class DebugCommands {
	private static final Path FILE = Path.of(System.getProperty("java.io.tmpdir"), "ultracraft_debug" + UltracraftConfig.instanceSuffix() + ".txt");
	private static int tick;
	private static double[] mark;

	private DebugCommands() {}

	/**
	 * Only in a Minecraft started for testing (-Dultracraft.debug=true): any other one, a player's own game included,
	 * never runs what's in the file.
	 */
	private static final boolean ENABLED = Boolean.getBoolean("ultracraft.debug");

	static void tick(Minecraft mc) {
		if (!ENABLED || ++tick % 5 != 0 || !Files.exists(FILE)) return;
		List<String> lines;
		try {
			lines = Files.readAllLines(FILE);
			Files.delete(FILE);
		} catch (Exception e) {
			return;
		}
		for (String line : lines) {
			try {
				run(mc, line.trim().split("\\s+"));
			} catch (Exception e) {
				org.slf4j.LoggerFactory.getLogger("ultracraft").warn("debug command '{}' failed", line, e);
			}
		}
	}

	private static void run(Minecraft mc, String[] a) {
		if (a.length == 0 || a[0].isEmpty() || mc.player == null) return;
		switch (a[0]) {
			case "hands" -> Ultracraft.setHands(mc, a[1].equals("1"));
			case "give" -> {
				var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(a[1]));
				Ultracraft.runOnServer(mc, (server, sp) -> sp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item)));
			}
			case "clear" -> Ultracraft.runOnServer(mc, (server, sp) -> sp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
			case "inv" -> mc.setScreen(new InventoryScreen(mc.player));
			case "close" -> mc.setScreen(null);
			case "swing" -> mc.player.swing(InteractionHand.MAIN_HAND);
			case "nopause" -> mc.options.pauseOnLostFocus = false;
			case "view" -> mc.options.setCameraType(net.minecraft.client.CameraType.values()[Integer.parseInt(a[1])]);
			case "shot" -> Screenshot.grab(mc.gameDirectory, a[1] + ".png", mc.getMainRenderTarget(), 1, msg -> {});
			case "arm" -> {
				V1Arm.handX = Float.parseFloat(a[1]);
				V1Arm.handY = Float.parseFloat(a[2]);
				V1Arm.handZ = Float.parseFloat(a[3]);
				V1Arm.tiltZ = Float.parseFloat(a[4]);
				V1Arm.tiltX = Float.parseFloat(a[5]);
				V1Arm.roll = Float.parseFloat(a[6]);
			}
			case "cmd" -> {
				String command = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
				Ultracraft.runOnServer(mc, (server, sp) -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(sp).withPosition(sp.position()).withLevel(sp.level()), command));
			}
			case "v1" -> Ultracraft.setActive(mc, a[1].equals("1"));
			case "mark" -> {
				// remember where the player is, to come back after a test somewhere else
				var p = mc.player;
				mark = new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
				org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] marked {} {} {}", p.getX(), p.getY(), p.getZ());
			}
			case "return" -> {
				if (mark == null) return;
				String c = String.format(java.util.Locale.ROOT, "tp @s %.3f %.3f %.3f %.1f %.1f", mark[0], mark[1], mark[2], mark[3], mark[4]);
				Ultracraft.runOnServer(mc, (server, sp) -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(sp).withPosition(sp.position()).withLevel(sp.level()), c));
			}
			case "quit" -> mc.stop();
			case "mesh" -> org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] mesh plantBlocks={} plantQuads={}", WorldMesh.plantBlocks, WorldMesh.plantQuads);
			case "op" -> UcNet.toServer(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
			// chatcmd <command>: typed as this player (on someone else's server too, with only this player's rights)
			case "chatcmd" -> mc.player.connection.sendCommand(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
			case "mobs" -> UltracraftConfig.mcMobs = a[1].equals("1");
			case "settings" -> mc.setScreen(new UcSettingsScreen(null));
			case "page" -> mc.setScreen(UcSettingsScreen.page(a[1]));
			case "perf" -> {
				// perf low|default: the Performance page's presets
				if (a[1].equals("low")) UltracraftConfig.lowEndPreset();
				else UltracraftConfig.performanceDefaults();
				UltracraftConfig.sendOpts();
			}
			// terrain: the sections ULTRAKILL has, and those kept around its enemies
			case "terrain" -> org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] terrain {}", WorldMesh.info(mc.player.blockPosition()));
			// fps [tag]: Minecraft's frame rate (last second), and ULTRAKILL's (its log)
			case "fps" -> {
				String tag = a.length > 1 ? a[1] : "";
				org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] fps {} mc={} {}", tag, mc.getFps(), UkFrame.rateInfo());
				UkLink.send("FPSINFO " + tag);
			}
			// fpscap <n>: Minecraft's frame limit (260 = unlimited), ULTRAKILL's follows
			case "fpscap" -> {
				mc.options.framerateLimit().set(Integer.parseInt(a[1]));
				UltracraftConfig.sendOpts();
			}
			// proj x y z: where Minecraft's camera puts that point on screen (-1..1 each way; ULTRAKILL's: "uk PROJ x y z")
			case "proj" -> org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] proj {} {} {} -> {}", a[1], a[2], a[3],
				mc.gameRenderer.projectPointToScreen(new net.minecraft.world.phys.Vec3(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]))));
			case "picker" -> mc.setScreen(new UcMusicScreen(new UcSettingsScreen(null)));
			case "cheat" -> UcCheats.debugSet(a[1], a[2].equals("1"));
			case "respawn" -> mc.player.respawn();
			case "state" -> org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] stains={} grind={} wave={} shopTouch={} shopNear={} active={} hands={} pos={} alive={} boss={} money={} hp={}",
				BloodStains.count(), CyberGrind.running, CyberGrind.wave, Ultracraft.shopTouch, Ultracraft.shopNear, Ultracraft.active, Ultracraft.hands, mc.player.position(),
				mc.player.isAlive(), UkBosses.state(), UkProgress.shownMoney, UkLink.hp);
			case "boss" -> {
				// boss next|<key> [seconds of warning]
				String key = a.length > 1 ? a[1] : "next";
				int secs = a.length > 2 ? Integer.parseInt(a[2]) : UltracraftConfig.bossWarnSeconds;
				String mods = a.length > 3 ? a[3] : null;
				Ultracraft.lastInputAt = System.currentTimeMillis();
				Ultracraft.runOnServer(mc, (server, sp) -> UkBosses.force(sp, key, secs, mods));
			}
			// ukbind <Map/Action[/part]> <glfw key | -1-mouse button | ->: rebind one of ULTRAKILL's controls
			case "ukbind" -> {
				if (a[2].equals("-")) UltracraftConfig.ukBinds.remove(a[1]);
				else UltracraftConfig.ukBinds.put(a[1], Integer.parseInt(a[2]));
				UkLink.send("UKBIND " + a[1] + " " + a[2]);
			}
			// lanoffline: the hosted world stops checking players' Mojang sessions (testing with offline accounts)
			case "grindinfo" -> Ultracraft.runOnServer(mc, (server, sp) -> org.slf4j.LoggerFactory.getLogger("ultracraft").info("[debug] grind {}", CyberGrind.debugInfo()));
			case "lanoffline" -> Ultracraft.runOnServer(mc, (server, sp) -> server.setUsesAuthentication(false));
			// opshop 0|1: the OP Shop setting (every player's Upgrades page hears it)
			case "opshop" -> {
				UltracraftConfig.opShop = a.length > 1 && a[1].equals("1");
				Ultracraft.runOnServer(mc, (server, sp) -> {
					for (var o : server.getPlayerList().getPlayers()) UkProgress.get(o).sendUpgrades();
				});
			}
			// keep the boss countdown going while a script drives the game
			case "playing" -> Ultracraft.lastInputAt = System.currentTimeMillis();
			case "bosskill" -> Ultracraft.runOnServer(mc, (server, sp) -> {
				if (UkBosses.fightId() != 0) UkLink.send("BOSSHURT " + UkBosses.fightId() + " 100000");
			});
			case "p" -> {
				int amount = Integer.parseInt(a[1]);
				Ultracraft.runOnServer(mc, (server, sp) -> UkProgress.get(server).award(amount));
			}
			case "uk" -> UkLink.send(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
			case "effect" -> {
				var effect = BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(a[1])).orElseThrow();
				int seconds = Integer.parseInt(a[2]), amp = a.length > 3 ? Integer.parseInt(a[3]) : 0;
				Ultracraft.runOnServer(mc, (server, sp) -> sp.addEffect(new MobEffectInstance(effect, seconds * 20, amp)));
			}
			default -> {
			}
		}
	}
}
