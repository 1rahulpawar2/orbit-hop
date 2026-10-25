package com.orbithop.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * The whole game. A comet orbits a planet on 2-4 rings; tap to hop between them.
 * Modes: Journey (30 levels in 3 worlds) and Classic (endless).
 * Also: first-run tutorial, "new mechanic" cards, skin shop, daily gift.
 */
public class GameView extends View implements Choreographer.FrameCallback {

    /** Things the game asks the Activity for (ads, purchases, privacy). */
    public interface Host {
        boolean canOfferContinue();      // a rewarded video is ready (or the player bought Remove Ads)
        boolean continueNeedsAd();       // false if the player bought Remove Ads
        void showContinueAd(Runnable onRewarded);
        void onGameOver();
        void beforeNewGame(Runnable startGame);
        String removeAdsPrice();         // null = don't show the button
        void buyRemoveAds();
        boolean privacyButtonNeeded();
        void openPrivacyOptions();
    }

    private enum State { MENU, DAILY, LEVELS, SHOP, TUTORIAL, INTRO, PLAYING, PAUSED, DYING, GAME_OVER, LEVEL_DONE }

    // ------------------------------------------------------------ look

    private static final int COL_BG_TOP = Color.rgb(11, 16, 38);
    private static final int COL_BG_BOTTOM = Color.rgb(28, 20, 62);
    private static final int COL_ACCENT = Color.rgb(94, 234, 212);
    private static final int COL_BLOCK = Color.rgb(251, 113, 133);
    private static final int COL_GEM = Color.rgb(253, 224, 71);
    private static final int COL_TEXT = Color.rgb(238, 242, 255);
    private static final int COL_DIM = Color.rgb(148, 163, 184);
    private static final int COL_OFF = Color.rgb(71, 85, 105);
    private static final int COL_CARD = Color.rgb(23, 30, 66);

    // planet colours: [0] Classic, [1] Moon, [2] Mars, [3] Saturn
    private static final int[] PLANET_LIGHT = {
            Color.rgb(129, 140, 248), Color.rgb(226, 232, 240), Color.rgb(253, 164, 105), Color.rgb(253, 230, 138)};
    private static final int[] PLANET_DARK = {
            Color.rgb(49, 46, 129), Color.rgb(71, 85, 105), Color.rgb(154, 52, 18), Color.rgb(146, 64, 14)};

    private static final String[] SKIN_NAMES = {"Mint", "Sky", "Violet", "Lime", "Star", "Ember", "Rainbow", "Ghost"};
    private static final int[] SKIN_COLORS = {
            Color.rgb(94, 234, 212), Color.rgb(96, 165, 250), Color.rgb(167, 139, 250), Color.rgb(163, 230, 53),
            Color.rgb(241, 245, 249), Color.rgb(251, 146, 60), 0, Color.rgb(165, 243, 252)};
    private static final int[] SKIN_PRICES = {0, 40, 80, 120, 160, 200, 350, 500};
    private static final int SKIN_RAINBOW = 6, SKIN_GHOST = 7;

    private static final float MOVE_AMP = 0.22f; // how far moving blocks slide (radians)
    private static final int FREE_GEMS = 15;     // gems per "watch a video" in the shop

    // ------------------------------------------------------------ state

    private final Host host;
    private final Progress prog;
    private final Random rnd = new Random();

    private State state = State.MENU;
    private boolean running = false;
    private long lastFrameNanos = 0;
    private float time, stateTimer, worldTime;

    // geometry
    private float w, h, cx, cy, unit, playerSize, blockSize, planetR;
    private final float[] ringR = new float[4];
    private int ringCount = 2;

    // current run (level == null means Classic)
    private Level level;
    private float angle, radius, angVel, invulnerable, elapsed, spawnTimer;
    private int ring, score, gemsRun, earned, starsEarned, skin;
    private boolean continueUsed, doubled, newBest, firstClear, thirdEarned;
    private final float[] trailX = new float[14], trailY = new float[14];
    private int trailCount = 0;

    // tutorial
    private int tutStep, tutHops, tutAfter; // tutAfter: 0 = Classic, 1 = level, 2 = back to menu
    private boolean tutFreeze;
    private float tutTimer;
    private Level tutLevel;

    // ui
    private int worldPage;
    private boolean dailyShown;
    private String toast;
    private float toastTimer;

    private static final class Thing {
        float base, age, phase;
        int ring;
        boolean passed, gem, moving, blink;

        float angleAt(float t) {
            return moving ? base + MOVE_AMP * (float) Math.sin(t * 1.7f + phase) : base;
        }

        boolean solidAt(float t) {
            return !blink || (t + phase) % 2.0f < 1.2f;
        }
    }

    private final List<Thing> things = new ArrayList<>();

    private static final class Particle { float x, y, vx, vy, life; int color; }
    private final List<Particle> particles = new ArrayList<>();

    private final float[] starX = new float[90], starY = new float[90], starP = new float[90];

    private static final class Button { final RectF r = new RectF(); Runnable action; }
    private final List<Button> buttons = new ArrayList<>();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private Shader bgShader, planetShader;
    private int planetKey = -1;

