package net.ragnar.ragnarsmagicmod.item.spell;

import java.util.EnumMap;
import java.util.Map;

public final class Spells {
    private static final Map<SpellId, Spell> REG = new EnumMap<>(SpellId.class);

    private Spells() {}

    public static void register(SpellId id, Spell spell) {
        REG.put(id, spell);
    }

    public static Spell get(SpellId id) {
        return REG.get(id);
    }
}
