#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
BINARY=$(mktemp "${TMPDIR:-/tmp}/DataCloudConsentSupportTests.XXXXXX")
trap 'rm -f "$BINARY"' EXIT HUP INT TERM

xcrun swiftc -parse-as-library -D CONSENT_CORE_TESTS \
  "$ROOT/ios/tests/DataCloudConsentBridgeTests.swift" \
  "$ROOT/ios/DataCloudConsentModule.swift" \
  -o "$BINARY"
"$BINARY"
python3 -m unittest "$ROOT/ios/tests/test_ios_consent_contract.py"