    public GameView(Context context, Host host) {
        super(context);
        this.host = host;
        this.prog = new Progress(context);
        this.skin = prog.skin();
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat();
            starY[i] = rnd.nextFloat();
            starP[i] = rnd.nextFloat() * 6.28f;
        }
        setHapticFeedbackEnabled(true);
        ring = 1;
        angle = -(float) Math.PI / 2f;
        if (prog.tutorialDone() && prog.dailyAvailable()) {
            state = State.DAILY;
            dailyShown = true;
        }
    }

    // ------------------------------------------------------------ lifecycle

    public void resume() {
        if (!running) {
            running = true;
            lastFrameNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void pause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        if (state == State.PLAYING) state = State.PAUSED;
    }

    /** Back button: returns true if the game handled it. */
    public boolean onBack() {
        switch (state) {
            case MENU:
                return false;
            case DAILY:
            case LEVELS:
            case SHOP:
            case TUTORIAL:
                goToMenu();
                return true;
            default:
                if (level != null) goToLevels(pageFor(level.number));
                else goToMenu();
                return true;
        }
    }

    /** Called by the Activity when ad/purchase state changes so buttons refresh. */
    public void refreshUi() {
        post(this::invalidate);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw;
        h = nh;
        unit = Math.min(w, h * 0.75f);
        cx = w / 2f;
        cy = h * 0.44f;
        playerSize = unit * 0.024f;
        blockSize = unit * 0.032f;
        layoutRings(ringCount);
        radius = ringR[ring];
        bgShader = new LinearGradient(0, 0, 0, h, COL_BG_TOP, COL_BG_BOTTOM, Shader.TileMode.CLAMP);
        planetKey = -1;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastFrameNanos == 0 ? 0f : (frameTimeNanos - lastFrameNanos) / 1e9f;
        lastFrameNanos = frameTimeNanos;
        if (dt > 0.05f) dt = 0.05f;
        update(dt);
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // ------------------------------------------------------------ layout

    private void layoutRings(int n) {
        ringCount = n;
        if (ring >= n) ring = n - 1;
        if (unit == 0) return;
        float inner = unit * (n == 2 ? 0.23f : n == 3 ? 0.19f : 0.165f);
        float outer = unit * (n == 2 ? 0.36f : 0.39f);
        for (int i = 0; i < n; i++) ringR[i] = inner + (outer - inner) * i / (n - 1);
        planetR = unit * (n == 2 ? 0.11f : n == 3 ? 0.095f : 0.08f);
        planetKey = -1;
    }

    private int theme() {
        if (state == State.LEVELS) return worldPage + 1;
        return level != null ? level.world : 0;
    }

    private void ensurePlanetShader() {
        int t = theme();
        int key = t * 100000 + (int) planetR;
        if (key == planetKey && planetShader != null) return;
        planetKey = key;
        planetShader = new RadialGradient(cx - planetR * 0.3f, cy - planetR * 0.3f, planetR * 1.2f,
                PLANET_LIGHT[t], PLANET_DARK[t], Shader.TileMode.CLAMP);
    }

    private static int pageFor(int levelNumber) {
        return (Math.max(1, Math.min(Level.COUNT, levelNumber)) - 1) / Level.PER_WORLD;
    }

    // ------------------------------------------------------------ navigation

    private void resetOrbit(int rings) {
        things.clear();
        layoutRings(rings);
        ring = rings / 2;
        radius = ringR[ring];
        angle = -(float) Math.PI / 2f;
        trailCount = 0;
    }

    private void goToMenu() {
        level = null;
        resetOrbit(2);
        stateTimer = 0;
        if (!dailyShown && prog.tutorialDone() && prog.dailyAvailable()) {
            dailyShown = true;
            state = State.DAILY;
        } else {
            state = State.MENU;
        }
    }

    private void goToLevels(int page) {
        resetOrbit(2);
        worldPage = page;
        state = State.LEVELS;
        stateTimer = 0;
    }

    private void goToShop() {
        state = State.SHOP;
        stateTimer = 0;
    }

    private void requestLevel(Level l) {
        host.beforeNewGame(() -> beginLevel(l));
    }

    /** Starts a level (or Classic if null), showing the tutorial / intro card first if needed. */
    private void beginLevel(Level l) {
        if (!prog.tutorialDone()) {
            startTutorial(l == null ? 0 : 1, l);
            return;
        }
        if (l != null && l.intro != null && !prog.seenIntro(l.intro)) {
            level = l;
            resetOrbit(l.rings);
            state = State.INTRO;
            stateTimer = 0;
            return;
        }
        startRun(l);
    }

    private void startRun(Level l) {
        level = l;
        resetOrbit(l == null ? 2 : l.rings);
        particles.clear();
        score = 0;
        gemsRun = 0;
        earned = 0;
        elapsed = 0;
        worldTime = 0;
        invulnerable = 0;
        spawnTimer = 1.0f;
        continueUsed = false;
        doubled = false;
        newBest = false;
        state = State.PLAYING;
        stateTimer = 0;
    }

    // ------------------------------------------------------------ update

    private void update(float dt) {
        time += dt;
        stateTimer += dt;
        if (toastTimer > 0) toastTimer -= dt;
        updateParticles(dt);

        switch (state) {
            case PLAYING:
                updatePlaying(dt);
                break;
            case TUTORIAL:
                updateTutorial(dt);
                break;
            case DYING:
                if (stateTimer > 0.9f) {
                    state = State.GAME_OVER;
                    stateTimer = 0f;
                    host.onGameOver();
                }
                break;
            case MENU:
            case DAILY:
            case LEVELS:
            case SHOP:
            case INTRO:
                angle += 1.0f * dt; // idle orbit behind the menus
                moveRadius(dt);
                pushTrail();
                break;
            default:
                break;
        }
    }

    private void updatePlaying(float dt) {
        worldTime += dt;
        elapsed += dt;
        angVel = level == null ? Math.min(3.6f, 1.7f + score * 0.035f) : level.speed;
        angle += angVel * dt;
        moveRadius(dt);
        if (invulnerable > 0) invulnerable -= dt;
        pushTrail();

        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawn();
            spawnTimer = level == null
                    ? Math.max(0.42f, 1.05f - score * 0.016f)
                    : level.interval * (0.85f + rnd.nextFloat() * 0.3f);
        }

        float px = cx + (float) Math.cos(angle) * radius;
        float py = cy + (float) Math.sin(angle) * radius;

        Iterator<Thing> it = things.iterator();
        while (it.hasNext()) {
            Thing t = it.next();
            t.age += dt;
            float a = t.angleAt(worldTime);
            float d = wrap(a - angle);
            float r = ringR[t.ring];
            if (t.gem) {
                float gx = cx + (float) Math.cos(a) * r, gy = cy + (float) Math.sin(a) * r;
                if (dist(px, py, gx, gy) < playerSize + blockSize) {
                    gemsRun++;
                    earned++;
                    prog.addGems(1);
                    if (level == null) score += 3;
                    burst(gx, gy, COL_GEM, 14);
                    it.remove();
                    continue;
                }
            } else if (t.age > 0.45f && invulnerable <= 0 && t.solidAt(worldTime)
                    && Math.abs(radius - r) < playerSize + blockSize * 0.8f
                    && Math.abs(d) * r < playerSize + blockSize * 0.7f) {
                die(px, py);
                return;
            }
            if (!t.passed && d < -0.25f) {
                t.passed = true;
                if (!t.gem) score++;
            }
            if (d < -1.6f) it.remove();
        }

        if (goalReached()) completeLevel();
    }

    private void spawn() {
        float gap = angVel * 0.38f + 0.45f; // always leave time to hop
        float gemChance = level == null ? 0.14f : level.gemChance;
        for (int attempt = 0; attempt < 8; attempt++) {
            float a = angle + 2.0f + rnd.nextFloat();
            int rg = rnd.nextInt(ringCount);
            boolean gem = rnd.nextFloat() < gemChance;
            boolean mv = !gem && level != null && level.moving && rnd.nextFloat() < 0.45f;
            boolean bl = !gem && level != null && level.blinking && rnd.nextFloat() < 0.45f;
            boolean ok = true;
            for (Thing t : things) {
                if (t.gem) continue;
                float extra = (t.moving ? MOVE_AMP : 0) + (mv ? MOVE_AMP : 0);
                float diff = Math.abs(wrap(t.base - a));
                if ((t.ring != rg && diff < gap + extra) || (t.ring == rg && diff < 0.18f + extra)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            Thing t = new Thing();
            t.base = a;
            t.ring = rg;
            t.gem = gem;
            t.moving = mv;
            t.blink = bl;
            t.phase = rnd.nextFloat() * 6.28f;
            things.add(t);
            return;
        }
    }

    private boolean goalReached() {
        if (level == null) return false;
        switch (level.goalType) {
            case Level.GEMS: return gemsRun >= level.goal;
            case Level.TIME: return elapsed >= level.goal;
            default: return score >= level.goal;
        }
    }

    private float progressFraction() {
        if (level == null) return 0;
        float v;
        switch (level.goalType) {
            case Level.GEMS: v = gemsRun; break;
            case Level.TIME: v = elapsed; break;
            default: v = score;
        }
        return Math.min(1f, v / level.goal);
    }

    private String progressText() {
        switch (level.goalType) {
            case Level.GEMS: return "◆ " + Math.min(gemsRun, level.goal) + " / " + level.goal;
            case Level.TIME: return Math.min((int) elapsed, level.goal) + "s / " + level.goal + "s";
            default: return Math.min(score, level.goal) + " / " + level.goal;
        }
    }

    private void completeLevel() {
        int stars = 1;
        if (!continueUsed) stars++;
        boolean third = level.goalType == Level.GEMS ? elapsed <= level.starTime() : gemsRun >= level.starGems();
        if (third) stars++;
        thirdEarned = third;
        starsEarned = stars;
        firstClear = prog.stars(level.number) == 0;
        int bonus = stars * 2 + (firstClear ? 10 : 0);
        prog.addGems(bonus);
        earned += bonus;
        prog.recordLevel(level.number, stars);
        state = State.LEVEL_DONE;
        stateTimer = 0;
        float px = cx + (float) Math.cos(angle) * radius, py = cy + (float) Math.sin(angle) * radius;
        burst(px, py, COL_GEM, 30);
        burst(px, py, playerColor(), 20);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        host.onGameOver();
    }

    private void die(float x, float y) {
        burst(x, y, playerColor(), 30);
        burst(x, y, COL_BLOCK, 16);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        newBest = false;
        if (level == null && score > prog.best()) {
            prog.setBest(score);
            newBest = true;
        }
        state = State.DYING;
        stateTimer = 0f;
    }

    private void revive() {
        Iterator<Thing> it = things.iterator();
        while (it.hasNext()) {
            if (Math.abs(wrap(it.next().angleAt(worldTime) - angle)) < 1.4f) it.remove();
        }
        continueUsed = true;
        invulnerable = 1.6f;
        state = State.PLAYING;
        stateTimer = 0f;
    }

    private void hop(float x) {
        if (ringCount == 2) {
            ring = 1 - ring;
        } else if (x < w / 2f) {
            if (ring > 0) ring--;
        } else {
            if (ring < ringCount - 1) ring++;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void moveRadius(float dt) {
        radius += (ringR[ring] - radius) * Math.min(1f, dt * 22f);
    }

    private void pushTrail() {
        float px = cx + (float) Math.cos(angle) * radius;
        float py = cy + (float) Math.sin(angle) * radius;
        for (int i = trailX.length - 1; i > 0; i--) {
            trailX[i] = trailX[i - 1];
            trailY[i] = trailY[i - 1];
        }
        trailX[0] = px;
        trailY[0] = py;
        if (trailCount < trailX.length) trailCount++;
    }

    // ------------------------------------------------------------ tutorial

    private void startTutorial(int after, Level next) {
        tutAfter = after;
        tutLevel = next;
        level = null;
        resetOrbit(2);
        particles.clear();
        tutStep = 0;
        tutHops = 0;
        tutFreeze = false;
        tutTimer = 0;
        worldTime = 0;
        state = State.TUTORIAL;
        stateTimer = 0;
    }

    private void updateTutorial(float dt) {
        if (!tutFreeze) {
            worldTime += dt;
            angle += 1.25f * dt;
        }
        moveRadius(dt);
        pushTrail();
        Thing t = things.isEmpty() ? null : things.get(0);
        float d = t == null ? 0 : wrap(t.base - angle);
        switch (tutStep) {
            case 0: // learn to tap
                if (tutHops >= 2 && stateTimer > 1f) {
                    tutStep = 1;
                    spawnTutorialThing(false);
                }
                break;
            case 1: // dodge a block
                if (t == null) break;
                if (!tutFreeze && t.ring == ring && d > 0 && d < 0.7f) tutFreeze = true;
                if (d < -0.35f) {
                    tutStep = 2;
                    spawnTutorialThing(true);
                }
                break;
            case 2: // grab a gem
                if (t == null) break;
                float r = ringR[t.ring];
                float gx = cx + (float) Math.cos(t.base) * r, gy = cy + (float) Math.sin(t.base) * r;
                float px = cx + (float) Math.cos(angle) * radius, py = cy + (float) Math.sin(angle) * radius;
                if (dist(px, py, gx, gy) < playerSize + blockSize * 1.2f) {
                    burst(gx, gy, COL_GEM, 18);
                    things.clear();
                    tutFreeze = false;
                    tutStep = 3;
                    tutTimer = 0;
                    break;
                }
                if (!tutFreeze && t.ring != ring && d > 0 && d < 0.7f) tutFreeze = true;
                if (d < -0.35f) spawnTutorialThing(true); // missed it: try again
                break;
            case 3: // done
                tutTimer += dt;
                if (tutTimer > 1.8f) finishTutorial();
                break;
            default:
                break;
        }
    }

    private void spawnTutorialThing(boolean gem) {
        things.clear();
        Thing t = new Thing();
        t.gem = gem;
        t.ring = gem ? 1 - ring : ring;
        t.base = angle + 2.4f;
        t.age = 1f;
        things.add(t);
    }

    private void tutorialTap(float x) {
        if (tutStep == 3) return;
        hop(x);
        tutHops++;
        tutFreeze = false;
    }

    private void finishTutorial() {
        prog.setTutorialDone();
        things.clear();
        if (tutAfter == 2) goToMenu();
        else beginLevel(tutLevel);
    }

    // ------------------------------------------------------------ effects

    private void burst(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = unit * (0.2f + rnd.nextFloat() * 0.6f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp;
            p.life = 0.5f + rnd.nextFloat() * 0.5f;
            p.color = color;
            particles.add(p);
        }
    }

    private void updateParticles(float dt) {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 0.96f;
            p.vy *= 0.96f;
        }
    }

    private void showToast(String s) {
        toast = s;
        toastTimer = 2.2f;
    }

    private int playerColor() {
        if (skin == SKIN_RAINBOW) return Color.HSVToColor(new float[]{(time * 90f) % 360f, 0.55f, 1f});
        return SKIN_COLORS[skin];
    }

    private static int withAlpha(int color, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float wrap(float a) {
        double twoPi = Math.PI * 2;
        double r = (a + Math.PI) % twoPi;
        if (r < 0) r += twoPi;
        return (float) (r - Math.PI);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        float x = e.getX(), y = e.getY();
        switch (state) {
            case PLAYING:
                hop(x);
                break;
            case PAUSED:
                state = State.PLAYING;
                break;
            case TUTORIAL:
                if (!tapButton(x, y)) tutorialTap(x);
                break;
            case INTRO:
                if (stateTimer > 0.4f) {
                    prog.markIntro(level.intro);
                    startRun(level);
                }
                break;
            case GAME_OVER:
            case LEVEL_DONE:
                if (stateTimer > 0.5f) tapButton(x, y);
                break;
            case DYING:
                break;
            default:
                tapButton(x, y);
                break;
        }
        return true;
    }

    private boolean tapButton(float x, float y) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Button b = buttons.get(i);
            if (b.r.contains(x, y)) {
                if (b.action != null) b.action.run();
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (w == 0) return;
        fill.setShader(bgShader);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        for (int i = 0; i < starX.length; i++) {
            float tw = 0.35f + 0.35f * (float) Math.sin(time * 1.5f + starP[i]);
            fill.setColor(Color.argb((int) (255 * tw), 255, 255, 255));
            c.drawCircle(starX[i] * w, starY[i] * h, unit * 0.0035f * (1 + (i % 3)), fill);
        }

        stroke.setStrokeWidth(unit * 0.006f);
        stroke.setColor(Color.argb(70, 165, 180, 252));
        for (int i = 0; i < ringCount; i++) c.drawCircle(cx, cy, ringR[i], stroke);

        ensurePlanetShader();
        fill.setShader(planetShader);
        c.drawCircle(cx, cy, planetR, fill);
        fill.setShader(null);

        for (Thing t : things) {
            if (t.ring >= ringCount) continue;
            float r = ringR[t.ring];
            float a = t.angleAt(worldTime);
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (t.gem) drawGem(c, x, y, blockSize * (0.9f + 0.12f * (float) Math.sin(time * 6)));
            else drawBlock(c, x, y, a, t);
            if (state == State.TUTORIAL && tutFreeze) {
                stroke.setColor(withAlpha(t.gem ? COL_GEM : COL_BLOCK, (int) (150 + 100 * Math.sin(time * 8))));
                stroke.setStrokeWidth(unit * 0.006f);
                c.drawCircle(x, y, blockSize * 2.4f, stroke);
            }
        }

        if (state != State.DYING && state != State.GAME_OVER) drawPlayer(c);

        for (Particle p : particles) {
            fill.setColor(withAlpha(p.color, (int) (255 * Math.min(1f, p.life * 1.5f))));
            c.drawCircle(p.x, p.y, playerSize * 0.35f, fill);
        }

        buttons.clear();
        switch (state) {
            case MENU: drawMenu(c); break;
            case DAILY: drawDaily(c); break;
            case LEVELS: drawLevels(c); break;
            case SHOP: drawShop(c); break;
            case TUTORIAL: drawTutorial(c); break;
            case INTRO: drawIntro(c); break;
            case PLAYING: drawHud(c); break;
            case PAUSED:
                drawHud(c);
                centerText(c, "Tap to resume", h * 0.86f, unit * 0.06f, COL_TEXT);
                break;
            case DYING: drawHud(c); break;
            case GAME_OVER: drawGameOver(c); break;
            case LEVEL_DONE: drawLevelDone(c); break;
        }

        if (toastTimer > 0 && toast != null) {
            text.setTextSize(unit * 0.042f);
            float tw = text.measureText(toast) + unit * 0.1f;
            float ty = h * 0.94f;
            tmp.set(cx - tw / 2, ty - unit * 0.05f, cx + tw / 2, ty + unit * 0.05f);
            fill.setColor(Color.argb((int) (230 * Math.min(1f, toastTimer * 3)), 15, 23, 42));
            c.drawRoundRect(tmp, unit * 0.05f, unit * 0.05f, fill);
            centerText(c, toast, ty + unit * 0.015f, unit * 0.042f, withAlpha(COL_TEXT, (int) (255 * Math.min(1f, toastTimer * 3))));
        }
    }

    private void drawPlayer(Canvas c) {
        int pc = playerColor();
        float alpha = skin == SKIN_GHOST ? 0.55f : 1f;
        for (int i = trailCount - 1; i >= 1; i--) {
            float k = 1f - i / (float) trailX.length;
            fill.setColor(withAlpha(pc, (int) (110 * k * (skin == SKIN_GHOST ? 1.4f : 1f))));
            c.drawCircle(trailX[i], trailY[i], playerSize * (0.4f + 0.6f * k), fill);
        }
        boolean blink = invulnerable > 0 && ((int) (time * 12) % 2 == 0);
        if (!blink && trailCount > 0) {
            fill.setColor(withAlpha(pc, 60));
            c.drawCircle(trailX[0], trailY[0], playerSize * 2.2f, fill);
            fill.setColor(withAlpha(pc, (int) (255 * alpha)));
            c.drawCircle(trailX[0], trailY[0], playerSize, fill);
        }
    }

    private void drawBlock(Canvas c, float x, float y, float a, Thing t) {
        float s = blockSize;
        c.save();
        c.rotate((float) Math.toDegrees(a) + 45f, x, y);
        tmp.set(x - s, y - s, x + s, y + s);
        boolean solid = t.solidAt(worldTime);
        if (t.age < 0.45f || !solid) { // warning phase / blinked out: outline only
            int al = t.age < 0.45f ? (int) (90 + 120 * Math.abs(Math.sin(t.age * 20))) : 80;
            stroke.setColor(withAlpha(COL_BLOCK, al));
            stroke.setStrokeWidth(unit * 0.005f);
            c.drawRoundRect(tmp, s * 0.25f, s * 0.25f, stroke);
        } else {
            fill.setColor(Color.argb(70, 251, 113, 133));
            c.drawCircle(x, y, s * 1.7f, fill);
            fill.setColor(COL_BLOCK);
            c.drawRoundRect(tmp, s * 0.25f, s * 0.25f, fill);
            if (t.moving) { // hollow core marks moving blocks
                fill.setColor(COL_BG_TOP);
                tmp.set(x - s * 0.4f, y - s * 0.4f, x + s * 0.4f, y + s * 0.4f);
                c.drawRect(tmp, fill);
            }
        }
        c.restore();
    }

    private void drawGem(Canvas c, float x, float y, float s) {
        path.reset();
        path.moveTo(x, y - s);
        path.lineTo(x + s * 0.75f, y);
        path.lineTo(x, y + s);
        path.lineTo(x - s * 0.75f, y);
        path.close();
        fill.setColor(Color.argb(60, 253, 224, 71));
        c.drawCircle(x, y, s * 1.6f, fill);
        fill.setColor(COL_GEM);
        c.drawPath(path, fill);
    }

    private void drawGemIcon(Canvas c, float x, float y, float s) {
        path.reset();
        path.moveTo(x, y - s);
        path.lineTo(x + s * 0.75f, y);
        path.lineTo(x, y + s);
        path.lineTo(x - s * 0.75f, y);
        path.close();
        fill.setColor(COL_GEM);
        c.drawPath(path, fill);
    }

    private void drawStar(Canvas c, float x, float y, float r, int color) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? r : r * 0.45f;
            float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private void drawGemCounter(Canvas c) {
        String s = String.valueOf(prog.gems());
        float size = unit * 0.05f;
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(COL_GEM);
        float x = w - unit * 0.06f, y = unit * 0.11f;
        c.drawText(s, x, y, text);
        float tw = text.measureText(s);
        text.setTextAlign(Paint.Align.CENTER);
        drawGemIcon(c, x - tw - size * 0.55f, y - size * 0.35f, size * 0.42f);
    }

    private void dim(Canvas c, int alpha) {
        fill.setColor(Color.argb(alpha, 5, 8, 22));
        c.drawRect(0, 0, w, h, fill);
    }

    private void card(Canvas c, float top, float bottom) {
        tmp.set(cx - unit * 0.44f, top, cx + unit * 0.44f, bottom);
        fill.setColor(COL_CARD);
        c.drawRoundRect(tmp, unit * 0.05f, unit * 0.05f, fill);
        stroke.setColor(Color.argb(120, 165, 180, 252));
        stroke.setStrokeWidth(unit * 0.004f);
        c.drawRoundRect(tmp, unit * 0.05f, unit * 0.05f, stroke);
    }

    // ---- screens

    private void drawMenu(Canvas c) {
        drawGemCounter(c);
        centerText(c, "ORBIT HOP", h * 0.1f, unit * 0.12f, COL_TEXT);
        centerText(c, "One tap. Endless orbits.", h * 0.1f + unit * 0.08f, unit * 0.042f, COL_DIM);

        float y = h * 0.70f;
        float sp = unit * 0.135f;
        int next = prog.unlocked();
        String journey = prog.allCleared() ? "▶  Journey  ·  All clear!" : "▶  Journey  ·  Level " + next;
        wideButton(c, journey, y, 0, () -> goToLevels(pageFor(next)));
        y += sp;
        wideButton(c, "Classic  ·  Best " + prog.best(), y, 1, () -> requestLevel(null));
        y += sp;
        wideButton(c, "Shop  ·  Skins", y, 1, this::goToShop);
        y += sp * 0.85f;

        String price = host.removeAdsPrice();
        if (price != null) {
            link(c, "How to play", cx - unit * 0.22f, y, () -> startTutorial(2, null));
            link(c, "Remove ads " + price, cx + unit * 0.22f, y, host::buyRemoveAds);
        } else {
            link(c, "How to play", cx, y, () -> startTutorial(2, null));
        }
        if (host.privacyButtonNeeded()) {
            link(c, "Privacy settings", cx, Math.min(y + unit * 0.08f, h - unit * 0.04f), host::openPrivacyOptions);
        }
    }

    private void drawDaily(Canvas c) {
        centerText(c, "ORBIT HOP", h * 0.1f, unit * 0.12f, COL_TEXT);
        dim(c, 190);
        float top = cy - unit * 0.36f, bottom = cy + unit * 0.62f;
        card(c, top, bottom);
        int day = prog.dailyDay();
        int amount = prog.dailyAmount();
        centerText(c, "DAILY GIFT", top + unit * 0.11f, unit * 0.07f, COL_GEM);
        centerText(c, "Day " + day + " of 7  ·  come back tomorrow for more", top + unit * 0.18f, unit * 0.034f, COL_DIM);

        float bs = unit * 0.075f, gap = unit * 0.02f;
        float total = 7 * bs + 6 * gap, x0 = cx - total / 2;
        float by = top + unit * 0.24f;
        for (int i = 0; i < 7; i++) {
            tmp.set(x0 + i * (bs + gap), by, x0 + i * (bs + gap) + bs, by + bs);
            if (i < day) {
                fill.setColor(i == day - 1 ? COL_GEM : withAlpha(COL_GEM, 110));
                c.drawRoundRect(tmp, bs * 0.2f, bs * 0.2f, fill);
            } else {
                stroke.setColor(COL_OFF);
                stroke.setStrokeWidth(unit * 0.004f);
                c.drawRoundRect(tmp, bs * 0.2f, bs * 0.2f, stroke);
            }
            centerText(c, String.valueOf(i + 1), tmp.centerX(), tmp.centerY() + bs * 0.15f, bs * 0.4f, i < day ? COL_BG_TOP : COL_DIM);
        }

        float gy = by + bs + unit * 0.15f;
        drawGemIcon(c, cx - unit * 0.1f, gy - unit * 0.03f, unit * 0.045f);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(unit * 0.09f);
        text.setColor(COL_TEXT);
        c.drawText("+" + amount, cx - unit * 0.05f, gy, text);
        text.setTextAlign(Paint.Align.CENTER);

        float y = gy + unit * 0.12f;
        if (host.canOfferContinue()) {
            String label = host.continueNeedsAd() ? "▶  Claim 2×  (watch video)" : "Claim 2×";
            button(c, label, cx, y, unit * 0.78f, unit * 0.11f, 2, () -> host.showContinueAd(() -> {
                int got = prog.claimDaily(2);
                showToast("+" + got + " gems!");
                state = State.MENU;
            }));
            y += unit * 0.13f;
        }
        button(c, "Claim", cx, y, unit * 0.78f, unit * 0.11f, host.canOfferContinue() ? 1 : 0, () -> {
            int got = prog.claimDaily(1);
            showToast("+" + got + " gems!");
            state = State.MENU;
        });
    }

    private void drawLevels(Canvas c) {
        dim(c, 150);
        drawGemCounter(c);
        int world = worldPage + 1;
        centerText(c, "WORLD " + world, h * 0.07f, unit * 0.04f, COL_DIM);
        centerText(c, Level.WORLD_NAMES[worldPage].toUpperCase(), h * 0.07f + unit * 0.1f, unit * 0.1f, COL_TEXT);
        float sy = h * 0.07f + unit * 0.17f;
        drawStar(c, cx - unit * 0.09f, sy - unit * 0.015f, unit * 0.025f, COL_GEM);
        centerText(c, prog.starsInWorld(world) + " / " + (Level.PER_WORLD * 3), cx + unit * 0.03f, sy, unit * 0.04f, COL_GEM);

        int unlocked = prog.unlocked();
        boolean allClear = prog.allCleared();
        float tile = unit * 0.15f, gap = unit * 0.03f;
        float total = 5 * tile + 4 * gap;
        float x0 = cx - total / 2, y0 = cy - tile - gap / 2;
        for (int i = 0; i < Level.PER_WORLD; i++) {
            final int n = worldPage * Level.PER_WORLD + i + 1;
            float x = x0 + (i % 5) * (tile + gap), y = y0 + (i / 5) * (tile + gap);
            Button b = new Button();
            b.r.set(x, y, x + tile, y + tile);
            boolean open = n <= unlocked;
            float rad = tile * 0.22f;
            if (open) {
                fill.setColor(Color.rgb(30, 41, 82));
                c.drawRoundRect(b.r, rad, rad, fill);
                boolean current = n == unlocked && !allClear;
                stroke.setColor(current ? COL_ACCENT : Color.argb(140, 165, 180, 252));
                stroke.setStrokeWidth(unit * (current ? 0.007f : 0.004f));
                c.drawRoundRect(b.r, rad, rad, stroke);
                centerText(c, String.valueOf(n), x + tile / 2, y + tile * 0.52f, tile * 0.38f, COL_TEXT);
                int st = prog.stars(n);
                for (int s = 0; s < 3; s++) {
                    drawStar(c, x + tile * (0.28f + s * 0.22f), y + tile * 0.78f, tile * 0.085f, s < st ? COL_GEM : COL_OFF);
                }
                b.action = () -> requestLevel(Level.get(n));
            } else {
                fill.setColor(Color.rgb(17, 24, 52));
                c.drawRoundRect(b.r, rad, rad, fill);
                drawLock(c, x + tile / 2, y + tile / 2, tile * 0.16f);
                b.action = () -> showToast("Clear level " + (n - 1) + " to unlock");
            }
            buttons.add(b);
        }

        float ay = y0 + 2 * tile + gap + unit * 0.13f;
        if (worldPage > 0) {
            button(c, "◀  " + Level.WORLD_NAMES[worldPage - 1], cx - unit * 0.2f, ay, unit * 0.36f, unit * 0.1f, 1,
                    () -> worldPage--);
        }
        if (worldPage < Level.WORLD_NAMES.length - 1) {
            button(c, Level.WORLD_NAMES[worldPage + 1] + "  ▶", cx + unit * 0.2f, ay, unit * 0.36f, unit * 0.1f, 1,
                    () -> worldPage++);
        }
        wideButton(c, "Back", Math.max(ay + unit * 0.16f, h * 0.86f), 1, this::goToMenu);
    }

    private void drawLock(Canvas c, float x, float y, float s) {
        stroke.setColor(COL_OFF);
        stroke.setStrokeWidth(s * 0.3f);
        tmp.set(x - s * 0.6f, y - s * 1.3f, x + s * 0.6f, y + s * 0.2f);
        c.drawRoundRect(tmp, s * 0.6f, s * 0.6f, stroke);
        fill.setColor(COL_OFF);
        tmp.set(x - s, y - s * 0.2f, x + s, y + s * 1.1f);
        c.drawRoundRect(tmp, s * 0.2f, s * 0.2f, fill);
    }

    private void drawShop(Canvas c) {
        dim(c, 170);
        drawGemCounter(c);
        centerText(c, "SHOP", h * 0.08f + unit * 0.06f, unit * 0.1f, COL_TEXT);
        centerText(c, "Earn gems by playing levels", h * 0.08f + unit * 0.12f, unit * 0.036f, COL_DIM);

        float tw = unit * 0.2f, th = unit * 0.25f, gap = unit * 0.025f;
        float total = 4 * tw + 3 * gap;
        float x0 = cx - total / 2, y0 = h * 0.08f + unit * 0.2f;
        int gems = prog.gems();
        for (int i = 0; i < SKIN_NAMES.length; i++) {
            final int idx = i;
            float x = x0 + (i % 4) * (tw + gap), y = y0 + (i / 4) * (th + gap);
            Button b = new Button();
            b.r.set(x, y, x + tw, y + th);
            boolean owned = prog.ownsSkin(i), equipped = skin == i;
            fill.setColor(Color.rgb(30, 41, 82));
            c.drawRoundRect(b.r, tw * 0.15f, tw * 0.15f, fill);
            stroke.setColor(equipped ? COL_ACCENT : Color.argb(110, 165, 180, 252));
            stroke.setStrokeWidth(unit * (equipped ? 0.007f : 0.004f));
            c.drawRoundRect(b.r, tw * 0.15f, tw * 0.15f, stroke);

            int col = i == SKIN_RAINBOW
                    ? Color.HSVToColor(new float[]{(time * 90f + i * 40) % 360f, 0.55f, 1f}) : SKIN_COLORS[i];
            float pcx = x + tw / 2, pcy = y + th * 0.33f;
            fill.setColor(withAlpha(col, 60));
            c.drawCircle(pcx, pcy, tw * 0.2f, fill);
            fill.setColor(withAlpha(col, i == SKIN_GHOST ? 140 : 255));
            c.drawCircle(pcx, pcy, tw * 0.1f, fill);
            centerText(c, SKIN_NAMES[i], pcx, y + th * 0.67f, tw * 0.15f, COL_TEXT);

            if (equipped) {
                centerText(c, "Equipped", pcx, y + th * 0.87f, tw * 0.13f, COL_ACCENT);
                b.action = null;
            } else if (owned) {
                centerText(c, "Use", pcx, y + th * 0.87f, tw * 0.14f, COL_DIM);
                b.action = () -> equipSkin(idx);
            } else {
                int price = SKIN_PRICES[i];
                centerText(c, "◆ " + price, pcx, y + th * 0.87f, tw * 0.14f, gems >= price ? COL_GEM : COL_OFF);
                b.action = () -> {
                    if (prog.spendGems(price)) {
                        prog.unlockSkin(idx);
                        equipSkin(idx);
                        showToast(SKIN_NAMES[idx] + " unlocked!");
                    } else {
                        showToast("You need " + (price - prog.gems()) + " more gems");
                    }
                };
            }
            buttons.add(b);
        }

        float y = y0 + 2 * th + gap + unit * 0.12f;
        int left = prog.freeVideosLeft();
        if (left > 0 && host.canOfferContinue()) {
            String label = host.continueNeedsAd()
                    ? "▶  Free " + FREE_GEMS + " gems  (" + left + " left today)"
                    : "Free " + FREE_GEMS + " gems  (" + left + " left today)";
            wideButton(c, label, y, 2, () -> host.showContinueAd(() -> {
                prog.addGems(FREE_GEMS);
                prog.useFreeVideo();
                showToast("+" + FREE_GEMS + " gems!");
            }));
            y += unit * 0.135f;
        }
        wideButton(c, "Back", Math.max(y, h * 0.86f), 1, this::goToMenu);
    }

    private void equipSkin(int i) {
        skin = i;
        prog.setSkin(i);
    }

    private void drawTutorial(Canvas c) {
        centerText(c, "HOW TO PLAY", h * 0.08f, unit * 0.06f, COL_DIM);
        text.setTextSize(unit * 0.042f);
        float skipW = text.measureText("Skip") + unit * 0.08f;
        link(c, "Skip", w - skipW / 2 - unit * 0.03f, unit * 0.1f, this::finishTutorial);

        float ty = cy + ringR[1] + unit * 0.16f;
        String title, sub;
        switch (tutStep) {
            case 0:
                title = "Tap anywhere to hop";
                sub = "Your comet jumps between the two rings";
                break;
            case 1:
                title = tutFreeze ? "Tap now!" : "Red blocks end your run";
                sub = tutFreeze ? "Hop to the other ring to dodge it" : "Hop to the other ring to avoid them";
                break;
            case 2:
                title = tutFreeze ? "Tap to grab it!" : "Collect gold gems";
                sub = "Gems unlock new skins in the shop";
                break;
            default:
                title = "You're ready!";
                sub = "Good luck out there";
                break;
        }
        int titleColor = tutFreeze ? COL_GEM : (tutStep == 3 ? COL_ACCENT : COL_TEXT);
        float pulse = tutFreeze ? 1f + 0.06f * (float) Math.sin(time * 10) : 1f;
        centerText(c, title, ty, unit * 0.065f * pulse, titleColor);
        centerText(c, sub, ty + unit * 0.07f, unit * 0.038f, COL_DIM);

        if (tutStep == 0 || tutFreeze) { // pulsing tap hint
            float hy = ty + unit * 0.22f;
            float p = (time * 1.4f) % 1f;
            stroke.setColor(withAlpha(COL_TEXT, (int) (200 * (1 - p))));
            stroke.setStrokeWidth(unit * 0.005f);
            c.drawCircle(cx, hy, unit * (0.03f + 0.06f * p), stroke);
            fill.setColor(COL_TEXT);
            c.drawCircle(cx, hy, unit * 0.022f, fill);
        }
        // step dots
        for (int i = 0; i < 3; i++) {
            fill.setColor(i < tutStep ? COL_ACCENT : (i == tutStep ? COL_TEXT : COL_OFF));
            c.drawCircle(cx + (i - 1) * unit * 0.05f, h * 0.08f + unit * 0.06f, unit * 0.012f, fill);
        }
    }

    private void drawIntro(Canvas c) {
        dim(c, 170);
        float top = cy - unit * 0.3f, bottom = cy + unit * 0.42f;
        card(c, top, bottom);
        centerText(c, "NEW!", top + unit * 0.09f, unit * 0.045f, COL_GEM);
        centerText(c, Level.introTitle(level.intro), top + unit * 0.18f, unit * 0.075f, COL_TEXT);
        float y = wrapped(c, Level.introText(level.intro), cx, top + unit * 0.28f, unit * 0.74f, unit * 0.042f, COL_DIM);
        if (level.rings >= 3) {
            y += unit * 0.04f;
            float bw = unit * 0.34f, bh = unit * 0.1f;
            tmp.set(cx - bw - unit * 0.01f, y, cx - unit * 0.01f, y + bh);
            stroke.setColor(COL_ACCENT);
            stroke.setStrokeWidth(unit * 0.004f);
            c.drawRoundRect(tmp, bh * 0.3f, bh * 0.3f, stroke);
            centerText(c, "◀  IN", tmp.centerX(), tmp.centerY() + bh * 0.15f, bh * 0.38f, COL_ACCENT);
            tmp.set(cx + unit * 0.01f, y, cx + bw + unit * 0.01f, y + bh);
            c.drawRoundRect(tmp, bh * 0.3f, bh * 0.3f, stroke);
            centerText(c, "OUT  ▶", tmp.centerX(), tmp.centerY() + bh * 0.15f, bh * 0.38f, COL_ACCENT);
        }
        float pulse = 0.6f + 0.4f * (float) Math.sin(time * 4);
        centerText(c, "Tap to start", bottom - unit * 0.06f, unit * 0.05f, withAlpha(COL_TEXT, (int) (255 * pulse)));
        centerText(c, "LEVEL " + level.number + "  ·  " + level.goalText(), bottom + unit * 0.08f, unit * 0.04f, COL_DIM);
    }

    private void drawHud(Canvas c) {
        if (level == null) {
            centerText(c, String.valueOf(score), unit * 0.2f, unit * 0.14f, COL_TEXT);
            centerText(c, "BEST " + prog.best(), unit * 0.27f, unit * 0.04f, COL_DIM);
            return;
        }
        centerText(c, "LEVEL " + level.number, unit * 0.09f, unit * 0.038f, COL_DIM);
        centerText(c, progressText(), unit * 0.19f, unit * 0.09f, COL_TEXT);
        float bw = unit * 0.5f, bh = unit * 0.014f, by = unit * 0.235f;
        tmp.set(cx - bw / 2, by, cx + bw / 2, by + bh);
        fill.setColor(COL_OFF);
        c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
        tmp.set(cx - bw / 2, by, cx - bw / 2 + bw * progressFraction(), by + bh);
        fill.setColor(COL_ACCENT);
        c.drawRoundRect(tmp, bh / 2, bh / 2, fill);

        if (ringCount >= 3) { // control reminder
            int al = elapsed < 5 ? 200 : 70;
            centerText(c, "◀  IN", w * 0.22f, h - unit * 0.06f, unit * 0.04f, withAlpha(COL_DIM, al));
            centerText(c, "OUT  ▶", w * 0.78f, h - unit * 0.06f, unit * 0.04f, withAlpha(COL_DIM, al));
            stroke.setColor(withAlpha(COL_DIM, al / 3));
            stroke.setStrokeWidth(unit * 0.003f);
            c.drawLine(cx, h - unit * 0.14f, cx, h - unit * 0.02f, stroke);
        }

        if (state == State.PLAYING && elapsed < 2.6f) { // goal banner
            int al = (int) (255 * Math.min(1f, (2.6f - elapsed) * 2f));
            float y = cy + ringR[ringCount - 1] + unit * 0.13f;
            centerText(c, level.goalText(), y, unit * 0.06f, withAlpha(COL_TEXT, al));
            centerText(c, "★★★  " + level.thirdStarText(), y + unit * 0.065f, unit * 0.036f, withAlpha(COL_GEM, al));
        }
    }

    private void drawGameOver(Canvas c) {
        dim(c, 150);
        float by;
        if (level == null) {
            centerText(c, "GAME OVER", h * 0.2f, unit * 0.1f, COL_BLOCK);
            centerText(c, String.valueOf(score), h * 0.2f + unit * 0.22f, unit * 0.2f, COL_TEXT);
            centerText(c, newBest ? "NEW BEST!" : "BEST " + prog.best(), h * 0.2f + unit * 0.31f, unit * 0.05f, COL_GEM);
        } else {
            centerText(c, "LEVEL " + level.number, h * 0.16f, unit * 0.045f, COL_DIM);
            centerText(c, "SO CLOSE!", h * 0.16f + unit * 0.11f, unit * 0.1f, COL_BLOCK);
            centerText(c, progressText(), h * 0.16f + unit * 0.24f, unit * 0.09f, COL_TEXT);
            centerText(c, level.goalText(), h * 0.16f + unit * 0.31f, unit * 0.04f, COL_DIM);
        }
        if (earned > 0) {
            centerText(c, "+" + earned + " gems collected", h * 0.2f + unit * 0.4f, unit * 0.04f, COL_GEM);
        }

        by = h * 0.62f;
        boolean canContinue = !continueUsed && (level != null || score > 0) && host.canOfferContinue();
        if (canContinue) {
            wideButton(c, host.continueNeedsAd() ? "▶  Continue  (watch video)" : "▶  Continue", by, 2,
                    () -> host.showContinueAd(this::revive));
            by += unit * 0.135f;
        }
        final Level l = level;
        wideButton(c, l == null ? "Play again" : "Retry", by, canContinue ? 1 : 0, () -> requestLevel(l));
        by += unit * 0.135f;
        if (l == null) wideButton(c, "Menu", by, 1, this::goToMenu);
        else wideButton(c, "Levels", by, 1, () -> goToLevels(pageFor(l.number)));
    }

    private void drawLevelDone(Canvas c) {
        dim(c, 150);
        centerText(c, "LEVEL " + level.number, h * 0.1f, unit * 0.045f, COL_DIM);
        centerText(c, "COMPLETE!", h * 0.1f + unit * 0.11f, unit * 0.11f, COL_ACCENT);

        float sy = h * 0.1f + unit * 0.27f;
        for (int i = 0; i < 3; i++) {
            float appear = Math.min(1f, Math.max(0f, (stateTimer - 0.25f - i * 0.3f) * 5f));
            float sx = cx + (i - 1) * unit * 0.17f;
            float r = unit * 0.07f;
            drawStar(c, sx, sy, r, COL_OFF);
            if (i < starsEarned && appear > 0) {
                float pop = appear < 1f ? appear * 1.25f : 1f;
                drawStar(c, sx, sy, r * pop, COL_GEM);
            }
        }
        float ly = sy + unit * 0.14f;
        String[] labels = {"Level cleared", "No continue used", level.thirdStarText()};
        boolean[] got = {true, !continueUsed, thirdEarned};
        for (int i = 0; i < 3; i++) {
            int col = got[i] ? COL_GEM : COL_OFF;
            drawStar(c, cx - unit * 0.27f, ly + i * unit * 0.06f - unit * 0.013f, unit * 0.018f, col);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(unit * 0.036f);
            text.setColor(got[i] ? COL_TEXT : COL_DIM);
            c.drawText(labels[i], cx - unit * 0.23f, ly + i * unit * 0.06f, text);
            text.setTextAlign(Paint.Align.CENTER);
        }

        float gy = ly + unit * 0.24f;
        String gemLine = "+" + earned + (doubled ? " gems  (doubled!)" : " gems") + (firstClear ? "  ·  first clear bonus" : "");
        centerText(c, gemLine, gy, unit * 0.05f, COL_GEM);

        float by = gy + unit * 0.12f;
        final Level l = level;
        if (l.number < Level.COUNT) {
            wideButton(c, "Next level  ▶", by, 0, () -> requestLevel(Level.get(l.number + 1)));
        } else {
            wideButton(c, "All levels cleared!", by, 0, () -> goToLevels(pageFor(l.number)));
        }
        by += unit * 0.135f;
        if (!doubled && earned > 0 && host.canOfferContinue()) {
            final int extra = earned;
            wideButton(c, host.continueNeedsAd() ? "▶  Double gems  (watch video)" : "Double gems", by, 2,
                    () -> host.showContinueAd(() -> {
                        prog.addGems(extra);
                        earned += extra;
                        doubled = true;
                    }));
            by += unit * 0.135f;
        }
        button(c, "Replay", cx - unit * 0.2f, by, unit * 0.37f, unit * 0.1f, 1, () -> requestLevel(l));
        button(c, "Levels", cx + unit * 0.2f, by, unit * 0.37f, unit * 0.1f, 1, () -> goToLevels(pageFor(l.number + 1)));
    }

    // ---- ui helpers

    /** style: 0 = primary (teal), 1 = outline, 2 = gold (rewarded video) */
    private void button(Canvas c, String label, float bx, float by, float bw, float bh, int style, Runnable action) {
        Button b = new Button();
        b.r.set(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2);
        b.action = action;
        buttons.add(b);
        float rad = bh / 2;
        if (style == 1) {
            stroke.setColor(Color.argb(160, 165, 180, 252));
            stroke.setStrokeWidth(unit * 0.005f);
            c.drawRoundRect(b.r, rad, rad, stroke);
        } else {
            fill.setColor(style == 2 ? COL_GEM : COL_ACCENT);
            c.drawRoundRect(b.r, rad, rad, fill);
        }
        text.setTextSize(Math.min(unit * 0.048f, bh * 0.42f));
        float tw = text.measureText(label);
        if (tw > bw * 0.88f) text.setTextSize(text.getTextSize() * bw * 0.88f / tw);
        text.setColor(style == 1 ? COL_TEXT : COL_BG_TOP);
        c.drawText(label, bx, by - (text.descent() + text.ascent()) / 2, text);
    }

    private void wideButton(Canvas c, String label, float y, int style, Runnable action) {
        button(c, label, cx, y, unit * 0.78f, unit * 0.11f, style, action);
    }

    private void link(Canvas c, String label, float x, float y, Runnable action) {
        text.setTextSize(unit * 0.038f);
        float tw = text.measureText(label);
        Button b = new Button();
        b.r.set(x - tw / 2 - unit * 0.04f, y - unit * 0.05f, x + tw / 2 + unit * 0.04f, y + unit * 0.05f);
        b.action = action;
        buttons.add(b);
        text.setColor(COL_DIM);
        c.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text);
    }

    private void centerText(Canvas c, String s, float y, float size, int color) {
        centerText(c, s, cx, y, size, color);
    }

    private void centerText(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private float wrapped(Canvas c, String s, float x, float y, float maxW, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        float lh = size * 1.4f;
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (text.measureText(test) > maxW && line.length() > 0) {
                c.drawText(line.toString(), x, y, text);
                y += lh;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            c.drawText(line.toString(), x, y, text);
            y += lh;
        }
        return y;
    }
}
