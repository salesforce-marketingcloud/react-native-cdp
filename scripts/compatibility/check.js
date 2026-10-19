const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '../..');
const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== '--platform') {
  throw new Error('Usage: node scripts/compatibility/check.js --platform <android|ios>');
}
const platform = args[1];
if (!['android', 'ios'].includes(platform)) throw new Error(`Invalid platform "${platform}": expected android or ios`);

const requiredEnvironment = ['RN_VERSION', 'RN_ARCH', 'RN_CLI'];
for (const name of requiredEnvironment) {
  if (!process.env[name]?.trim()) throw new Error(`Missing required environment variable: ${name}`);
}
if (!['legacy', 'new'].includes(process.env.RN_ARCH)) {
  throw new Error(`Invalid RN_ARCH "${process.env.RN_ARCH}": expected legacy or new`);
}
if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(process.env.RN_CLI)) {
  throw new Error(`Invalid RN_CLI "${process.env.RN_CLI}": expected a version like 20.1.0`);
}
const frameworks = process.env.USE_FRAMEWORKS;
if (platform === 'android' && frameworks) throw new Error('USE_FRAMEWORKS must be empty for Android');
if (platform === 'ios' && frameworks && frameworks !== 'static') {
  throw new Error(`Invalid USE_FRAMEWORKS "${process.env.USE_FRAMEWORKS}": expected static or empty for iOS`);
}
const entry = {
  reactNative: process.env.RN_VERSION,
  architecture: process.env.RN_ARCH,
  cli: process.env.RN_CLI,
};
const repositories = [
  'https://salesforce-marketingcloud.github.io/MarketingCloudSDK-Android/repository',
  'https://salesforce-marketingcloud.github.io/mobile-sdk-cdp-android/repository',
  'https://salesforce-marketingcloud.github.io/Personalization-Android/repository',
];

function run(command, args, cwd, env = {}) {
  console.log(`$ ${command} ${args.join(' ')}`);
  execFileSync(command, args, { cwd, env: { ...process.env, ...env }, stdio: 'inherit' });
}

function output(command, args, cwd) {
  return execFileSync(command, args, { cwd, encoding: 'utf8' });
}

function packSdk(packRoot) {
  const result = JSON.parse(output('npm', ['pack', '--json', '--ignore-scripts', '--pack-destination', packRoot], root));
  const tarball = path.join(packRoot, result[0].filename);
  if (!fs.existsSync(tarball)) throw new Error(`npm pack did not create ${tarball}`);
  return tarball;
}

function validateCoexistence(app) {
  const config = JSON.parse(output('npx', ['react-native', 'config'], app));
  const cdp = config.dependencies['@salesforce-data-cloud/react-native-cdp'];
  const personalization = config.dependencies['@salesforce-personalization/react-native-personalization'];
  if (!cdp?.platforms?.android || !cdp?.platforms?.ios || !personalization?.platforms?.android || !personalization?.platforms?.ios) {
    throw new Error('React Native config did not discover both packages on Android and iOS');
  }
  const registrations = [
    ['@salesforce-data-cloud/react-native-cdp', cdp.platforms.android.packageImportPath, cdp.platforms.android.packageInstance, 'DataCloudConsentPackage'],
    ['Personalization', personalization.platforms.android.packageImportPath, personalization.platforms.android.packageInstance, 'SdkPersonalizationReactnativePluginViewPackage'],
  ];
  for (const [name, importPath, instance, packageName] of registrations) {
    if (!importPath?.includes(packageName) || !instance?.includes(packageName)) throw new Error(`${name} has invalid Android package registration`);
  }
  const cdpSource = fs.readFileSync(path.join(app, 'node_modules/@salesforce-data-cloud/react-native-cdp/android/src/main/java/com/salesforce/marketingcloud/cdp/reactnative/DataCloudConsentPackage.kt'), 'utf8');
  const cdpSpec = fs.readFileSync(path.join(app, 'node_modules/@salesforce-data-cloud/react-native-cdp/src/native/NativeDataCloudConsentModule.ts'), 'utf8');
  const personalizationSource = fs.readFileSync(path.join(app, 'node_modules/@salesforce-personalization/react-native-personalization/android/src/main/java/com/sdkpersonalizationreactnativeplugin/BridgeConstants.kt'), 'utf8');
  if (!cdpSource.includes('DataCloudConsentModule.MODULE_NAME') ||
      !cdpSpec.includes("TurboModuleRegistry.get<Spec>('DataCloudConsentModule')") ||
      !personalizationSource.includes('const val CONSENT = "ConsentModule"')) {
    throw new Error('Distinct DataCloudConsentModule and ConsentModule registrations were not found');
  }
  console.log('Autolinking coexistence valid: DataCloudConsentPackage/DataCloudConsentModule and SdkPersonalizationReactnativePluginViewPackage/ConsentModule');
}

