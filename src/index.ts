/** Public Data Cloud consent API. */
export {
  DataCloudModule,
  getConsent,
  setConsent,
  default,
} from './api/DataCloud'
/** Public Data Cloud error types and stable runtime codes. */
export { DataCloudError, ErrorCode } from './errors/DataCloudError'

/** Public consent-state type declarations. */
export type { ConsentState, WritableConsentState } from './types/Consent'
