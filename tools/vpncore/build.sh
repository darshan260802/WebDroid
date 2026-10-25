#!/usr/bin/env bash
# Builds the per-site VPN core into app/libs/vpncore.aar with gomobile.
# Needs Go (see go.mod) and an Android NDK (ANDROID_NDK_HOME or ANDROID_HOME/ndk/*).
set -euo pipefail
cd "$(dirname "$0")"
MOBILE_VERSION=$(go list -m -f '{{.Version}}' golang.org/x/mobile)
GOBIN="$(go env GOPATH)/bin"
go install "golang.org/x/mobile/cmd/gomobile@${MOBILE_VERSION}" "golang.org/x/mobile/cmd/gobind@${MOBILE_VERSION}"
export PATH="$GOBIN:$PATH"
gomobile init
mkdir -p ../../app/libs
gomobile bind \
  -target=android/arm64,android/arm,android/amd64 \
  -androidapi 26 \
  -javapkg com.webdroid \
  -trimpath -ldflags="-s -w" \
  -o ../../app/libs/vpncore.aar \
  .
ls -la ../../app/libs
