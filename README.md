# Salesforce Data Cloud Consent for React Native

`@salesforce-data-cloud/react-native-cdp` exposes the Salesforce Data Cloud native SDK consent state to React Native applications. Its public API is limited to reading and writing consent.

## Requirements

- React Native >=0.76 <0.86
- React 18.3 or 19
- iOS 15.1 or later
- Android API 26 or later
- Java 17 for Android builds

The accepted peer range is React Native 0.76 through 0.85. Within that range, CI explicitly verifies React Native 0.76.9 and 0.81.0 with both the Legacy and New Architectures, and React Native 0.82.1 and 0.85.1 with the New Architecture. React Native 0.82 and later do not support the Legacy Architecture. The canonical example remains on React Native 0.85.1 with the New Architecture enabled. Other minors within the peer range are not individual CI matrix lanes, and later React Native minors are not claimed until they are verified and the peer range is updated.

### Personalization coexistence

This package and `@salesforce-personalization/react-native-personalization@2.0.0` can be installed and autolinked in the same application. Their consent bridges have distinct native module names: `DataCloudConsentModule` here and `ConsentModule` in Personalization. Both operate on the shared underlying Data Cloud SDK consent state. Choose one JS-level consent writer in the application to avoid conflicting writes. Native co-installed Release builds are verified on iOS; Android verifies installation, autolinking, and distinct registration, but not combined native compilation.

The compatibility matrix validates the packed npm artifact, TypeScript declarations, CommonJS and ESM resolution, JavaScript bundling, autolinking, distinct native registrations, and Android and iOS Release compilation for every listed React Native and architecture combination. RN 0.76 New Architecture is also compiled with static frameworks. iOS builds exercise the co-installed packages. Android Release builds exercise this package but not Personalization. The unmodified published Personalization 2.0.0 Android dependency is not claimed to compile natively in this matrix: its dynamic `com.salesforce.personalization:sdk:3.+` currently resolves to 3.1.2, whose Kotlin 2.4.10 metadata is newer than both React Native 0.76's Kotlin 1.9.25 compiler and React Native 0.85's Kotlin 2.1.20 compiler. Replacing the compiler with Kotlin 2.4.10 is also incompatible with React Native 0.76's Gradle plugin. No dependency-source mutation or metadata-skip flag is recommended. This coexistence support does not validate tenant-specific semantics.

## Installation

```bash
npm install @salesforce-data-cloud/react-native-cdp
```

React Native autolinking registers the native module; do not add a package manually. Install the iOS pods after adding or updating the dependency:

```bash
cd ios
pod install
cd ..
```

The package supplies its native dependencies: MarketingCloud-Cdp 3.0.2 on iOS and `com.salesforce.marketingcloud:cdp:3.0.1` on Android. The following are customer-application settings, not changes to either installed package. Add the Salesforce repositories to the customer application's `android/settings.gradle` so Marketing Cloud, CDP, and Personalization can coexist:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url "https://salesforce-marketingcloud.github.io/MarketingCloudSDK-Android/repository" }
        maven { url "https://salesforce-marketingcloud.github.io/mobile-sdk-cdp-android/repository" }
        maven { url "https://salesforce-marketingcloud.github.io/Personalization-Android/repository" }
    }
}
```

## Configure the native Data Cloud SDK

Initialize the native SDK before React Native starts. The examples below match the application in `example/`.

Keep tenant-specific app IDs and endpoints in build configuration. Do not commit tenant configuration, credentials, or production endpoint values to source control. The literal `YOUR_CDP_APP_ID` and `YOUR_CDP_ENDPOINT` values below are placeholders only.

Treat the endpoint as trusted packaged app configuration supplied by the selected build environment. It must not come from user input or untrusted remote data, because the native builders do not enforce Salesforce host allowlisting. Verify that each build configuration contains the tenant endpoint format expected by the native Data Cloud SDK. The example leaves Android cleartext traffic disabled by platform default, and native SDK logging defaults to `ERROR` in all builds.

### iOS

Define the user-defined Xcode build settings `CDP_APP_ID` and `CDP_ENDPOINT` for each environment. Reference them from `Info.plist`:

```xml
<key>com.salesforce.cdp.CDP_APP_ID</key>
<string>$(CDP_APP_ID)</string>
<key>com.salesforce.cdp.CDP_ENDPOINT</key>
<string>$(CDP_ENDPOINT)</string>
```

Initialize Salesforce from `AppDelegate.swift` before starting the React Native factory:

```swift
import SFMCSDK
import Cdp

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
  let sdkConfig = ConfigBuilder().setCdp(config: cdpConfig).build()
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
```

Call `configureSalesforceSdk()` at the start of `application(_:didFinishLaunchingWithOptions:)`, before `factory.startReactNative(...)`. The preconditions fail fast when build configuration still contains missing or placeholder credentials, and the configured endpoint is passed directly to the native builder.

### Android

Pass tenant values through Gradle properties or another build-time secret source, then map them to manifest placeholders in `android/app/build.gradle`:

```gradle
defaultConfig {
    manifestPlaceholders = [
        cdpAppId: project.findProperty("CDP_APP_ID") ?: "YOUR_CDP_APP_ID",
        cdpEndpoint: project.findProperty("CDP_ENDPOINT") ?: "YOUR_CDP_ENDPOINT",
    ]
}
```

Reference the placeholders in `AndroidManifest.xml`:

```xml
<application ...>
    <meta-data android:name="com.salesforce.cdp.CDP_APP_ID" android:value="${cdpAppId}" />
    <meta-data android:name="com.salesforce.cdp.CDP_ENDPOINT" android:value="${cdpEndpoint}" />
