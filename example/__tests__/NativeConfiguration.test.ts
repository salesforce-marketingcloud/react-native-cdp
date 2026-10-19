const fs = jest.requireActual('fs');
const path = jest.requireActual('path');

const exampleRoot = path.resolve('.');
const read = (relativePath: string) =>
  fs.readFileSync(path.join(exampleRoot, relativePath), 'utf8');

describe('standalone native example configuration', () => {
  const androidApplication = 'android/app/src/main/java/com/datacloudconsentexample/MainApplication.kt';
  const androidActivity = 'android/app/src/main/java/com/datacloudconsentexample/MainActivity.kt';
  const scheme = 'ios/DataCloudConsentExample.xcodeproj/xcshareddata/xcschemes/DataCloudConsentExample.xcscheme';

  it('uses one DataCloudConsentExample identity across both native apps', () => {
    expect(JSON.parse(read('app.json')).name).toBe('DataCloudConsentExample');
    expect(read('android/settings.gradle')).toContain("rootProject.name = 'DataCloudConsentExample'");
    expect(read('android/app/build.gradle')).toContain('namespace "com.datacloudconsentexample"');
    expect(read(androidApplication)).toContain('package com.datacloudconsentexample');
    expect(read(androidActivity)).toContain('override fun getMainComponentName(): String = "DataCloudConsentExample"');
    expect(read('android/app/src/main/res/values/strings.xml')).toContain('Data Cloud Consent');
    expect(read(scheme)).toContain('BlueprintName = "DataCloudConsentExample"');
    expect(read(scheme)).not.toContain('DataCloudConsentExampleTests');
    expect(read('ios/DataCloudConsentExample/Info.plist')).toContain('<string>Data Cloud Consent</string>');
    expect(read('ios/DataCloudConsentExample/Info.plist')).not.toContain('NSLocationWhenInUseUsageDescription');
  });

  it('resolves React Native dependencies from the example node_modules directory', () => {
    expect(read('android/settings.gradle')).toContain('../node_modules/@react-native/gradle-plugin');
    expect(read('android/app/build.gradle')).toContain('reactNativeDir = file("../../node_modules/react-native")');
    expect(read('android/app/build.gradle')).toContain('codegenDir = file("../../node_modules/@react-native/codegen")');
    expect(read('android/app/build.gradle')).toContain('cliFile = file("../../node_modules/react-native/cli.js")');
  });

  it('uses Gradle default debug signing without a repository-local keystore', () => {
    const build = read('android/app/build.gradle');
    expect(build).not.toContain("storeFile file('debug.keystore')");
    expect(build).not.toContain('signingConfig signingConfigs.debug');
  });

  it('fails fast on missing credentials and passes the configured endpoint directly to the SDK', () => {
    const android = read(androidApplication);
    const ios = read('ios/DataCloudConsentExample/AppDelegate.swift');

    expect(android).toContain('require(appId.isNotBlank() && !appId.startsWith("YOUR_"))');
    expect(android).toContain('require(endpoint.isNotBlank() && !endpoint.startsWith("YOUR_"))');
    expect(android).toContain('CdpConfig.Builder(this, appId, endpoint)');

    expect(ios).toContain('!appId.isEmpty && !appId.hasPrefix("YOUR_")');
    expect(ios).toContain('!endpoint.isEmpty && !endpoint.hasPrefix("YOUR_")');
    expect(ios).toContain('CdpConfigBuilder(appId: appId, endpoint: endpoint)');

    const scripts = JSON.parse(read('package.json')).scripts;
    expect(scripts['test:endpoints']).toBeUndefined();
    expect(scripts['test:endpoints:android']).toBeUndefined();
    expect(scripts['test:endpoints:ios']).toBeUndefined();
  });

  it('uses safe native logging and transport defaults', () => {
    const android = read(androidApplication);
    const ios = read('ios/DataCloudConsentExample/AppDelegate.swift');
    const manifest = read('android/app/src/main/AndroidManifest.xml');

    expect(android).toContain('LogLevel.ERROR');
    expect(android).not.toContain('LogLevel.DEBUG');
    expect(android).not.toContain('Log.d(');
    expect(ios).toContain('SFMCSdk.setLogger(logLevel: .error)');
    expect(ios).not.toContain('SFMCSdk.setLogger(logLevel: .debug)');
    expect(ios).not.toMatch(/print\("Salesforce module/);
    expect(manifest).not.toContain('usesCleartextTraffic');
  });

  it('allows Metro to resolve the locally linked SDK in Release builds', () => {
    const metro = read('metro.config.js');

    expect(metro).toContain("path.resolve(__dirname, '..')");
    expect(metro).toContain('watchFolders: [repositoryRoot]');
  });

  it('documents the trusted packaged endpoint security contract', () => {
    const readme = read('../README.md');
    expect(readme).toContain('trusted packaged app configuration');
    expect(readme).toContain('must not come from user input or untrusted remote data');
    expect(readme).toContain('native builders do not enforce Salesforce host allowlisting');
    expect(readme).toContain('native SDK logging defaults to `ERROR`');
  });

  it('pins the Gradle distribution checksum', () => {
    const wrapper = read('android/gradle/wrapper/gradle-wrapper.properties');
    expect(wrapper).toContain('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip');
    expect(wrapper).toContain('distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06');
  });
});
