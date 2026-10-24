package com.orbithop.game;

import android.app.Activity;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles GDPR/consent (Google UMP), banner, interstitial (every few games) and rewarded (continue) ads.
 */
public class AdsManager {

    private static final int GAMES_BETWEEN_INTERSTITIALS = 3;
    private static final long MIN_MS_BETWEEN_INTERSTITIALS = 60_000;

    private final Activity activity;
    private final FrameLayout bannerContainer;
    private final Runnable onChanged;
    private final ConsentInformation consent;
    private final AtomicBoolean sdkStarted = new AtomicBoolean(false);

    private boolean adsRemoved;
    private AdView banner;
    private InterstitialAd interstitial;
    private RewardedAd rewarded;
    private int gamesSinceInterstitial = 0;
    private long lastInterstitialAt = 0;
    private boolean loadingInterstitial, loadingRewarded;

    public AdsManager(Activity activity, FrameLayout bannerContainer, boolean adsRemoved, Runnable onChanged) {
        this.activity = activity;
        this.bannerContainer = bannerContainer;
        this.adsRemoved = adsRemoved;
        this.onChanged = onChanged;
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    /** Ask for consent where legally required (EEA/UK etc.), then start ads. */
    public void start() {
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consent.requestConsentInfoUpdate(activity, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, formError -> {
                    if (consent.canRequestAds()) startSdk();
                    onChanged.run();
                }),
                requestError -> {
                    if (consent.canRequestAds()) startSdk();
                });
        // Consent from a previous session lets us start right away.
        if (consent.canRequestAds()) startSdk();
    }

    private void startSdk() {
        if (sdkStarted.getAndSet(true)) return;
        new Thread(() -> MobileAds.initialize(activity, status ->
                activity.runOnUiThread(this::loadAll))).start();
    }

    private void loadAll() {
        if (adsRemoved) return; // buyers get free continues, no ads at all
        loadBanner();
        loadInterstitial();
        loadRewarded();
    }

    public boolean privacyOptionsRequired() {
        return consent.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    public void showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
            if (consent.canRequestAds()) startSdk();
        });
    }

    public void setAdsRemoved(boolean removed) {
        if (removed == adsRemoved) return;
        adsRemoved = removed;
        if (removed) {
            if (banner != null) {
                bannerContainer.removeAllViews();
                banner.destroy();
                banner = null;
            }
            bannerContainer.setVisibility(View.GONE);
            interstitial = null;
        } else if (sdkStarted.get()) {
            loadAll();
        }
    }

    // ---------------------------------------------------------------- banner

    private void loadBanner() {
        if (banner != null || adsRemoved) return;
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int widthDp = (int) (dm.widthPixels / dm.density);
        banner = new AdView(activity);
        banner.setAdUnitId(BuildConfig.BANNER_ID);
        banner.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp));
        bannerContainer.removeAllViews();
        bannerContainer.addView(banner);
        bannerContainer.setVisibility(View.VISIBLE);
        banner.loadAd(new AdRequest.Builder().build());
    }

    public void pause() { if (banner != null) banner.pause(); }
    public void resume() { if (banner != null) banner.resume(); }
    public void destroy() { if (banner != null) banner.destroy(); }

    // ---------------------------------------------------------------- interstitial

    private void loadInterstitial() {
        if (adsRemoved || interstitial != null || loadingInterstitial || !sdkStarted.get()) return;
        loadingInterstitial = true;
        InterstitialAd.load(activity, BuildConfig.INTERSTITIAL_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        loadingInterstitial = false;
                        interstitial = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        loadingInterstitial = false;
                        interstitial = null;
                    }
                });
    }

    public void onGameOver() {
        gamesSinceInterstitial++;
        loadInterstitial();
        loadRewarded();
    }

    /** Called when the player starts a new game. Maybe shows an interstitial first. */
    public void beforeNewGame(Runnable startGame) {
        long now = SystemClock.elapsedRealtime();
        boolean due = gamesSinceInterstitial >= GAMES_BETWEEN_INTERSTITIALS
                && now - lastInterstitialAt >= MIN_MS_BETWEEN_INTERSTITIALS;
        if (adsRemoved || !due || interstitial == null) {
            startGame.run();
            return;
        }
        InterstitialAd ad = interstitial;
        interstitial = null;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                startGame.run();
                loadInterstitial();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                startGame.run();
                loadInterstitial();
            }
        });
        gamesSinceInterstitial = 0;
        lastInterstitialAt = now;
        ad.show(activity);
    }

    // ---------------------------------------------------------------- rewarded

    private void loadRewarded() {
        if (adsRemoved || rewarded != null || loadingRewarded || !sdkStarted.get()) return;
        loadingRewarded = true;
        RewardedAd.load(activity, BuildConfig.REWARDED_ID, new AdRequest.Builder().build(),
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        loadingRewarded = false;
                        rewarded = ad;
                        onChanged.run();
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        loadingRewarded = false;
                        rewarded = null;
                    }
                });
    }

    public boolean canOfferContinue() {
        return adsRemoved || rewarded != null;
    }

    public boolean continueNeedsAd() {
        return !adsRemoved;
    }

    public void showContinue(Runnable onRewarded) {
        if (adsRemoved) {
            onRewarded.run();
            return;
        }
        RewardedAd ad = rewarded;
        if (ad == null) return;
        rewarded = null;
        final boolean[] earned = {false};
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                if (earned[0]) onRewarded.run();
                loadRewarded();
                onChanged.run();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                loadRewarded();
                onChanged.run();
            }
        });
        ad.show(activity, rewardItem -> earned[0] = true);
    }
}
