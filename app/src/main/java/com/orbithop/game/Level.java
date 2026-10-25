package com.orbithop.game;

/** One Journey level. All 30 levels are defined in {@link #ALL} at the bottom of this file. */
final class Level {

    static final int SCORE = 0; // dodge N blocks
    static final int GEMS = 1;  // collect N gems
    static final int TIME = 2;  // survive N seconds

    static final String[] WORLD_NAMES = {"Moon", "Mars", "Saturn"};
    static final int PER_WORLD = 10;

    final int number, world, rings, goalType, goal;
    final float speed, interval, gemChance;
    final boolean moving, blinking;
    final String intro; // key of the "new mechanic" card shown before the first play, or null

    private Level(int number, int rings, int goalType, int goal, float speed, float interval,
                  boolean moving, boolean blinking, String intro) {
        this.number = number;
        this.world = (number - 1) / PER_WORLD + 1;
        this.rings = rings;
        this.goalType = goalType;
        this.goal = goal;
        this.speed = speed;
        this.interval = interval;
        this.moving = moving;
        this.blinking = blinking;
        this.intro = intro;
        this.gemChance = goalType == GEMS ? 0.30f : 0.14f;
    }

    String goalText() {
        switch (goalType) {
            case GEMS: return "Collect " + goal + " gems";
            case TIME: return "Survive " + goal + " seconds";
            default: return "Dodge " + goal + " blocks";
        }
    }

    /** Gems needed for the third star on SCORE / TIME levels. */
    int starGems() {
        if (goalType == TIME) return Math.max(1, Math.round(goal / interval * 0.10f));
        return Math.max(1, Math.round(goal * 0.12f));
    }

    /** Time limit for the third star on GEMS levels. */
    int starTime() {
        return goal * 7;
    }

    String thirdStarText() {
        return goalType == GEMS ? "Finish within " + starTime() + "s" : "Collect " + starGems() + " gems";
    }

    static String introTitle(String key) {
        switch (key) {
            case "gemgoal": return "Gem hunt";
            case "time": return "Survival";
            case "moving": return "Moving blocks";
            case "rings3": return "Three rings!";
            case "blink": return "Blinking blocks";
            case "rings4": return "Four rings!";
            default: return "";
        }
    }

    static String introText(String key) {
        switch (key) {
            case "gemgoal": return "Collect the gems shown in the goal to clear the level. Blocks still end your run!";
            case "time": return "No target to hit. Just stay alive until the timer runs out.";
            case "moving": return "Some blocks slide back and forth along their ring. Watch their rhythm before you hop.";
            case "rings3": return "Tap the LEFT half of the screen to hop inward, and the RIGHT half to hop outward.";
            case "blink": return "Faded blocks can't hurt you. Slip past while they're dim!";
            case "rings4": return "Same controls: LEFT half = in, RIGHT half = out. Plan your route early.";
            default: return "";
        }
    }

    static final int COUNT = 30;

    static Level get(int number) {
        return ALL[Math.max(1, Math.min(COUNT, number)) - 1];
    }

    private static Level L(int n, int rings, int type, int goal, float speed, float interval,
                           boolean moving, boolean blinking, String intro) {
        return new Level(n, rings, type, goal, speed, interval, moving, blinking, intro);
    }

    private static final boolean N = false, Y = true;

    //              #   rings  goal type   goal  speed  interval moving blink  intro card
    static final Level[] ALL = {
            // ---- World 1: Moon (2 rings)
            L(1,  2, SCORE, 8,  1.45f, 1.15f, N, N, null),
            L(2,  2, SCORE, 12, 1.55f, 1.10f, N, N, null),
            L(3,  2, GEMS,  3,  1.55f, 1.10f, N, N, "gemgoal"),
            L(4,  2, SCORE, 16, 1.65f, 1.05f, N, N, null),
            L(5,  2, TIME,  25, 1.70f, 1.00f, N, N, "time"),
            L(6,  2, SCORE, 12, 1.60f, 1.10f, Y, N, "moving"),
            L(7,  2, SCORE, 16, 1.70f, 1.05f, Y, N, null),
            L(8,  2, GEMS,  5,  1.75f, 1.00f, Y, N, null),
            L(9,  2, TIME,  35, 1.85f, 0.95f, Y, N, null),
            L(10, 2, SCORE, 25, 1.95f, 0.90f, Y, N, null),
            // ---- World 2: Mars (3 rings)
            L(11, 3, SCORE, 10, 1.50f, 1.10f, N, N, "rings3"),
            L(12, 3, SCORE, 14, 1.60f, 1.00f, N, N, null),
            L(13, 3, GEMS,  4,  1.65f, 1.00f, N, N, null),
            L(14, 3, SCORE, 16, 1.70f, 0.95f, Y, N, null),
            L(15, 3, TIME,  30, 1.75f, 0.95f, Y, N, null),
            L(16, 3, SCORE, 14, 1.70f, 1.00f, N, Y, "blink"),
            L(17, 3, GEMS,  6,  1.80f, 0.95f, N, Y, null),
            L(18, 3, SCORE, 20, 1.85f, 0.90f, Y, Y, null),
            L(19, 3, TIME,  40, 1.90f, 0.88f, Y, Y, null),
            L(20, 3, SCORE, 28, 2.00f, 0.85f, Y, Y, null),
            // ---- World 3: Saturn (4 rings)
            L(21, 4, SCORE, 12, 1.60f, 1.00f, N, N, "rings4"),
            L(22, 4, SCORE, 16, 1.70f, 0.95f, N, N, null),
            L(23, 4, GEMS,  5,  1.75f, 0.92f, Y, N, null),
            L(24, 4, TIME,  30, 1.85f, 0.90f, N, Y, null),
            L(25, 4, SCORE, 20, 1.90f, 0.88f, Y, N, null),
            L(26, 4, GEMS,  7,  1.95f, 0.86f, Y, Y, null),
            L(27, 4, TIME,  40, 2.00f, 0.85f, Y, N, null),
            L(28, 4, SCORE, 24, 2.10f, 0.82f, Y, Y, null),
            L(29, 4, TIME,  45, 2.20f, 0.80f, Y, Y, null),
            L(30, 4, SCORE, 32, 2.30f, 0.78f, Y, Y, null),
    };
}
