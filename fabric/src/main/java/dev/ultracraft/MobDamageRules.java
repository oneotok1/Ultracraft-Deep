package dev.ultracraft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which Minecraft mobs ULTRAKILL's side can touch at all: what the Mob Damage Filter is set to allow.
 *
 * <p>A mob it does not allow is left out of the entity list Minecraft sends ULTRAKILL (Ultracraft's
 * exportEntities), so no stand-in is ever made for it over there: no weapon hits it, its fist never
 * lights a parry, ULTRAKILL's enemies never pick it, the Whiplash never catches it and its blasts never
 * shove it. The same answer guards the few places that hurt a mob straight from Minecraft's side
 * (a slam, a parried projectile, a nuke), so nothing slips through while the list catches up.
 *
 * <p>A mob's group comes from its own class, which is the one thing that tells a neutral wolf from a
 * hostile zombie: {@code EntityType} in 1.21.11 no longer carries its mob's class ({@code getBaseClass}
 * hands back {@code Entity.class} for every type), so a type the screen lists is asked by building one
 * throwaway mob of it once, and that answer is kept.
 */
public final class MobDamageRules {
    private MobDamageRules() {}

    /** The filter's own four groups, in the order the screen lists them. */
    public static final int HOSTILE = 0;
    public static final int NEUTRAL = 1;
    public static final int PASSIVE = 2;
    public static final int OTHER = 3;

    /** A mob's group. Monsters come first on purpose: a zombified piglin and an enderman are both a
     * monster and a neutral mob, and ULTRAKILL has always counted them as monsters. */
    public static int category(Class<?> type) {
        if (Enemy.class.isAssignableFrom(type)) return HOSTILE;
        if (NeutralMob.class.isAssignableFrom(type)) return NEUTRAL;
        if (Animal.class.isAssignableFrom(type) || AmbientCreature.class.isAssignableFrom(type)
                || WaterAnimal.class.isAssignableFrom(type)) return PASSIVE;
        return OTHER;
    }

    public static int category(Entity e) {
        return category(e.getClass());
    }

    // ------------------------------------------------------------------ types the screen lists

    private static final Map<EntityType<?>, Entity> SAMPLES = new HashMap<>();
    private static final Map<EntityType<?>, Integer> TYPE_CATEGORY = new HashMap<>();
    private static final Map<EntityType<?>, Boolean> TYPE_IS_MOB = new HashMap<>();

    /** Mobs whose group its category alone cannot tell: these are neutral however they spawn. */
    private static final Set<String> NEUTRAL_IDS = new HashSet<>(Set.of(
            "minecraft:wolf", "minecraft:enderman", "minecraft:iron_golem", "minecraft:polar_bear",
            "minecraft:bee", "minecraft:zombified_piglin"));

    /** Mobs the game files under {@code MISC}: no less a mob than a zombie is, they just do not count
     * towards the mob cap. Kept by name because a type that will not build says nothing about what it
     * is, and reading its category alone would strike it off the list entirely. */
    private static final Set<String> MISC_MOBS = new HashSet<>(Set.of(
            "minecraft:villager", "minecraft:wandering_trader", "minecraft:iron_golem",
            "minecraft:snow_golem", "minecraft:ender_dragon", "minecraft:giant"));

    /** One throwaway mob of this type, to ask it what it is; null if it cannot be built. */
    private static Entity sample(EntityType<?> type) {
        if (SAMPLES.containsKey(type)) return SAMPLES.get(type);
        Entity made = null;
        var level = Minecraft.getInstance().level;
        try {
            if (level != null) made = type.create(level, EntitySpawnReason.COMMAND);
        } catch (Throwable ignored) {
            // a type that will not build (a mod's, or one that needs a real server): the category answers instead
        }
        // without a world nothing can be built, so the answer is no answer at all: keep it only to save
        // the work, and let the next look with a world in hand ask again
        if (made != null || level != null) SAMPLES.put(type, made);
        return made;
    }

