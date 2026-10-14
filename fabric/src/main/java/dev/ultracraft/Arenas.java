package dev.ultracraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * ULTRAKILL arenas in Minecraft's world: round arenas themed after its layers of Hell (Limbo's blue stone and sea
 * lanterns on the plains, Greed's sandstone and gold in the deserts, Heresy's blackstone and soul fire in the Nether,
 * a Prime Sanctum of obsidian and gold in the End...), generated as structures in new chunks (the ULTRAKILL Arenas
 * setting; /locate structure finds them, #ultracraft:arenas). Each has a boss waiting: step inside the ring as V1 and
 * it comes, with the layer's song (Boss Themes); beat it and a chest of loot appears on the dais, and the arena stays
 * cleared (saved with the world). Server thread.
 */
final class Arenas {
	/**
	 * One of ULTRAKILL's layers as an arena: its name, its boss (or one of two), how deep it is (the loot grows), and its
	 * blocks: floor, its pattern, wall, wall top, pillars, lights, foundation, dais, windows (null: none).
	 */
	record Layer(String key, String title, String boss, String altBoss, int depth, Block floor, Block accent, Block wall, Block top, Block pillar,
			Block light, Block base, Block dais, Block window) {}

	static final List<Layer> LAYERS = List.of(
		new Layer("prelude", "PRELUDE", "cerberus", "swordsmachine", 0, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.MAGMA_BLOCK, Blocks.BLACKSTONE,
			Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.POLISHED_BASALT, Blocks.SHROOMLIGHT, Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE, null),
		new Layer("limbo", "LIMBO", "v2", "swordsmachine", 1, Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
			Blocks.QUARTZ_PILLAR, Blocks.SEA_LANTERN, Blocks.STONE, Blocks.QUARTZ_BLOCK, Blocks.LIGHT_BLUE_STAINED_GLASS),
		new Layer("lust", "LUST", "swordsmachine", "mindflayer", 2, Blocks.RED_NETHER_BRICKS, Blocks.CRYING_OBSIDIAN, Blocks.NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
			Blocks.PURPUR_PILLAR, Blocks.PEARLESCENT_FROGLIGHT, Blocks.NETHER_BRICKS, Blocks.CRYING_OBSIDIAN, Blocks.PURPLE_STAINED_GLASS),
		new Layer("gluttony", "GLUTTONY", "hideousmass", "gabriel", 3, Blocks.NETHER_WART_BLOCK, Blocks.BONE_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
			Blocks.BONE_BLOCK, Blocks.SHROOMLIGHT, Blocks.NETHER_WART_BLOCK, Blocks.BONE_BLOCK, null),
		new Layer("greed", "GREED", "v2green", "insurrectionist", 4, Blocks.CUT_SANDSTONE, Blocks.ORANGE_TERRACOTTA, Blocks.SMOOTH_SANDSTONE,
			Blocks.CHISELED_SANDSTONE, Blocks.SANDSTONE, Blocks.OCHRE_FROGLIGHT, Blocks.SANDSTONE, Blocks.GOLD_BLOCK, null),
		new Layer("wrath", "WRATH", "ferryman", "v2green", 5, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.PRISMARINE, Blocks.DARK_PRISMARINE,
			Blocks.PRISMARINE_BRICKS, Blocks.SEA_LANTERN, Blocks.PRISMARINE, Blocks.DARK_PRISMARINE, Blocks.CYAN_STAINED_GLASS),
		new Layer("heresy", "HERESY", "gabriel2", "gabriel", 6, Blocks.POLISHED_BLACKSTONE, Blocks.GILDED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
			Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.POLISHED_BASALT, Blocks.CRYING_OBSIDIAN, Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE, null),
		new Layer("violence", "VIOLENCE", "mindflayer", "v2green", 7, Blocks.POLISHED_DEEPSLATE, Blocks.WAXED_CUT_COPPER, Blocks.DEEPSLATE_TILES,
			Blocks.WAXED_CUT_COPPER, Blocks.IRON_BLOCK, Blocks.OCHRE_FROGLIGHT, Blocks.COBBLED_DEEPSLATE, Blocks.IRON_BLOCK, Blocks.IRON_BARS),
		new Layer("fraud", "FRAUD", "insurrectionist", "minosprime", 8, Blocks.WHITE_CONCRETE, Blocks.BLACK_CONCRETE, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_QUARTZ,
			Blocks.QUARTZ_PILLAR, Blocks.SEA_LANTERN, Blocks.SMOOTH_QUARTZ, Blocks.BLACK_CONCRETE, Blocks.TINTED_GLASS),
		new Layer("prime", "PRIME SANCTUM", "minosprime", "sisyphusprime", 9, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.OBSIDIAN, Blocks.GOLD_BLOCK,
			Blocks.PURPUR_PILLAR, Blocks.PEARLESCENT_FROGLIGHT, Blocks.END_STONE_BRICKS, Blocks.GOLD_BLOCK, null));

	private static final Map<String, Layer> BY_KEY = LAYERS.stream().collect(Collectors.toMap(Layer::key, Function.identity()));

	static final StructureType<ArenaStructure> TYPE = () -> ArenaStructure.CODEC;
	static final StructurePieceType PIECE = (StructurePieceType.ContextlessType) ArenaPiece::new;
	static final TagKey<Structure> ARENAS = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath("ultracraft", "arenas"));

	private Arenas() {}

	/** At startup: the structure and its piece. */
	static void register() {
		Registry.register(BuiltInRegistries.STRUCTURE_TYPE, Identifier.fromNamespaceAndPath("ultracraft", "arena"), TYPE);
		Registry.register(BuiltInRegistries.STRUCTURE_PIECE, Identifier.fromNamespaceAndPath("ultracraft", "arena_piece"), PIECE);
	}

	static Layer layer(String key) {
		return BY_KEY.get(key);
	}

	// ------------------------------------------------------------------ the boss waiting inside

	/** Every second while V1 is active: an arena here, and whether its boss comes. */
	static void tick(ServerPlayer sp) {
		// not for a V1 lying dead in it
		if (!sp.isAlive()) return;
		ServerLevel level = sp.level();
		StructureStart start = level.structureManager().getStructureWithPieceAt(sp.blockPosition(), ARENAS);
		if (!start.isValid() || start.getPieces().isEmpty() || !(start.getPieces().get(0) instanceof ArenaPiece piece)) return;
		Layer L = layer(piece.layer());
		if (L == null) return;
		String id = level.dimension().identifier() + "|" + start.getChunkPos().toLong();
		Data data = Data.get(level.getServer());
		BlockPos c = piece.center();
		double d = Math.sqrt(sp.distanceToSqr(c.getX() + 0.5, sp.getY(), c.getZ() + 0.5));
		boolean inside = d < ArenaPiece.R - 1.5 && Math.abs(sp.getY() - c.getY()) < 10;
		ServerOps.State st = ServerOps.state(sp);
		long now = level.getGameTime();
		if (data.cleared.contains(id)) {
			if (inside && now - st.arenaNoticeAt > 20 * 30) {
				st.arenaNoticeAt = now;
				sp.displayClientMessage(Component.literal(L.title + " ARENA: CLEARED").withStyle(ChatFormatting.GRAY), true);
			}
			return;
		}
		String bossKey = boss(L, id);
		UkBosses.Boss boss = UkBosses.byKey(bossKey);
		if (boss == null) return;
		if (!inside) {
			if (now - st.arenaNoticeAt > 20 * 10) {
				st.arenaNoticeAt = now;
				sp.displayClientMessage(Component.literal(L.title + " ARENA").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
					.append(Component.literal("   " + boss.name() + " waits inside").withStyle(ChatFormatting.GRAY)), true);
			}
			return;
		}
		if (UkBosses.busy()) return;
		// the layer's title, as ULTRAKILL opens a level
		sp.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
		sp.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("ARENA").withStyle(ChatFormatting.GRAY)));
		sp.connection.send(new ClientboundSetTitleTextPacket(Component.literal(L.title).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
		Vec3 at = new Vec3(c.getX() + 0.5, c.getY() + 2, c.getZ() + 0.5);
		UkBosses.arena(sp, bossKey, at, L.key, () -> cleared(sp, level, piece, id, L));
	}

	/** Which of the layer's two bosses this arena has (the same one every time). */
	private static String boss(Layer L, String id) {
		return (id.hashCode() & 3) == 0 ? L.altBoss : L.boss;
	}

	/** Its boss is beaten: a chest of loot on the dais, and the arena stays cleared. */
	private static void cleared(ServerPlayer sp, ServerLevel level, ArenaPiece piece, String id, Layer L) {
		Data data = Data.get(level.getServer());
		data.cleared.add(id);
		data.setDirty();
		BlockPos chest = piece.center().above(2);
		level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
		if (level.getBlockEntity(chest) instanceof ChestBlockEntity be) {
			List<ItemStack> loot = loot(L, level.getRandom());
			for (int i = 0; i < loot.size() && i < be.getContainerSize(); i++) be.setItem(i * 2 % be.getContainerSize(), loot.get(i));
		}
		sp.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 15));
		sp.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("ARENA CLEARED").withStyle(ChatFormatting.GOLD)));
		sp.connection.send(new ClientboundSetTitleTextPacket(Component.literal(L.title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
		level.playSound(null, chest, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
		sp.sendSystemMessage(Component.literal("[Ultracraft] " + L.title + " arena cleared: a chest of loot waits on the dais.").withStyle(ChatFormatting.GOLD));
	}

	/** The deeper the layer, the better. */
	private static List<ItemStack> loot(Layer L, RandomSource r) {
		int d = L.depth;
		List<ItemStack> out = new java.util.ArrayList<>();
		out.add(new ItemStack(Items.DIAMOND, 2 + d / 2 + r.nextInt(3)));
		out.add(new ItemStack(Items.GOLD_INGOT, 4 + r.nextInt(8)));
		out.add(new ItemStack(Items.EMERALD, 2 + d + r.nextInt(4)));
		out.add(new ItemStack(Items.GOLDEN_APPLE, 1 + d / 3));
		out.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 4 + d * 2));
		if (d >= 3) out.add(new ItemStack(Items.ENDER_PEARL, 4 + r.nextInt(5)));
		if (d >= 5) out.add(new ItemStack(Items.DIAMOND_BLOCK, 1 + (d - 5) / 2));
		if (d >= 6) out.add(new ItemStack(r.nextInt(3) == 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.TOTEM_OF_UNDYING));
		if (d >= 8) out.add(new ItemStack(Items.NETHERITE_INGOT, 1 + (d - 8)));
		if (d >= 9) out.add(new ItemStack(Items.NETHER_STAR));
		// each layer's own touch
		out.add(switch (L.key) {
			case "greed" -> new ItemStack(Items.GOLD_BLOCK, 3 + r.nextInt(4));
			case "wrath" -> new ItemStack(r.nextInt(4) == 0 ? Items.TRIDENT : Items.HEART_OF_THE_SEA);
			case "gluttony" -> new ItemStack(Items.COOKED_BEEF, 16);
			case "violence" -> new ItemStack(Items.TNT, 8);
			case "lust" -> new ItemStack(Items.CRYING_OBSIDIAN, 8);
			case "heresy" -> new ItemStack(Items.BLAZE_ROD, 8);
			case "fraud" -> new ItemStack(Items.ECHO_SHARD, 2 + r.nextInt(3));
			case "prime" -> new ItemStack(Items.ELYTRA);
			default -> new ItemStack(Items.IRON_BLOCK, 2 + r.nextInt(3));
		});
		return out;
	}

	// ------------------------------------------------------------------ cleared arenas, kept with the world

	static final class Data extends SavedData {
		private static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.listOf().optionalFieldOf("cleared", List.of()).forGetter(d -> List.copyOf(d.cleared))
		).apply(i, Data::new));
		static final SavedDataType<Data> TYPE = new SavedDataType<>("ultracraft_arenas", Data::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

		final Set<String> cleared = new HashSet<>();

		Data() {}

		private Data(List<String> cleared) {
			this.cleared.addAll(cleared);
		}

		static Data get(MinecraftServer server) {
			return server.overworld().getDataStorage().computeIfAbsent(TYPE);
		}
	}

	/** "LIMBO" for a layer key, for messages. */
	static String title(String key) {
		Layer L = layer(key);
		return L != null ? L.title : key.toUpperCase(Locale.ROOT);
	}
}
