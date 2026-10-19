package dev.ultracraft;

import dev.ultracraft.mixin.MobAccessor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * The server half: ULTRAKILL's enemies as entities Minecraft's mobs can fight, and Minecraft's mobs bleeding and
 * dying in ULTRAKILL's blood. Runs on the integrated server's thread.
 */
public final class UltracraftCommon implements ModInitializer {
	public static final ResourceKey<EntityType<?>> UK_ENEMY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("ultracraft", "uk_enemy"));
	public static final EntityType<UkEnemyEntity> UK_ENEMY = Registry.register(BuiltInRegistries.ENTITY_TYPE, UK_ENEMY_KEY,
		EntityType.Builder.<UkEnemyEntity>of(UkEnemyEntity::new, MobCategory.MISC).noLootTable().noSave().noSummon()
			.sized(1.0f, 2.0f).clientTrackingRange(8).updateInterval(1).build(UK_ENEMY_KEY));


	/**
	 * ULTRAKILL's shop, crafted and placed (two blocks wide, three high). Like ULTRAKILL's own shops it shrugs off blasts
	 * (and V1's guns, see BlockDamage); a pickaxe takes it down.
	 */
	public static final ResourceKey<Block> UK_SHOP_KEY = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("ultracraft", "uk_shop"));
	public static final Block UK_SHOP = Registry.register(BuiltInRegistries.BLOCK, UK_SHOP_KEY, new UkShopBlock(BlockBehaviour.Properties.of()
		.setId(UK_SHOP_KEY).mapColor(MapColor.METAL).strength(3.5f, 1200.0f).requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion()
		.lightLevel(s -> s.getValue(UkShopBlock.PART) >= 2 ? 4 : 0).pushReaction(PushReaction.BLOCK)));
	public static final ResourceKey<Item> UK_SHOP_ITEM_KEY = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("ultracraft", "uk_shop"));
	public static final Item UK_SHOP_ITEM = registerShopItem();
	public static final BlockEntityType<UkShopBlockEntity> UK_SHOP_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Identifier.fromNamespaceAndPath("ultracraft", "uk_shop"), FabricBlockEntityTypeBuilder.create(UkShopBlockEntity::new, UK_SHOP).build());

	private static Item registerShopItem() {
		BlockItem item = new BlockItem(UK_SHOP, new Item.Properties().setId(UK_SHOP_ITEM_KEY).useBlockDescriptionPrefix());
		item.registerBlocks(Item.BY_BLOCK, item);
		return Registry.register(BuiltInRegistries.ITEM, UK_SHOP_ITEM_KEY, item);
	}

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(UK_ENEMY, Mob.createMobAttributes());
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> entries.accept(UK_SHOP_ITEM));
		// the Cyber Grind's arenas can't be dug into (or their shop taken away), except in creative
		net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) ->
			level.dimension() != GrindArenas.DIMENSION || player.isCreative());
		// hostile mobs (zombies, skeletons, spiders, creepers...) also go for ULTRAKILL's enemies, as they would for a
		// player; golems already attack anything that's an Enemy
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Mob mob && entity instanceof Enemy && !(entity instanceof UkEnemyEntity)) {
				((MobAccessor) mob).ultracraft$targetSelector().addGoal(2, new NearestAttackableTargetGoal<>(mob, UkEnemyEntity.class, true));
			}
		});
		// blood: mobs hurt by anything but V1 (V1's own hits already bleed in ULTRAKILL), and every death near V1
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (!UcNet.anyV1() || taken <= 0f || !bleeds(entity)) return;
			Entity by = source.getEntity();
			if (UcNet.isV1(by) || by instanceof UkEnemyEntity) return;
			UcNet.sendNear(entity, 80, String.format(Locale.ROOT, "BLEED %d %.2f", entity.getId(), taken));
		});
		// every V1's spawns, bosses and Cyber Grind (and "Minecraft Mobs" off: monsters that came back with their chunks go)
		ServerTickEvents.END_SERVER_TICK.register(ServerOps::tick);
		// the Cyber Grind's columns sliding between patterns
		ServerTickEvents.END_SERVER_TICK.register(GrindArenas::tick);
		// a won boss arena's countdown back
		ServerTickEvents.END_SERVER_TICK.register(BossArenas::tick);
		Arenas.register();
		UcNet.registerCommon();
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> UkCommands.register(dispatcher));
		// a world closing mid-warning or mid-fight: nothing of it carries into the next world
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			if (UkBosses.busy()) UkLink.send("BOSSCLEAR");
			UkBosses.reset();
			CyberGrind.reset();
			ServerOps.reset();
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			// Minecraft's monsters V1 kills pay P on top of the style they earn: more the tougher they are (a zombie or
			// skeleton 300, an enderman 500, at most 2,000)
			if (entity instanceof Enemy && !(entity instanceof UkEnemyEntity) && source.getEntity() instanceof ServerPlayer killer && UcNet.isV1(killer)) {
				int p = Math.max(100, Math.min(2000, 100 + Math.round(entity.getMaxHealth() * 10f)));
				UkProgress.get(killer).award(p);
			}
			// a mob V1 kills pays style whichever way it died: a blast that goes off in Minecraft hurts every mob in its
			// reach without a damage message being sent, so the deaths that never went through the DMG channel were
			// paying nothing at all
			if (UcNet.anyV1() && bleeds(entity)) {
				ServerPlayer killer = v1Behind(source);
				if (killer != null && entity.level() instanceof ServerLevel sl) {
					UcNet.send(killer, "KILL " + entity.getId());
					float worth = StyleRewards.kill(killer, entity.position().add(0, entity.getBbHeight() * 0.5, 0));
					if (worth > 1f) net.minecraft.world.entity.ExperienceOrb.award(sl, entity.position(), Math.round(5f * (worth - 1f)) + 2);
				}
			}
			if (!UcNet.anyV1() || !bleeds(entity)) return;
			UcNet.sendNear(entity, 80, String.format(Locale.ROOT, "DIED %d %.3f %.3f %.3f %.3f %.3f", entity.getId(), entity.getX(), entity.getY(), entity.getZ(), entity.getBbWidth(), entity.getBbHeight()));
		});
	}

	/** The V1 behind a death: whoever swung, whose arrow it was, or whose explosion went off in Minecraft. */
	private static ServerPlayer v1Behind(net.minecraft.world.damagesource.DamageSource source) {
		Entity by = source.getEntity();
		if (by instanceof ServerPlayer sp) return UcNet.isV1(sp) ? sp : null;
		if (by instanceof net.minecraft.world.entity.projectile.Projectile pr && pr.getOwner() instanceof ServerPlayer sp) return UcNet.isV1(sp) ? sp : null;
		return null;
	}

	/** Mobs bleed ULTRAKILL's blood; players and ULTRAKILL's own enemies (who have their own) don't. */
	private static boolean bleeds(LivingEntity e) {
		return !(e instanceof Player) && !(e instanceof UkEnemyEntity);
	}

	/**
	 * UKE id,type,x,y,z,w,h,hp[,key,yaw,anim];... from one player's ULTRAKILL: where its enemies are now; their
	 * stand-ins appear, follow, and go (one that just died stays a moment, so the other players see it die).
	 */
	static void updateUkEnemies(ServerPlayer owner, String data) {
		ServerLevel level = owner.level();
		Map<Integer, UkEnemyEntity> mine = ServerOps.state(owner).enemies;
		long now = System.currentTimeMillis();
		Set<Integer> seen = new HashSet<>();
		for (String rec : data.split(";")) {
			if (rec.isEmpty()) continue;
			String[] a = rec.split(",");
			if (a.length < 7) continue;
			int id = Integer.parseInt(a[0]);
			double x = Double.parseDouble(a[2]), y = Double.parseDouble(a[3]), z = Double.parseDouble(a[4]);
			float w = Float.parseFloat(a[5]), h = Float.parseFloat(a[6]);
			seen.add(id);
			UkEnemyEntity e = mine.get(id);
			if (e == null || e.isRemoved() || e.level() != level) {
				if (e != null && !e.isRemoved()) e.discard();
				e = new UkEnemyEntity(UK_ENEMY, level);
				e.ukId = id;
				e.ukType = a[1];
				e.setOwner(owner.getUUID());
				e.setSize(w, h);
				e.setPos(x, y, z);
				e.lastSeen = now;
				level.addFreshEntity(e);
				mine.put(id, e);
			} else {
				e.setSize(w, h);
				e.setPos(x, y, z);
				e.lastSeen = now;
			}
			if (a.length > 10) e.setLook(a[8], Float.parseFloat(a[9]), Integer.parseInt(a[10]));
		}
		Iterator<Map.Entry<Integer, UkEnemyEntity>> it = mine.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, UkEnemyEntity> en = it.next();
			UkEnemyEntity e = en.getValue();
			if (seen.contains(en.getKey())) continue;
			if (!e.isDying()) e.discard();
			it.remove();
		}
	}

	/** Every stand-in of every player's ULTRAKILL (server thread). */
	static java.util.List<UkEnemyEntity> allUkEnemies() {
		java.util.List<UkEnemyEntity> all = new java.util.ArrayList<>();
		for (ServerOps.State st : ServerOps.statesView()) all.addAll(st.enemies.values());
		return all;
	}
}
