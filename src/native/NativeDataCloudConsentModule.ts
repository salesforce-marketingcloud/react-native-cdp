import type { TurboModule } from 'react-native'
import { TurboModuleRegistry } from 'react-native'

/** React Native codegen contract implemented by the iOS and Android consent modules. */
export interface Spec extends TurboModule {
  /** Returns the raw native consent string for validation by the JavaScript bridge. */
  getConsent(): Promise<string>

  /** Assigns a raw consent string after validation by the JavaScript bridge. */
  setConsent(consent: string): Promise<void>
}

/**
 * Resolves the native consent module lazily.
 *
 * Lazy lookup keeps package import side-effect free and allows callers to normalize an unavailable
 * native module into the public Data Cloud error contract.
 */
export const getDataCloudConsentModule = (): Spec | null =>
  TurboModuleRegistry.get<Spec>('DataCloudConsentModule')