function configureConsumerSource(app) {
  fs.writeFileSync(path.join(app, 'compatibility.ts'), `import DataCloudConsent, {
  DataCloudError,
  DataCloudModule,
  getConsent,
  setConsent,
  type ConsentState,
  type WritableConsentState,
} from '@salesforce-data-cloud/react-native-cdp';

const state: Promise<ConsentState> = getConsent();
const choice: WritableConsentState = 'opt_in';
const update: Promise<void> = setConsent(choice);
const moduleState: Promise<ConsentState> = DataCloudModule.getConsent();
const moduleUpdate: Promise<void> = DataCloudModule.setConsent(choice);
const error: typeof DataCloudError = DataCloudError;
void [DataCloudConsent, DataCloudModule, state, update, moduleState, moduleUpdate, error];
`);
  fs.appendFileSync(path.join(app, 'index.js'), "\nimport { getConsent } from '@salesforce-data-cloud/react-native-cdp';\nvoid getConsent;\n");
}

function validatePackageEntrypoints(app) {
  const commonjs = output('node', ['-e', "process.stdout.write(require.resolve('@salesforce-data-cloud/react-native-cdp'))"], app).trim();
  const module = output('node', ['--input-type=module', '-e', "process.stdout.write(import.meta.resolve('@salesforce-data-cloud/react-native-cdp'))"], app).trim();
  if (!commonjs.endsWith('/lib/commonjs/index.js') || !module.endsWith('/lib/module/index.js')) {
    throw new Error(`Unexpected package entry points: require=${commonjs}, import=${module}`);
  }
  console.log(`Package entry points valid: require=${commonjs}, import=${module}`);
}

function configureAndroidApp(app) {
  const settings = path.join(app, 'android/settings.gradle');
  const repositoryBlock = `dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS); repositories { google(); mavenCentral(); ${repositories.map((url) => `maven { url '${url}' }`).join('; ')} } }\n`;
  fs.appendFileSync(settings, `\n${repositoryBlock}`);
  const buildGradle = path.join(app, 'android/build.gradle');
  fs.writeFileSync(buildGradle, fs.readFileSync(buildGradle, 'utf8')
    .replace(/minSdkVersion = \d+/, 'minSdkVersion = 26')
    .replace(/compileSdkVersion = \d+(?:\.\d+)?/, 'compileSdkVersion = 36'));
}

function findFiles(directory, filename) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name);
    return entry.isDirectory() ? findFiles(entryPath, filename) : entry.name === filename ? [entryPath] : [];
  });
}

function validateAdvertisingIdPermission(app, expected) {
  const manifests = findFiles(path.join(app, 'android/app/build/intermediates/merged_manifests'), 'AndroidManifest.xml')
    .filter((file) => file.includes('release'));
  if (manifests.length !== 1) throw new Error(`Expected one merged Android Release manifest, received ${manifests.length}`);
  const declaresPermission = fs.readFileSync(manifests[0], 'utf8').includes('com.google.android.gms.permission.AD_ID');
  if (declaresPermission !== expected) {
    throw new Error(`Expected merged Android Release manifest AD_ID=${expected}, received ${declaresPermission}`);
  }
  console.log(`Merged Android Release manifest AD_ID=${declaresPermission}`);
}

