package com.iraitec

import android.app.Activity
import com.android.billingclient.api.*

/** Google Play subscriptions. Product IDs must match Play Console exactly. */
class PlaySubscriptions(
    private val activity: Activity,
    private val onPurchase: (String, String) -> Unit,
    private val onError: (String) -> Unit
) : PurchasesUpdatedListener {
    companion object {
        val PRODUCT_IDS = listOf("irai_starter", "irai_plus", "irai_premium", "irai_ultimate")
    }

    private val client = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases()
        .build()
    private var products: List<ProductDetails> = emptyList()

    fun connect(onReady: () -> Unit) {
        if (client.isReady) { onReady(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {
                onError("Google Play connection lost. Try again.")
            }
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProducts(onReady)
                } else activity.runOnUiThread { onError("Google Play Billing unavailable: ${result.debugMessage}") }
            }
        })
    }

    private fun loadProducts(onReady: () -> Unit) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(PRODUCT_IDS.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }).build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                products = details
                activity.runOnUiThread(onReady)
            } else activity.runOnUiThread { onError("Could not load subscriptions: ${result.debugMessage}") }
        }
    }

    fun availablePlans(): List<Pair<String, String>> =
        products.map { detail ->
            val offer = detail.subscriptionOfferDetails?.firstOrNull()
            val price = offer?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
                ?: "See Google Play"
            detail.productId to price
        }

    fun purchase(productId: String) {
        val detail = products.firstOrNull { it.productId == productId }
        val offer = detail?.subscriptionOfferDetails?.firstOrNull()
        if (detail == null || offer == null) {
            onError("This plan is not available in Google Play yet.")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(detail)
                    .setOfferToken(offer.offerToken)
                    .build()
            )).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            onError("Could not open Google Play checkout: ${result.debugMessage}")
        }
    }

    fun restorePurchases() {
        if (!client.isReady) {
            connect { restorePurchases() }
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                activity.runOnUiThread { onError("Could not restore purchases: ${result.debugMessage}") }
                return@queryPurchasesAsync
            }
            purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .forEach { purchase ->
                    val product = purchase.products.firstOrNull { it in PRODUCT_IDS }
                    if (product != null) onPurchase(product, purchase.purchaseToken)
                }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            purchases.orEmpty().filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .forEach { purchase ->
                    val product = purchase.products.firstOrNull { it in PRODUCT_IDS }
                    if (product != null) onPurchase(product, purchase.purchaseToken)
                }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            onError("Purchase was not completed: ${result.debugMessage}")
        }
    }

    fun close() { client.endConnection() }
}
