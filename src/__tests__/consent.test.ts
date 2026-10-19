type NativeConsentModule = {
  getConsent: jest.Mock<Promise<unknown>, []>
  setConsent: jest.Mock<Promise<void>, [string]>
}

const loadApi = (
  nativeModule: NativeConsentModule | null,
  registryError?: Error
) => {
  jest.resetModules()
  jest.doMock('react-native', () => ({
    TurboModuleRegistry: {
      get: jest.fn(() => {
        if (registryError !== undefined) throw registryError
        return nativeModule
      }),
    },
  }))

  return require('../index') as typeof import('../index')
}

const createNativeModule = (): NativeConsentModule => ({
  getConsent: jest.fn(),
  setConsent: jest.fn(),
})

describe('Data Cloud consent', () => {
  afterEach(() => {
    jest.clearAllMocks()
  })

  it.each(['opt_in', 'opt_out', 'pending'] as const)(
    'returns the native %s consent state',
    async (state) => {
      const nativeModule = createNativeModule()
      nativeModule.getConsent.mockResolvedValue(state)
      const { getConsent } = loadApi(nativeModule)

      await expect(getConsent()).resolves.toBe(state)
      expect(nativeModule.getConsent).toHaveBeenCalledTimes(1)
    }
  )

  it('forwards updates and repeated updates to native', async () => {
    const nativeModule = createNativeModule()
    nativeModule.setConsent.mockResolvedValue(undefined)
    const { setConsent } = loadApi(nativeModule)

    await setConsent('opt_in')
    await setConsent('opt_out')
    await setConsent('opt_out')

    expect(nativeModule.setConsent.mock.calls).toEqual([
      ['opt_in'],
      ['opt_out'],
      ['opt_out'],
    ])
  })

  it.each([
    'pending',
    'unknown',
    ' opt_in',
    'opt_in ',
    'OPT_IN',
    '',
    null,
    undefined,
    true,
    1,
    {},
  ])('rejects invalid writable consent value %p', async (value) => {
    const nativeModule = createNativeModule()
    const { DataCloudError, setConsent } = loadApi(nativeModule)

    await expect(setConsent(value as never)).rejects.toMatchObject({
      name: 'DataCloudError',
      code: 'E_INVALID_CONSENT',
    })
    expect(nativeModule.setConsent).not.toHaveBeenCalled()
    expect(DataCloudError).toBeDefined()
  })

  it.each(['OPT_IN', 'unknown', '', null, undefined, true, 1, {}])(
    'rejects malformed native consent result %p',
    async (value) => {
      const nativeModule = createNativeModule()
      nativeModule.getConsent.mockResolvedValue(value)
      const { getConsent } = loadApi(nativeModule)

      await expect(getConsent()).rejects.toMatchObject({
        name: 'DataCloudError',
        code: 'E_NATIVE_FAILURE',
      })
    }
  )

  it('does not look up the TurboModule merely by importing the package', () => {
    jest.resetModules()
    const get = jest.fn(() => {
      throw new Error('module lookup failed')
    })
    jest.doMock('react-native', () => ({ TurboModuleRegistry: { get } }))

    expect(() => require('../index')).not.toThrow()
    expect(get).not.toHaveBeenCalled()
  })

  it.each(['getConsent', 'setConsent'] as const)(
    'normalizes an unavailable module for %s',
    async (method) => {
      const api = loadApi(null)

      const operation =
        method === 'getConsent' ? api.getConsent() : api.setConsent('opt_in')
      await expect(operation).rejects.toMatchObject({
        name: 'DataCloudError',
        code: 'E_SDK_UNAVAILABLE',
      })
    }
  )

  it.each(['getConsent', 'setConsent'] as const)(
    'preserves a recognized native error code from %s',
    async (method) => {
      const nativeModule = createNativeModule()
      const nativeError = {
        code: 'E_SDK_NOT_READY',
        message: 'SDK is starting',
      }
      nativeModule[method].mockRejectedValue(nativeError)
      const api = loadApi(nativeModule)

      const operation =
        method === 'getConsent' ? api.getConsent() : api.setConsent('opt_in')
      await expect(operation).rejects.toMatchObject({
        name: 'DataCloudError',
        code: 'E_SDK_NOT_READY',
        message: 'SDK is starting',
        cause: nativeError,
      })
    }
  )

  it.each(['getConsent', 'setConsent'] as const)(
    'normalizes an unrecognized native failure from %s',
    async (method) => {
      const nativeModule = createNativeModule()
      const nativeError = new Error('native exploded')
      nativeModule[method].mockRejectedValue(nativeError)
      const api = loadApi(nativeModule)

      const operation =
        method === 'getConsent' ? api.getConsent() : api.setConsent('opt_out')
      await expect(operation).rejects.toMatchObject({
        name: 'DataCloudError',
        code: 'E_NATIVE_FAILURE',
        message: 'native exploded',
        cause: nativeError,
      })
    }
  )

  it('preserves a TurboModule registry error as the unavailable error cause', async () => {
    const registryError = new Error('module lookup failed')
    const api = loadApi(null, registryError)

    await expect(api.getConsent()).rejects.toMatchObject({
      name: 'DataCloudError',
      code: 'E_SDK_UNAVAILABLE',
      cause: registryError,
    })
  })

  it('keeps cause non-enumerable and omits it when none is supplied', () => {
    const api = loadApi(null)
    const cause = new Error('root cause')
    const wrapped = new api.DataCloudError(
      api.ErrorCode.E_NATIVE_FAILURE,
      'wrapped',
      { cause }
    )
    const standalone = new api.DataCloudError(
      api.ErrorCode.E_NATIVE_FAILURE,
      'standalone'
    )

    expect(wrapped.cause).toBe(cause)
    expect(Object.getOwnPropertyDescriptor(wrapped, 'cause')).toEqual({
      value: cause,
      writable: true,
      configurable: true,
      enumerable: false,
    })
    expect(Object.prototype.hasOwnProperty.call(standalone, 'cause')).toBe(false)
  })

  it('exports compatibility methods on the default object', async () => {
    const nativeModule = createNativeModule()
    nativeModule.getConsent.mockResolvedValue('opt_in')
    const api = loadApi(nativeModule)

    expect(api.default.getConsent).toBe(api.getConsent)
    expect(api.default.setConsent).toBe(api.setConsent)
    await expect(api.default.getConsent()).resolves.toBe('opt_in')
  })

  it('exports one canonical Data Cloud API object', async () => {
    const nativeModule = createNativeModule()
    nativeModule.getConsent.mockResolvedValue('opt_out')
    nativeModule.setConsent.mockResolvedValue(undefined)
    const api = loadApi(nativeModule)

    expect(api.default).toBe(api.DataCloudModule)
    expect(api.DataCloudModule.getConsent).toBe(api.getConsent)
    expect(api.DataCloudModule.setConsent).toBe(api.setConsent)
    await expect(api.DataCloudModule.getConsent()).resolves.toBe('opt_out')
    await expect(api.DataCloudModule.setConsent('opt_in')).resolves.toBeUndefined()
  })

  it('exports only the documented runtime API', () => {
    const api = loadApi(createNativeModule())

    expect(Object.keys(api).sort()).toEqual(
      [
        'DataCloudError',
        'DataCloudModule',
        'ErrorCode',
        'default',
        'getConsent',
        'setConsent',
      ].sort()
    )
  })

  it('exports the documented error code values', () => {
    const nativeModule = createNativeModule()
    const { ErrorCode } = loadApi(nativeModule)

    expect(ErrorCode).toEqual({
      E_INVALID_CONSENT: 'E_INVALID_CONSENT',
      E_SDK_NOT_READY: 'E_SDK_NOT_READY',
      E_SDK_UNAVAILABLE: 'E_SDK_UNAVAILABLE',
      E_NATIVE_FAILURE: 'E_NATIVE_FAILURE',
    })
  })
})
