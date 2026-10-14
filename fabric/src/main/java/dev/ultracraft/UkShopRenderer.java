package dev.ultracraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's drawing of a placed shop: ULTRAKILL's terminal with its screen dark. As V1, ULTRAKILL draws the real
 * one (its screen on as V1 walks up) in the same spot, so Minecraft's stays out of the way within the range ULTRAKILL is
 * told about.
 */
public final class UkShopRenderer implements BlockEntityRenderer<UkShopBlockEntity, UkShopRenderer.State> {
	public static final class State extends BlockEntityRenderState {
		Direction facing = Direction.NORTH;
	}

	public UkShopRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(UkShopBlockEntity be, State state, float partial, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
		BlockEntityRenderer.super.extractRenderState(be, state, partial, camera, crumbling);
		state.facing = be.getBlockState().getValue(UkShopBlock.FACING);
		// the light in front of the screen (the block itself is inside the terminal)
		if (be.getLevel() != null) state.lightCoords = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(state.facing));
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (Ultracraft.active && UkLink.connected && camera.pos.distanceToSqr(Vec3.atCenterOf(state.blockPos)) < ShopExport.RANGE * ShopExport.RANGE) return;
		if (!ShopMesh.load()) return;
		Direction f = state.facing, r = UkShopBlock.right(f);
		int light = state.lightCoords;
		poseStack.pushPose();
		// the middle of the 2 x 1 floor, turned from north to where the screen faces
		poseStack.translate(0.5 + 0.5 * r.getStepX(), 0.0, 0.5 + 0.5 * r.getStepZ());
		poseStack.mulPose(Axis.YP.rotationDegrees(180f - f.toYRot()));
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(ShopMesh.texture()), (pose, vc) -> ShopMesh.emit(pose, vc, light));
		// its screen and lights glow in the dark
		Identifier glow = ShopMesh.glow();
		if (glow != null) collector.submitCustomGeometry(poseStack, RenderTypes.eyes(glow), (pose, vc) -> ShopMesh.emit(pose, vc, LightTexture.FULL_BRIGHT));
		poseStack.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		// it stands a block beyond its own: drawn even when that one block is out of view
		return true;
	}
}
