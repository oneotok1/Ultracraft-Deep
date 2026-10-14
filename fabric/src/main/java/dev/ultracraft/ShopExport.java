package dev.ultracraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Where the shops stand around V1 (SHOPS id,x,y,z,fx,fz;...: the middle of each one's floor and the way its screen
 * faces), so ULTRAKILL puts its real shop there; and where their light comes from.
 */
final class ShopExport {
	/** How far from V1 ULTRAKILL stands shops (blocks); further out Minecraft draws them itself. */
	static final double RANGE = 48.0;
	private static String last = "";
	private static long lastAt;
	private static volatile List<Lighting.Spot> spots = List.of();

	private ShopExport() {}

	static List<Lighting.Spot> spots() {
		return spots;
	}

	static void reset() {
		last = "";
	}

	static void export(ClientLevel level, BlockPos center) {
		StringBuilder sb = new StringBuilder("SHOPS ");
		List<Lighting.Spot> lights = new ArrayList<>();
		int r = (int) Math.ceil(RANGE / 16.0);
		int cx = center.getX() >> 4, cz = center.getZ() >> 4;
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				if (!level.hasChunk(x, z)) continue;
				LevelChunk chunk = level.getChunk(x, z);
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (!(be instanceof UkShopBlockEntity)) continue;
					BlockPos p = be.getBlockPos();
					if (p.distSqr(center) > RANGE * RANGE) continue;
					Direction f = be.getBlockState().getValue(UkShopBlock.FACING), right = UkShopBlock.right(f);
					double fx = p.getX() + 0.5 + 0.5 * right.getStepX(), fz = p.getZ() + 0.5 + 0.5 * right.getStepZ();
					long id = p.asLong();
					sb.append(String.format(Locale.ROOT, "%d,%.2f,%d,%.2f,%d,%d;", id, fx, p.getY(), fz, f.getStepX(), f.getStepZ()));
					lights.add(new Lighting.Spot("s" + id, fx + f.getStepX(), p.getY() + 1.0, fz + f.getStepZ()));
				}
			}
		}
		spots = lights;
		String s = sb.toString();
		long now = System.currentTimeMillis();
		if (s.equals(last) && now - lastAt < 5000) return;
		last = s;
		lastAt = now;
		UkLink.send(s);
	}
}
