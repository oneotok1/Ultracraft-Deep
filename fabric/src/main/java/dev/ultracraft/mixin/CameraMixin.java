package dev.ultracraft.mixin;

import dev.ultracraft.Movement;
import dev.ultracraft.UkFrame;
import dev.ultracraft.UkLink;
import dev.ultracraft.Ultracraft;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's camera sits exactly where ULTRAKILL's camera was for the frame we're about to show (V1's eye and look,
 * every frame). Taking the newest frame here, before the world is drawn, and the view it was drawn from, keeps
 * Minecraft's world and ULTRAKILL's layer (enemies, effects, what terrain hides) locked together while turning.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow protected abstract void setPosition(double x, double y, double z);
	@Shadow protected abstract void setRotation(float yaw, float pitch);
	@Shadow @Final private Quaternionf rotation;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow private float xRot;
	@Shadow private float yRot;

	/**
	 * ULTRAKILL's camera leans as V1 strafes (and shakes, and turns with a slide); Minecraft's camera has no lean of its
	 * own, so without it the world (and its sky) stood straight under ULTRAKILL's leaning layer and slid apart from it.
	 * The same rotation Minecraft builds (yaw, then pitch), with the lean last, as ULTRAKILL applies it. ULTRAKILL's
	 * world is Minecraft's mirrored front to back, which turns the lean the other way: Minecraft's is the opposite of the
	 * roll the frame came with (measured: a point off to the side lands on the same pixel in both, to 0.0004).
	 */
	private void ultracraft$lean(float roll) {
		if (roll == 0f || Float.isNaN(roll)) return;
		float rad = (float) (Math.PI / 180.0);
		rotation.rotationYXZ((float) Math.PI - yRot * rad, -xRot * rad, roll * rad);
		forwards.set(0f, 0f, -1f).rotate(rotation);
		up.set(0f, 1f, 0f).rotate(rotation);
		left.set(-1f, 0f, 0f).rotate(rotation);
	}

	@Inject(method = "setup", at = @At("TAIL"))
	private void ultracraft$v1Camera(Level level, Entity entity, boolean detached, boolean mirror, float partial, CallbackInfo ci) {
		if (!Ultracraft.active && Ultracraft.steveView) {
			// Steve with ULTRAKILL's enemies about: ULTRAKILL draws from where Minecraft's camera would be, and Minecraft
			// draws its world from the view of the frame ULTRAKILL drew, so the two never slide apart
			Ultracraft.steveDrawn = false;
			// down in a boss fight: round the teammate we're watching, as the mouse turns it (Spectate)
			double[] orbit = dev.ultracraft.Spectate.pose(net.minecraft.client.Minecraft.getInstance(), partial);
			if (orbit != null) {
				setRotation((float) orbit[3], (float) orbit[4]);
				setPosition(orbit[0], orbit[1], orbit[2]);
			} else if (detached) {
				return;
			}
			Vec3 at = ((Camera) (Object) this).position();
			UkLink.send(String.format(java.util.Locale.ROOT, "STEVECAM %.4f %.4f %.4f %.3f %.3f %.2f", at.x, at.y, at.z,
				((Camera) (Object) this).yRot(), ((Camera) (Object) this).xRot(), Ultracraft.steveFov));
			UkFrame.updateForCamera();
			float[] f = UkFrame.framePose;
			if (f == null || !UkFrame.fresh()) return;
			setRotation(f[3], f[4]);
			setPosition(f[0], f[1], f[2]);
			Ultracraft.steveDrawn = true;
			return;
		}
		// asleep in a bed: Minecraft's own view from the pillow. In third person (F5) ULTRAKILL's camera moves out
		// behind V1 too (VIEW), so its frames still say where to draw from
		if (!Ultracraft.active || Movement.sleeping) return;
		UkFrame.updateForCamera();
		float[] f = UkFrame.framePose;
		if (f != null) {
			setRotation(f[3], f[4]);
			ultracraft$lean(-f[5]);
			setPosition(f[0], f[1], f[2]);
			return;
		}
		UkLink.Pose p = UkLink.pose;
		if (p == null || detached) return;
		setRotation(p.yaw, p.pitch);
		ultracraft$lean(-p.roll);
		setPosition(p.ex, p.ey, p.ez);
	}
}