function validateConsumer(app) {
  validateCoexistence(app);
  validatePackageEntrypoints(app);
  run('npx', ['tsc', '--noEmit'], app);
  run('npx', ['react-native', 'bundle', '--platform', 'android', '--dev', 'false', '--entry-file', 'index.js', '--bundle-output', path.join(app, 'compatibility.bundle.js'), '--assets-dest', path.join(app, 'compatibility-assets')], app);
}

function buildNative(app, entry, platform, tarball) {
  if (platform === 'android') {
    const appName = 'NativeCompatibilityApp';
    const nativeApp = path.join(path.dirname(app), appName);
    run('npx', ['--yes', `@react-native-community/cli@${entry.cli}`, 'init', appName, '--version', entry.reactNative, '--skip-install', '--pm', 'npm'], path.dirname(app));
    run('npm', ['install', tarball], nativeApp);
    configureAndroidApp(nativeApp);
    run(path.join(nativeApp, 'android/gradlew'), ['-p', 'android', 'app:assembleRelease', `-PnewArchEnabled=${entry.architecture === 'new'}`], nativeApp);
    validateAdvertisingIdPermission(nativeApp, true);
    return;
  }
  const enabled = entry.architecture === 'new' ? '1' : '0';
  const staticFrameworks = process.env.USE_FRAMEWORKS === 'static' ? 'static' : undefined;
  const iosEnv = { RCT_NEW_ARCH_ENABLED: enabled, LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8' };
  if (staticFrameworks) iosEnv.USE_FRAMEWORKS = staticFrameworks;
  run('pod', ['install'], path.join(app, 'ios'), iosEnv);
  run('xcodebuild', ['-workspace', 'CompatibilityApp.xcworkspace', '-scheme', 'CompatibilityApp', '-configuration', 'Release', '-destination', 'generic/platform=iOS Simulator', 'CODE_SIGNING_ALLOWED=NO', `RCT_NEW_ARCH_ENABLED=${enabled}`, 'build'], path.join(app, 'ios'));
}

function createConsumer(entry, platform) {
  const id = `rn-${entry.reactNative}-${entry.architecture}-${platform}`;
  const fixtureRoot = path.join(os.tmpdir(), 'react-native-cdp-compatibility');
  const laneRoot = path.join(fixtureRoot, id);
  const app = path.join(laneRoot, 'CompatibilityApp');
  fs.rmSync(laneRoot, { recursive: true, force: true });
  fs.mkdirSync(laneRoot, { recursive: true });
  try {
    const tarball = packSdk(laneRoot);
    run('npx', ['--yes', `@react-native-community/cli@${entry.cli}`, 'init', 'CompatibilityApp', '--version', entry.reactNative, '--skip-install', '--pm', 'npm'], laneRoot);
    run('npm', ['install', tarball, '@salesforce-personalization/react-native-personalization@2.0.0'], app);
    configureConsumerSource(app);
    validateConsumer(app);
    buildNative(app, entry, platform, tarball);
    const frameworkMode = platform === 'ios' && process.env.USE_FRAMEWORKS ? `, frameworks=${process.env.USE_FRAMEWORKS}` : '';
    const fixtureMode = platform === 'android' ? 'CDP-only native Release' : 'co-installed native Release';
    console.log(`PASS ${id}: ${fixtureMode}${frameworkMode}, artifact=${path.basename(tarball)}`);
  } finally {
    if (process.env.KEEP_COMPATIBILITY_APP === '1') console.log(`Kept fixture: ${app}`);
    else fs.rmSync(laneRoot, { recursive: true, force: true });
  }
}

run('npm', ['run', 'build'], root);
createConsumer(entry, platform);
