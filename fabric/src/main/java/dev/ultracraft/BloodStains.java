package dev.ultracraft;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * ULTRAKILL's blood on Minecraft's blocks. ULTRAKILL paints a stain wherever its blood lands, but folds its stains into
 * its picture by darkening what's under them, and over Minecraft's blocks its picture is see-through: there they'd
 * vanish. So every stain comes here (STAINS x,y,z,nx,ny,nz;...) and Minecraft paints it onto the block itself, in the
 * block's light and fog. A stain goes when its block does; past a few thousand the oldest go first, as in ULTRAKILL.
 */
final class BloodStains {
	static final int MAX = 8192;
	/** How many are kept (Performance → Blood Stains); gasoline always keeps a few, it shows where it's spread. */
	private static int cap = MAX;
	private static final int OIL_MIN = 256;
	/** How far a stain is drawn from (blocks). */
	private static final double RANGE = 96.0;
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("ultracraft", "dynamic/blood");
	// each stain: its middle just off the surface, the two half-axes of its square (size and turn baked in), the way
	// its surface faces, which of the four splats it is, the block it lies on and the light where it lies
	private static final float[] cx = new float[MAX], cy = new float[MAX], cz = new float[MAX];
	private static final float[] ax = new float[MAX], ay = new float[MAX], az = new float[MAX];
	private static final float[] bx = new float[MAX], by = new float[MAX], bz = new float[MAX];
	private static final float[] sx = new float[MAX], sy = new float[MAX], sz = new float[MAX];
	private static final byte[] splat = new byte[MAX], blockLight = new byte[MAX], skyLight = new byte[MAX], shade = new byte[MAX];
	/** What it is: blood, or the Firestarter's gasoline (OIL). */
	private static final byte[] kind = new byte[MAX];
	private static final byte BLOOD = 0, OIL = 1;
	private static final long[] support = new long[MAX];
	private static final boolean[] alive = new boolean[MAX];
	private static int next, live;
	private static int checked;
	/** One of ULTRAKILL's stains across, in blocks (STAINSIZE). */
	private static float size = 0.5f;
	private static boolean textureMade;
	private static final Random RANDOM = new Random();

	private BloodStains() {}

	static void register() {
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(BloodStains::render);
	}

	/** Performance → Blood Stains: fewer kept (and drawn); those past the new limit go. */
	static void setCap(int c) {
		c = Mth.clamp(c, 0, MAX);
		if (c == cap) return;
		cap = c;
		int used = used();
		for (int i = used; i < MAX; i++) {
			if (alive[i]) {
				alive[i] = false;
				live--;
			}
		}
		if (next >= used) next = 0;
		if (checked >= used) checked = 0;
	}

	/** Slots in use: the cap, or a few for gasoline when blood stains are off. */
	private static int used() {
		return Math.max(cap, OIL_MIN);
	}

	static void setSize(float s) {
		size = Mth.clamp(s, 0.2f, 1.5f);
	}

	static int count() {
		return live;
	}

	static void clear() {
		java.util.Arrays.fill(alive, false);
		live = 0;
	}

	/** STAINS x,y,z,nx,ny,nz;...: where ULTRAKILL's blood landed (Minecraft coordinates) and the way the surface faces. */
	static void add(ClientLevel level, String data) {
		add(level, data, BLOOD);
	}

	/** OILS x,y,z,nx,ny,nz: the Firestarter's gasoline landed there (ULTRAKILL folds it into its picture like blood). */
	static void addOil(ClientLevel level, String data) {
		add(level, data, OIL);
	}

	/** Gasoline caught fire around there: it burns away. */
	static void burnOil(float x, float y, float z) {
		for (int i = 0, n = used(); i < n; i++) {
			if (!alive[i] || kind[i] != OIL) continue;
			float dx = cx[i] - x, dy = cy[i] - y, dz = cz[i] - z;
			if (dx * dx + dy * dy + dz * dz > 2.25f) continue;
			alive[i] = false;
			live--;
		}
	}

	private static byte adding = BLOOD;

