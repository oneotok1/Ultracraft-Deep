package dev.ultracraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The best wave the Cyber Grind has been reached at in this world, kept in the world's save
 * (data/ultracraft_grind.dat). It used to sit in config/ultracraft.properties, one number for the whole instance,
 * which was fine while it was only a score to show. It now decides how far a wave can be picked to start at, so it
 * belongs to the world the score was made in.
 */
final class GrindRecord extends SavedData {
	private static final Codec<GrindRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.optionalFieldOf("best", 0).forGetter(r -> r.best)
	).apply(i, GrindRecord::new));
	static final SavedDataType<GrindRecord> TYPE =
		new SavedDataType<>("ultracraft_grind", GrindRecord::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** The wave reached, 0 until the Cyber Grind has been played here at all. */
	int best;

	GrindRecord() {}

	private GrindRecord(int best) {
		this.best = best;
	}

	/** This world's record, or 0 with no world to ask (the score is only ever read where a player is). */
	static int best(MinecraftServer server) {
		return server == null ? 0 : server.overworld().getDataStorage().computeIfAbsent(TYPE).best;
	}

	static int best(ServerPlayer sp) {
		return sp == null ? 0 : best(sp.level().getServer());
	}

	/** Raises the record to wave if it is lower; a lower one changes nothing. */
	static void reached(ServerPlayer sp, int wave) {
		if (sp == null || wave < 1) return;
		GrindRecord r = sp.level().getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
		if (wave <= r.best) return;
		r.best = wave;
		r.setDirty();
	}
}
