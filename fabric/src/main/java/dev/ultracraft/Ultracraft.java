package dev.ultracraft;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import dev.ultracraft.mixin.AbstractArrowAccessor;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseFireBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.entity.monster.Enemy;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.glfw.GLFW;

/**
 * Ultracraft: real ULTRAKILL inside real Minecraft.
 *
 * ULTRAKILL (with the UltraBridge BepInEx plugin) runs alongside. While "V1 mode" is on, V1 is
 * ULTRAKILL's own player: we forward keyboard and mouse to it, put Minecraft's camera where V1's
 * camera is, draw ULTRAKILL's frame (guns, arm, HUD, effects) over the world, give ULTRAKILL the
 * blocks and mobs around us as colliders and enemies, and turn its hits into Minecraft damage.
 */
public final class Ultracraft implements ClientModInitializer {
	public static volatile boolean active;
	/** An ULTRAKILL menu (spawn menu, alter menu) is open: Minecraft's cursor is free and points at it. */
	public static volatile boolean uiMode;
	/**
	 * Minecraft hands (the hands key, V): V1 puts the guns away and uses items like Steve (mine, place, eat, bows,
	 * hotbar, inventory, drop), while moving, punching and whiplashing stay ULTRAKILL's.
	 */
	public static volatile boolean hands;
	/** V1 stands at a shop's screen: clicks go to ULTRAKILL's shop, whichever hands are out, and Minecraft's hand hides. */
	public static volatile boolean shopTouch;
	/** V1 is close enough to a shop that its screen is on: ULTRAKILL draws at full size, so the screen's text is sharp. */
	public static volatile boolean shopNear;
	/** Wheel notches waiting to go to ULTRAKILL. */
	public static double wheel;
	/** Xaero's minimap (or world map) is in: it draws after the whole HUD, so V1's overlay waits for it (InGameHudMixin). */
	public static final boolean xaeroPresent = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaerominimap")
		|| net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap");

	/** Implemented on every Projectile by ProjectileMixin: apply a hit ULTRAKILL decided on. */
	public interface HitApplier {
		void ultracraft$applyHit(HitResult hit);
	}

	public static boolean isV1(Entity e) {
		return UcNet.isV1(e);
	}
	private static boolean originSent;
	/** Where (and in which dimension) ULTRAKILL's world origin is. */
	private static net.minecraft.world.phys.Vec3 originAt;
	private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> originDim;
	/** A teleport further than this from the origin moves the origin along: ULTRAKILL's floats lose precision far
	 * from its own origin (its HUD text and guns jitter and break up thousands of blocks out). */
	private static final double REBASE = 1024.0;
	private static BlockPos lastExport;
	private static int tick;
	private static int lastW, lastH;
	public static double mouseDx, mouseDy;
	private static double lastCursorX, lastCursorY;
	private static boolean haveCursor;
	/** Whether the last line handed to ULTRAKILL carried a live input state (see sendInput). */
	private static boolean inputLive;
	/** Ultracraft's own keys (V1/Steve, hands): never given up to a rebind. */
	public static boolean isOwnKey(KeyMapping k) {
		return k == toggle || k == handsKey || k == sneakKey;
	}

	/** How the key that becomes V1 (or Steve) is bound now, e.g. "F8": prompts name the key the player set. */
	public static String toggleKeyName() {
		return keyName(toggle, "F8");
	}

	/** How the key that swaps V1's guns for Minecraft hands is bound now, e.g. "V". */
	public static String handsKeyName() {
		return keyName(handsKey, "V");
	}

	/** A key's bound name, or the default when it is unbound or there is no mapping yet (server side). */
	private static String keyName(KeyMapping k, String fallback) {
		if (k == null) return fallback;
		String name = k.getTranslatedKeyMessage().getString();
		return name == null || name.isBlank() ? fallback : name;
	}

	/**
	 * Steve is playing after having been V1, with ULTRAKILL still running: its enemies stay, drawn from Steve's camera
	 * (bridge SteveView.cs).
	 */
	public static boolean steveView;
	/** Steve's field of view as Minecraft has it (sent to ULTRAKILL with the camera). */
	public static float steveFov = 70f;
	private static boolean everActive;
	private static int fpsCheck, fpsSent;

	private static KeyMapping toggle;
	private static KeyMapping handsKey;
	/** ULTRAKILL's sneak: held, not toggled. A Minecraft key of its own, so it is rebound in Minecraft's controls. */
	private static KeyMapping sneakKey;
	private static boolean frozen;
	private static long frozenAt;
	/** ULTRAKILL is between scenes and is busy preparing the next level (its first navmesh bake is the better part of
	 * a second): Minecraft's world waits with it, or the player watches a world nothing is drawing. Its own clock, not
	 * the hitstop's: a load is allowed to be a great deal longer than a parry's freeze. */
	private static boolean loadFrozen;
	private static long loadFrozenAt;
	/** V1 is being handed over to ULTRAKILL after a teleport (it has to take the view, land V1, build the ground it
	 * stands on): Minecraft waits for it, and for its "LANDED" once it is done. Its own clock, like the load. */
	private static boolean handoffFrozen;
	private static long handoffFrozenAt;
	/** ULTRAKILL said V1 is down; the freeze now waits for Minecraft's own client to run again, because the stall
	 * the player sees is the client's (render buffers, chunk rebuilds), not ULTRAKILL's. */
	private static boolean handoffLanded;
	private static long handoffFrameAt;
	/** nanoTime when the handover freeze went on: the frame gate compares against it, so it must share its clock. */
	private static long handoffFrozenNs;
	/** The freeze exists for one thing: the stall of V1's first takeover in a world (ULTRAKILL loading its scene
	 * while Minecraft runs on behind it). Every later teleport - respawning, walking into another dimension, coming
	 * back out of the Cyber Grind - is quick and reads as a stutter when the world is held for it, so only the
	 * first handover of each world waits. Cleared when the world is left, and when ULTRAKILL restarts (it
	 * comes back with no level loaded, so the handover after that is a first one again). */
	private static boolean handoffUsed;
	private static int handoffFrames;
	/** What Minecraft's world should be doing right now, whatever the reasons are. */
	private static boolean worldFrozen;
	/** What was last actually handed to the integrated server. worldFrozen alone does not mean it heard it: the
	 * reasons can turn on while getSingleplayerServer() is still null (the server is created a little after the
	 * world is entered), and treating the wish as done left the whole freeze never applied. */
	private static boolean freezePushed;
	/** The "no server yet" warning is worth one line per wait, not one per tick of the retry below. */
	private static boolean pauseSent;
	/** Minecraft's game mode as ULTRAKILL last heard it (GOD 1/0): creative and spectator take no damage at all. */
	private static boolean godSent;
	/** "Enemies Ignore Player" switched on by the game mode rather than by hand, and whether the player has since
	 * switched it off themselves (in which case creative does not switch it back on until they leave it again). */
	private static LocalPlayer lastPlayer;
	private static boolean wasDead;
	private static boolean autoPending = true;
	private static String lastBlocks;
	private static boolean playingSent;
	private static long lastBlocksAt;
	/** How old a pose may be before it counts as "ULTRAKILL is not sending anything right now" (a scene change). */
	private static final long STALE_POSE_NS = 500_000_000L;
	/** A frame slower than this is a stall, not a frame: the handover freeze only lifts after a run of frames
	 * faster than it (60 ms ≈ 16 fps, well under anything a healthy client shows). */
	private static final long HANDOFF_FRAME_NS = 60_000_000L;
	/** How many such frames in a row the client must draw before the freeze lifts. */
	private static final int HANDOFF_FRAMES = 6;

	/** GLFW keys ULTRAKILL can use. */
	private static final int[] KEYS;

