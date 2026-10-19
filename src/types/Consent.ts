/**
 * Consent state reported by the native Data Cloud SDK.
 *
 * `pending` represents a native pending or unset consent state.
 */
export type ConsentState = 'opt_in' | 'opt_out' | 'pending'

/** Explicit consent choice that applications may assign to the native SDK. */
export type WritableConsentState = Exclude<ConsentState, 'pending'>
