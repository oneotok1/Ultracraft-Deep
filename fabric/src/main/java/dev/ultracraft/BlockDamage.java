package dev.ultracraft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL weapon hits on Minecraft blocks (server thread). Damage adds up per block and shows as the mining crack
 * overlay; when it reaches the block's health (from its hardness) the block breaks and drops as if mined. Cracks that
 * aren't hit again heal after a while. Unbreakable blocks (bedrock, barriers) shrug everything off.
 */
final class BlockDamage {
	/** ULTRAKILL damage per point of hardness: stone (1.5) takes 3 revolver shots, dirt 1, obsidian 100. */
	private static final float HP_PER_HARDNESS = 2f;
	private static final long HEAL_MS = 8000;

	private static final class Hurt {
		final int breakerId;
		float damage;
		long last;

		Hurt(int breakerId) {
			this.breakerId = breakerId;
		}
	}

	private static final Map<BlockPos, Hurt> HURT = new HashMap<>();
	/** Crack overlays are keyed by a "breaker" id; keep ours far away from real entity ids. */
	private static int nextBreaker = 0x40000000;

	private BlockDamage() {}

	/** A weapon hit at point (Minecraft coordinates) travelling along dir; radius > 0 spreads it (heavy impacts). */
	static void hit(ServerLevel level, ServerPlayer by, Vec3 point, Vec3 dir, float damage, float radius) {
		heal(level);
		BlockPos center = find(level, point, dir);
		if (center == null) return;
		if (radius <= 0f) {
			damage(level, by, center, point, damage);
			return;
		}
		int r = (int) Math.ceil(radius);
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					// the blocks around get most of the blow (a block's edge neighbours too), so they break alongside the
					// one struck instead of only ever cracking
					if (d > radius + 0.5) continue;
					BlockPos p = center.offset(dx, dy, dz);
					damage(level, by, p, Vec3.atCenterOf(p), damage * (float) Math.max(0.4, 1.0 - 0.35 * d / Math.max(radius, 0.5)));
				}
			}
		}
	}

	/**
	 * Grass, flowers, vines, torches, crops: nothing ULTRAKILL can hit (they have no collision, so no collider), so the
	 * shot's path is traced back from where it landed and every soft thing it passed through is cut down and drops.
	 */
	static void clearPlants(ServerLevel level, ServerPlayer by, Vec3 hit, Vec3 aim, double back) {
		BlockPos last = null;
		for (double t = 0; t <= back; t += 0.2) {
			Vec3 p = hit.subtract(aim.scale(t));
			BlockPos bp = BlockPos.containing(p);
			if (bp.equals(last)) continue;
			last = bp;
			BlockState s = level.getBlockState(bp);
			if (s.isAir() || s.getBlock() instanceof LiquidBlock || !s.getCollisionShape(level, bp).isEmpty()) continue;
			float hardness = s.getDestroySpeed(level, bp);
			if (hardness < 0f || hardness > 0.5f) continue;
			var shape = s.getShape(level, bp);
			// only what the shot actually went through (a vine is a thin sheet on its wall)
			if (shape.isEmpty() || !shape.bounds().move(bp).inflate(0.25).contains(p)) continue;
			level.destroyBlock(bp, true, by);
		}
	}

	/** What the Electric railcannon does to each block along its tunnel: stone, ores, wood and deepslate go at once. */
	private static final float RAIL_DAMAGE = 40f;
	/** Like a blast, only some of what the tunnel breaks drops (and the world isn't buried in items). */
	private static final float RAIL_DROP_CHANCE = 0.3f;

	/**
	 * The Electric railcannon: everything within radius of the beam's line, from where it hit for length blocks, is
	 * blasted out (only the hardest blocks crack instead), with smoke puffs along the way.
	 */
	static void tunnel(ServerLevel level, ServerPlayer by, Vec3 start, Vec3 dir, float length, float radius) {
		heal(level);
		int r = (int) Math.ceil(radius);
		Set<BlockPos> seen = new HashSet<>();
		int broken = 0;
		for (double t = 0; t <= length && broken < 900; t += 0.5) {
			Vec3 c = start.add(dir.scale(t));
			BlockPos center = BlockPos.containing(c);
			for (int dx = -r; dx <= r; dx++) {
				for (int dy = -r; dy <= r; dy++) {
					for (int dz = -r; dz <= r; dz++) {
						BlockPos p = center.offset(dx, dy, dz);
						if (!seen.add(p)) continue;
						Vec3 rel = Vec3.atCenterOf(p).subtract(start);
						double along = rel.dot(dir);
						if (along < -0.5 || along > length) continue;
						if (rel.subtract(dir.scale(along)).lengthSqr() > radius * radius) continue;
						if (smash(level, by, p, RAIL_DAMAGE)) broken++;
					}
				}
			}
			if (((int) (t * 2)) % 6 == 0) level.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		}
	}

	/** Blast a block out like an explosion would (quietly, some drops), or crack it if it's too hard. */
	private static boolean smash(ServerLevel level, ServerPlayer by, BlockPos pos, float damage) {
		BlockState s = level.getBlockState(pos);
		if (s.isAir() || s.getBlock() instanceof LiquidBlock || s.is(UltracraftCommon.UK_SHOP)) return false;
		float hardness = s.getDestroySpeed(level, pos);
		if (hardness < 0f) return false;
		float health = Math.max(0.25f, hardness * HP_PER_HARDNESS);
		Hurt h = HURT.get(pos);
		float total = damage + (h != null ? h.damage : 0f);
		if (total < health) {
			Hurt hh = HURT.computeIfAbsent(pos.immutable(), k -> new Hurt(nextBreaker++));
			hh.damage = total;
			hh.last = System.currentTimeMillis();
			level.destroyBlockProgress(hh.breakerId, pos, Math.min(9, (int) (total / health * 10f)));
			return false;
		}
		if (h != null) {
			level.destroyBlockProgress(h.breakerId, pos, -1);
			HURT.remove(pos);
		}
		if (level.random.nextFloat() < RAIL_DROP_CHANCE) {
			BlockEntity be = s.hasBlockEntity() ? level.getBlockEntity(pos) : null;
			Block.dropResources(s, level, pos, be, by, ItemStack.EMPTY);
		}
		level.removeBlock(pos, false);
		return true;
	}

	/** The block the hit went into: step a little way along the hit direction from the surface point. */
	private static BlockPos find(ServerLevel level, Vec3 point, Vec3 dir) {
		for (int i = -1; i <= 15; i++) {
			BlockPos p = BlockPos.containing(point.add(dir.scale(i * 0.05)));
			if (!level.getBlockState(p).isAir()) return p;
		}
		return null;
	}

	private static void damage(ServerLevel level, ServerPlayer by, BlockPos pos, Vec3 at, float damage) {
		if (damage <= 0f) return;
		BlockState s = level.getBlockState(pos);
		if (s.isAir() || s.getCollisionShape(level, pos).isEmpty()) return;
		// ULTRAKILL's shops take no damage from V1's guns (as in ULTRAKILL)
		if (s.is(UltracraftCommon.UK_SHOP)) return;
		float hardness = s.getDestroySpeed(level, pos);
		if (hardness < 0f) return;
		float health = Math.max(0.25f, hardness * HP_PER_HARDNESS);
		Hurt h = HURT.computeIfAbsent(pos.immutable(), k -> new Hurt(nextBreaker++));
		h.damage += damage;
		h.last = System.currentTimeMillis();
		// the block's own hit sound and crumbs where it was struck
		level.playSound(null, pos, s.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.7f, 0.75f + level.random.nextFloat() * 0.3f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), at.x, at.y, at.z, 6, 0.1, 0.1, 0.1, 0.1);
		if (h.damage >= health) {
			level.destroyBlockProgress(h.breakerId, pos, -1);
			HURT.remove(pos);
			level.destroyBlock(pos, true, by);
		} else {
			level.destroyBlockProgress(h.breakerId, pos, Math.min(9, (int) (h.damage / health * 10f)));
		}
	}

	/** Cracks nobody kept hitting close up again. */
	static void heal(ServerLevel level) {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<BlockPos, Hurt>> it = HURT.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Hurt> e = it.next();
			Hurt h = e.getValue();
			if (now - h.last > HEAL_MS || level.getBlockState(e.getKey()).isAir()) {
				level.destroyBlockProgress(h.breakerId, e.getKey(), -1);
				it.remove();
			}
		}
	}
}