	static {
		List<Integer> k = new ArrayList<>();
		for (int c = GLFW.GLFW_KEY_A; c <= GLFW.GLFW_KEY_Z; c++) k.add(c);
		for (int c = GLFW.GLFW_KEY_0; c <= GLFW.GLFW_KEY_9; c++) k.add(c);
		k.add(GLFW.GLFW_KEY_SPACE);
		k.add(GLFW.GLFW_KEY_LEFT_SHIFT);
		k.add(GLFW.GLFW_KEY_RIGHT_SHIFT);
		k.add(GLFW.GLFW_KEY_LEFT_CONTROL);
		k.add(GLFW.GLFW_KEY_RIGHT_CONTROL);
		k.add(GLFW.GLFW_KEY_LEFT_ALT);
		k.add(GLFW.GLFW_KEY_TAB);
		// keys only a rebind would use
		for (int c : new int[] {GLFW.GLFW_KEY_RIGHT_ALT, GLFW.GLFW_KEY_CAPS_LOCK, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN,
			GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SLASH, GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_MINUS,
			GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_BACKSLASH}) k.add(c);
		KEYS = k.stream().mapToInt(Integer::intValue).toArray();
	}

	/** Only while an ULTRAKILL menu is open; otherwise Esc stays Minecraft's (pause screen). */
	private static final int[] MENU_KEYS = {GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_BACKSPACE};

