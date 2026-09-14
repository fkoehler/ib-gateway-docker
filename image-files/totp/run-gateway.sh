#!/bin/sh
set -eu
if [ -n "${TOTP_SECRET:-}" ]; then
	echo 'Use TOTP_SECRET_FILE; inline TOTP_SECRET is not supported' >&2
	exit 1
fi
if [ -n "${TOTP_SECRET_FILE:-}" ]; then
	if [ "${GATEWAY_OR_TWS:-gateway}" != gateway ]; then
		echo 'This TOTP extension supports IB Gateway only' >&2
		exit 1
	fi
	if [ "${TRADING_MODE:-paper}" = both ]; then
		echo 'TOTP requires one username per container; dual trading mode is unsupported' >&2
		exit 1
	fi
	TWOFA_DEVICE=${TWOFA_DEVICE:-Mobile Authenticator app}
	export TWOFA_DEVICE
	if [ "$TWOFA_DEVICE" != 'Mobile Authenticator app' ]; then
		echo 'TOTP requires TWOFA_DEVICE=Mobile Authenticator app (exact English Gateway label)' >&2
		exit 1
	fi
	java_bin=$(/opt/ib-gateway-totp/find-java.sh)
	"$java_bin" -cp /home/ibgateway/ibc/IBC.jar ibcalpha.ibc.Totp
fi
if [ "${1:-}" = --check-totp-config ]; then
	exit 0
fi
exec /home/ibgateway/scripts/run.sh "$@"
