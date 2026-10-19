import React from 'react';
import ReactTestRenderer, {act} from 'react-test-renderer';
import {AccessibilityInfo, Platform, Text} from 'react-native';
import {DataCloudError, ErrorCode} from '@salesforce-data-cloud/react-native-cdp';

const mockGetConsent = jest.fn();
const mockSetConsent = jest.fn();
const mockAnnounce = jest.spyOn(AccessibilityInfo, 'announceForAccessibilityWithOptions').mockImplementation(() => undefined);

jest.mock('@salesforce-data-cloud/react-native-cdp', () => {
  const actual = jest.requireActual('@salesforce-data-cloud/react-native-cdp');
  return {
    ...actual,
    DataCloudModule: {
      getConsent: (...args: []) => mockGetConsent(...args),
      setConsent: (...args: ['opt_in' | 'opt_out']) => mockSetConsent(...args),
    },
  };
});

jest.mock('react-native-safe-area-context', () => ({
  SafeAreaProvider: ({children}: {children: React.ReactNode}) => children,
  SafeAreaView: ({children}: {children: React.ReactNode}) => children,
}));

import App from '../src/App';

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return {promise, resolve, reject};
};

const render = async () => {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<App />); });
  return renderer;
};

const text = (renderer: ReactTestRenderer.ReactTestRenderer) => renderer.root
  .findAllByType(Text).flatMap(node => node.props.children).flat(Infinity).join(' ');

