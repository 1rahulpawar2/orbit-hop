package com.orbithop.game;

import java.util.Random;

/**
 * One Journey level. Levels 1-30 are hand-made (see {@link #HANDMADE}); every level after that is
 * generated from its number, so the Journey never ends and needs no updates.
 * Daily Challenge levels are generated from the date.
 */
final class Level {

    static final int SCORE = 0; // dodge N blocks
    static final int GEMS = 1;  // collect N gems
    static final int TIME = 2;  // survive N seconds

    static final int PER_WORLD = 10;
    static final int THEMES = 9;
    private static final String[] WORLD_NAMES = {
            "Moon", "Mars", "Saturn", "Neptune", "Nebula", "Emerald", "Black Hole", "Frost", "Sun Gate"};

    /** number <= 0 means Daily Challenge. */
    final int number, world, rings, goalType, goal;
    final float speed, interval, gemChance;
    final boolean moving, blinking;
    final String intro; // key of the "new mechanic" card shown before the first play, or null
    final Level base;   // untuned original (difficulty tuning makes copies)

    private Level(int number, int world, int rings, int goalType, int goal, float speed, float interval,
                  boolean moving, boolean blinking, String intro, Level base) {
        this.number = number;
        this.world = world;
        this.rings = rings;
        this.goalType = goalType;
        this.goal = goal;
        this.speed = speed;
        this.interval = interval;
        this.moving = moving;
        this.blinking = blinking;
        this.intro = intro;
        this.gemChance = goalType == GEMS ? 0.30f : 0.14f;
        this.base = base == null ? this : base;
    }

    boolean isDaily() {
        return number <= 0;
    }

    /** Copy with speed scaled by {@code mult} (>1 harder, <1 easier). */
    Level tuned(float mult) {
        if (Math.abs(mult - 1f) < 0.001f) return base;
        Level b = base;
        return new Level(b.number, b.world, b.rings, b.goalType, b.goal, b.speed * mult, b.interval / mult,
                b.moving, b.blinking, b.intro, b);
    }

    String title() {
        return isDaily() ? "DAILY CHALLENGE" : "LEVEL " + number;
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
        if (goalType == TIME) return Math.max(1, Math.round(goal / base.interval * 0.10f));
        return Math.max(1, Math.round(goal * 0.12f));
    }

    /** Time limit for the third star on GEMS levels. */
    int starTime() {
        return goal * 7;
    }

    String thirdStarText() {
        return goalType == GEMS ? "Finish within " + starTime() + "s" : "Collect " + starGems() + " gems";
    }

    static String worldName(int world) {
        String name = WORLD_NAMES[(world - 1) % THEMES];
        int lap = (world - 1) / THEMES + 1;
        return lap > 1 ? name + " " + lap : name;
    }

    /** Planet colour index 1..THEMES for a world. */
    static int theme(int world) {
        return (Math.max(1, world) - 1) % THEMES + 1;
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

    // ------------------------------------------------------------------ levels

    static final int HANDMADE = 30;

    static Level get(int number) {
        if (number < 1) number = 1;
        if (number <= HANDMADE) return ALL[number - 1];
        return generate(number, number * 7919L + 17, number, true);
    }

    /** Today's challenge, pitched just below the player's own progress. */
    static Level daily(long day, int unlocked) {
        int d = Math.max(3, unlocked - 1);
        return generate(0, day * 104729L + 7, d, false);
    }

    /**
     * Builds a level for difficulty step {@code d} (1 = easiest). Difficulty rises quickly at first
     * and then levels off, so it stays playable forever. Inside each world it ramps up in a
     * "sawtooth": an easy opener, harder towards the end, and a boss level at x0.
     */
    private static Level generate(int number, long seed, int d, boolean pacing) {
        Random r = new Random(seed);
        int world = (d - 1) / PER_WORLD + 1;
        int p = pacing ? (d - 1) % PER_WORLD : 4 + r.nextInt(3); // position inside the world
        boolean opener = pacing && p == 0, boss = pacing && p == PER_WORLD - 1;
        float grow = 1f - (float) Math.exp(-d / 40f);
        float saw = p / (float) (PER_WORLD - 1);

        int rings = 2 + (world - 1) % 3; // 2, 3, 4, 2, 3, 4 ...
        float speed = 1.5f + 1.0f * grow + 0.15f * saw + (boss ? 0.08f : 0f) - (opener ? 0.12f : 0f);
        if (rings == 2) speed += 0.1f; // two rings are easier to read, so they can go faster
        float interval = 1.1f - 0.38f * grow - 0.05f * saw + (opener ? 0.08f : 0f);

        int type;
        if (boss) type = SCORE;
        else {
            float t = r.nextFloat();
            type = t < 0.5f ? SCORE : t < 0.75f ? TIME : GEMS;
        }
        int goal;
        switch (type) {
            case TIME: goal = 25 + Math.round(15 * grow) + p; break;
            case GEMS: goal = 4 + Math.round(3 * grow) + p / 4; break;
            default: goal = 12 + Math.round(12 * grow) + p + (boss ? 6 : 0);
        }

        float movingChance = d < 6 ? 0f : Math.min(0.85f, 0.45f + d * 0.004f);
        float blinkChance = d < 16 ? 0f : Math.min(0.75f, 0.3f + d * 0.004f);
        boolean moving = boss || r.nextFloat() < movingChance;
        boolean blinking = (boss && d >= 16) || r.nextFloat() < blinkChance;
        if (opener) blinking = false;
        if (rings > 2 && d < 11) rings = 2; // 3+ rings only once the player has met them in level 11
        if (rings > 3 && d < 21) rings = 3;
        return new Level(number, world, rings, type, goal, speed, interval, moving, blinking, null, null);
    }

    private static Level L(int n, int rings, int type, int goal, float speed, float interval,
                           boolean moving, boolean blinking, String intro) {
        return new Level(n, (n - 1) / PER_WORLD + 1, rings, type, goal, speed, interval, moving, blinking, intro, null);
    }

    private static final boolean N = false, Y = true;

    //              #   rings  goal type   goal  speed  interval moving blink  intro card
    private static final Level[] ALL = {
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
