import pathlib
import unittest


IOS = pathlib.Path(__file__).parents[1]
ROOT = IOS.parent


class IOSConsentContractTest(unittest.TestCase):
    def test_generated_turbo_module_contract_is_implemented(self):
        module = (IOS / "DataCloudConsentModule.mm").read_text()
        registration = (IOS / "DataCloudConsentModuleBridge.m").read_text()
        swift = (IOS / "DataCloudConsentModule.swift").read_text()

        self.assertIn('RNDataCloudConsentSpec/RNDataCloudConsentSpec.h', module)
        self.assertIn('SdkDataCloudReactnativePlugin/SdkDataCloudReactnativePlugin-Swift.h', module)
        self.assertNotIn('RNCdp-Swift.h', module)
        self.assertIn('NativeDataCloudConsentModuleSpec', module)
        self.assertIn('NativeDataCloudConsentModuleSpecJSI', module)
        self.assertNotIn('RCT_EXTERN_MODULE', module)
        self.assertNotIn('RNCdp-Swift.h', registration)
        self.assertIn('@interface RCT_EXTERN_MODULE(DataCloudConsentModule, NSObject)', registration)
        self.assertIn(
            'RCT_EXTERN_METHOD(getConsent:(RCTPromiseResolveBlock)resolve\n'
            '                  reject:(RCTPromiseRejectBlock)reject)',
            registration,
        )
        self.assertIn(
            'RCT_EXTERN_METHOD(setConsent:(NSString *)value\n'
            '                  resolve:(RCTPromiseResolveBlock)resolve\n'
            '                  reject:(RCTPromiseRejectBlock)reject)',
            registration,
        )
        self.assertIn('RCTBridgeModule', swift)
        self.assertIn('@objc(DataCloudConsentModule)', swift)
        self.assertIn('requestCdpSdk', swift)
        self.assertIn('E_INVALID_CONSENT', swift)
        self.assertIn('E_SDK_NOT_READY', swift)
        self.assertIn('E_SDK_UNAVAILABLE', swift)
        self.assertIn('ConsentCoordinator<any CdpInterface>', swift)
        self.assertNotIn('DeferredReadinessGate', swift)
        self.assertNotIn('PendingConsentOperations', swift)
        self.assertLess(swift.index('guard let consent = writableConsent(for: value)'), swift.index('begin(reject) { cdp in', swift.index('@objc public func setConsent')))

    def test_podspec_uses_required_platform_codegen_and_native_sdk(self):
        podspec_path = ROOT / "SdkDataCloudReactnativePlugin.podspec"
        self.assertTrue(podspec_path.exists())
        podspec = podspec_path.read_text()

        self.assertIn('s.name         = "SdkDataCloudReactnativePlugin"', podspec)
        self.assertIn(':ios => "15.1"', podspec)
        self.assertIn('install_modules_dependencies(s)', podspec)
        self.assertIn('respond_to?(:install_modules_dependencies, true)', podspec)
        self.assertIn("s.dependency 'React-Core'", podspec)
        self.assertNotIn('private_header_files', podspec)
        self.assertIn("'MarketingCloud-Cdp', '3.0.2'", podspec)
        self.assertIn('s.swift_versions = "5.0"', podspec)
        self.assertNotIn("s.dependency 'Cdp'", podspec)
        self.assertNotIn("s.dependency 'SFMCSDK'", podspec)

    def test_legacy_bridge_is_removed(self):
        self.assertFalse((IOS / "RNCdp.h").exists())
        self.assertFalse((IOS / "RNCdp.m").exists())
        self.assertFalse((IOS / "DataCloudConsentSupport.h").exists())
        self.assertFalse((IOS / "DataCloudConsentSupport.mm").exists())
        self.assertFalse((IOS / "DataCloudConsentCore.swift").exists())


if __name__ == "__main__":
    unittest.main()