</application>
```

Initialize Salesforce in `MainApplication.kt` before `loadReactNative(this)`:

```kotlin
import android.util.Log
import android.content.pm.PackageManager
import com.salesforce.marketingcloud.cdp.CdpConfig
import com.salesforce.marketingcloud.sfmcsdk.InitializationStatus
import com.salesforce.marketingcloud.sfmcsdk.SFMCSdk
import com.salesforce.marketingcloud.sfmcsdk.SFMCSdkModuleConfig
import com.salesforce.marketingcloud.sfmcsdk.components.logging.LogLevel
import com.salesforce.marketingcloud.sfmcsdk.components.logging.LogListener

private fun configureSalesforceSdk() {
  SFMCSdk.setLogging(LogLevel.ERROR, LogListener.AndroidLogger())
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
      Log.e("DataCloudConsent", "SFMC SDK initialization failed: ${status.status}")
    }
  }
}
```

The requirements fail fast when manifest metadata still contains missing or placeholder credentials, and the configured endpoint is passed directly to the native builder. Do not enable `android:usesCleartextTraffic` for the CDP endpoint. Metro development does not require this application-level cleartext opt-in in the included example.

## Privacy and store disclosures

The Android CDP 3.0.1 dependency transitively declares `com.google.android.gms.permission.AD_ID` and uses Google Play services to read the advertising ID for device identity when it is available and the user has not limited ad tracking. Review this access with your privacy team and keep the permission only when your application is permitted to use advertising ID. Reflect the resulting data use in the Google Play Data safety form and any consent experience required for your application.

If your application does not use advertising ID, remove the transitive permission in the host application's `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="com.google.android.gms.permission.AD_ID"
        tools:node="remove" />
</manifest>
```

On Android 13 and later, removing the permission prevents access to the real advertising ID. Android 12 and earlier do not enforce `AD_ID`, so manifest removal alone does not prevent Google Play services from returning the identifier on those supported versions. The CDP SDK continues to initialize when the ID is unavailable and uses its advertising-ID opt-out/unavailable value for that device-identity field. This does not disable the consent API exposed by this package. If your policy requires preventing advertising-ID collection on every supported Android version, obtain privacy-owner guidance and a native SDK collection control rather than relying only on manifest removal. Verify the final merged Release manifest because other dependencies can also declare this permission.

On iOS, review the privacy manifests supplied by React Native and the native Salesforce dependencies together with the host application's `PrivacyInfo.xcprivacy`. The host application remains responsible for an accurate assembled privacy manifest, any required tracking authorization, and its App Store Connect privacy disclosures. The example declares device-ID and other-data use for app functionality and analytics without tracking; copy declarations only when they accurately describe your application and dependency behavior.

## Consent API

```typescript
import {
  DataCloudError,
  DataCloudModule,
  ErrorCode,
  getConsent,
  setConsent,
  type ConsentState,
  type WritableConsentState,
} from '@salesforce-data-cloud/react-native-cdp'

const current: ConsentState = await getConsent()

const choice: WritableConsentState = 'opt_in'
await setConsent(choice)

