package dev.ultracraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * The "Minecraft Mobs" setting: with it off, Minecraft's monsters don't spawn (naturally, from spawners, eggs or
 * raids) and the ones already about are gone. Bosses are the exception: the Ender Dragon, the Wither, Elder Guardians
 * and the Warden always come, and so does anything with a name tag.
 */
public final class MobRules {
	private MobRules() {}

	public static boolean blocked(Entity e) {
		if (UltracraftConfig.mcMobs) return false;
		if (!(e instanceof Mob) || !(e instanceof Enemy) || e instanceof UkEnemyEntity) return false;
		if (e instanceof EnderDragon || e instanceof WitherBoss || e instanceof ElderGuardian || e instanceof Warden) return false;
		return !e.hasCustomName();
	}

	/** Every second: monsters that came with their chunk (saved before the setting went off) go too. */
	static void sweep(ServerLevel level) {
		if (UltracraftConfig.mcMobs) return;
		for (Entity e : level.getAllEntities()) {
			if (blocked(e)) e.discard();
		}
	}
}
