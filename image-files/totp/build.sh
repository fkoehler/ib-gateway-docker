#!/bin/sh
# Build-time only: compile against the exact IBC release bundled by the image.
set -eu
ibc_path=${1:?Pass the IBC installation directory}
case "${IBC_VERSION:-}" in
3.24.1) checksum=65bef00fb783ab32e11d7e272eeae1a6940dc9057d2cd4e0ca082d3521a6795a ;;
3.24.2) checksum=ba4a359e7c55ad000074fb71a10323eb0886764fef45b1fe4d164a47b2a78365 ;;
*)
	echo 'Review the TOTP handler patch before supporting this IBC version' >&2
	exit 1
	;;
esac
printf '%s  %s/IBC.jar\n' "$checksum" "$ibc_path" | sha256sum -c -
mkdir -p /tmp/totp-classes /tmp/totp-test-classes
javac --release 8 -cp "$ibc_path/IBC.jar" -d /tmp/totp-classes "$ibc_path"/totp/src/ibcalpha/ibc/*.java
jar uf "$ibc_path/IBC.jar" -C /tmp/totp-classes .
javac --release 8 -cp "$ibc_path/IBC.jar" -d /tmp/totp-test-classes /tmp/totp-tests/ibcalpha/ibc/*.java
