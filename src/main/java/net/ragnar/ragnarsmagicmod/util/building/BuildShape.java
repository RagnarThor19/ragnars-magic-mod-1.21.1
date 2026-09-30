package net.ragnar.ragnarsmagicmod.util.building;

/**
 * The shapes the Tome of Building can make. Each shape uses up to three sizes: width (across, or the
 * diameter/base of round shapes), height and length (away from you).
 */
public enum BuildShape {
    //        label       width label  maxW  height label  maxH  length label  maxL  solid    hollow
    WALL("Wall", "Width", 5, "Height", 5, null, 0, "Solid", "Frame",
            "A wall standing up in front of you."),
    FLOOR("Floor", "Width", 5, null, 0, "Length", 5, "Solid", "Border",
            "A flat platform. Aim at the side of a block to stick it out like a bridge."),
    BOX("Box", "Width", 5, "Height", 5, "Length", 5, "Solid", "Shell",
            "A block of blocks. Hollow makes a little room."),
    CIRCLE("Circle", "Diameter", 7, null, 0, null, 0, "Filled", "Ring",
            "A flat circle on the ground."),
    CYLINDER("Cylinder", "Diameter", 5, "Height", 5, null, 0, "Filled", "Tube",
            "A round pillar. Hollow makes a tower."),
    SPHERE("Sphere", "Diameter", 5, null, 0, null, 0, "Filled", "Hollow",
            "A small ball."),
    DOME("Dome", "Diameter", 7, null, 0, null, 0, "Filled", "Hollow",
            "Half a ball sitting on the ground."),
    PYRAMID("Pyramid", "Base", 7, null, 0, null, 0, "Filled", "Hollow",
            "A stepped pyramid."),
    STAIRS("Stairs", "Width", 5, null, 0, "Length", 5, "Solid", "Steps",
            "A staircase climbing away from you.");

    public static final int MIN_SIZE = 1;

    public final String label;
    public final String widthLabel, heightLabel, lengthLabel;
    public final int maxWidth, maxHeight, maxLength;
    public final String solidLabel, hollowLabel;
    public final String description;

    BuildShape(String label, String widthLabel, int maxWidth, String heightLabel, int maxHeight,
               String lengthLabel, int maxLength, String solidLabel, String hollowLabel, String description) {
        this.label = label;
        this.widthLabel = widthLabel;
        this.maxWidth = maxWidth;
        this.heightLabel = heightLabel;
        this.maxHeight = maxHeight;
        this.lengthLabel = lengthLabel;
        this.maxLength = maxLength;
        this.solidLabel = solidLabel;
        this.hollowLabel = hollowLabel;
        this.description = description;
    }

    public boolean usesWidth() { return widthLabel != null; }
    public boolean usesHeight() { return heightLabel != null; }
    public boolean usesLength() { return lengthLabel != null; }

    /** Shapes that grow out of the block you aim at rather than being centred on it. */
    public boolean startsAtAnchor() { return this == STAIRS; }

    public static BuildShape byIndex(int i) {
        BuildShape[] all = values();
        return all[Math.floorMod(i, all.length)];
    }
}