	private static void add(ClientLevel level, String data, byte what) {
		if (level == null) return;
		adding = what;
		for (String rec : data.split(";")) {
			String[] a = rec.split(",");
			if (a.length < 6) continue;
			try {
				add(level, Float.parseFloat(a[0]), Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5]));
			} catch (NumberFormatException ignored) {
			}
		}
	}

	private static void add(ClientLevel level, float x, float y, float z, float nx, float ny, float nz) {
		float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-3f) return;
		nx /= len;
		ny /= len;
		nz /= len;
		// on a block's face: lie flat on it
		if (Math.abs(nx) > 0.95f) { nx = Math.signum(nx); ny = 0; nz = 0; }
		else if (Math.abs(ny) > 0.95f) { ny = Math.signum(ny); nx = 0; nz = 0; }
		else if (Math.abs(nz) > 0.95f) { nz = Math.signum(nz); nx = 0; ny = 0; }
		BlockPos on = BlockPos.containing(x - nx * 0.05, y - ny * 0.05, z - nz * 0.05);
		if (!holds(level, on)) {
			on = BlockPos.containing(x - nx * 0.25, y - ny * 0.25, z - nz * 0.25);
			if (!holds(level, on)) return;
		}
		if (adding == BLOOD && cap == 0) return;
		int i = next;
		next = (next + 1) % used();
		if (!alive[i]) live++;
		alive[i] = true;
		cx[i] = x + nx * 0.004f;
		cy[i] = y + ny * 0.004f;
		cz[i] = z + nz * 0.004f;
		// two axes across the surface, turned at random, each half the stain's size (a little bigger or smaller)
		Vector3f n = new Vector3f(nx, ny, nz);
		Vector3f t = Math.abs(ny) < 0.9f ? new Vector3f(0, 1, 0).cross(n) : new Vector3f(1, 0, 0).cross(n);
		t.normalize();
		Vector3f b = new Vector3f(n).cross(t);
		// a bit bigger than ULTRAKILL's own: fewer, fuller splats read better on Minecraft's blocks
		float turn = RANDOM.nextFloat() * Mth.TWO_PI, half = (adding == OIL ? 0.55f : size) * (0.85f + RANDOM.nextFloat() * 0.45f);
		float c = Mth.cos(turn) * half, s = Mth.sin(turn) * half;
		ax[i] = t.x * c + b.x * s;
		ay[i] = t.y * c + b.y * s;
		az[i] = t.z * c + b.z * s;
		bx[i] = -t.x * s + b.x * c;
		by[i] = -t.y * s + b.y * c;
		bz[i] = -t.z * s + b.z * c;
		sx[i] = nx;
		sy[i] = ny;
		sz[i] = nz;
		splat[i] = (byte) RANDOM.nextInt(4);
		kind[i] = adding;
		// fresh blood is a little brighter or darker from splash to splash
		shade[i] = (byte) RANDOM.nextInt(64);
		support[i] = on.asLong();
		light(level, i);
	}

	/** A block blood can lie on: something solid, not a fluid, not the shop (ULTRAKILL draws that one itself). */
	private static boolean holds(ClientLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.isAir() || s.getBlock() instanceof LiquidBlock || s.is(UltracraftCommon.UK_SHOP)) return false;
		return !s.getCollisionShape(level, pos).isEmpty();
	}

	private static void light(ClientLevel level, int i) {
		// the light in front of the face it lies on
		BlockPos front = BlockPos.containing(cx[i] + sx[i] * 0.5f, cy[i] + sy[i] * 0.5f, cz[i] + sz[i] * 0.5f);
		blockLight[i] = (byte) level.getBrightness(LightLayer.BLOCK, front);
		skyLight[i] = (byte) level.getBrightness(LightLayer.SKY, front);
	}

	/** Every tick, a slice of the stains: gone with their block, and their light kept up to date. */
	static void tick(ClientLevel level) {
		if (level == null || live == 0) return;
		for (int k = 0; k < 256; k++) {
			int i = checked;
			checked = (checked + 1) % used();
			if (!alive[i]) continue;
			if (!holds(level, BlockPos.of(support[i]))) {
				alive[i] = false;
				live--;
				continue;
			}
			light(level, i);
		}
	}

	private static void render(WorldRenderContext ctx) {
		if (live == 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		if (!textureMade) makeTexture(mc);
		Vec3 cam = ctx.worldState().cameraRenderState.pos;
		float[] lightmap = Lightmaps.table(mc);
		RenderType type = RenderTypes.entityShadow(TEXTURE);
		VertexConsumer vc = ctx.consumers().getBuffer(type);
		PoseStack ps = ctx.matrices();
		ps.pushPose();
		ps.translate(-cam.x, -cam.y, -cam.z);
		PoseStack.Pose pose = ps.last();
		double r2 = RANGE * RANGE;
		for (int i = 0, n = used(); i < n; i++) {
			if (!alive[i]) continue;
			double dx = cx[i] - cam.x, dy = cy[i] - cam.y, dz = cz[i] - cam.z;
			if (dx * dx + dy * dy + dz * dz > r2) continue;
			int l = (blockLight[i] & 15) * 16 + (skyLight[i] & 15);
			// ULTRAKILL's blood: a deep red, darker where it's darker (Minecraft's own light there, as on the block);
			// gasoline: a dark, oily brown
			float k = 0.78f + (shade[i] & 63) / 63f * 0.22f;
			int color = kind[i] == OIL
				? ARGBf(0.88f, 0.16f * k * lightmap[l * 3], 0.12f * k * lightmap[l * 3 + 1], 0.05f * k * lightmap[l * 3 + 2])
				: ARGBf(0.94f, 0.5f * k * lightmap[l * 3], 0.035f * k * lightmap[l * 3 + 1], 0.03f * k * lightmap[l * 3 + 2]);
			float u0 = (splat[i] & 1) * 0.5f, v0 = (splat[i] >> 1) * 0.5f;
			corner(vc, pose, i, -1, -1, color, u0, v0 + 0.5f);
			corner(vc, pose, i, 1, -1, color, u0 + 0.5f, v0 + 0.5f);
			corner(vc, pose, i, 1, 1, color, u0 + 0.5f, v0);
			corner(vc, pose, i, -1, 1, color, u0, v0);
		}
		ps.popPose();
		// drawn now, under the water and glass still to come
		if (ctx.consumers() instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
	}

	private static void corner(VertexConsumer vc, PoseStack.Pose pose, int i, float sa, float sb, int color, float u, float v) {
		vc.addVertex(pose, cx[i] + ax[i] * sa + bx[i] * sb, cy[i] + ay[i] * sa + by[i] * sb, cz[i] + az[i] * sa + bz[i] * sb).setColor(color).setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0f, 1f, 0f);
	}

	private static int ARGBf(float a, float r, float g, float b) {
		return ((int) (Mth.clamp(a, 0f, 1f) * 255f) << 24) | ((int) (Mth.clamp(r, 0f, 1f) * 255f) << 16) | ((int) (Mth.clamp(g, 0f, 1f) * 255f) << 8)
			| (int) (Mth.clamp(b, 0f, 1f) * 255f);
	}

	/** Four splats of blood in a 2 x 2 sheet: a blob with a ragged rim, thicker in the middle, with droplets around. */
	private static void makeTexture(Minecraft mc) {
		textureMade = true;
		int cell = 64, n = cell * 2;
		NativeImage img = new NativeImage(n, n, true);
		Random r = new Random(1229490);
		for (int s = 0; s < 4; s++) {
			int ox = (s & 1) * cell, oy = (s >> 1) * cell;
			double[] wave = new double[6];
			for (int k = 0; k < wave.length; k++) wave[k] = r.nextDouble() * Math.PI * 2;
			double base = 0.26 + r.nextDouble() * 0.06;
			int drops = 4 + r.nextInt(5);
			double[][] drop = new double[drops][3];
			for (int k = 0; k < drops; k++) {
				double ang = r.nextDouble() * Math.PI * 2, dist = 0.3 + r.nextDouble() * 0.16;
				drop[k][0] = 0.5 + Math.cos(ang) * dist;
				drop[k][1] = 0.5 + Math.sin(ang) * dist;
				drop[k][2] = 0.015 + r.nextDouble() * 0.045;
			}
			for (int y = 0; y < cell; y++) {
				for (int x = 0; x < cell; x++) {
					double px = (x + 0.5) / cell, py = (y + 0.5) / cell;
					double dx = px - 0.5, dy = py - 0.5, d = Math.sqrt(dx * dx + dy * dy), ang = Math.atan2(dy, dx);
					double rim = base * (1 + 0.16 * Math.sin(3 * ang + wave[0]) + 0.10 * Math.sin(5 * ang + wave[1]) + 0.07 * Math.sin(9 * ang + wave[2])
						+ 0.05 * Math.sin(13 * ang + wave[3]));
					double cover = Mth.clamp((rim - d) * cell * 0.5, 0, 1);
					for (double[] dp : drop) {
						double ddx = px - dp[0], ddy = py - dp[1];
						cover = Math.max(cover, Mth.clamp((dp[2] - Math.sqrt(ddx * ddx + ddy * ddy)) * cell * 0.5, 0, 1));
					}
					// pooled thick and dark in the middle, thinner and brighter towards a ragged rim, mottled throughout
					double inner = Mth.clamp(1 - d / Math.max(rim, 1e-3), 0, 1);
					double mottle = 0.5 * Math.sin(px * 37 + wave[4]) * Math.sin(py * 41 + wave[5]) + 0.5 * Math.sin(px * 83 + wave[1]) * Math.sin(py * 71 + wave[2]);
					double thick = 0.75 + 0.25 * inner + 0.06 * mottle;
					int a = (int) (Mth.clamp(cover * (0.62 + 0.38 * thick), 0, 1) * 255);
					int c = (int) (Mth.clamp(1.0 - 0.42 * Math.sqrt(inner) + 0.14 * mottle, 0.3, 1) * 255);
					img.setPixel(ox + x, oy + y, (a << 24) | (c << 16) | (c << 8) | c);
				}
			}
		}
		mc.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "ultracraft blood", img));
	}

	/** Minecraft's lightmap as colours for every block and sky light level, for the frame being drawn. */
	static final class Lightmaps {
		private static final float[] table = new float[256 * 3];
		private static long frame = -1;

		static float[] table(Minecraft mc) {
			long now = mc.level != null ? mc.level.getGameTime() * 1000 + (long) (mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) * 999) : 0;
			if (now == frame) return table;
			frame = now;
			Lighting.Lightmap lm = mc.player != null ? new Lighting.Lightmap(mc, mc.player) : null;
			for (int b = 0; b < 16; b++) {
				for (int s = 0; s < 16; s++) {
					int k = (b * 16 + s) * 3;
					if (lm == null) {
						table[k] = table[k + 1] = table[k + 2] = 1f;
						continue;
					}
					Vector3f c = lm.color(b, s);
					table[k] = c.x;
					table[k + 1] = c.y;
					table[k + 2] = c.z;
				}
			}
			return table;
		}
	}
}