describe('Data Cloud consent example', () => {
  beforeEach(() => {
    Object.defineProperty(Platform, 'OS', {configurable: true, value: 'ios'});
    mockGetConsent.mockReset().mockResolvedValue('pending');
    mockSetConsent.mockReset().mockResolvedValue(undefined);
    mockAnnounce.mockClear();
  });

  it('shows module and readiness status and reads pending consent on launch', async () => {
    const renderer = await render();
    expect(mockGetConsent).toHaveBeenCalledTimes(1);
    expect(text(renderer)).toContain('Module status: Available');
    expect(text(renderer)).toContain('SDK readiness: Ready');
    expect(text(renderer)).toContain('Current consent: Pending');
  });

  it('refreshes consent successfully', async () => {
    mockGetConsent.mockResolvedValueOnce('opt_out').mockResolvedValueOnce('opt_in');
    const renderer = await render();
    await act(async () => {
      await renderer.root.findByProps({testID: 'refresh-consent'}).props.onPress();
    });
    expect(text(renderer)).toContain('Current consent: Opted in');
    expect(text(renderer)).toContain('Consent refreshed. Current consent: Opted in.');
    expect(mockAnnounce).toHaveBeenLastCalledWith(
      'Consent refreshed. Current consent: Opted in.',
      {queue: true},
    );
    expect(mockAnnounce).not.toHaveBeenCalledWith('Refreshing consent…', expect.anything());
  });

  it.each([['opt_in', 'Opt In', 'Opted in'], ['opt_out', 'Opt Out', 'Opted out']] as const)(
    'updates consent to %s and confirms the current native state', async (state, button, label) => {
      mockGetConsent.mockResolvedValueOnce('pending').mockResolvedValueOnce(state);
      const renderer = await render();
      await act(async () => {
        await renderer.root.findByProps({accessibilityLabel: button}).props.onPress();
      });
      expect(mockSetConsent).toHaveBeenCalledWith(state);
      expect(text(renderer)).toContain(`Current consent: ${label}`);
      expect(text(renderer)).toContain(`Consent updated to ${label.toLowerCase()}.`);
    },
  );

  it('waits for a write before reading the confirmed consent state', async () => {
    const write = deferred<void>();
    mockSetConsent.mockReturnValue(write.promise);
    mockGetConsent.mockResolvedValueOnce('pending').mockResolvedValueOnce('opt_in');
    const renderer = await render();
    let update!: Promise<void>;
    await act(async () => {
      update = renderer.root.findByProps({accessibilityLabel: 'Opt In'}).props.onPress();
      await Promise.resolve();
    });
    expect(mockSetConsent).toHaveBeenCalledTimes(1);
    expect(mockGetConsent).toHaveBeenCalledTimes(1);
    write.resolve();
    await act(async () => {
      await update;
    });
    expect(mockGetConsent).toHaveBeenCalledTimes(2);
    expect(text(renderer)).toContain('Current consent: Opted in');
  });

  it.each([
    [ErrorCode.E_SDK_NOT_READY, 'Native SDK is not ready'],
    [ErrorCode.E_SDK_UNAVAILABLE, 'Native module is unavailable'],
    [ErrorCode.E_NATIVE_FAILURE, 'Native consent operation failed'],
  ])('surfaces %s failures', async (code, message) => {
    mockGetConsent.mockRejectedValue(new DataCloudError(code, message));
    const renderer = await render();
    expect(text(renderer)).toContain(message);
    expect(text(renderer)).toContain(code === ErrorCode.E_SDK_UNAVAILABLE ? 'Module status: Unavailable' : 'Module status: Available');
    expect(text(renderer)).toContain(code === ErrorCode.E_SDK_NOT_READY ? 'SDK readiness: Timed out' : 'SDK readiness: Unknown');
    expect(mockAnnounce).toHaveBeenLastCalledWith(message, {queue: true});
  });

  it('does not read consent after a rejected write and restores the controls', async () => {
    mockSetConsent.mockRejectedValue(
      new DataCloudError(ErrorCode.E_SDK_NOT_READY, 'Native SDK is not ready'),
    );
    const renderer = await render();
    await act(async () => {
      await renderer.root.findByProps({accessibilityLabel: 'Opt In'}).props.onPress();
    });
    expect(mockGetConsent).toHaveBeenCalledTimes(1);
    expect(text(renderer)).toContain('Native SDK is not ready');
    expect(text(renderer)).toContain('SDK readiness: Timed out');
    for (const id of ['refresh-consent', 'opt-in', 'opt-out']) {
      expect(renderer.root.findByProps({testID: id}).props.disabled).toBe(false);
    }
  });

  it('surfaces a rejected confirmation read and restores the controls', async () => {
    mockGetConsent
      .mockResolvedValueOnce('pending')
      .mockRejectedValueOnce(
        new DataCloudError(ErrorCode.E_NATIVE_FAILURE, 'Confirmation read failed'),
      );
    const renderer = await render();
    await act(async () => {
      await renderer.root.findByProps({accessibilityLabel: 'Opt Out'}).props.onPress();
    });
    expect(mockSetConsent).toHaveBeenCalledWith('opt_out');
    expect(mockGetConsent).toHaveBeenCalledTimes(2);
    expect(text(renderer)).toContain('Confirmation read failed');
    expect(text(renderer)).toContain('SDK readiness: Unknown');
    for (const id of ['refresh-consent', 'opt-in', 'opt-out']) {
      expect(renderer.root.findByProps({testID: id}).props.disabled).toBe(false);
    }
  });

  it('surfaces an invalid native state as a native failure', async () => {
    mockGetConsent.mockRejectedValue(new DataCloudError(ErrorCode.E_NATIVE_FAILURE, 'Native module returned an invalid consent state'));
    const renderer = await render();
    expect(text(renderer)).toContain('Native module returned an invalid consent state');
  });

  it('prevents a stale refresh from replacing a newer update result', async () => {
    const staleRead = deferred<'opt_out'>();
    mockGetConsent.mockReturnValueOnce(staleRead.promise).mockResolvedValueOnce('opt_in');
    let renderer!: ReactTestRenderer.ReactTestRenderer;
    await act(async () => { renderer = ReactTestRenderer.create(<App />); });
    await act(async () => {
      await renderer.root.findByProps({accessibilityLabel: 'Opt In'}).props.onPress();
    });
    staleRead.resolve('opt_out');
    await act(async () => {
      await staleRead.promise;
    });
    expect(text(renderer)).toContain('Current consent: Opted in');
  });

  it('disables conflicting controls and exposes accessible operation status', async () => {
    const update = deferred<void>();
    mockSetConsent.mockReturnValue(update.promise);
    const renderer = await render();
    await act(async () => {
      renderer.root.findByProps({accessibilityLabel: 'Opt In'}).props.onPress();
      await Promise.resolve();
    });
    for (const id of ['refresh-consent', 'opt-in', 'opt-out']) {
      expect(renderer.root.findByProps({testID: id}).props.accessibilityState.disabled).toBe(true);
    }
    expect(renderer.root.findByProps({testID: 'operation-status'}).props.accessibilityLiveRegion).toBe('polite');
    expect(renderer.root.findByProps({testID: 'operation-status'}).props.role).toBe('status');
    expect(renderer.root.findByProps({testID: 'refresh-consent'}).props.accessibilityHint).toBeTruthy();
    update.resolve();
    await act(async () => {
      await update.promise;
    });
  });

  it('retains Android live-region behavior without explicit announcements', async () => {
    Object.defineProperty(Platform, 'OS', {configurable: true, value: 'android'});
    const renderer = await render();
    expect(renderer.root.findByProps({testID: 'operation-status'}).props.accessibilityLiveRegion).toBe('polite');
    expect(mockAnnounce).not.toHaveBeenCalled();
  });
});
