package dev.ultracraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

/**
 * V1's right arm in first person while it holds Minecraft things: an armoured forearm in V1's blue, black joints and
 * a dark steel hand, drawn the way Minecraft draws a held item's arm (same swing, equip and use animations, same
 * light), so the item sits in V1's hand instead of floating.
 *
 * <p>The pose stack arrives at the held item's anchor: x right, y up, z toward the eye. The hand sits on the item's
 * grip (where a sword's handle is held) and the forearm runs off toward the lower right corner of the view.
 */
public final class V1Arm {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("ultracraft", "textures/entity/v1_arm.png");
	// texture regions, 16 px each along a 64x16 strip
	private static final int PLATE = 0, STEEL = 1, JOINT = 2, EDGE = 3;

	/** Where the hand sits from the held item's anchor, and how the arm leaves it (degrees). */
	static float handX = 0.07f, handY = -0.03f, handZ = -0.01f, tiltZ = 75f, tiltX = 0f, roll = -15f;
	/** Minecraft holds items from just below the screen's edge; with V1's hand on them, they come up this far. */
	public static final float LIFT_X = -0.02f, LIFT_Y = 0.12f;

	private V1Arm() {}

	/** Draw the arm with its hand at the pose's origin (a fist when nothing is held); mirrored for a left main hand. */
	public static void render(PoseStack poseStack, SubmitNodeCollector collector, int light, HumanoidArm arm, boolean fist) {
		// ULTRAKILL's own arm, once ULTRAKILL has written it out (V1ArmMesh); this blocky one till then
		if (V1ArmMesh.ready()) {
			V1ArmMesh.render(poseStack, collector, light, arm);
			return;
		}
		poseStack.pushPose();
		if (arm == HumanoidArm.LEFT) poseStack.scale(-1f, 1f, 1f);
		poseStack.translate(handX, handY, handZ);
		poseStack.mulPose(Axis.ZP.rotationDegrees(tiltZ));
		poseStack.mulPose(Axis.XP.rotationDegrees(tiltX));
		poseStack.mulPose(Axis.YP.rotationDegrees(roll));
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(TEXTURE), (pose, vc) -> {
			// the hand: a closed grip (or fist), knuckle plate on its back
			box(vc, pose, light, -0.058f, -0.07f, -0.06f, 0.058f, 0.06f, 0.06f, STEEL);
			box(vc, pose, light, -0.05f, -0.035f, 0.06f, 0.05f, 0.05f, 0.072f, fist ? EDGE : PLATE);
			// the thumb along the grip
			box(vc, pose, light, -0.075f, -0.04f, -0.02f, -0.058f, 0.035f, 0.04f, STEEL);
			// wrist joint
			box(vc, pose, light, -0.045f, -0.12f, -0.045f, 0.045f, -0.07f, 0.045f, JOINT);
			// forearm armour, with a raised edge plate on the outside and a cuff toward the hand
			box(vc, pose, light, -0.066f, -0.64f, -0.066f, 0.066f, -0.12f, 0.066f, PLATE);
			box(vc, pose, light, -0.072f, -0.2f, -0.072f, 0.072f, -0.12f, 0.072f, EDGE);
			box(vc, pose, light, 0.066f, -0.56f, -0.03f, 0.08f, -0.22f, 0.03f, EDGE);
			// elbow and the start of the upper arm (mostly out of view)
			box(vc, pose, light, -0.052f, -0.72f, -0.052f, 0.052f, -0.64f, 0.052f, JOINT);
			box(vc, pose, light, -0.07f, -1.2f, -0.07f, 0.07f, -0.72f, 0.07f, PLATE);
		});
		poseStack.popPose();
	}

	/** An axis-aligned box, every face showing the given 16 px texture region. */
	private static void box(VertexConsumer vc, PoseStack.Pose pose, int light, float x0, float y0, float z0, float x1, float y1, float z1, int region) {
		float u0 = region * 16 / 64f, u1 = (region * 16 + 16) / 64f;
		// +x, -x, +y, -y, +z, -z
		quad(vc, pose, light, u0, u1, 1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
		quad(vc, pose, light, u0, u1, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(vc, pose, light, u0, u1, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
		quad(vc, pose, light, u0, u1, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		quad(vc, pose, light, u0, u1, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(vc, pose, light, u0, u1, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose, int light, float u0, float u1, float nx, float ny, float nz,
		float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
		vertex(vc, pose, light, ax, ay, az, u0, 1f, nx, ny, nz);
		vertex(vc, pose, light, bx, by, bz, u1, 1f, nx, ny, nz);
		vertex(vc, pose, light, cx, cy, cz, u1, 0f, nx, ny, nz);
		vertex(vc, pose, light, dx, dy, dz, u0, 0f, nx, ny, nz);
	}

	private static void vertex(VertexConsumer vc, PoseStack.Pose pose, int light, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		vc.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
	}
}
