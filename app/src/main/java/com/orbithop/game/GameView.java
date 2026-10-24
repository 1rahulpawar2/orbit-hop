package com.orbithop.game;

import android.content.Context;
import android.content.SharedPreferences;
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
 * The whole game: a dot orbits a planet on one of two rings.
 * Tap to hop between rings, dodge the blocks, grab the gems.
 */
public class GameView extends View implements Choreographer.FrameCallback {

    /** Things the game asks the Activity for (ads, purchases, privacy). */
    public interface Host {
        boolean canOfferContinue();
        boolean continueNeedsAd();
        void showContinueAd(Runnable onRewarded);
        void onGameOver();
        void beforeNewGame(Runnable startGame);
        String removeAdsPrice(); // null = don't show the button
        void buyRemoveAds();
        boolean privacyButtonNeeded();
        void openPrivacyOptions();
    }

    private enum State { MENU, PLAYING, PAUSED, DYING, GAME_OVER }

    private static final int COL_BG_TOP = Color.rgb(11, 16, 38);
    private static final int COL_BG_BOTTOM = Color.rgb(28, 20, 62);
    private static final int COL_PLAYER = Color.rgb(94, 234, 212);
    private static final int COL_BLOCK = Color.rgb(251, 113, 133);
    private static final int COL_GEM = Color.rgb(253, 224, 71);
    private static final int COL_TEXT = Color.rgb(238, 242, 255);
    private static final int COL_DIM = Color.rgb(148, 163, 184);

    private final Host host;
    private final Random rnd = new Random();
    private final SharedPreferences prefs;

    private State state = State.MENU;
    private boolean running = false;
    private long lastFrameNanos = 0;
    private float time = 0f;

    // geometry
    private float w, h, cx, cy, unit, rInner, rOuter, playerSize, blockSize;

    // player
    private float angle, radius, angVel;
    private int ring;
    private float invulnerable;
    private final float[] trailX = new float[14], trailY = new float[14];
    private int trailCount = 0;

    // world
    private static final class Thing { float angle; int ring; float age; boolean passed; boolean gem; }
    private final List<Thing> things = new ArrayList<>();
    private float spawnTimer;
    private int score, best;
    private boolean continueUsed;
    private float stateTimer;

    // particles
    private static final class Particle { float x, y, vx, vy, life; int color; }
    private final List<Particle> particles = new ArrayList<>();

    // background stars
    private final float[] starX = new float[90], starY = new float[90], starP = new float[90];

    // ui
    private static final class Button { final RectF r = new RectF(); String label; boolean primary; Runnable action; }
    private final List<Button> buttons = new ArrayList<>();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Shader bgShader, planetShader;

