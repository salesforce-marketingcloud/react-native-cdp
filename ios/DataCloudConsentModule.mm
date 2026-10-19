#if RCT_NEW_ARCH_ENABLED
#import <RNDataCloudConsentSpec/RNDataCloudConsentSpec.h>
#if __has_include(<SdkDataCloudReactnativePlugin/SdkDataCloudReactnativePlugin-Swift.h>)
#import <SdkDataCloudReactnativePlugin/SdkDataCloudReactnativePlugin-Swift.h>
#else
#import "SdkDataCloudReactnativePlugin-Swift.h"
#endif

// Adapts the Swift product implementation to React Native's generated ObjC++ spec.
@interface DataCloudConsentModule (TurboModule) <NativeDataCloudConsentModuleSpec>
@end

@implementation DataCloudConsentModule (TurboModule)

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeDataCloudConsentModuleSpecJSI>(params);
}

@end
#endif
