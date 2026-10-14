package dev.ultracraft.mixin;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's fullscreen (F11, the video setting) is windowed fullscreen: a borderless window covering the monitor
 * it's on, so ULTRAKILL, alt-tab and overlays behave, and the taskbar stays out of the way. The window is one pixel
 * taller than the monitor: Windows hides the taskbar for any window covering the whole screen, but a window exactly
 * the monitor's size gets switched to exclusive fullscreen by the driver.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Shadow private boolean fullscreen;
	@Shadow private int x;
	@Shadow private int y;
	@Shadow private int width;
	@Shadow private int height;
	@Shadow private int windowedX;
	@Shadow private int windowedY;
	@Shadow private int windowedWidth;
	@Shadow private int windowedHeight;
	@Shadow @Final private long handle;
	@Shadow @Final private ScreenManager screenManager;

	@Unique private boolean ultracraft$borderless;

	@Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
	private void ultracraft$borderlessFullscreen(CallbackInfo ci) {
		ci.cancel();
		Monitor monitor = fullscreen ? screenManager.findBestMonitor((Window) (Object) this) : null;
		if (fullscreen && monitor != null) {
			if (!ultracraft$borderless && GLFW.glfwGetWindowMonitor(handle) == 0L) {
				windowedX = x;
				windowedY = y;
				windowedWidth = width;
				windowedHeight = height;
			}
			VideoMode mode = monitor.getCurrentMode();
			x = monitor.getX();
			y = monitor.getY();
			width = mode.getWidth();
			height = mode.getHeight() + 1;
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowMonitor(handle, 0L, x, y, width, height, GLFW.GLFW_DONT_CARE);
			ultracraft$borderless = true;
		} else {
			fullscreen = false;
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			if (ultracraft$borderless || GLFW.glfwGetWindowMonitor(handle) != 0L) {
				x = windowedX;
				y = windowedY;
				width = windowedWidth;
				height = windowedHeight;
				GLFW.glfwSetWindowMonitor(handle, 0L, x, y, width, height, GLFW.GLFW_DONT_CARE);
			}
			ultracraft$borderless = false;
		}
	}
}
