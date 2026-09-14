#!/bin/bash
# Sourced by the standard Gateway/TWS entrypoints before starting any services.
configure_totp() {
	local java_bin
	if [ -n "${TOTP_SECRET:-}" ]; then
		echo 'Use TOTP_SECRET_FILE; inline TOTP_SECRET is not supported' >&2
		return 1
	fi
	if [ -z "${TOTP_SECRET_FILE:-}" ]; then
		return 0
	fi
	if [ "${GATEWAY_OR_TWS:-gateway}" != gateway ]; then
		echo 'Automatic TOTP supports IB Gateway only' >&2
		return 1
	fi
	if [ "${TRADING_MODE:-paper}" = both ] || [ "${DUAL_MODE:-}" = yes ]; then
		echo 'TOTP requires one username per container; dual trading mode is unsupported' >&2
		return 1
	fi
	TWOFA_DEVICE=${TWOFA_DEVICE:-Mobile Authenticator app}
	export TWOFA_DEVICE
	if [ "$TWOFA_DEVICE" != 'Mobile Authenticator app' ]; then
		echo 'TOTP requires TWOFA_DEVICE=Mobile Authenticator app (exact English Gateway label)' >&2
		return 1
	fi
	java_bin=$("${IBC_PATH}/totp/find-java.sh") || return 1
	"$java_bin" -cp "${IBC_PATH}/IBC.jar" ibcalpha.ibc.Totp
}
