package net.ragnar.ragnarsmagicmod.terraform;

/** What the Tome of Terraforming does to the ground. Each has its own strength (or none), kept separately. */
public enum TerraformMode {
    FLATTEN("Flatten", "Levels the ground to the height of the block you aim at: hills are cut down, dips filled in.",
            null, 0, 0, 0),
    SMOOTH("Smooth", "Irons out bumps, spikes and pits while keeping the land's overall shape.",
            "Passes", 1, 10, 3),
    RAISE("Raise", "Lifts the ground into a smooth hill (Hard edge: lifts the whole patch, keeping its shape).",
            "Height", 1, 16, 4),
    LOWER("Lower", "Sinks the ground into a smooth hollow (Hard edge: sinks the whole patch, keeping its shape).",
            "Depth", 1, 16, 4),
    TERRACE("Terrace", "Turns slopes into flat stepped ledges, lined up with the block you aim at.",
            "Step", 2, 8, 3),
    CLIFF("Cliff", "Makes the slope you aim at a sheer drop: the ground above stays, everything below the aimed height falls away.",
            "Drop", 2, 24, 6),
    RAMP("Ramp", "A smooth road from your feet to the aimed spot, as wide as the size.",
            null, 0, 0, 0),
    ROUGHEN("Roughen", "Adds gentle, natural-looking rolls to ground that's too flat.",
            "Roughness", 1, 6, 2);

    public final String label, description, strengthLabel;
    public final int minStrength, maxStrength, defaultStrength;

    TerraformMode(String label, String description, String strengthLabel, int minStrength, int maxStrength, int defaultStrength) {
        this.label = label;
        this.description = description;
        this.strengthLabel = strengthLabel;
        this.minStrength = minStrength;
        this.maxStrength = maxStrength;
        this.defaultStrength = defaultStrength;
    }

    public boolean hasStrength() {
        return strengthLabel != null;
    }

    public static TerraformMode byIndex(int i) {
        TerraformMode[] all = values();
        return all[Math.floorMod(i, all.length)];
    }
}
