/** Stable machine-readable codes exposed by rejected Data Cloud operations. */
export const ErrorCode = {
  /** `setConsent` received a value other than `opt_in` or `opt_out`. */
  E_INVALID_CONSENT: 'E_INVALID_CONSENT',
  /** The native Data Cloud SDK did not become ready within the readiness deadline. */
  E_SDK_NOT_READY: 'E_SDK_NOT_READY',
  /** The native module or active React Native instance is unavailable. */
  E_SDK_UNAVAILABLE: 'E_SDK_UNAVAILABLE',
  /** The native operation failed or returned an invalid consent value. */
  E_NATIVE_FAILURE: 'E_NATIVE_FAILURE',
} as const

/** A stable machine-readable Data Cloud failure category. */
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode]

/** Error returned when a Data Cloud operation cannot be completed. */
export class DataCloudError extends Error {
  /** Machine-readable failure category. */
  readonly code: ErrorCode
  /** Original error or rejection value, when this error wraps another failure. */
  readonly cause?: unknown

  /**
   * Creates a Data Cloud API error.
   *
   * @param code - Stable machine-readable failure category.
   * @param message - Human-readable failure description.
   * @param options - Optional settings containing the original rejection value as `cause`.
   */
  constructor(
    code: ErrorCode,
    message: string,
    options?: { cause?: unknown }
  ) {
    super(message)
    this.name = 'DataCloudError'
    this.code = code
    if (options && Object.prototype.hasOwnProperty.call(options, 'cause')) {
      Object.defineProperty(this, 'cause', {
        value: options.cause,
        writable: true,
        configurable: true,
      })
    }
  }
}

/** Codes that may pass through unchanged from native promise rejections. */
const errorCodes: ReadonlySet<unknown> = new Set(Object.values(ErrorCode))

/** Extracts a useful message without assuming the rejection is an Error instance. */
const messageFrom = (error: unknown): string => {
  if (
    typeof error === 'object' &&
    error !== null &&
    'message' in error &&
    typeof error.message === 'string'
  ) {
    return error.message
  }
  return 'Native consent operation failed'
}

/**
 * Converts native or arbitrary rejection values into the public error contract.
 *
 * @param error - Value rejected by the native module or bridge infrastructure.
 * @returns The original `DataCloudError`, or a new `DataCloudError` preserving a recognized native
 * code when available and otherwise using `E_NATIVE_FAILURE`.
 * @internal
 */
export const normalizeError = (error: unknown): DataCloudError => {
  if (error instanceof DataCloudError) return error
  if (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    errorCodes.has(error.code)
  ) {
    return new DataCloudError(error.code as ErrorCode, messageFrom(error), {
      cause: error,
    })
  }
  return new DataCloudError(ErrorCode.E_NATIVE_FAILURE, messageFrom(error), {
    cause: error,
  })
}
