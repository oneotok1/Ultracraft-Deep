package dev.ultracraft.mixin;

import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A parried ghast fireball comes back with a bigger blast. */
@Mixin(LargeFireball.class)
public interface LargeFireballAccessor {
	@Accessor("explosionPower")
	int ultracraft$getExplosionPower();

	@Accessor("explosionPower")
	void ultracraft$setExplosionPower(int power);
}
