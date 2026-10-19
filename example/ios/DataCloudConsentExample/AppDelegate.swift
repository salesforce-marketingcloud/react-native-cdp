import UIKit
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider
import SFMCSDK
import Cdp

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    configureSalesforceSdk()

    let delegate = ReactNativeDelegate()
    let factory = RCTReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()

    reactNativeDelegate = delegate
    reactNativeFactory = factory

    window = UIWindow(frame: UIScreen.main.bounds)

    factory.startReactNative(
      withModuleName: "DataCloudConsentExample",
      in: window,
      launchOptions: launchOptions
    )

    return true
  }

  private func configureSalesforceSdk() {
    SFMCSdk.setLogger(logLevel: .error)

    let info = Bundle.main.infoDictionary ?? [:]
    let appId = info["com.salesforce.cdp.CDP_APP_ID"] as? String ?? ""
    let endpoint = info["com.salesforce.cdp.CDP_ENDPOINT"] as? String ?? ""

    precondition(
      !appId.isEmpty && !appId.hasPrefix("YOUR_"),
      "Set com.salesforce.cdp.CDP_APP_ID in Info.plist before running the example."
    )
    precondition(
      !endpoint.isEmpty && !endpoint.hasPrefix("YOUR_"),
      "Set com.salesforce.cdp.CDP_ENDPOINT in Info.plist before running the example."
    )

    let cdpConfig = CdpConfigBuilder(appId: appId, endpoint: endpoint)
      .eventFlushRate(EventFlushRateQuantity(quantity: 1))
      .trackScreens(false)
      .trackLifecycle(false)
      .build()
    let sdkConfig = ConfigBuilder()
      .setCdp(config: cdpConfig)
      .build()

    SFMCSdk.initializeSdk(sdkConfig) { statuses in
      statuses.filter { $0.initStatus != .success }.forEach { status in
        SFMCSdkLogger.shared.logMessage(
          level: .error,
          subsystem: .module(value: status.moduleName),
          category: .module,
          message: "Initialization failed: \(status.initStatus.rawValue)"
        )
      }
    }
  }

}

class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    self.bundleURL()
  }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