    public static boolean isMob(EntityType<?> type) {
        Boolean known = TYPE_IS_MOB.get(type);
        if (known != null) return known;
        Entity made = sample(type);
        boolean mob = made != null ? made instanceof Mob
                : type.getCategory() != MobCategory.MISC || MISC_MOBS.contains(key(type));
        TYPE_IS_MOB.put(type, mob);
        return mob;
    }

    /** A type's group, from a mob of it if one can be built, else from its category. */
    public static int category(EntityType<?> type) {
        Integer known = TYPE_CATEGORY.get(type);
        if (known != null) return known;
        Entity made = sample(type);
        int cat;
        if (made != null) {
            cat = category(made.getClass());
        } else {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String key = id == null ? "" : id.toString();
            cat = NEUTRAL_IDS.contains(key) ? NEUTRAL
                    : type.getCategory() == MobCategory.MONSTER ? HOSTILE
                    : type.getCategory() == MobCategory.MISC && !MISC_MOBS.contains(key) ? OTHER : PASSIVE;
        }
        TYPE_CATEGORY.put(type, cat);
        return cat;
    }

    /** "minecraft:zombie": the name the settings file keeps a mob's own setting under. */
    public static String key(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id == null ? "minecraft:pig" : id.toString();
    }

    /** A mob's group setting: its own if the settings file has one, otherwise its group's. */
    private static boolean allowed(EntityType<?> type, int category) {
        Boolean own = UltracraftConfig.dmgMobs.get(key(type));
        if (own != null) return own;
        return switch (category) {
            case HOSTILE -> UltracraftConfig.dmgHostile;
            case NEUTRAL -> UltracraftConfig.dmgNeutral;
            case PASSIVE -> UltracraftConfig.dmgPassive;
            default -> UltracraftConfig.dmgOther;
        };
    }

    public static boolean allowed(EntityType<?> type) {
        return allowed(type, category(type));
    }

    /** Whether ULTRAKILL's side may touch this thing. Everything that is not a Minecraft mob is left
     * alone: players, ULTRAKILL's own enemies (their stand-ins are ULTRAKILL's already), dropped items
     * and the rest never go through the filter. */
    public static boolean touchable(Entity e) {
        if (!(e instanceof Mob) || e instanceof UkEnemyEntity) return true;
        return allowed(e.getType(), category(e));
    }

    /** Every mob the screen lists: what the game's registry holds now, so a mod's mobs are in it too. */
    public static List<EntityType<?>> mobs() {
        List<EntityType<?>> list = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == UltracraftCommon.UK_ENEMY) continue;
            if (isMob(type)) list.add(type);
        }
        list.sort(Comparator.comparing(MobDamageRules::key));
        return list;
    }

    /** A mob's English name ("Zombie") - what the search box's words are matched against. */
    public static String englishName(EntityType<?> type) {
        return type.getDescription().getString();
    }

    // ------------------------------------------------------------------ the search box

    private static MethodHandle jec;
    private static boolean jecTried;

    private static void probe() {
        if (jecTried) return;
        jecTried = true;
        try {
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("jecharacters")) return;
            Class<?> match = Class.forName("me.towdium.jecharacters.utils.Match");
            jec = MethodHandles.publicLookup().findStatic(match, "contains",
                    MethodType.methodType(boolean.class, CharSequence.class, CharSequence.class));
        } catch (Throwable ignored) {
            // no pinyin mod, or one whose own API moved: the search box simply matches the text itself
            jec = null;
        }
    }

    /** Whether a name contains what was typed: pinyin as well when jecharacters is here to say so. */
    public static boolean contains(String text, String query) {
        probe();
        if (jec != null) {
            try {
                return (boolean) jec.invokeWithArguments(text, query);
            } catch (Throwable t) {
                jec = null;
            }
        }
        return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
}
