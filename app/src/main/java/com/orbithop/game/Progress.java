package com.orbithop.game;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.TimeZone;

/** Everything the game remembers, stored on the phone only (no server). */
final class Progress {

    private static final int FREE_VIDEOS_PER_DAY = 5;

    private final SharedPreferences p;

    Progress(Context c) {
        p = c.getSharedPreferences("orbit", Context.MODE_PRIVATE);
    }

    // ---- Classic best score
    int best() { return p.getInt("best", 0); }
    void setBest(int v) { p.edit().putInt("best", v).apply(); }

    // ---- Gems (currency)
    int gems() { return p.getInt("gems", 0); }
    void addGems(int n) { p.edit().putInt("gems", gems() + n).apply(); }
    boolean spendGems(int n) {
        if (gems() < n) return false;
        p.edit().putInt("gems", gems() - n).apply();
        return true;
    }

    // ---- Journey levels
    int stars(int level) { return p.getInt("stars_" + level, 0); }
    int unlocked() { return Math.min(Level.COUNT, p.getInt("unlocked", 1)); }
    boolean allCleared() { return p.getInt("unlocked", 1) > Level.COUNT; }
    int starsInWorld(int world) {
        int sum = 0;
        for (int i = (world - 1) * Level.PER_WORLD + 1; i <= world * Level.PER_WORLD; i++) sum += stars(i);
        return sum;
    }
    void recordLevel(int level, int stars) {
        SharedPreferences.Editor e = p.edit();
        if (stars > stars(level)) e.putInt("stars_" + level, stars);
        if (level + 1 > p.getInt("unlocked", 1)) e.putInt("unlocked", level + 1);
        e.apply();
    }

    // ---- Tutorial and "new mechanic" cards
    boolean tutorialDone() { return p.getBoolean("tutorial_done", false); }
    void setTutorialDone() { p.edit().putBoolean("tutorial_done", true).apply(); }
    boolean seenIntro(String key) { return p.getBoolean("intro_" + key, false); }
    void markIntro(String key) { p.edit().putBoolean("intro_" + key, true).apply(); }

    // ---- Skins
    int skin() { return p.getInt("skin", 0); }
    void setSkin(int i) { p.edit().putInt("skin", i).apply(); }
    boolean ownsSkin(int i) { return i == 0 || p.getBoolean("skin_" + i, false); }
    void unlockSkin(int i) { p.edit().putBoolean("skin_" + i, true).apply(); }

    // ---- Daily gift (7-day streak)
    static long today() {
        long now = System.currentTimeMillis();
        return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L;
    }
    boolean dailyAvailable() { return today() > p.getLong("daily_day", -1); }
    int dailyDay() {
        long last = p.getLong("daily_day", -1);
        int streak = p.getInt("daily_streak", 0);
        return last == today() - 1 ? Math.min(streak, 6) + 1 : 1;
    }
    int dailyAmount() { return 10 + 5 * (dailyDay() - 1); }
    int claimDaily(int multiplier) {
        int day = dailyDay();
        int amount = (10 + 5 * (day - 1)) * multiplier;
        p.edit().putLong("daily_day", today()).putInt("daily_streak", day)
                .putInt("gems", gems() + amount).apply();
        return amount;
    }

    // ---- "Watch a video for free gems" (capped per day)
    int freeVideosLeft() {
        return p.getLong("free_day", -1) == today() ? p.getInt("free_left", FREE_VIDEOS_PER_DAY) : FREE_VIDEOS_PER_DAY;
    }
    void useFreeVideo() {
        p.edit().putInt("free_left", freeVideosLeft() - 1).putLong("free_day", today()).apply();
    }
}
