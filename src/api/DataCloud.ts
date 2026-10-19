import { ConsentBridge } from '../bridges/ConsentBridge'
import type { ConsentState, WritableConsentState } from '../types/Consent'

/**
 * Reads the current consent state from the native Data Cloud SDK.
 *
 * Valid operations admitted through one active native bridge instance execute native actions one
 * at a time in admission order, regardless of readiness callback order. Readiness failures or
 * bridge invalidation can prevent execution. Callers should still await dependent operations.
 * This FIFO contract excludes direct native access, other packages, and overlapping bridge
 * instances; it does not guarantee promise settlement order.
 *
 * @returns A promise that resolves to `opt_in`, `opt_out`, or `pending`.
 * @throws `DataCloudError` with `E_SDK_NOT_READY` when the readiness deadline expires,
 * `E_SDK_UNAVAILABLE` when the native module or active React Native instance is unavailable, or
 * `E_NATIVE_FAILURE` when the native operation fails or returns an invalid value.
 */
export function getConsent(): Promise<ConsentState> {
  return ConsentBridge.getConsent()
}

/**
 * Sets an explicit consent choice in the native Data Cloud SDK.
 *
 * Valid operations admitted through one active native bridge instance execute native actions one
 * at a time in admission order, regardless of readiness callback order. Readiness failures or
 * bridge invalidation can prevent execution. Callers should still await dependent operations.
 * This FIFO contract excludes direct native access, other packages, and overlapping bridge
 * instances; it does not guarantee promise settlement order.
 *
 * @param consent - The `opt_in` or `opt_out` choice to set.
 * @returns A promise that resolves after synchronous local native assignment returns. Resolution
 * does not confirm completion of asynchronous persistence, event-queue processing, consent-event
 * delivery, or server propagation.
 * @throws `DataCloudError` with `E_INVALID_CONSENT`, `E_SDK_NOT_READY`, `E_SDK_UNAVAILABLE`, or
 * `E_NATIVE_FAILURE` when the update cannot be completed.
 */
export function setConsent(consent: WritableConsentState): Promise<void> {
  return ConsentBridge.setConsent(consent)
}

/** Object-form API containing the same function references as the `getConsent` and `setConsent` named exports. */
export const DataCloudModule = { getConsent, setConsent }

/** The same object as the named `DataCloudModule` export. */
export default DataCloudModule
