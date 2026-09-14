#!/bin/sh
set -eu
java_bin=$("${IBC_PATH}/totp/find-java.sh")
export DISPLAY=:98
export TOTP_SECRET_FILE=/tmp/totp-public-test-vector
Xvfb "$DISPLAY" -ac -screen 0 1024x768x16 >/tmp/totp-xvfb.log 2>&1 &
xvfb_pid=$!
trap 'kill "$xvfb_pid" 2>/dev/null || true' EXIT
count=0
while [ ! -S /tmp/.X11-unix/X98 ]; do
	count=$((count + 1))
	if [ "$count" -ge 50 ]; then
		echo 'Test Xvfb did not start' >&2
		exit 1
	fi
	sleep 0.1
done
"$java_bin" -cp "${IBC_PATH}/IBC.jar:/tmp/totp-test-classes" ibcalpha.ibc.TotpTest
"$java_bin" -cp "${IBC_PATH}/IBC.jar:/tmp/totp-test-classes" ibcalpha.ibc.TotpDialogTest
unset TOTP_SECRET_FILE
"$java_bin" -cp "${IBC_PATH}/IBC.jar:/tmp/totp-test-classes" ibcalpha.ibc.TotpDialogTest
