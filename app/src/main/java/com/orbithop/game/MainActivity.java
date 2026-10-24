package com.orbithop.game;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends ComponentActivity implements GameView.Host, BillingManager.Listener {

    private GameView game;
    private AdsManager ads;
    private BillingManager billing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 16, 38));

        game = new GameView(this, this);
        root.addView(game, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout bannerBox = new FrameLayout(this);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bannerLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(bannerBox, bannerLp);

        // Keep content clear of the status bar, notch and navigation bar (Android 15+ is edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        setContentView(root);

        billing = new BillingManager(this, this);
        ads = new AdsManager(this, bannerBox, billing.adsRemoved(), game::refreshUi);
        billing.start();
        ads.start();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!game.onBack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        game.resume();
        ads.resume();
        billing.refreshPurchases();
    }

    @Override
    protected void onPause() {
        game.pause();
        ads.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ads.destroy();
        billing.end();
        super.onDestroy();
    }

    // ---- GameView.Host
    @Override public boolean canOfferContinue() { return ads.canOfferContinue(); }
    @Override public boolean continueNeedsAd() { return ads.continueNeedsAd(); }
    @Override public void showContinueAd(Runnable onRewarded) { ads.showContinue(onRewarded); }
    @Override public void onGameOver() { ads.onGameOver(); }
    @Override public void beforeNewGame(Runnable startGame) { ads.beforeNewGame(startGame); }
    @Override public String removeAdsPrice() { return billing.price(); }
    @Override public void buyRemoveAds() { billing.buy(); }
    @Override public boolean privacyButtonNeeded() { return ads.privacyOptionsRequired(); }
    @Override public void openPrivacyOptions() { ads.showPrivacyOptions(); }

    // ---- BillingManager.Listener
    @Override
    public void onAdsRemovedChanged(boolean removed) {
        ads.setAdsRemoved(removed);
        game.refreshUi();
    }

    @Override
    public void onPriceReady() {
        game.refreshUi();
    }
}
