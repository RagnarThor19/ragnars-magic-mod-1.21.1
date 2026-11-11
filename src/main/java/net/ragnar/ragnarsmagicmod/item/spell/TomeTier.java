package net.ragnar.ragnarsmagicmod.item.spell;

/**
 * How strong a tome is, which decides which staffs can use it. PASSIVE tomes are a class of their own: they never go on
 * a staff and are never cast - they simply work while they're in your inventory (see PassiveTomes).
 */
public enum TomeTier {
    BEGINNER, ADVANCED, MASTER, PASSIVE;

    public boolean isPassive() {
        return this == PASSIVE;
    }
}
