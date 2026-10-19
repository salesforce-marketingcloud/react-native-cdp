#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(DataCloudConsentModule, NSObject)

RCT_EXTERN_METHOD(getConsent:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(setConsent:(NSString *)value
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)

@end
