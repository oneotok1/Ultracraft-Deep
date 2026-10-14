package dev.ultracraft;

import java.io.InputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Leaves and glass as Minecraft draws them: textures with holes. Their faces go to ULTRAKILL with the texture (sent
 * once, TEX id name png), so its enemies hide behind the solid pixels and show through the holes. Fast leaves
 * (cutout leaves off) are drawn solid and hide everything, like any block.
 */
final class CutoutTextures {
	/** How WorldMesh treats a see-through full block. */
	enum Kind { SOLID, SEE_THROUGH, TEXTURED }

	private static final Map<Block, Integer> ids = new HashMap<>();
	private static final Map<Identifier, Integer> byTexture = new HashMap<>();
	private static final Set<Integer> sent = new HashSet<>();

	private CutoutTextures() {}

	/** ULTRAKILL (re)connected or resource packs changed: every texture goes again. */
	static void reset() {
		sent.clear();
		ids.clear();
		failed.clear();
	}

	static Kind kind(BlockState s) {
		if (s.canOcclude()) return Kind.SOLID;
		boolean leaves = s.is(BlockTags.LEAVES);
		if (leaves || s.is(Blocks.GLASS)) {
			// leaves with "cutout leaves" off are drawn opaque
			if (ItemBlockRenderTypes.getChunkRenderType(s) == ChunkSectionLayer.SOLID) return Kind.SOLID;
			return Kind.TEXTURED;
		}
		return Kind.SEE_THROUGH;
	}

	/** The texture id for one of a model's sprites (sent to ULTRAKILL first if new), or -1 if it can't be had. */
	static int spriteId(TextureAtlasSprite sprite) {
		Identifier name = sprite.contents().name();
		Integer known = byTexture.get(name);
		if (known != null && sent.contains(known)) return failed.contains(known) ? -1 : known;
		int id = known != null ? known : byTexture.size();
		byTexture.put(name, id);
		try {
			Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
			try (InputStream in = Minecraft.getInstance().getResourceManager().getResourceOrThrow(file).open()) {
				UkLink.send("TEX " + id + " " + name.getPath().replace(' ', '_') + " " + Base64.getEncoder().encodeToString(in.readAllBytes()));
			}
			sent.add(id);
			return id;
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("ultracraft").warn("cutout texture {}: {}", name, e.toString());
			sent.add(id);
			failed.add(id);
			return -1;
		}
	}

	private static final Set<Integer> failed = new HashSet<>();

	/** The texture id for a textured block (sent to ULTRAKILL first if new), or -1 if it can't be had. */
	static int id(BlockState s) {
		Integer have = ids.get(s.getBlock());
		if (have != null) return have;
		int id = -1;
		try {
			Minecraft mc = Minecraft.getInstance();
			TextureAtlasSprite sprite = mc.getModelManager().getBlockModelShaper().getParticleIcon(s);
			Identifier name = sprite.contents().name();
			Integer known = byTexture.get(name);
			id = known != null ? known : byTexture.size();
			byTexture.put(name, id);
			if (sent.add(id)) {
				Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
				try (InputStream in = mc.getResourceManager().getResourceOrThrow(file).open()) {
					UkLink.send("TEX " + id + " " + name.getPath().replace(' ', '_') + " " + Base64.getEncoder().encodeToString(in.readAllBytes()));
				}
			}
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("ultracraft").warn("cutout texture for {}: {}", s, e.toString());
			id = -1;
		}
		ids.put(s.getBlock(), id);
		return id;
	}
}