// Module-style access supports a scalable API surface.
await DataCloudModule.setConsent('opt_out')
```

### `getConsent(): Promise<ConsentState>`

Reads the native SDK state. It resolves to exactly one lowercase value:

- `opt_in`
- `opt_out`
- `pending`

Native SDK unset or future enum values are normalized by the platform adapter to `pending`. A
malformed value crossing the React Native bridge is rejected with `E_NATIVE_FAILURE`.

### `setConsent(consent: WritableConsentState): Promise<void>`

Submits `opt_in` or `opt_out` to the native SDK. The promise resolves after the synchronous native setter returns. Native consent changes can trigger local side effects such as consent notifications, device-ID creation on opt-in, and queued-event or location cleanup on opt-out. Resolution does not confirm completion of asynchronous persistence, event-queue processing, consent-event delivery, or server propagation. `WritableConsentState` deliberately excludes `pending`; callers cannot set the SDK back to pending.

Valid consent operations admitted through one active native bridge instance execute native actions one at a time in admission order, regardless of readiness callback order. Readiness failures or bridge invalidation can prevent an admitted operation from executing. Callers should still await a write before issuing a dependent read or another write:

```typescript
await setConsent('opt_in')
const consent = await getConsent() // observes opt_in
```

FIFO applies only within one active native bridge instance. Direct native access, Personalization or other packages, and overlapping bridge instances are outside that FIFO contract. Overlapping bridge instances serialize native execution but have no ordering guarantee between instances. Promise settlement order is not guaranteed, even when native actions execute in admission order.

Initialize SFMCSdk/CDP once before React Native starts. Runtime SFMCSdk/CDP reinitialization is unsupported while the React Native bridge is active or consent operations may be executing.

`DataCloudModule` provides the same methods for module-style access. The default export is the same object as `DataCloudModule`; named imports remain supported.

## Errors and readiness

Rejected operations use `DataCloudError`, whose `code` is one of:

| Code | Meaning |
| --- | --- |
| `E_INVALID_CONSENT` | `setConsent` received a value other than `opt_in` or `opt_out`. |
| `E_SDK_NOT_READY` | The native Data Cloud SDK did not become ready within 10 seconds. |
| `E_SDK_UNAVAILABLE` | The native module or active React Native instance is unavailable. |
| `E_NATIVE_FAILURE` | The native consent operation failed or returned an invalid state. |

```typescript
try {
  await setConsent('opt_out')
} catch (error) {
  if (error instanceof DataCloudError) {
    if (error.code === ErrorCode.E_SDK_NOT_READY) {
      // Verify native initialization, then offer an explicit user-triggered retry.
    }
    console.error(error.code, error.message)
  }
}
```

Each call has up to 10 seconds to become SDK-ready. Time spent behind earlier native work or waiting for promise delivery after readiness is outside that deadline. Treat `E_SDK_NOT_READY` as a configuration or startup-readiness signal: verify initialization completed, then retry the operation in response to a deliberate application action. Avoid unbounded or immediate automatic retry loops.

## Run the example

Set `CDP_APP_ID` and `CDP_ENDPOINT` through local build configuration. For iOS, set the user-defined build settings in Xcode or pass them to the example command as shown below. Then run:

```bash
cd example
npm install
npm run ios -- --extra-params "CDP_APP_ID=YOUR_CDP_APP_ID CDP_ENDPOINT=YOUR_CDP_ENDPOINT"
# or
npm run android -- --extra-params "-PCDP_APP_ID=YOUR_CDP_APP_ID -PCDP_ENDPOINT=YOUR_CDP_ENDPOINT"
```

Replace both placeholder values with tenant configuration. The placeholders do not initialize the SDK.

## Troubleshooting

- **`E_SDK_NOT_READY`:** confirm `SFMCSdk.initializeSdk`/`SFMCSdk.configure` runs before React Native startup and that build configuration supplied non-placeholder values. Correct initialization, then retry.
- **`E_SDK_UNAVAILABLE`:** confirm the package is installed and the application has been rebuilt rather than only reloading JavaScript.
- **iOS module or link errors:** run `cd ios && pod install`, open the generated `.xcworkspace`, clean, and rebuild.
- **Android dependency resolution errors:** add all three Salesforce Maven repositories to the customer application's `android/settings.gradle`, use Java 17, sync Gradle, and rebuild.
- **Android module registration:** rely on autolinking. Inspect generated autolinking output if registration fails; do not manually register the package as a first step.
- **Consent remains `pending`:** confirm the tenant app ID and endpoint are correct for the selected build environment and inspect native initialization status.

## License

See [LICENSE](LICENSE).

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