    public GameView(Context context, Host host) {
        super(context);
        this.host = host;
        this.prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE);
        this.best = prefs.getInt("best", 0);
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rnd.nextFloat();
            starY[i] = rnd.nextFloat();
            starP[i] = rnd.nextFloat() * 6.28f;
        }
        setHapticFeedbackEnabled(true);
        resetPlayer();
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
        if (state == State.PLAYING || state == State.PAUSED || state == State.GAME_OVER) {
            goToMenu();
            return true;
        }
        return false;
    }

    /** Called by the Activity when ad/purchase state changes so buttons refresh. */
    public void refreshUi() {
        post(this::invalidate);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh;
        unit = Math.min(w, h * 0.75f);
        cx = w / 2f;
        cy = h * 0.47f;
        rInner = unit * 0.23f;
        rOuter = unit * 0.36f;
        playerSize = unit * 0.024f;
        blockSize = unit * 0.032f;
        radius = ring == 0 ? rInner : rOuter;
        bgShader = new LinearGradient(0, 0, 0, h, COL_BG_TOP, COL_BG_BOTTOM, Shader.TileMode.CLAMP);
        planetShader = new RadialGradient(cx - unit * 0.03f, cy - unit * 0.03f, unit * 0.12f,
                Color.rgb(129, 140, 248), Color.rgb(49, 46, 129), Shader.TileMode.CLAMP);
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

    // ------------------------------------------------------------ game logic

    private void resetPlayer() {
        angle = -(float) Math.PI / 2f;
        ring = 1;
        radius = rOuter;
        angVel = 1.7f;
        invulnerable = 0f;
        trailCount = 0;
    }

    private void startGame() {
        things.clear();
        particles.clear();
        resetPlayer();
        score = 0;
        spawnTimer = 0.6f;
        continueUsed = false;
        state = State.PLAYING;
    }

    private void goToMenu() {
        things.clear();
        particles.clear();
        resetPlayer();
        state = State.MENU;
    }

    private void update(float dt) {
        time += dt;
        stateTimer += dt;
        updateParticles(dt);

        if (state == State.DYING) {
            if (stateTimer > 0.9f) {
                state = State.GAME_OVER;
                stateTimer = 0f;
                host.onGameOver();
            }
            return;
        }
        if (state == State.MENU) {
            angle += 1.1f * dt; // idle orbit on the menu
            pushTrail();
            return;
        }
        if (state != State.PLAYING) return;

        angVel = Math.min(3.6f, 1.7f + score * 0.035f);
        angle += angVel * dt;
        float target = ring == 0 ? rInner : rOuter;
        radius += (target - radius) * Math.min(1f, dt * 22f);
        if (invulnerable > 0) invulnerable -= dt;
        pushTrail();

        spawnTimer -= dt;
        if (spawnTimer <= 0) {
            spawn();
            spawnTimer = Math.max(0.42f, 1.05f - score * 0.016f);
        }

        float px = cx + (float) Math.cos(angle) * radius;
        float py = cy + (float) Math.sin(angle) * radius;

        Iterator<Thing> it = things.iterator();
        while (it.hasNext()) {
            Thing t = it.next();
            t.age += dt;
            float d = wrap(t.angle - angle);
            float r = t.ring == 0 ? rInner : rOuter;
            if (t.gem) {
                float gx = cx + (float) Math.cos(t.angle) * r, gy = cy + (float) Math.sin(t.angle) * r;
                if (dist(px, py, gx, gy) < playerSize + blockSize) {
                    score += 3;
                    burst(gx, gy, COL_GEM, 14);
                    it.remove();
                    continue;
                }
            } else if (t.age > 0.45f && invulnerable <= 0) {
                if (Math.abs(radius - r) < playerSize + blockSize * 0.8f
                        && Math.abs(d) * r < playerSize + blockSize * 0.7f) {
                    die(px, py);
                    return;
                }
            }
            if (!t.passed && d < -0.25f) {
                t.passed = true;
                if (!t.gem) score++;
            }
            if (d < -1.6f) it.remove();
        }
    }

    private void spawn() {
        float gap = angVel * 0.38f + 0.45f; // always leave time to hop
        for (int attempt = 0; attempt < 6; attempt++) {
            float a = angle + 2.0f + rnd.nextFloat() * 1.0f;
            int rg = rnd.nextInt(2);
            boolean ok = true;
            for (Thing t : things) {
                if (t.gem) continue;
                float diff = Math.abs(wrap(t.angle - a));
                if ((t.ring != rg && diff < gap) || (t.ring == rg && diff < 0.18f)) { ok = false; break; }
            }
            if (!ok) continue;
            Thing t = new Thing();
            t.angle = a;
            t.ring = rg;
            t.gem = rnd.nextFloat() < 0.14f;
            things.add(t);
            return;
        }
    }

    private void die(float x, float y) {
        burst(x, y, COL_PLAYER, 30);
        burst(x, y, COL_BLOCK, 16);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (score > best) {
            best = score;
            prefs.edit().putInt("best", best).apply();
        }
        state = State.DYING;
        stateTimer = 0f;
    }

    private void revive() {
        // clear the area around the player and keep going
        Iterator<Thing> it = things.iterator();
        while (it.hasNext()) {
            if (Math.abs(wrap(it.next().angle - angle)) < 1.4f) it.remove();
        }
        continueUsed = true;
        invulnerable = 1.6f;
        state = State.PLAYING;
        stateTimer = 0f;
    }

    private void hop() {
        ring = 1 - ring;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void pushTrail() {
        float px = cx + (float) Math.cos(angle) * radius;
        float py = cy + (float) Math.sin(angle) * radius;
        for (int i = trailX.length - 1; i > 0; i--) { trailX[i] = trailX[i - 1]; trailY[i] = trailY[i - 1]; }
        trailX[0] = px; trailY[0] = py;
        if (trailCount < trailX.length) trailCount++;
    }

    private void burst(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = unit * (0.2f + rnd.nextFloat() * 0.6f);
            p.x = x; p.y = y;
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
            if (p.life <= 0) { it.remove(); continue; }
            p.x += p.vx * dt; p.y += p.vy * dt;
            p.vx *= 0.96f; p.vy *= 0.96f;
        }
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
                hop();
                break;
            case PAUSED:
                state = State.PLAYING;
                break;
            case MENU:
                if (!tapButton(x, y)) host.beforeNewGame(this::startGame);
                break;
            case GAME_OVER:
                if (stateTimer > 0.5f) tapButton(x, y);
                break;
            default:
                break;
        }
        return true;
    }

    private boolean tapButton(float x, float y) {
        for (Button b : buttons) {
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

        // twinkling stars
        for (int i = 0; i < starX.length; i++) {
            float tw = 0.35f + 0.35f * (float) Math.sin(time * 1.5f + starP[i]);
            fill.setColor(Color.argb((int) (255 * tw), 255, 255, 255));
            c.drawCircle(starX[i] * w, starY[i] * h, unit * 0.0035f * (1 + (i % 3)), fill);
        }

        // rings
        stroke.setStrokeWidth(unit * 0.006f);
        stroke.setColor(Color.argb(70, 165, 180, 252));
        c.drawCircle(cx, cy, rInner, stroke);
        c.drawCircle(cx, cy, rOuter, stroke);

        // planet
        fill.setShader(planetShader);
        c.drawCircle(cx, cy, unit * 0.11f, fill);
        fill.setShader(null);

        // obstacles & gems
        for (Thing t : things) {
            float r = t.ring == 0 ? rInner : rOuter;
            float x = cx + (float) Math.cos(t.angle) * r;
            float y = cy + (float) Math.sin(t.angle) * r;
            if (t.gem) drawGem(c, x, y);
            else drawBlock(c, x, y, t.angle, t.age);
        }

        // player trail + player
        if (state != State.DYING && state != State.GAME_OVER) {
            for (int i = trailCount - 1; i >= 1; i--) {
                float k = 1f - i / (float) trailX.length;
                fill.setColor(Color.argb((int) (110 * k), 94, 234, 212));
                c.drawCircle(trailX[i], trailY[i], playerSize * (0.4f + 0.6f * k), fill);
            }
            boolean blink = invulnerable > 0 && ((int) (time * 12) % 2 == 0);
            if (!blink && trailCount > 0) {
                fill.setColor(Color.argb(60, 94, 234, 212));
                c.drawCircle(trailX[0], trailY[0], playerSize * 2.2f, fill);
                fill.setColor(COL_PLAYER);
                c.drawCircle(trailX[0], trailY[0], playerSize, fill);
            }
        }

        // particles
        for (Particle p : particles) {
            fill.setColor(p.color);
            fill.setAlpha((int) (255 * Math.min(1f, p.life * 1.5f)));
            c.drawCircle(p.x, p.y, playerSize * 0.35f, fill);
        }
        fill.setAlpha(255);

        buttons.clear();
        switch (state) {
            case MENU: drawMenu(c); break;
            case PLAYING: drawHud(c); break;
            case PAUSED: drawHud(c); drawCenterText(c, "Tap to resume", h * 0.86f, unit * 0.06f, COL_TEXT); break;
            case DYING: drawHud(c); break;
            case GAME_OVER: drawGameOver(c); break;
        }
    }

    private void drawBlock(Canvas c, float x, float y, float a, float age) {
        float s = blockSize;
        c.save();
        c.rotate((float) Math.toDegrees(a) + 45f, x, y);
        RectF r = new RectF(x - s, y - s, x + s, y + s);
        if (age < 0.45f) { // warning phase: outline only, fading in
            stroke.setColor(COL_BLOCK);
            stroke.setAlpha((int) (90 + 120 * Math.abs(Math.sin(age * 20))));
            stroke.setStrokeWidth(unit * 0.005f);
            c.drawRoundRect(r, s * 0.25f, s * 0.25f, stroke);
            stroke.setAlpha(255);
        } else {
            fill.setColor(Color.argb(70, 251, 113, 133));
            c.drawCircle(x, y, s * 1.7f, fill);
            fill.setColor(COL_BLOCK);
            c.drawRoundRect(r, s * 0.25f, s * 0.25f, fill);
        }
        c.restore();
    }

    private void drawGem(Canvas c, float x, float y) {
        float s = blockSize * (0.9f + 0.12f * (float) Math.sin(time * 6));
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

    private void drawHud(Canvas c) {
        drawCenterText(c, String.valueOf(score), unit * 0.2f, unit * 0.14f, COL_TEXT);
        drawCenterText(c, "BEST " + best, unit * 0.27f, unit * 0.04f, COL_DIM);
    }

    private void drawMenu(Canvas c) {
        drawCenterText(c, "ORBIT HOP", h * 0.13f, unit * 0.13f, COL_TEXT);
        drawCenterText(c, "Tap to hop between rings", h * 0.13f + unit * 0.09f, unit * 0.045f, COL_DIM);
        if (best > 0) drawCenterText(c, "BEST " + best, h * 0.13f + unit * 0.16f, unit * 0.045f, COL_GEM);

        float pulse = 0.75f + 0.25f * (float) Math.sin(time * 4);
        drawCenterText(c, "TAP TO PLAY", h * 0.79f, unit * 0.07f, Color.argb((int) (255 * pulse), 238, 242, 255));

        float by = h * 0.86f;
        String price = host.removeAdsPrice();
        if (price != null) {
            addButton(c, "Remove ads  " + price, by, false, host::buyRemoveAds);
            by += unit * 0.15f;
        }
        if (host.privacyButtonNeeded()) {
            addSmallLink(c, "Privacy settings", Math.min(by, h - unit * 0.06f), host::openPrivacyOptions);
        }
    }

    private void drawGameOver(Canvas c) {
        fill.setColor(Color.argb(150, 5, 8, 22));
        c.drawRect(0, 0, w, h, fill);
        drawCenterText(c, "GAME OVER", h * 0.2f, unit * 0.1f, COL_BLOCK);
        drawCenterText(c, String.valueOf(score), h * 0.2f + unit * 0.22f, unit * 0.2f, COL_TEXT);
        drawCenterText(c, (score >= best && score > 0 ? "NEW BEST!" : "BEST " + best),
                h * 0.2f + unit * 0.31f, unit * 0.05f, COL_GEM);

        float by = h * 0.6f;
        if (!continueUsed && score > 0 && host.canOfferContinue()) {
            addButton(c, host.continueNeedsAd() ? "▶  Continue (watch ad)" : "▶  Continue", by, true, () -> host.showContinueAd(this::revive));
            by += unit * 0.16f;
            addButton(c, "Play again", by, false, () -> host.beforeNewGame(this::startGame));
        } else {
            addButton(c, "Play again", by, true, () -> host.beforeNewGame(this::startGame));
        }
        by += unit * 0.16f;
        addButton(c, "Menu", by, false, this::goToMenu);
    }

    private void addButton(Canvas c, String label, float centerY, boolean primary, Runnable action) {
        Button b = new Button();
        float bw = unit * 0.78f, bh = unit * 0.12f;
        b.r.set(cx - bw / 2, centerY - bh / 2, cx + bw / 2, centerY + bh / 2);
        b.label = label; b.primary = primary; b.action = action;
        buttons.add(b);
        float rad = bh / 2;
        if (primary) {
            fill.setColor(COL_PLAYER);
            c.drawRoundRect(b.r, rad, rad, fill);
        } else {
            stroke.setColor(Color.argb(160, 165, 180, 252));
            stroke.setStrokeWidth(unit * 0.005f);
            c.drawRoundRect(b.r, rad, rad, stroke);
        }
        text.setTextSize(unit * 0.05f);
        text.setColor(primary ? COL_BG_TOP : COL_TEXT);
        c.drawText(label, cx, centerY - (text.descent() + text.ascent()) / 2, text);
    }

    private void addSmallLink(Canvas c, String label, float centerY, Runnable action) {
        text.setTextSize(unit * 0.038f);
        float tw = text.measureText(label);
        Button b = new Button();
        b.r.set(cx - tw / 2 - unit * 0.04f, centerY - unit * 0.05f, cx + tw / 2 + unit * 0.04f, centerY + unit * 0.05f);
        b.action = action;
        buttons.add(b);
        text.setColor(COL_DIM);
        c.drawText(label, cx, centerY - (text.descent() + text.ascent()) / 2, text);
    }

    private void drawCenterText(Canvas c, String s, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        c.drawText(s, cx, y, text);
    }
}
