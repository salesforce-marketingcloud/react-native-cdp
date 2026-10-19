package com.datacloudconsentexample

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.salesforce.marketingcloud.cdp.CdpConfig
import com.salesforce.marketingcloud.sfmcsdk.SFMCSdk
import com.salesforce.marketingcloud.sfmcsdk.SFMCSdkModuleConfig
import com.salesforce.marketingcloud.sfmcsdk.InitializationStatus
import com.salesforce.marketingcloud.sfmcsdk.components.logging.LogLevel
import com.salesforce.marketingcloud.sfmcsdk.components.logging.LogListener

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          // add(MyReactNativePackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    configureSalesforceSdk()
    loadReactNative(this)
  }

  private fun configureSalesforceSdk() {
    SFMCSdk.setLogging(
      LogLevel.ERROR,
      LogListener.AndroidLogger(),
    )

    val metaData = packageManager
      .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
      .metaData
    val appId = metaData?.getString("com.salesforce.cdp.CDP_APP_ID").orEmpty()
    val endpoint = metaData?.getString("com.salesforce.cdp.CDP_ENDPOINT").orEmpty()

    require(appId.isNotBlank() && !appId.startsWith("YOUR_")) {
      "Set com.salesforce.cdp.CDP_APP_ID in AndroidManifest.xml before running the example."
    }
    require(endpoint.isNotBlank() && !endpoint.startsWith("YOUR_")) {
      "Set com.salesforce.cdp.CDP_ENDPOINT in AndroidManifest.xml before running the example."
    }

    val cdpConfig = CdpConfig.Builder(this, appId, endpoint)
      .eventFlushRate(CdpConfig.EventFlushRate.Quantity(1))
      .build()
    SFMCSdk.configure(this, SFMCSdkModuleConfig.build {
      cdpModuleConfig = cdpConfig
    }) { status ->
      if (status.status != InitializationStatus.SUCCESS) {
        Log.e(TAG, "SFMC SDK initialization failed: ${status.status}")
      }
    }
  }

  private companion object {
    const val TAG = "DataCloudConsent"
  }
}
