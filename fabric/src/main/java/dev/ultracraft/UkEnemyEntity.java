package dev.ultracraft;

import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;

/**
 * Minecraft's stand-in for one of ULTRAKILL's enemies (a Filth, a Maurice, anything the Spawner Arm makes): an
 * invisible, mindless hitbox that ULTRAKILL moves around and sizes to the enemy. Being a Mob and an {@link Enemy},
 * zombies, skeletons and creepers can go for it (see UltracraftCommon), iron and snow golems already do, and every
 * hit it takes goes back to ULTRAKILL as real damage to the real enemy.
 */
public class UkEnemyEntity extends PathfinderMob implements Enemy {
	private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.FLOAT);
	// for the other players' ULTRAKILLs, which show it as a puppet: what to spawn ("s:Filth", "b:v2"...), whose
	// ULTRAKILL runs it, which way it faces, what its animator is playing, and whether it just died
	private static final EntityDataAccessor<String> KEY = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> DEAD = SynchedEntityData.defineId(UkEnemyEntity.class, EntityDataSerializers.BOOLEAN);

	public int ukId;
	public String ukType = "";
	/** The player whose ULTRAKILL runs this enemy (server side). */
	public UUID owner;
	public long lastSeen = System.currentTimeMillis();
	private int deadTicks = -1;

	public UkEnemyEntity(EntityType<? extends UkEnemyEntity> type, Level level) {
		super(type, level);
		setNoAi(true);
		setNoGravity(true);
		setSilent(true);
		setPersistenceRequired();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(WIDTH, 1.0f);
		builder.define(HEIGHT, 2.0f);
		builder.define(KEY, "");
		builder.define(OWNER, "");
		builder.define(YAW, 0f);
		builder.define(ANIM, 0);
		builder.define(DEAD, false);
	}

	/** From the owner's ULTRAKILL (UKE): what it is, which way it faces, what it's playing. */
	public void setLook(String key, float yaw, int anim) {
		if (!key.equals(entityData.get(KEY))) entityData.set(KEY, key);
		if (Math.abs(entityData.get(YAW) - yaw) > 1f) entityData.set(YAW, yaw);
		if (entityData.get(ANIM) != anim) entityData.set(ANIM, anim);
	}

	public void setOwner(UUID id) {
		owner = id;
		entityData.set(OWNER, id.toString());
	}

	public String key() {
		return entityData.get(KEY);
	}

	public String ownerId() {
		return entityData.get(OWNER);
	}

	public float ukYaw() {
		return entityData.get(YAW);
	}

	public int anim() {
		return entityData.get(ANIM);
	}

	public boolean isDying() {
		return entityData.get(DEAD);
	}

	/** Its owner's ULTRAKILL says it died: the puppets die too, then the stand-in goes. */
	public void died() {
		if (deadTicks >= 0) return;
		entityData.set(DEAD, true);
		deadTicks = 0;
	}

	public void setSize(float w, float h) {
		if (Math.abs(entityData.get(WIDTH) - w) > 0.05f || Math.abs(entityData.get(HEIGHT) - h) > 0.05f) {
			entityData.set(WIDTH, w);
			entityData.set(HEIGHT, h);
			refreshDimensions();
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		// called once from Entity's constructor, before the synced data exists
		if (entityData == null) return EntityDimensions.scalable(1.0f, 2.0f);
		return EntityDimensions.scalable(entityData.get(WIDTH), entityData.get(HEIGHT));
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (WIDTH.equals(key) || HEIGHT.equals(key)) refreshDimensions();
	}

	/**
	 * Hits from mobs, arrows, blasts or V1's own sword go to ULTRAKILL, and so does Minecraft's burning (lava, fire,
	 * magma) and its cactus, berry bushes and lightning; the stand-in itself never loses health. Falls, drowning and
	 * walls are ULTRAKILL's own business. Minecraft's invulnerability frames apply, as for any mob.
	 */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount);
		Entity attacker = source.getEntity();
		boolean fire = source.is(DamageTypeTags.IS_FIRE);
		boolean world = fire || source.is(DamageTypeTags.IS_LIGHTNING) || source.is(DamageTypes.CACTUS) || source.is(DamageTypes.SWEET_BERRY_BUSH);
		if (attacker == null && !world && !source.is(DamageTypeTags.IS_EXPLOSION)) return false;
		// ULTRAKILL's enemies hurting each other happens inside ULTRAKILL
		if (attacker instanceof UkEnemyEntity) return false;
		// Minecraft's rule: within half a second of a hit, only a harder one gets through (by the difference)
		float dealt = amount;
		if (invulnerableTime > 10) {
			if (amount <= hurtBefore) return false;
			dealt = amount - hurtBefore;
		} else {
			invulnerableTime = 20;
		}
		hurtBefore = amount;
		// (another player's V1: which one, so the enemy can turn on them)
		String by = attacker == null ? "-1" : UcNet.isV1(attacker) ? (attacker.getUUID().equals(owner) ? "V1" : "V1:" + attacker.getId()) : Integer.toString(attacker.getId());
		if (owner != null && level.getServer() != null) {
			var sp = level.getServer().getPlayerList().getPlayer(owner);
			if (sp != null) UcNet.send(sp, "EHURT " + ukId + " " + dealt + " " + by + (fire ? " fire" : ""));
		}
		return true;
	}

	private float hurtBefore;

	@Override
	public void tick() {
		super.tick();
		// ULTRAKILL stopped reporting it (dead, despawned, or ULTRAKILL went away)
		if (level().isClientSide()) return;
		if (deadTicks >= 0 && ++deadTicks > 20) discard();
		else if (deadTicks < 0 && System.currentTimeMillis() - lastSeen > 2000) discard();
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void pushEntities() {
	}

	@Override
	protected void doPush(Entity entity) {
	}
}
