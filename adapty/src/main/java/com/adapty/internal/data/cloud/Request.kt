package com.adapty.internal.data.cloud

import android.content.Context
import android.os.Build
import androidx.annotation.RestrictTo
import com.adapty.internal.data.cache.CacheRepository
import com.adapty.internal.data.cache.RequestCacheOptions
import com.adapty.internal.data.cache.ResponseCacheKeys
import com.adapty.internal.data.cloud.Request.Method.*
import com.adapty.internal.data.models.AttributionData
import com.adapty.internal.data.models.RestoreProductInfo
import com.adapty.internal.data.models.ValidateProductInfo
import com.adapty.internal.data.models.requests.*
import com.adapty.internal.utils.getCurrentLocale
import com.adapty.utils.ProfileParameterBuilder
import com.android.billingclient.api.Purchase
import com.google.gson.Gson
import java.util.*

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal class Request internal constructor(val baseUrl: String) {

    @get:JvmSynthetic
    @set:JvmSynthetic
    lateinit var method: Method

    @get:JvmSynthetic
    @set:JvmSynthetic
    lateinit var url: String

    @JvmSynthetic
    @JvmField
    var responseCacheKeys: ResponseCacheKeys? = null

    @JvmSynthetic
    @JvmField
    var requestCacheOptions: RequestCacheOptions? = null

    @JvmSynthetic
    @JvmField
    var body = ""

    @JvmSynthetic
    @JvmField
    var currentDataWhenSent: CurrentDataWhenSent? = null

    internal class Builder(private val baseRequest: Request = Request(baseUrl = "https://api.adapty.io/api/v1/")) {

        @get:JvmSynthetic
        @set:JvmSynthetic
        lateinit var method: Method

        @JvmSynthetic
        @JvmField
        var endPoint: String? = null

        @JvmSynthetic
        @JvmField
        var body: String? = null

        @JvmSynthetic
        @JvmField
        var currentDataWhenSent: CurrentDataWhenSent? = null

        @JvmSynthetic
        @JvmField
        var responseCacheKeys: ResponseCacheKeys? = null

        @JvmSynthetic
        @JvmField
        var requestCacheOptions: RequestCacheOptions? = null

        private var queryParams = arrayListOf<Pair<String, String>>()

        private fun queryDelimiter(index: Int) = if (index == 0) "?" else "&"

        @JvmSynthetic
        fun addQueryParam(param: Pair<String, String>) {
            queryParams.add(param)
        }

        @JvmSynthetic
        fun build() = baseRequest.apply {
            method = this@Builder.method
            url = StringBuilder(baseUrl).apply {
                endPoint?.let(::append)
                queryParams.forEachIndexed { i, (key, value) ->
                    append(queryDelimiter(i))
                    append(key)
                    append("=")
                    append(value)
                }
            }.toString()
            body = this@Builder.body.orEmpty()
            responseCacheKeys = this@Builder.responseCacheKeys
            requestCacheOptions = this@Builder.requestCacheOptions
            currentDataWhenSent = this@Builder.currentDataWhenSent
        }
    }

    internal enum class Method {
        GET, POST, PATCH
    }

    internal class CurrentDataWhenSent(val profileId: String)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
internal class RequestFactory(
    private val appContext: Context,
    private val cacheRepository: CacheRepository,
    private val gson: Gson
) {

    private val inappsEndpointPrefix = "sdk/in-apps"
    private val profilesEndpointPrefix = "sdk/analytics/profiles"

    private fun getEndpointForProfileRequests(profileId: String): String {
        return "$profilesEndpointPrefix/$profileId/"
    }

    @JvmSynthetic
    fun getPurchaserInfoRequest() =
        cacheRepository.getProfileId().let { profileId ->
            buildRequest {
                method = GET
                endPoint = getEndpointForProfileRequests(profileId)
                responseCacheKeys = ResponseCacheKeys.forGetPurchaserInfo()
                currentDataWhenSent = Request.CurrentDataWhenSent(profileId)
            }
        }

    @JvmSynthetic
    fun updateProfileRequest(params: ProfileParameterBuilder) =
        cacheRepository.getProfileId().let { profileId ->
            buildRequest {
                method = PATCH
                body = gson.toJson(
                    UpdateProfileRequest.create(profileId, params)
                )
                endPoint = getEndpointForProfileRequests(profileId)
                requestCacheOptions = RequestCacheOptions.forUpdateProfile()
                responseCacheKeys = ResponseCacheKeys.forGetPurchaserInfo()
                currentDataWhenSent = Request.CurrentDataWhenSent(profileId)
            }
        }

    @JvmSynthetic
    fun createProfileRequest(customerUserId: String?) =
        cacheRepository.getProfileId().let { profileId ->
            buildRequest {
                method = POST
                body = gson.toJson(
                    CreateProfileRequest.create(profileId, customerUserId)
                )
                endPoint = getEndpointForProfileRequests(profileId)
            }
        }

    @JvmSynthetic
    fun syncMetaInstallRequest(
        pushToken: String?,
        adId: String?
    ) = buildRequest {
        val appBuild: String
        val appVersion: String
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            .let { packageInfo ->
                appBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    "${packageInfo.longVersionCode}"
                } else {
                    "${packageInfo.versionCode}"
                }
                appVersion = packageInfo.versionName
            }

        method = POST
        body = gson.toJson(
            SyncMetaRequest.create(
                id = cacheRepository.getOrCreateMetaUUID(),
                adaptySdkVersion = com.adapty.BuildConfig.VERSION_NAME,
                adaptySdkVersionBuild = com.adapty.BuildConfig.VERSION_CODE,
                advertisingId = adId,
                appBuild = appBuild,
                appVersion = appVersion,
                device = cacheRepository.deviceName,
                deviceToken = pushToken,
                locale = getCurrentLocale(appContext)?.let { "${it.language}_${it.country}" },
                os = Build.VERSION.RELEASE,
                platform = "Android",
                timezone = TimeZone.getDefault().id
            )
        )
        endPoint =
            "${getEndpointForProfileRequests(cacheRepository.getProfileId())}installation-metas/${cacheRepository.getInstallationMetaId()}/"
        requestCacheOptions = RequestCacheOptions.forSyncMeta()
    }

    @JvmSynthetic
    fun validatePurchaseRequest(
        purchaseType: String,
        purchase: Purchase,
        product: ValidateProductInfo?
    ) = cacheRepository.getProfileId().let { profileId ->
        buildRequest {
            method = POST
            endPoint = "$inappsEndpointPrefix/google/token/validate/"
            body = gson.toJson(
                ValidateReceiptRequest.create(profileId, purchase, product, purchaseType)
            )
            currentDataWhenSent = Request.CurrentDataWhenSent(profileId)
        }
    }

    @JvmSynthetic
    fun restorePurchasesRequest(purchases: List<RestoreProductInfo>) =
        cacheRepository.getProfileId().let { profileId ->
            buildRequest {
                method = POST
                body = gson.toJson(
                    RestoreReceiptRequest.create(profileId, purchases)
                )
                endPoint = "$inappsEndpointPrefix/google/token/restore/"
                currentDataWhenSent = Request.CurrentDataWhenSent(profileId)
            }
        }

    @JvmSynthetic
    fun getPaywallsRequest() = buildRequest {
        method = GET
        endPoint = "$inappsEndpointPrefix/purchase-containers/"
        addQueryParam(Pair("profile_id", cacheRepository.getProfileId()))
        addQueryParam(Pair("automatic_paywalls_screen_reporting_enabled", "false"))
        responseCacheKeys = ResponseCacheKeys.forGetPaywalls()
    }

    @JvmSynthetic
    fun updateAttributionRequest(
        attributionData: AttributionData,
    ) = buildRequest {
        method = POST
        endPoint = "${getEndpointForProfileRequests(cacheRepository.getProfileId())}attribution/"
        body = gson.toJson(
            UpdateAttributionRequest.create(attributionData)
        )
        requestCacheOptions = RequestCacheOptions.forUpdateAttribution(attributionData.source)
    }

    @JvmSynthetic
    fun getPromoRequest() = buildRequest {
        method = GET
        endPoint = "${getEndpointForProfileRequests(cacheRepository.getProfileId())}promo/"
        responseCacheKeys = ResponseCacheKeys.forGetPromo()
    }

    @JvmSynthetic
    fun setTransactionVariationIdRequest(transactionId: String, variationId: String) =
        buildRequest {
            method = POST
            endPoint = "$inappsEndpointPrefix/transaction-variation-id/"
            body = gson.toJson(
                TransactionVariationIdRequest.create(
                    transactionId,
                    variationId,
                    cacheRepository.getProfileId()
                )
            )
        }

    @JvmSynthetic
    fun setExternalAnalyticsEnabledRequest(enabled: Boolean) = buildRequest {
        method = POST
        endPoint = "${getEndpointForProfileRequests(cacheRepository.getProfileId())}analytics-enabled/"
        body = gson.toJson(
            ExternalAnalyticsEnabledRequest.create(enabled)
        )
    }

    @JvmSynthetic
    fun kinesisRequest(requestBody: HashMap<String, Any>) =
        Request.Builder(Request("https://kinesis.us-east-1.amazonaws.com/"))
            .apply {
                method = POST
                body = gson.toJson(requestBody).replace("\\u003d", "=")
            }
            .build()

    private inline fun buildRequest(action: Request.Builder.() -> Unit) =
        Request.Builder().apply(action).build()
}