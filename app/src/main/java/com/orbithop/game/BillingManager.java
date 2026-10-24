package com.orbithop.game;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/**
 * One non-consumable product: "Remove Ads". No server needed — Google Play keeps the record
 * of who bought it, and we re-check it every time the app comes to the foreground.
 */
public class BillingManager {

    public interface Listener { void onAdsRemovedChanged(boolean removed); void onPriceReady(); }

    private final Activity activity;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final BillingClient client;
    private ProductDetails removeAdsDetails;
    private boolean ready;

    public BillingManager(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.prefs = activity.getSharedPreferences("orbit", Context.MODE_PRIVATE);
        this.client = BillingClient.newBuilder(activity)
                .setListener(this::onPurchasesUpdated)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }

    public boolean adsRemoved() {
        return prefs.getBoolean("ads_removed", false);
    }

    public void start() {
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                ready = true;
                queryProduct();
                refreshPurchases();
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Auto reconnection is enabled; nothing to do.
            }
        });
    }

    public void end() {
        client.endConnection();
    }

    private void queryProduct() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(BuildConfig.REMOVE_ADS_PRODUCT_ID)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()))
                .build();
        client.queryProductDetailsAsync(params, (result, detailsResult) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            List<ProductDetails> list = detailsResult.getProductDetailsList();
            if (list != null && !list.isEmpty()) {
                removeAdsDetails = list.get(0);
                activity.runOnUiThread(listener::onPriceReady);
            }
        });
    }

    /** Re-checks ownership with Google Play (call on app resume). Handles refunds too. */
    public void refreshPurchases() {
        if (!ready) return;
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                    boolean owned = false;
                    for (Purchase p : purchases) {
                        if (isRemoveAds(p) && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            owned = true;
                            acknowledgeIfNeeded(p);
                        }
                    }
                    setRemoved(owned);
                });
    }

    /** Price text like "₹99.00" or "$1.99", or null if the product isn't available. */
    public String price() {
        if (adsRemoved() || removeAdsDetails == null) return null;
        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                removeAdsDetails.getOneTimePurchaseOfferDetailsList();
        return (offers == null || offers.isEmpty()) ? null : offers.get(0).getFormattedPrice();
    }

    public void buy() {
        if (removeAdsDetails == null) return;
        BillingFlowParams.ProductDetailsParams.Builder pdp =
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(removeAdsDetails);
        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                removeAdsDetails.getOneTimePurchaseOfferDetailsList();
        if (offers != null && !offers.isEmpty() && offers.get(0).getOfferToken() != null) {
            pdp.setOfferToken(offers.get(0).getOfferToken());
        }
        BillingFlowParams flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(pdp.build()))
                .build();
        client.launchBillingFlow(activity, flow);
    }

    private void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase p : purchases) {
                if (isRemoveAds(p) && p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    acknowledgeIfNeeded(p);
                    setRemoved(true);
                }
            }
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refreshPurchases();
        }
    }

    private void acknowledgeIfNeeded(Purchase p) {
        if (p.isAcknowledged()) return;
        client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.getPurchaseToken()).build(),
                ackResult -> { });
    }

    private boolean isRemoveAds(Purchase p) {
        return p.getProducts().contains(BuildConfig.REMOVE_ADS_PRODUCT_ID);
    }

    private void setRemoved(boolean removed) {
        if (removed == adsRemoved()) return;
        prefs.edit().putBoolean("ads_removed", removed).apply();
        activity.runOnUiThread(() -> listener.onAdsRemovedChanged(removed));
    }
}
