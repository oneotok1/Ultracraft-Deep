package dev.ultracraft;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3f;

/**
 * Minecraft's light on ULTRAKILL's things: for V1 (its arm and guns) and each of ULTRAKILL's enemies, the colour
 * Minecraft's own lightmap gives the block light and sky light where it stands (time of day, the brightness setting,
 * night vision, darkness), so they are exactly as lit as a mob standing there. LIGHT v1,r,g,b;id,r,g,b;...
 */
final class Lighting {
	/** Its middle, and how wide and tall it is (blocks). */
	record Spot(String id, double x, double y, double z, double w, double h) {
		Spot(String id, double x, double y, double z) {
			this(id, x, y, z, 1.0, 2.0);
		}
	}

	// where ULTRAKILL's enemies are (their middle), from UKE
	private static volatile List<Spot> enemies = List.of();

	private Lighting() {}

	static List<Spot> enemySpots() {
		return enemies;
	}

	/** UKE id,type,x,y,z,w,h,hp;... (feet, Minecraft coordinates). */
	static void ukEnemies(String data) {
		List<Spot> list = new ArrayList<>();
		for (String rec : data.split(";")) {
			String[] a = rec.split(",");
			if (a.length < 7) continue;
			try {
				double w = Double.parseDouble(a[5]), h = Double.parseDouble(a[6]);
				list.add(new Spot(a[0], Double.parseDouble(a[2]), Double.parseDouble(a[3]) + h * 0.5, Double.parseDouble(a[4]), w, h));
			} catch (NumberFormatException ignored) {
			}
		}
		enemies = list;
	}

	static void export(Minecraft mc, LocalPlayer p) {
		ClientLevel level = mc.level;
		if (level == null) return;
		Lightmap lm = new Lightmap(mc, p);
		StringBuilder sb = new StringBuilder("LIGHT ");
		append(sb, "v1", lm, level, p.getX(), p.getEyeY(), p.getZ());
		for (Spot s : enemies) append(sb, s.id, lm, level, s.x, s.y, s.z);
		// the shops, lit where they stand (in front of their screen, where the light reaches them)
		for (Spot s : ShopExport.spots()) append(sb, s.id, lm, level, s.x, s.y, s.z);
		UkLink.send(sb.toString());
	}

	private static void append(StringBuilder sb, String id, Lightmap lm, ClientLevel level, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		Vector3f c = lm.color(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
		sb.append(id).append(',');
		f3(sb, c.x).append(',');
		f3(sb, c.y).append(',');
		f3(sb, c.z).append(';');
	}

	/** Three decimals without String.format: this runs for every lit thing, every other tick. */
	private static StringBuilder f3(StringBuilder sb, double v) {
		return sb.append(Math.round(v * 1000.0) / 1000.0);
	}

	/** Minecraft's lightmap (LightTexture's inputs, lightmap.fsh's sums) for one frame. */
	static final class Lightmap {
		final float ambient, skyFactor, blockFactor, nightVision, darknessScale, darkenWorld, brightness;
		final Vector3f skyColor;

		Lightmap(Minecraft mc, LocalPlayer p) {
			float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Camera camera = mc.gameRenderer.getMainCamera();
			skyColor = ARGB.vector3fFromRGB24(camera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partial));
			ambient = mc.level.dimensionType().ambientLight();
			skyFactor = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, partial);
			float darknessOption = mc.options.darknessEffectScale().get().floatValue();
			float darkness = p.getEffectBlendFactor(MobEffects.DARKNESS, partial) * darknessOption;
			darknessScale = Math.max(0f, Mth.cos((p.tickCount - partial) * (float) Math.PI * 0.025F) * 0.45F * darkness) * darknessOption;
			float waterVision = p.getWaterVision();
			nightVision = p.hasEffect(MobEffects.NIGHT_VISION) ? GameRenderer.getNightVisionScale(p, partial)
				: waterVision > 0f && p.hasEffect(MobEffects.CONDUIT_POWER) ? waterVision : 0f;
			// torchlight's flicker left out: 1.5 is where it rests
			blockFactor = 1.5f;
			darkenWorld = mc.gameRenderer.getDarkenWorldAmount(partial);
			brightness = Math.max(0f, mc.options.gamma().get().floatValue() - darkness);
		}

		private static float curve(float level) {
			return level / (4f - 3f * level);
		}

		Vector3f color(int block, int sky) {
			float b = curve(block / 15f) * blockFactor;
			float s = curve(sky / 15f) * skyFactor;
			Vector3f c = new Vector3f(b, b * ((b * 0.6f + 0.4f) * 0.6f + 0.4f), b * (b * b * 0.6f + 0.4f));
			c.lerp(new Vector3f(1f), ambient);
			c.add(new Vector3f(skyColor).mul(s));
			c.lerp(new Vector3f(0.75f), 0.04f);
			if (ambient == 0f) c.lerp(new Vector3f(c).mul(0.7f, 0.6f, 0.6f), darkenWorld);
			if (nightVision > 0f) {
				float max = Math.max(c.x, Math.max(c.y, c.z));
				if (max < 1f && max > 0f) c.lerp(new Vector3f(c).div(max), nightVision);
			}
			if (ambient == 0f) c.sub(darknessScale, darknessScale, darknessScale);
			c.set(Mth.clamp(c.x, 0f, 1f), Mth.clamp(c.y, 0f, 1f), Mth.clamp(c.z, 0f, 1f));
			float max = Math.max(c.x, Math.max(c.y, c.z));
			if (max > 0f) {
				float inv = 1f - max;
				float scaled = 1f - inv * inv * inv * inv;
				c.lerp(new Vector3f(c).mul(scaled / max), brightness);
			}
			c.lerp(new Vector3f(0.75f), 0.04f);
			return c;
		}
	}
}
