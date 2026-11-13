package dev.ultracraft.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own death protection, reachable from outside: it is private and only ever called from the tail of
 * {@code hurt}, which V1's damage never reaches while his health lives in ULTRAKILL. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
	@Invoker("checkTotemDeathProtection")
	boolean ultracraft$checkTotemDeathProtection(DamageSource source);
}
