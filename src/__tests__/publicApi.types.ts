import DataCloud, {
  DataCloudError,
  DataCloudModule,
  ErrorCode,
  getConsent,
  setConsent,
  type ConsentState,
  type ErrorCode as ErrorCodeType,
  type WritableConsentState,
} from '../index'

const state: Promise<ConsentState> = getConsent()
const choice: WritableConsentState = 'opt_in'
const update: Promise<void> = setConsent(choice)
const moduleState: Promise<ConsentState> = DataCloudModule.getConsent()
const moduleUpdate: Promise<void> = DataCloudModule.setConsent('opt_out')
const errorCode: ErrorCodeType = ErrorCode.E_NATIVE_FAILURE
const error = new DataCloudError(errorCode, 'Native failure')

// @ts-expect-error Pending is reported by the SDK but cannot be written.
void setConsent('pending')
// @ts-expect-error The module API has the same writable consent contract.
void DataCloudModule.setConsent('pending')

void [DataCloud, state, update, moduleState, moduleUpdate, error]
