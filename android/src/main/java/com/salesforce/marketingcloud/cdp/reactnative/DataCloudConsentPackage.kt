package com.salesforce.marketingcloud.cdp.reactnative

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Registers [DataCloudConsentModule] with React Native on Android. */
class DataCloudConsentPackage : BaseReactPackage() {
  /** Creates the consent module when React Native requests its generated module name. */
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == DataCloudConsentModule.MODULE_NAME) DataCloudConsentModule(reactContext) else null

  /** Describes the consent module to React Native's lazy native-module registry. */
  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
    mapOf(
      DataCloudConsentModule.MODULE_NAME to ReactModuleInfo(
        DataCloudConsentModule.MODULE_NAME,
        DataCloudConsentModule::class.java.name,
        false,
        false,
        false,
        true
      )
    )
  }
}
