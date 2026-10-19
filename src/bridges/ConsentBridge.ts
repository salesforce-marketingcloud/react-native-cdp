import { DataCloudError, ErrorCode, normalizeError } from '../errors/DataCloudError'
import { getDataCloudConsentModule } from '../native/NativeDataCloudConsentModule'
import type { ConsentState, WritableConsentState } from '../types/Consent'

/** Native values accepted as readable consent states. */
const consentStates: ReadonlySet<unknown> = new Set([
  'opt_in',
  'opt_out',
  'pending',
])
/** Native values accepted as explicit writable choices. */
const writableConsentStates: ReadonlySet<unknown> = new Set([
  'opt_in',
  'opt_out',
])

/** Returns the native module or throws the public unavailable error. */
const nativeModule = () => {
  try {
    const module = getDataCloudConsentModule()
    if (module !== null) return module
  } catch (error) {
    throw new DataCloudError(
      ErrorCode.E_SDK_UNAVAILABLE,
      'DataCloudConsentModule is unavailable',
      { cause: error }
    )
  }
  throw new DataCloudError(
    ErrorCode.E_SDK_UNAVAILABLE,
    'DataCloudConsentModule is unavailable'
  )
}

/**
 * Validated bridge to native Data Cloud consent operations.
 *
 * This boundary validates values in both directions and normalizes platform-specific rejections
 * into {@link DataCloudError} instances.
 */
export const ConsentBridge = {
  /** Reads and validates the consent state returned by the native module. */
  async getConsent(): Promise<ConsentState> {
    try {
      const consent = await nativeModule().getConsent()
      if (!consentStates.has(consent)) {
        throw new DataCloudError(
          ErrorCode.E_NATIVE_FAILURE,
          'Native module returned an invalid consent state'
        )
      }
      return consent as ConsentState
    } catch (error) {
      throw normalizeError(error)
    }
  },

  /** Validates and forwards an explicit consent choice to the native module. */
  async setConsent(consent: WritableConsentState): Promise<void> {
    if (!writableConsentStates.has(consent)) {
      throw new DataCloudError(
        ErrorCode.E_INVALID_CONSENT,
        'Consent must be exactly "opt_in" or "opt_out"'
      )
    }
    try {
      await nativeModule().setConsent(consent)
    } catch (error) {
      throw normalizeError(error)
    }
  },
}
