import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  AccessibilityInfo,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {
  DataCloudModule,
  DataCloudError,
  ErrorCode,
  type ConsentState,
  type WritableConsentState,
} from '@salesforce-data-cloud/react-native-cdp';

const consentLabel = (state: ConsentState | null) => {
  if (state === 'opt_in') return 'Opted in';
  if (state === 'opt_out') return 'Opted out';
  if (state === 'pending') return 'Pending';
  return 'Unknown';
};

export default function App() {
  const [consent, setCurrentConsent] = useState<ConsentState | null>(null);
  const [moduleStatus, setModuleStatus] = useState('Available');
  const [readinessStatus, setReadinessStatus] = useState('Checking');
  const [operationStatus, setOperationStatus] = useState('Reading consent…');
  const [busy, setBusy] = useState(true);
  const generation = useRef(0);

  const announceCompletion = useCallback((message: string) => {
    if (Platform.OS === 'ios') {
      AccessibilityInfo.announceForAccessibilityWithOptions(message, {queue: true});
    }
  }, []);

  const handleError = useCallback((error: unknown, operation: number) => {
    if (operation !== generation.current) return;
    const sdkError = error instanceof DataCloudError
      ? error
      : new DataCloudError(ErrorCode.E_NATIVE_FAILURE, String(error));
    if (sdkError.code === ErrorCode.E_SDK_UNAVAILABLE) {
      setModuleStatus('Unavailable');
      setReadinessStatus('Unknown');
    } else if (sdkError.code === ErrorCode.E_SDK_NOT_READY) {
      setModuleStatus('Available');
      setReadinessStatus('Timed out');
    } else {
      setModuleStatus('Available');
      setReadinessStatus('Unknown');
    }
    setOperationStatus(sdkError.message);
    announceCompletion(sdkError.message);
  }, [announceCompletion]);

  const readConsent = useCallback(async (successMessage?: string) => {
    const operation = ++generation.current;
    setBusy(true);
    setOperationStatus(successMessage ? 'Refreshing consent…' : 'Reading consent…');
    try {
      const state = await DataCloudModule.getConsent();
      if (operation === generation.current) {
        setCurrentConsent(state);
        setModuleStatus('Available');
        setReadinessStatus('Ready');
        const result = `Current consent: ${consentLabel(state)}.`;
        const message = successMessage ? `${successMessage} ${result}` : `Consent loaded. ${result}`;
        setOperationStatus(message);
        announceCompletion(message);
      }
    } catch (error) {
      handleError(error, operation);
    } finally {
      if (operation === generation.current) setBusy(false);
    }
  }, [announceCompletion, handleError]);

  useEffect(() => {
    readConsent();
  }, [readConsent]);

  const updateConsent = async (state: WritableConsentState) => {
    const operation = ++generation.current;
    setBusy(true);
    setOperationStatus(`Updating consent to ${consentLabel(state).toLowerCase()}…`);
    try {
      await DataCloudModule.setConsent(state);
      const current = await DataCloudModule.getConsent();
      if (operation === generation.current) {
        setCurrentConsent(current);
        setModuleStatus('Available');
        setReadinessStatus('Ready');
        const message = `Consent updated to ${consentLabel(current).toLowerCase()}.`;
        setOperationStatus(message);
        announceCompletion(message);
      }
    } catch (error) {
      handleError(error, operation);
    } finally {
      if (operation === generation.current) setBusy(false);
    }
  };

  const buttonProps = (testID: string, label: string, hint: string) => ({
    testID,
    accessibilityRole: 'button' as const,
    accessibilityLabel: label,
    accessibilityHint: hint,
    accessibilityState: {disabled: busy},
    disabled: busy,
    style: ({pressed}: {pressed: boolean}) => [
      styles.button,
      busy && styles.buttonDisabled,
      pressed && styles.buttonPressed,
    ],
  });

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea}>
        <ScrollView contentContainerStyle={styles.scrollContent}>
          <View style={styles.card}>
            <Text accessibilityRole="header" style={styles.title}>Data Cloud Consent</Text>
            <Text style={styles.intro}>Use the native Data Cloud module to inspect and update consent.</Text>
            <View style={styles.statusPanel}>
              <Text style={styles.statusText}>{`Module status: ${moduleStatus}`}</Text>
              <Text style={styles.statusText}>{`SDK readiness: ${readinessStatus}`}</Text>
              <Text style={styles.consent}>{`Current consent: ${consentLabel(consent)}`}</Text>
            </View>
            <Text
              testID="operation-status"
              role="status"
              accessibilityLiveRegion="polite"
              style={styles.operationStatus}>
              {operationStatus}
            </Text>
            <View style={styles.actions} accessibilityRole="none">
              <Pressable
                {...buttonProps('refresh-consent', 'Refresh consent', 'Reads the latest consent state from the native Data Cloud SDK.')}
                onPress={() => readConsent('Consent refreshed.')}>
                <Text style={styles.buttonText}>Refresh</Text>
              </Pressable>
              <Pressable
                {...buttonProps('opt-in', 'Opt In', 'Updates Data Cloud consent to opted in.')}
                onPress={() => updateConsent('opt_in')}>
                <Text style={styles.buttonText}>Opt In</Text>
              </Pressable>
              <Pressable
                {...buttonProps('opt-out', 'Opt Out', 'Updates Data Cloud consent to opted out.')}
                onPress={() => updateConsent('opt_out')}>
                <Text style={styles.buttonText}>Opt Out</Text>
              </Pressable>
            </View>
          </View>
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safeArea: {flex: 1, backgroundColor: '#F3F6F9'},
  scrollContent: {flexGrow: 1, alignItems: 'center', justifyContent: 'center', padding: 24},
  card: {width: '100%', maxWidth: 640, backgroundColor: '#FFFFFF', borderRadius: 16, padding: 24, borderWidth: 1, borderColor: '#C9D3DF'},
  title: {fontSize: 28, lineHeight: 34, fontWeight: '700', color: '#181818'},
  intro: {fontSize: 16, lineHeight: 24, color: '#3E3E3C', marginTop: 8},
  statusPanel: {backgroundColor: '#EEF4FF', borderRadius: 10, padding: 16, marginTop: 24, gap: 8},
  statusText: {fontSize: 16, color: '#181818'},
  consent: {fontSize: 18, fontWeight: '700', color: '#032D60'},
  operationStatus: {minHeight: 48, marginTop: 16, fontSize: 16, lineHeight: 24, color: '#181818'},
  actions: {gap: 12},
  button: {minHeight: 48, alignItems: 'center', justifyContent: 'center', borderRadius: 8, backgroundColor: '#0B5CAB', paddingHorizontal: 20},
  buttonDisabled: {backgroundColor: '#5C6F82'},
  buttonPressed: {backgroundColor: '#032D60'},
  buttonText: {color: '#FFFFFF', fontSize: 16, fontWeight: '700'},
});