	@Override
	public void onInitializeClient() {
		UltracraftConfig.load();
		UkLink.start();
		UcNet.registerClient();
		// our own controls get their own section in Minecraft's controls list instead of being mixed into "Miscellaneous"
		KeyMapping.Category ourKeys = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ultracraft", "keys"));
		toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ultracraft.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, ourKeys));
		handsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ultracraft.hands", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, ourKeys));
		sneakKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ultracraft.sneak", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, ourKeys));
		ClientTickEvents.END_CLIENT_TICK.register(Ultracraft::clientTick);
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> WorldMesh.chunkLoaded(chunk.getPos().x, chunk.getPos().z));
		// ULTRAKILL's enemies are drawn by ULTRAKILL; their Minecraft stand-ins are only hitboxes
		EntityRendererRegistry.register(UltracraftCommon.UK_ENEMY, NoopRenderer::new);
		BlockEntityRenderers.register(UltracraftCommon.UK_SHOP_ENTITY, UkShopRenderer::new);
		BloodStains.register();
		UcSettingsScreen.register();
		UkLauncher.register();
		// the V1 layer replaces Minecraft's own HUD while active; with Minecraft hands out, the hotbar and what goes
		// with items (hunger, armour, air, XP, the item's name, effects, the crosshair with its attack cooldown) come
		// back. Health stays ULTRAKILL's.
		Identifier[] vanilla = {
			VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR, VanillaHudElements.CROSSHAIR,
			VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.STATUS_EFFECTS
		};
		List<Identifier> withHands = List.of(VanillaHudElements.HOTBAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR,
			VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.STATUS_EFFECTS,
			VanillaHudElements.CROSSHAIR);
		for (Identifier id : vanilla) {
			boolean handsShows = withHands.contains(id);
			// the air bubbles show whenever V1 runs out of breath, guns out or not (Minecraft drowns V1); so does hunger
			// once V1 is hungry (it stops Minecraft's healing, and starving hurts)
			boolean always = id.equals(VanillaHudElements.AIR_BAR);
			boolean whenHungry = id.equals(VanillaHudElements.FOOD_BAR);
			HudElementRegistry.replaceElement(id, old -> (ctx, t) -> {
				if (!active || (hands && handsShows)) {
					old.render(ctx, t);
				} else if (always || (whenHungry && hungry())) {
					// guns out, no hotbar under them: bubbles and hunger sit down at the bottom edge, where the hotbar
					// would be, instead of floating above nothing
					ctx.pose().pushMatrix();
					ctx.pose().translate(0f, 29f);
					old.render(ctx, t);
					ctx.pose().popMatrix();
				}
			});
		}
		// ULTRAKILL's own HUD (its hud messages, the ones the bridge relays) sits at the same height Minecraft puts the
		// action bar, so the two lines land on top of each other. Lift ours clear of it; ULTRAKILL draws at full size
		// and its layout is fixed, so the room has to come from our side.
		HudElementRegistry.replaceElement(VanillaHudElements.OVERLAY_MESSAGE, old -> (ctx, t) -> {
			if (!active) {
				old.render(ctx, t);
				return;
			}
			ctx.pose().pushMatrix();
			ctx.pose().translate(0f, ACTION_BAR_LIFT);
			old.render(ctx, t);
			ctx.pose().popMatrix();
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath("ultracraft", "v1"), (ctx, t) -> {
			if (xaeroPresent) return; // xaero's minimap draws after the whole HUD (an InGameHud tail hook): draw over it in InGameHudMixin instead
			renderOverlay(ctx, t.getGameTimeDeltaPartialTick(false));
		});
	}

	/** A weapon's own key: with Minecraft's items out, the guns come back for it. */
	static void gunKey(Minecraft mc) {
		if (hands) setHands(mc, false);
	}

	/** How far up Minecraft's action bar moves while V1 is out, so it clears ULTRAKILL's own message line (GUI pixels, negative is up). */
	private static final float ACTION_BAR_LIFT = -30f;

	private static boolean hungry() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p != null && p.getFoodData().getFoodLevel() < 18;
	}

	// ------------------------------------------------------------------ per frame

	private static int viewSent = -1;

	/** Minecraft's camera (F5): first person, behind V1, or in front of it; ULTRAKILL's camera does the same (VIEW). */
	private static void sendView(Minecraft mc) {
		int view = switch (mc.options.getCameraType()) {
			case FIRST_PERSON -> 0;
			case THIRD_PERSON_BACK -> 1;
			case THIRD_PERSON_FRONT -> 2;
		};
		if (view == viewSent || !UkLink.connected) return;
		viewSent = view;
		UkLink.send("VIEW " + view);
	}

	private static String langSent = "";

	/**
	 * Minecraft's language (LANG zh_cn): ULTRAKILL's terminal writes its shop text itself, so it has to be told.
	 * Only on change - it cannot change while playing, and a language switch reloads the resources anyway.
	 */
	private static void sendLang(Minecraft mc) {
		String lang = mc.getLanguageManager().getSelected();
		if (lang == null || lang.equals(langSent) || !UkLink.connected) return;
		langSent = lang;
		UkLink.send("LANG " + lang);
	}

	/** This frame's input already went to ULTRAKILL, the moment its last frame came in (UkFrame.waitForNext). */
	static boolean inputSentEarly;

	/**
	 * The input, as early as it can go: right when ULTRAKILL's frame arrives, ULTRAKILL is between frames, so input
	 * sent now is in its very next frame (sent later, while it's already drawing, it would wait a frame more).
	 */
	static void sendInputEarly() {
		Minecraft mc = Minecraft.getInstance();
		if (!UkLink.connected || mc.getWindow() == null) return;
		sendInput(mc);
		inputSentEarly = true;
	}

	/** V1's overlay, from wherever it has to draw (its HUD slot, or over the whole HUD when xaero's maps are in). */
	public static void renderOverlay(GuiGraphics ctx, float tickDelta) {
		renderV1(ctx);
		Teammates.render(Minecraft.getInstance(), ctx, tickDelta);
		Spectate.render(Minecraft.getInstance(), ctx);
	}

	static void renderV1(GuiGraphics ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (UkLink.connected && !inputSentEarly) sendInput(mc);
		inputSentEarly = false;
		if (!active && steveView) {
			// Steve with ULTRAKILL's enemies about: its layer, drawn from Steve's camera, over Minecraft's world
			sendMobsDrawn(mc);
			sendPuppets(mc);
			// the layer itself goes in during the world pass (GameRendererMixin), under Minecraft's hand
			renderHurtFlash(ctx);
			return;
		}
		if (!active) {
			if (UkLink.connected && mc.player != null) {
				String where = UkLink.loadingScene.isEmpty() ? "" : " (" + UkLink.loadingScene + ")";
				ctx.drawString(mc.font, UkLink.ready ? "ULTRAKILL ready - press " + toggleKeyName() + " to become V1" : "ULTRAKILL connected - loading V1..." + where, 4, 4, 0xFFFFFF00);
			}
			return;
		}
		sendMobsDrawn(mc);
		sendPuppets(mc);
		sendView(mc);
		sendLang(mc);
		UcKeybindsScreen.pollGunKeys(mc, mc.screen == null && !uiMode && mc.mouseHandler.isMouseGrabbed());
		UcKeybindsScreen.pollSneak(sneakKey, mc.screen == null && !uiMode && mc.mouseHandler.isMouseGrabbed());
		Movement.frame(mc);
		// asleep: Minecraft's own view from the bed (its fade to morning), no V1 layer
		if (Movement.sleeping) return;
		// the layer itself goes in during the world pass (GameRendererMixin), under Minecraft's hand
		renderHurtFlash(ctx);
		renderP(mc, ctx);
	}

	/**
	 * The red ULTRAKILL washes over its own screen when V1 is hit. That pass is the HUD camera's, which writes the mask
	 * side of the shared frame rather than the colour Minecraft draws, so the layer never carried it. UltraBridge sends
	 * the alpha on its own and it is painted here, on top of the layer, the way the game does it.
	 */
	static void renderHurtFlash(GuiGraphics ctx) {
		int a = UkLink.hurtFlash;
		if (a <= 0) return;
		ctx.fill(0, 0, ctx.guiWidth(), ctx.guiHeight(), (a << 24) | 0xFF0000);
	}

	private static final Map<Integer, Vec3> mobsDrawn = new HashMap<>();

	/**
	 * Where Minecraft draws each mob this frame (between ticks; ENTS only has tick positions): their stand-ins in
	 * ULTRAKILL follow, so a mob hides ULTRAKILL's enemies behind it exactly, even mid-knockback. Only mobs that moved.
	 */
	private static void sendMobsDrawn(Minecraft mc) {
		LocalPlayer self = mc.player;
		if (mc.level == null || self == null) return;
		var rates = mc.level.tickRateManager();
		var dt = mc.getDeltaTracker();
		StringBuilder sb = null;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == self || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof UkEnemyEntity) continue;
			// a downed teammate watching the fight isn't there to be seen or hit
			if (e instanceof net.minecraft.world.entity.player.Player pl && pl.isSpectator()) continue;
			if (e.distanceToSqr(self) > 80 * 80) continue;
			Vec3 at = e.getPosition(dt.getGameTimeDeltaPartialTick(!rates.isEntityFrozen(e)));
			Vec3 was = mobsDrawn.put(e.getId(), at);
			if (was != null && was.distanceToSqr(at) < 1e-6) continue;
			if (sb == null) sb = new StringBuilder("EPOS ");
			sb.append(String.format(Locale.ROOT, "%d,%.3f,%.3f,%.3f;", e.getId(), at.x, at.y, at.z));
		}
		if (sb != null) UkLink.send(sb.toString());
		if (mobsDrawn.size() > 1024) mobsDrawn.clear();
	}

	private static String lastPuppets = "";

	/**
	 * The other players' ULTRAKILLs' enemies near us, where Minecraft draws their stand-ins this frame: our ULTRAKILL
	 * shows each as a puppet (the real enemy, its own brain off) that our shots hurt for real, in its owner's game.
	 * PUPS id,key,x,y,z,yaw,anim,dead,w,h;...
	 */
	private static void sendPuppets(Minecraft mc) {
		LocalPlayer self = mc.player;
		if (mc.level == null || self == null) return;
		String me = self.getUUID().toString();
		var dt = mc.getDeltaTracker();
		StringBuilder sb = new StringBuilder("PUPS ");
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof UkEnemyEntity u) || u.ownerId().isEmpty() || u.ownerId().equals(me) || u.key().isEmpty()) continue;
			if (e.distanceToSqr(self) > 96 * 96) continue;
			Vec3 at = e.getPosition(dt.getGameTimeDeltaPartialTick(true));
			sb.append(String.format(Locale.ROOT, "%d,%s,%.3f,%.3f,%.3f,%.1f,%d,%d,%.2f,%.2f;", e.getId(), u.key(), at.x, at.y, at.z, u.ukYaw(), u.anim(), u.isDying() ? 1 : 0,
				e.getBbWidth(), e.getBbHeight()));
		}
		String msg = sb.toString();
		if (msg.equals(lastPuppets)) return;
		lastPuppets = msg;
		UkLink.send(msg);
	}

	private static void sendInput(Minecraft mc) {
		var win = mc.getWindow();
		// ULTRAKILL renders V1's layer at v1Height (same aspect) and we scale it up: each frame crosses between
		// the two games, so fewer pixels is directly more frames
		int fw = win.getWidth(), fh = win.getHeight();
		// at a shop's screen (using it, not just near it: full size costs frames) the small text is drawn sharp
		// (1080 lines is plenty for its text: a 1440p or 4K window at full size cost a lot of frames right at the shop)
		int cap = UltracraftConfig.v1Height;
		if (shopTouch && UltracraftConfig.sharpShop && cap > 0) cap = Math.max(cap, 1080);
		double scale = cap > 0 && fh > cap ? (double) cap / fh : 1.0;
		int w = Math.max(4, (int) Math.round(fw * scale) & ~3), h = Math.max(1, (int) Math.round(fh * scale));
		if (w != lastW || h != lastH) {
			lastW = w;
			lastH = h;
			UkLink.send("SIZE " + w + " " + h);
		}
		if (!active) {
			// ULTRAKILL holds whatever the last IN said: stop sending without saying so and V1 keeps firing, walking
			// or holding whatever was down when Minecraft handed the game back. One all-zero line closes it out
			if (inputLive) {
				inputLive = false;
				UkLink.send("IN 0 0 0 0");
			}
			return;
		}
		inputLive = true;
		long handle = win.handle();
		StringBuilder sb = new StringBuilder("IN ");
		// read the cursor straight from GLFW (with the cursor grabbed it is an unbounded virtual position)
		double[] cx = new double[1], cy = new double[1];
		GLFW.glfwGetCursorPos(handle, cx, cy);
		boolean ui = uiMode;
		boolean ingame = mc.screen == null && (mc.mouseHandler.isMouseGrabbed() || ui);
		boolean look = ingame && !ui;
		double dx = 0, dy = 0;
		if (look && haveCursor) {
			dx = cx[0] - lastCursorX;
			dy = cy[0] - lastCursorY;
		}
		lastCursorX = cx[0];
		lastCursorY = cy[0];
		haveCursor = look;
		mouseDx = 0;
		mouseDy = 0;
		if (ingame && ui) {
			// a menu is open: point at it (window coordinates scaled to the size ULTRAKILL renders at)
			double sx = win.getScreenWidth() > 0 ? (double) w / win.getScreenWidth() : 1.0;
			double sy = win.getScreenHeight() > 0 ? (double) h / win.getScreenHeight() : 1.0;
			UkLink.send(String.format(Locale.ROOT, "PTR %.1f %.1f", cx[0] * sx, cy[0] * sy));
		}
		// wheel notches as Windows reports them to ULTRAKILL (120 each)
		double notches = ingame ? wheel : 0;
		wheel = 0;
		sb.append(String.format(Locale.ROOT, "%.3f %.3f %.1f ", dx, dy, notches * 120.0));
		// Minecraft hands: clicks, number keys, E and Q are Minecraft's (use, mine, hotbar, inventory, drop); at a shop's
		// screen the clicks are the shop's
		boolean mcHands = hands && !ui;
		int buttons = 0;
		if (ingame) {
			// GLFW numbers its buttons left, right, middle, X1 (back), X2 (forward); Unity's MouseState bits are left,
			// right, middle, forward, back. The two side buttons are swapped between the two, so bit 3 and bit 4 cross over.
			for (int b = 0; b < 5; b++)
				if (GLFW.glfwGetMouseButton(handle, b) == GLFW.GLFW_PRESS) buttons |= 1 << (b == 3 ? 4 : b == 4 ? 3 : b);
			if (mcHands && !shopTouch) buttons &= ~0b111;
		}
		sb.append(buttons);
		int bare = sb.length();
		// carried by Minecraft (gliding, riding, in bed): moving is Minecraft's (steering a horse or boat, jumping a
		// horse, Shift to get off), so ULTRAKILL's V1 doesn't also run, jump, dash or slide
		boolean carried = Movement.carried;
		if (ingame) {
			for (int k : KEYS) {
				if (mcHands && ((k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) || k == GLFW.GLFW_KEY_E || k == GLFW.GLFW_KEY_Q)) continue;
				if (carried && (k == GLFW.GLFW_KEY_W || k == GLFW.GLFW_KEY_A || k == GLFW.GLFW_KEY_S || k == GLFW.GLFW_KEY_D || k == GLFW.GLFW_KEY_SPACE
					|| k == GLFW.GLFW_KEY_LEFT_SHIFT || k == GLFW.GLFW_KEY_RIGHT_SHIFT || k == GLFW.GLFW_KEY_LEFT_CONTROL || k == GLFW.GLFW_KEY_RIGHT_CONTROL)) continue;
				if (GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS) sb.append(' ').append(k);
			}
			if (ui) {
				for (int k : MENU_KEYS) if (GLFW.glfwGetKey(handle, k) == GLFW.GLFW_PRESS) sb.append(' ').append(k);
			}
		}
		// someone is at the keyboard (bosses only come for a player who is)
		if (dx != 0 || dy != 0 || buttons != 0 || notches != 0 || sb.length() > bare) lastInputAt = System.currentTimeMillis();
		UkLink.send(sb.toString());
	}

	/** This world's P, ULTRAKILL-style ("48,242 P"). */
	public static String moneyText() {
		return String.format(Locale.ROOT, "%,d P", UkProgress.shownMoney);
	}

	/** When the player last moved, looked, clicked or pressed a key as V1. */
	static volatile long lastInputAt = System.currentTimeMillis();

	/** Big P gains come up at the side of the screen for a few seconds ("+15,000 P"), with the total below. */
	private static void renderP(Minecraft mc, GuiGraphics ctx) {
		long since = System.currentTimeMillis() - UkProgress.recentAt;
		if (UkProgress.recentGain <= 0 || since > 6000) return;
		int alpha = since < 5000 ? 255 : (int) (255 * (6000 - since) / 1000.0);
		if (alpha < 8) return;
		String gain = String.format(Locale.ROOT, "+%,d P", UkProgress.recentGain);
		String total = String.format(Locale.ROOT, "%,d P", UkProgress.shownMoney);
		int w = ctx.guiWidth(), y = ctx.guiHeight() / 2 + 24;
		ctx.pose().pushMatrix();
		ctx.pose().translate(w - 8, y);
		ctx.pose().scale(1.5f, 1.5f);
		ctx.drawString(mc.font, gain, -mc.font.width(gain), 0, (alpha << 24) | 0xFF4343);
		ctx.pose().popMatrix();
		ctx.drawString(mc.font, total, w - 8 - mc.font.width(total), y + 15, (alpha << 24) | 0xFFFFFF);
	}

	// ------------------------------------------------------------------ per tick

	private static boolean startedFullscreen;
	/** In a world (its progress asked for); leaving one resets ULTRAKILL's. */
	private static boolean inWorld;

	/** Ultracraft starts in windowed fullscreen (Minecraft's fullscreen is borderless, see WindowMixin); F11 toggles. */
	private static void startFullscreen(Minecraft mc) {
		if (startedFullscreen) return;
		startedFullscreen = true;
		if (!mc.options.fullscreen().get()) mc.options.fullscreen().set(true);
		mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
	}

	private static void clientTick(Minecraft mc) {
		startFullscreen(mc);
		DebugCommands.tick(mc);
		String msg;
		while ((msg = UkLink.INBOX.poll()) != null) handle(mc, msg);
		while (toggle.consumeClick()) {
			if (UkLink.ready && !downed) {
				active = !active;
				if (active) begin(mc);
				else end(mc);
			}
		}
		while (handsKey.consumeClick()) {
			if (active && !uiMode) setHands(mc, !hands);
		}
		// a hitstop never lasts this long: don't leave Minecraft frozen if ULTRAKILL never said it ended
		if (frozen && (System.currentTimeMillis() - frozenAt > 1500 || !active || !UkLink.connected)) setFrozen(mc, false);
		// nor does a level load: past half a minute it is stuck, and Minecraft is let go rather than held for good
		if (loadFrozen && (System.currentTimeMillis() - loadFrozenAt > 30000 || !UkLink.connected)) setLoadFrozen(mc, false);
		// and a handover is a few frames' work at most: if ULTRAKILL never says it landed, let Minecraft run again
		// a handover is over once ULTRAKILL has landed V1 AND this client is drawing frames again: the stall the
		// player sees when V1 takes over is Minecraft's own (render buffers, chunk rebuilds), and it happens after
		// ULTRAKILL's answer, so a clock alone released the freeze straight into it
		if (handoffFrozen && handoffLanded) {
			long now = System.nanoTime();
			long frameNs = handoffFrameAt == 0L ? 0L : now - handoffFrameAt;
			handoffFrameAt = now;
			if (frameNs > 0L && frameNs < HANDOFF_FRAME_NS) handoffFrames++;
			else handoffFrames = 0;
			boolean settled = handoffFrames >= HANDOFF_FRAMES && now - handoffFrozenNs > 300_000_000L;
			if (settled) setHandoffFrozen(mc, false);
		}
		// and the backstop: a client that never settles is let go rather than held for good
		if (handoffFrozen && (System.currentTimeMillis() - handoffFrozenAt > 9000 || !UkLink.connected)) {
			// it never landed, so no handover took place: the next takeover is still owed its freeze
			handoffUsed = false;
			setHandoffFrozen(mc, false);
		}
		// the reasons can turn on before the integrated server exists: keep asking until it does, or the world is
		// never told to stop at all
		if (worldFrozen != freezePushed) applyFreeze(mc);
		gameModeTick(mc);
		// Minecraft's pause menu (Esc, or the window losing focus) stops ULTRAKILL too: V1, enemies, projectiles, sound
		// as Steve without ULTRAKILL's enemies about, ULTRAKILL waits (frozen) until V1 is back
		boolean paused = (active || steveView) ? mc.isPaused() : everActive && mc.level != null;
		if (paused != pauseSent && UkLink.connected) {
			pauseSent = paused;
			UkLink.send("PAUSE " + (paused ? 1 : 0));
		}
		// Minecraft's frame limit (or VSync) changed: ULTRAKILL's cap follows it
		if (UkLink.connected && UltracraftConfig.ukFps == 0 && ++fpsCheck % 40 == 0) {
			int now = UltracraftConfig.ukFpsNow();
			if (now != fpsSent) {
				fpsSent = now;
				UltracraftConfig.sendOpts();
			}
		}
		LocalPlayer p = mc.player;
		if (mc.level == null) {
			// out of the world: Steve's view and V1's history start over
			steveView = false;
			everActive = false;
			// The sync starts over too. ULTRAKILL is on a menu scene by now and sends no pose, so the last one
			// from the world just left would be applied to the player every tick of the next world - the server
			// keeps rejecting that position and pushing V1 back, which reads as being blocked by thin air. The
			// origin goes with it: the next teleportV1 has to send a fresh ORIGIN and TP, not assume the old ones
			// still hold (that is why switching to Steve and back used to be the only way out).
			UkLink.pose = null;
			setHandoffFrozen(mc, false);
			// the next world's takeover is a first one again
			handoffUsed = false;
			// the server this was told to is gone; the next world's has heard nothing
			freezePushed = false;
			worldFrozen = false;
			originSent = false;
			lastBlocks = null;
			lastExport = null;
			// and nothing of its P, gear or upgrades goes on to the next world
			if (inWorld) {
				inWorld = false;
				UkProgress.shownMoney = 0;
				if (UkLink.connected) UkLink.send("WORLDRESET");
			}
		} else if (!inWorld && mc.player != null) {
			// into a world: its own P, gear and upgrades, straight away
			inWorld = true;
			// The server forgot who was V1: it was a new one (single player), or this player left the last one. active
			// survives leaving a world, so begin's registration would not run again on its own - without it the server
			// takes V1 for Steve, refuses his moves (pushing him back as if by thin air) and reads his hits as an
			// enemy's, which is also why a blast stopped falling Idols. Register again before anything else.
			if (active) begin(mc);
			if (UkLink.connected) UcNet.toServer("PROGRESS");
		}
		if (p == null || mc.level == null) return;
		safely("blood", () -> BloodStains.tick(mc.level));
		safely("oil", () -> OilDrips.tick(mc));
		if (autoPending && UltracraftConfig.autoV1 && UkLink.ready && !active && mc.screen == null) {
			// one-click play: become V1 as soon as both games are up
			autoPending = false;
			active = true;
			lastPlayer = p;
			begin(mc);
		}
		if (p != lastPlayer) {
			// respawned or changed dimension: put V1 where Minecraft put us
			lastPlayer = p;
			// out of the Cyber Grind's arenas (however: died, left), ULTRAKILL's sky over them goes
			if (p.level().dimension() != GrindArenas.DIMENSION && UkLink.connected) UkLink.send("SKY -");
			if (active) {
				UkLink.send("RESPAWN");
				teleportV1(p);
				lastExport = null;
			}
		}
		if (steveView && !active && UkLink.connected) {
			Spectate.tick(mc);
			steveTick(mc, p);
			return;
		}
		if (!active || !UkLink.connected) return;
		tick++;
		UkLink.Pose pose = UkLink.pose;
		// A pose older than this belongs to a ULTRAKILL that has stopped talking (a scene change, a level load,
		// its window closing): following it would drag the player to wherever V1 last stood while the server
		// keeps putting them back. Standing still for a tick is always better than teleporting to a stale spot.
		if (pose != null && System.nanoTime() - pose.time > STALE_POSE_NS) pose = null;
		boolean follow = Movement.tick(mc, p, pose);
		if (pose != null && follow) {
			// V1 is the player: Minecraft's player follows V1 (no Minecraft physics)
			p.getAbilities().flying = true;
			p.setDeltaMovement(Vec3.ZERO);
			p.setPos(pose.fx, pose.fy, pose.fz);
			p.setYRot(pose.yaw);
			p.setXRot(pose.pitch);
			p.setYHeadRot(pose.yaw);
			p.setOnGround(pose.onGround);
		} else if (Movement.carried) {
			// carried by Minecraft: V1 still looks where ULTRAKILL's camera looks (a glide and a mount steer by it)
			if (pose != null && !Movement.sleeping) {
				p.setYRot(pose.yaw);
				p.setXRot(pose.pitch);
				p.setYHeadRot(pose.yaw);
			}
		} else {
			// teleported, ULTRAKILL not there yet: hold still where Minecraft put us
			p.getAbilities().flying = true;
			p.setDeltaMovement(Vec3.ZERO);
		}
		BlockPos bp = p.blockPosition();
		if (lastExport == null || !lastExport.closerThan(bp, 2) || tick % 10 == 0) {
			exportBlocks(mc.level, bp);
			safely("fluids", () -> Fluids.export(mc.level, bp));
			lastExport = bp;
		}
		if (tick % 2 == 0) safely("light", () -> Lighting.export(mc, p));
		if (tick % 10 == 0) safely("weather", () -> Weather.export(mc));
		if (tick % 20 == 0) safely("shops", () -> ShopExport.export(mc.level, bp));
		if (tick % 10 == 0) {
			// at the keyboard in the last minute, in the game (not a menu or paused): bosses only come for a player who is
			boolean playing = System.currentTimeMillis() - lastInputAt < 60_000 && mc.screen == null && !mc.isPaused();
			if (playing != playingSent || tick % 200 == 0) {
				playingSent = playing;
				UcNet.toServer("PLAYING " + (playing ? 1 : 0));
			}
		}
		safely("steve", () -> SteveMode.tick(mc, p));
		exportEntities(mc, p);
		exportProjectiles(mc, p);
		WorldMesh.tick(mc.level, bp);
		safely("culling", () -> EnemyCulling.tick(mc, p));
		if (UkLink.dead && !wasDead) {
			// V1 died in ULTRAKILL: die in Minecraft too (respawn goes through Minecraft); a Cyber Grind run ends there
			UcNet.toServer("DEAD");
		}
		wasDead = UkLink.dead;
		// everything this tick said goes out together: one write for the tick instead of one per line
		UkLink.flush();
	}

	/** As Steve with ULTRAKILL's enemies about: ULTRAKILL keeps getting the world, and V1's body follows Steve. */
	private static void steveTick(Minecraft mc, LocalPlayer p) {
		tick++;
		if (tick % 40 == 0) UkLink.send("STEVE 1");
		UkLink.send(String.format(java.util.Locale.ROOT, "STEVEPOS %.3f %.3f %.3f", p.getX(), p.getY(), p.getZ()));
		BlockPos bp = p.blockPosition();
		if (lastExport == null || !lastExport.closerThan(bp, 2) || tick % 10 == 0) {
			exportBlocks(mc.level, bp);
			safely("fluids", () -> Fluids.export(mc.level, bp));
			lastExport = bp;
		}
		if (tick % 2 == 0) safely("light", () -> Lighting.export(mc, p));
		if (tick % 10 == 0) safely("weather", () -> Weather.export(mc));
		if (tick % 20 == 0) safely("shops", () -> ShopExport.export(mc.level, bp));
		exportEntities(mc, p);
		exportProjectiles(mc, p);
		WorldMesh.tick(mc.level, bp);
		safely("culling", () -> EnemyCulling.tick(mc, p));
		// everything this tick said goes out together: one write for the tick instead of one per line
		UkLink.flush();
	}

	/** Steve's view drawn this frame (the camera took a fresh frame from ULTRAKILL, not a third-person view). */
	public static boolean steveDrawn;

	private static void begin(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		// the server knows us as V1 now: gamerules, flight, our P and gear (ServerOps "V1")
		UcNet.toServer("V1 1");
		everActive = true;
		if (steveView) {
			steveView = false;
			UkLink.send("STEVE 0");
		}
		lastInputAt = System.currentTimeMillis();
		lastBlocks = null;
		Movement.reset();
		// the origin is only dropped when it really has to move (another dimension, a long way off): sending it again
		// from where Steve happened to walk to made ULTRAKILL throw its terrain away and rebuild it, and the second
		// V1 stood on the ground it was not there yet - "fell through the floor into the void"
		teleportV1(p);
		lastExport = null;
		UkLink.send("HANDS " + (hands ? 1 : 0));
		viewSent = -1;
	}

	/** Back to Steve: nothing of V1's may linger (frozen ticks, ULTRAKILL's enemies' stand-ins). */
	private static void end(Minecraft mc) {
		setUiMode(mc, false);
		setFrozen(mc, false);
		SteveMode.reset();
		// sendInput stops the moment V1 is off, so ULTRAKILL keeps whatever buttons and keys were held on the last
		// frame it heard about: come back with the fire button down and V1 shoots on its own until the game restarts.
		// An empty IN is that last frame, with nothing held.
		UkLink.send("IN 0 0 0 0");
		// let go of V1 if Minecraft was carrying it
		if (Movement.carried) UkLink.send("UNDRIVE 0 0 0");
		Movement.reset();
		shopTouch = false;
		shopNear = false;
		UkLink.send("ZOOM 1");
		UkLink.send("VIEW 0");
		viewSent = 0;
		if (UltracraftConfig.steveEnemies && UkLink.ready) {
			// ULTRAKILL's enemies stay: drawn from Steve's camera, still fighting (their stand-ins stay in the world)
			steveView = true;
			UkLink.send("STEVE 1");
			UcNet.toServer("V1 0 keep");
		} else {
			UcNet.toServer("V1 0");
		}
	}

	/** In a duel (Duels): whose V1 our shots hurt, or -1. */
	static int duelWith = -1;

	/** C:DUEL id | C:DUEL -: a duel began against that player, or it's over (a V1 that died in it gets up again). */
	private static void duel(Minecraft mc, String rest) {
		boolean on = !rest.equals("-");
		duelWith = on ? Integer.parseInt(rest) : -1;
		UkLink.send("DUEL " + rest);
		if (!on && mc.player != null) {
			if (UkLink.dead) UkLink.send("RESPAWN");
			UkLink.send("FULLHEAL");
			if (active) teleportV1(mc.player);
		}
	}

	/** Down in a boss fight (BossParty): watching a teammate, with ULTRAKILL drawing the fight from that view. */
	public static boolean downed;

	static void setDowned(Minecraft mc, boolean on, boolean dead) {
		if (on == downed) return;
		downed = on;
		if (!on) Spectate.reset();
		if (on) {
			setUiMode(mc, false);
			setFrozen(mc, false);
			Movement.reset();
			active = false;
			steveView = true;
			UkLink.send("STEVE 1");
			UkLink.send("DOWNED 1");
		} else {
			UkLink.send("DOWNED 0");
			if (dead) {
				// the fight was lost: dead like everyone; V1 comes back with Minecraft's respawn, as after any death
				steveView = false;
				UkLink.send("STEVE 0");
				active = true;
				return;
			}
			// back up: V1 again, right where Minecraft put us
			UkLink.send("RESPAWN");
			active = true;
			begin(mc);
			UkLink.send("HUD <color=#FFD040>BACK ON YOUR FEET</color>");
		}
	}

	/** Become V1 or Steve (F8). */
	static void setActive(Minecraft mc, boolean on) {
		if (on == active || (on && !UkLink.ready)) return;
		active = on;
		if (on) begin(mc);
		else end(mc);
	}

	static void setHands(Minecraft mc, boolean on) {
		hands = on;
		UkLink.send("HANDS " + (on ? 1 : 0));
		if (mc.player != null) {
			mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(on ? "Minecraft hands (%s for guns)" : "V1's guns (%s for Minecraft hands)", handsKeyName()), true);
		}
	}

	/**
	 * Creative and spectator, told to ULTRAKILL: V1 cannot be hurt and its enemies must stop going for him.
	 * ULTRAKILL picks its targets on its own side (Harmony's Retarget), so the game mode is sent there once and
	 * the enemies leave V1 alone while still hunting Minecraft's mobs: the switch here would have stopped them
	 * dead, which is not what an untouchable player wants to watch.
	 */
	private static void gameModeTick(Minecraft mc) {
		if (!UkLink.connected) return;
		LocalPlayer p = mc.player;
		boolean untouchable = p != null && mc.level != null && (p.isCreative() || p.isSpectator());
		if (untouchable != godSent) {
			godSent = untouchable;
			UkLink.send("GOD " + (untouchable ? 1 : 0));
		}
	}

	/** ULTRAKILL's hitstop: Minecraft's world stops for as long (mobs, projectiles, particles; like /tick freeze). */
	private static void setFrozen(Minecraft mc, boolean on) {
		frozen = on;
		frozenAt = System.currentTimeMillis();
		applyFreeze(mc);
	}

	/** ULTRAKILL's level load: same pause, its own clock and ceiling (see loadFrozen). */
	private static void setLoadFrozen(Minecraft mc, boolean on) {
		loadFrozen = on;
		loadFrozenAt = System.currentTimeMillis();
		applyFreeze(mc);
	}

	/** Handing V1 over after a teleport (see handoffFrozen): the short pause that covers the takeover itself. */
	static void setHandoffFrozen(Minecraft mc, boolean on) {
		if (on == handoffFrozen) return;
		long now = System.currentTimeMillis();
		handoffFrozen = on;
		handoffFrozenAt = now;
		if (on) {
			handoffLanded = false;
			handoffFrames = 0;
			handoffFrameAt = 0L;
			handoffFrozenNs = System.nanoTime();
		}
		applyFreeze(mc);
	}


	/** Minecraft's world is frozen while any of the reasons holds: a hitstop is short, a load is not. */
	private static void applyFreeze(Minecraft mc) {
		boolean on = frozen || loadFrozen || handoffFrozen;
		worldFrozen = on;
		if (on == freezePushed) return;
		// the integrated server does not exist yet: the call is retried from clientTick until it does, so the flag
		// must not advance here or the freeze is never pushed
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null) return;
		freezePushed = on;
		server.execute(() -> server.tickRateManager().setFrozen(on));
	}

	/** Whether Minecraft's world is being held right now, whatever the reason. While it is, nothing on this side
	 * moves, so nothing may arrive in ULTRAKILL either: a dark spawn or a boss called in now would land in a world
	 * standing still, and the handover freeze exists exactly to keep the scene quiet until V1 has been landed. The
	 * server tick keeps running while the world is frozen (only the game elements stop), so the spawners have to ask
	 * this rather than assume the freeze reaches them. */
	static boolean worldHeld() {
		return frozen || loadFrozen || handoffFrozen;
	}

	private static void setUiMode(Minecraft mc, boolean on) {
		uiMode = on;
		if (on && active) {
			mc.mouseHandler.releaseMouse();
		} else if (mc.screen == null && mc.player != null) {
			mc.mouseHandler.grabMouse();
		}
	}

	private static final java.util.Set<String> failed = new java.util.HashSet<>();

	/** Extras that must never take the game down with them: a failure is logged once and the tick goes on. */
	private static void safely(String what, Runnable r) {
		try {
			r.run();
		} catch (RuntimeException e) {
			if (failed.add(what)) org.slf4j.LoggerFactory.getLogger("ultracraft").error("{} export failed", what, e);
		}
	}

	static void teleportV1(LocalPlayer p) {
		boolean rebase = false;
		if (originSent && originAt != null && (originDim != p.level().dimension()
			|| Math.max(Math.abs(p.getX() - originAt.x), Math.abs(p.getZ() - originAt.z)) > REBASE || Math.abs(p.getY() - originAt.y) > REBASE)) {
			// far from the origin (the Cyber Grind's arenas, another dimension, a long /tp): it moves here, and
			// ULTRAKILL gets the terrain, water and shops around it afresh (its enemies and bosses stay put)
			originSent = false;
			rebase = true;
			lastBlocks = null;
			lastExport = null;
			WorldMesh.reset();
			Fluids.reset();
			ShopExport.reset();
		}
		if (!originSent) {
			UkLink.send(String.format(Locale.ROOT, "ORIGIN %.3f %.3f %.3f", p.getX(), p.getY(), p.getZ()) + (rebase ? " keep" : ""));
			originSent = true;
			originAt = p.position();
			originDim = p.level().dimension();
		}
		exportBlocks(p.level() instanceof ClientLevel cl ? cl : null, p.blockPosition());
		// Only a world's first handover is slow: ULTRAKILL loads the scene behind it, and Minecraft holding its
		// world until the answer ("LANDED") is what hides that stall. Every later teleport - a respawn, another
		// dimension, out of the Cyber Grind - is quick, and freezing for it reads as a stutter of its own
		// ULTRAKILL drops every TP it is sent before it says READY, so a handover into a game that is not ready
		// yet never happens, and holding the world for it would only stall a teleport that nobody takes
		// only a takeover V1 actually goes for spends the token: a respawn or a dimension change while still Steve
		// sends a TP that ULTRAKILL drops (it is not in a level yet), and spending the token on that one left the
		// real handover - the toggling into V1 this freeze exists for - with nothing to hold the world
		if (!handoffUsed && UkLink.ready && active) {
			handoffUsed = true;
			setHandoffFrozen(Minecraft.getInstance(), true);
		}
		UkLink.send(String.format(Locale.ROOT, "TP %.3f %.3f %.3f %.2f %.2f", p.getX(), p.getY() + 0.05, p.getZ(), p.getYRot(), p.getXRot()));
	}

	private static void handle(Minecraft mc, String msg) {
		if (msg.equals("READY") && mc.player != null && mc.player.connection != null) {
			mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.translatable("[Ultracraft] ULTRAKILL is ready. Press %s to become V1.", toggleKeyName()));
			UcCheats.sendServer();
			// ULTRAKILL only now takes over the level (it strips the Sandbox and clears its camera), and every TP
			// sent before that was dropped: send the origin and the position again so the terrain and V1 arrive in
			// this tick instead of waiting for the next world tick to notice something is missing
			if (active) {
				originSent = false;
				lastExport = null;
				teleportV1(mc.player);
			}
		} else if (msg.equals("CONNECTED")) {
			// a (re)started ULTRAKILL needs our window size again, and whether we're paused
			lastW = -1;
			pauseSent = false;
			Fluids.reset();
			// a restarted ULTRAKILL has no terrain either, and it is the one place where starting over is right
			WorldMesh.reset();
			// a restarted ULTRAKILL knows none of our textures, shops or sky yet, nor the settings
			langSent = "";
			UltracraftConfig.sendOpts();
			CutoutTextures.reset();
			ShopExport.reset();
			Weather.reset();
			// it has no level of ours loaded either: the next takeover will be slow again, and worth holding for
			handoffUsed = false;
			UcNet.toServer("PROGRESS");
			UcCheats.sendServer();
		} else if (msg.equals("DISCONNECTED")) {
			active = false;
			autoPending = true;
			end(mc);
		} else if (msg.startsWith("FREEZE ")) {
			setFrozen(mc, msg.endsWith("1"));
		} else if (msg.startsWith("LOADFREEZE ")) {
			setLoadFrozen(mc, msg.endsWith("1"));
		} else if (msg.equals("LANDED")) {
			// V1 has taken the view and is on the ground: the world we were holding for it may run - but only once
			// this client is drawing frames again, because the stall the freeze hides happens here, not there
			if (handoffFrozen) {
				handoffLanded = true;
				handoffFrames = 0;
				handoffFrameAt = System.nanoTime();
			}
		} else if (msg.startsWith("UKE ")) {
			Lighting.ukEnemies(msg.substring(4));
			UcNet.toServer(msg);
		} else if (msg.startsWith("UI ")) {
			setUiMode(mc, msg.endsWith("1"));
		} else if (msg.matches("^(SHOPINFO|SUNINFO|PUSHINFO|NUKETEST|SPAWNED|MOVEINFO|WATERINFO|GROUND|LIGHTINFO|STAININFO|BOSSINFO|GEARINFO|BOSSSPAWNED|SHOPPRESSED|CUTINFO|FXINFO|PUPINFO|BINDINFO|SHOPRQ|FRAMESNAPPED|SPAWNABLES|MUSICINFO|ARMINFO|COMBATTEST|GUNINFO|MPINFO) .*")) {
			// debug answers (DebugCommands "uk ...")
			org.slf4j.LoggerFactory.getLogger("ultracraft").info("[uk] {}", msg);
		} else if (msg.startsWith("SHOPZONE ")) {
			// SHOPZONE touch near
			String[] a = msg.split(" ");
			boolean touch = a[1].equals("1");
			if (touch != shopTouch) UcNet.toServer("SHOP " + (touch ? 1 : 0));
			shopTouch = touch;
			shopNear = a.length > 2 && a[2].equals("1");
		} else if (msg.startsWith("CGPATTERNS ")) {
			GrindArenas.ultrakillPatterns(msg.substring(11));
		} else if (msg.startsWith("SONGS ")) {
			UcMusicScreen.songs(msg.substring(6));
		} else if (msg.startsWith("FIGHT ")) {
			UcThemes.fight(mc, msg.endsWith("1"));
		} else if (msg.startsWith("STAINS ")) {
			BloodStains.add(mc.level, msg.substring(7));
		} else if (msg.startsWith("OILS ")) {
			BloodStains.addOil(mc.level, msg.substring(5));
		} else if (msg.startsWith("OILBURN ")) {
			String[] a = msg.split(" ");
			BloodStains.burnOil(Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]));
		} else if (msg.startsWith("OILED ")) {
			String[] a = msg.split(" ");
			OilDrips.set(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
		} else if (msg.equals("STAINCLEAR")) {
			BloodStains.clear();
		} else if (msg.startsWith("STAINSIZE ")) {
			BloodStains.setSize(Float.parseFloat(msg.substring(10).trim()));
		} else if (msg.startsWith("UKPREFS ")) {
			// UKPREFS key=type:value;...: ULTRAKILL's own settings, for the settings screen
			UcSettingsScreen.reported(msg.substring(8));
		} else if (SERVER_OPS.matcher(msg).lookingAt()) {
			// what our ULTRAKILL did to the shared world (blocks, blasts, mobs, P, purchases, parries, bosses, its
			// enemies): the server (this process in singleplayer, the host's in multiplayer) applies it as our player
			UcNet.toServer(msg);
		}
	}

	/** ULTRAKILL's messages that act on the world, handled by the server (ServerOps). */
	private static final java.util.regex.Pattern SERVER_OPS = java.util.regex.Pattern.compile(
		"^(V1STATE|FX|SLAM|RAIL|BOOM|HIT|FIRE|DMG|WHIP|MKNOCK|PIMPACT|PHOLD|PRELEASE|PARRY|PEARN|PADD|GEARADD|UPBUY|UPSET|BOSSPOS|BOSSDEAD|BOSSGONE|GRIND|SPAWNS|UKDEAD|GRINDGONE|UKDIE|PHIT|SHURT|EQUIP|NOSPAWN|STYLE|UKSTYLE|UKMULTI|POOL|TOTEMUSE)( .*)?$");

	/** A line from the server for our Minecraft side ("C:..." in UcNet), on the client thread. */
	static void fromServer(String msg) {
		if (msg.startsWith("DOWNED ")) {
			setDowned(Minecraft.getInstance(), msg.startsWith("DOWNED 1"), msg.endsWith("dead"));
		} else if (msg.startsWith("WATCH ")) {
			Spectate.watch(msg.substring(6));
		} else if (msg.equals("SETTINGS")) {
			Minecraft.getInstance().setScreen(new UcSettingsScreen(null));
		} else if (msg.startsWith("DOWNS")) {
			Teammates.downs(msg.length() > 6 ? msg.substring(6) : "");
		} else if (msg.startsWith("DUEL ")) {
			duel(Minecraft.getInstance(), msg.substring(5).trim());
		} else if (msg.startsWith("V1S ")) {
			UcNet.clientV1s(msg.substring(4));
		} else if (msg.startsWith("PGAIN ")) {
			UkProgress.gained(Integer.parseInt(msg.substring(6).trim()));
		} else if (msg.startsWith("THEME ")) {
			UcThemes.theme(msg.substring(6).trim());
		}
	}

	interface ServerTask {
		void run(MinecraftServer server, ServerPlayer player);
	}

	static void runOnServer(Minecraft mc, ServerTask task) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return;
		var uuid = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null) task.run(server, sp);
		});
	}

	// ------------------------------------------------------------------ world export

	private static final int RH = 12, RDOWN = 8, RUP = 14;

	/** The six ways a face can point, made once: exposed() asked for them on every solid block it looked at. */
	private static final int[][] FACE_DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	/** What the last tick said its projectiles were, so an empty list is not sent over and over. */
	private static String lastProjs = "";

	/** The same for the entities: an empty list is only worth sending once. */
	private static String lastEnts = "";

	/** One builder for the two per-tick exports: each finishes with toString() before the next one starts. */
	private static final StringBuilder SENT = new StringBuilder(8 * 1024);

	private static StringBuilder reused() {
		SENT.setLength(0);
		return SENT;
	}

	/** Three decimals without String.format: this runs for every entity of the world, every tick. */
	private static StringBuilder f3(StringBuilder sb, double v) {
		return sb.append(Math.round(v * 1000.0) / 1000.0);
	}

	/** One decimal, written as x.y so a whole number still reads as one. */
	private static StringBuilder f1(StringBuilder sb, double v) {
		long n = Math.round(v * 10.0);
		return sb.append(n / 10).append('.').append(Math.abs(n % 10));
	}

	/** Any block of this column could be solid: asked of the section palettes, which cost nothing next to the blocks. */
	private static boolean columnHasSolids(ClientLevel level, int x, int z, int yLow, int yHigh) {
		net.minecraft.world.level.chunk.LevelChunkSection[] secs = level.getChunk(x >> 4, z >> 4).getSections();
		int s0 = Math.max(0, level.getSectionIndex(yLow));
		int s1 = Math.min(secs.length - 1, level.getSectionIndex(yHigh));
		for (int i = s0; i <= s1; i++) {
			net.minecraft.world.level.chunk.LevelChunkSection s = secs[i];
			if (s == null || s.hasOnlyAir()) continue;
			if (s.maybeHas(st -> !st.isAir())) return true;
		}
		return false;
	}

	/**
	 * Solid blocks around the player as boxes: exposed full cubes merged into vertical runs, partial shapes as-is.
	 *
	 * The grid is 25x25 columns of 23 blocks, so 14,375 blocks ten times a second to read - and in open ground almost
	 * every one of them is air. Each column now asks its chunk's palettes first and is skipped whole when no section
	 * in it can hold a solid block, which leaves only the columns that really have something to read.
	 */
	private static void exportBlocks(ClientLevel level, BlockPos c) {
		if (level == null) return;
		StringBuilder sb = new StringBuilder(64 * 1024);
		sb.append("BLOCKS ");
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int yLow = c.getY() - RDOWN, yHigh = c.getY() + RUP;
		for (int x = c.getX() - RH; x <= c.getX() + RH; x++) {
			for (int z = c.getZ() - RH; z <= c.getZ() + RH; z++) {
				if (!columnHasSolids(level, x, z, yLow, yHigh)) continue;
				int runStart = Integer.MIN_VALUE;
				for (int y = c.getY() - RDOWN; y <= c.getY() + RUP + 1; y++) {
					boolean fullExposed = false;
					if (y <= c.getY() + RUP) {
						m.set(x, y, z);
						BlockState s = level.getBlockState(m);
						if (!s.isAir()) {
							VoxelShape shape = s.getCollisionShape(level, m);
							if (!shape.isEmpty()) {
								if (s.isCollisionShapeFullBlock(level, m)) {
									fullExposed = exposed(level, x, y, z, m);
								} else {
									for (AABB b : shape.toAabbs()) {
										sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f;", x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ));
									}
								}
							}
						}
					}
					if (fullExposed && runStart == Integer.MIN_VALUE) runStart = y;
					if (!fullExposed && runStart != Integer.MIN_VALUE) {
						sb.append(x).append(',').append(runStart).append(',').append(z).append(',')
							.append(x + 1).append(',').append(y).append(',').append(z + 1).append(';');
						runStart = Integer.MIN_VALUE;
					}
				}
			}
		}
		// unchanged surroundings: don't make ULTRAKILL parse (and garbage-collect) the same list again
		String s = sb.toString();
		long now = System.currentTimeMillis();
		if (s.equals(lastBlocks) && now - lastBlocksAt < 5000) return;
		lastBlocks = s;
		lastBlocksAt = now;
		UkLink.send(s);
	}

	private static boolean exposed(ClientLevel level, int x, int y, int z, BlockPos.MutableBlockPos m) {
		for (int[] o : FACE_DIRS) {
			m.set(x + o[0], y + o[1], z + o[2]);
			if (!level.getBlockState(m).isCollisionShapeFullBlock(level, m)) {
				m.set(x, y, z);
				return true;
			}
		}
		m.set(x, y, z);
		return false;
	}

	/** Projectiles flying around V1 (not V1's own): ULTRAKILL tracks them for parries and decides hits on V1. */
	private static void exportProjectiles(Minecraft mc, LocalPlayer self) {
		StringBuilder sb = reused().append("PROJS ");
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Projectile pr) || e instanceof FishingHook || pr.getOwner() == self) continue;
			if (e.distanceToSqr(self) > 48 * 48) continue;
			Vec3 v = e.getDeltaMovement();
			if (v.lengthSqr() < 1e-4) continue; // stuck in a block
			String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
			sb.append(e.getId()).append(',').append(type).append(',')
				.append(e.getX()).append(',').append(e.getY() + e.getBbHeight() * 0.5).append(',').append(e.getZ()).append(',')
				.append(v.x).append(',').append(v.y).append(',').append(v.z).append(',')
				.append(Math.max(e.getBbWidth(), e.getBbHeight())).append(';');
		}
		// an empty list still has to go out after a full one: it is how ULTRAKILL learns its last one is gone
		String out = sb.toString();
		if (out.length() > 6 || lastProjs.length() > 6) UkLink.send(out);
		lastProjs = out;
	}

	private static void exportEntities(Minecraft mc, LocalPlayer self) {
		StringBuilder sb = reused().append("ENTS ");
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal) {
				if (!crystal.isAlive() || e.distanceToSqr(self) > 80 * 80) continue;
				sb.append(e.getId()).append(",end_crystal,");
				f3(sb, e.getX()).append(',');
				f3(sb, e.getY()).append(',');
				f3(sb, e.getZ()).append(',');
				f3(sb, e.getBbWidth()).append(',');
				f3(sb, e.getBbHeight()).append(',');
				f3(sb, e.getBbHeight() * 0.5).append(",1.0,1.0,0,0.0;");
				continue;
			}
			// ULTRAKILL's own enemies' stand-ins are ULTRAKILL's already
			if (e == self || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof UkEnemyEntity || e.isSpectator()) continue;
			// a mob the Mob Damage Filter leaves out is never sent over at all: no stand-in is made for it there,
			// so none of ULTRAKILL's weapons, fists, enemies, Whiplash or blasts has anything to reach
			if (!MobDamageRules.touchable(e)) continue;
			// another player who is V1: ULTRAKILL draws V1's body there, and its enemies go for them as for us (a
			// teammate who is down in a boss fight watches as a spectator: not there, and nobody's target)
			boolean v1 = UcNet.isV1(e);
			if (e.distanceToSqr(self) > (v1 ? 160 * 160 : 80 * 80)) continue;
			String type = v1 ? "v1" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
			// hostile mobs (and other V1s) are what ULTRAKILL's enemies go for
			int hostile = e instanceof Enemy || v1 ? 1 : 0;
			// on fire: ULTRAKILL's own fire burns on it (EntityMixin keeps Minecraft's flames off it meanwhile)
			// id,type,x,y,z,w,h,eye,hp,maxhp,hostile,yaw,burning,onGround,flying - read back by UltraBridge's ApplyEntities
			sb.append(e.getId()).append(',').append(type).append(',');
			f3(sb, e.getX()).append(',');
			f3(sb, e.getY()).append(',');
			f3(sb, e.getZ()).append(',');
			f3(sb, e.getBbWidth()).append(',');
			f3(sb, e.getBbHeight()).append(',');
			f3(sb, e.getEyeHeight()).append(',');
			f1(sb, le.getHealth()).append(',');
			f1(sb, le.getMaxHealth()).append(',').append(hostile).append(',');
			f1(sb, v1 ? e.getYHeadRot() : e.getYRot()).append(',').append(e.isOnFire() ? 1 : 0);
			// on the ground or in the air: ULTRAKILL's rocket reads this to tell a mob it caught in mid-air from one
			// standing on something, and that is what makes its blast the red superExplosion (Grenade.Collide)
			sb.append(',').append(le.onGround() ? 1 : 0).append(',').append(airborne(e) ? 1 : 0).append(';');
		}
		// as with the projectiles: an empty list has to follow a full one, or the last stand-ins would never go
		String out = sb.toString();
		if (out.length() > 5 || lastEnts.length() > 5) UkLink.send(out);
		lastEnts = out;
	}

	// creatures that live in the air, so ULTRAKILL never treats a blast on one of them as the red one (a flying enemy
	// is excluded there, Grenade.Collide). Minecraft has no such flag to ask for: FlyingAnimal covers the bee and the
	// parrot alone, so everything else goes by type
	private static boolean airborne(Entity e) {
		EntityType<?> t = e.getType();
		return t == EntityType.PHANTOM || t == EntityType.GHAST || t == EntityType.HAPPY_GHAST
			|| t == EntityType.ALLAY || t == EntityType.BAT || t == EntityType.BEE || t == EntityType.PARROT
			|| t == EntityType.VEX || t == EntityType.BLAZE || t == EntityType.WITHER
			|| t == EntityType.ENDER_DRAGON;
	}
}
