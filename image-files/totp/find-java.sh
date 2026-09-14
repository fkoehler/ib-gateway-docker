#!/bin/sh
set -eu
bundled_java="${TWS_PATH:-/home/ibgateway/Jts}/ibgateway/${IB_GATEWAY_VERSION:-}/jre/bin/java"
if [ -x "$bundled_java" ]; then
	printf '%s\n' "$bundled_java"
	exit 0
fi
java_bin=$(find /usr/local/i4j_jres -type f -path '*/bin/java' -print -quit 2>/dev/null || true)
if [ -z "$java_bin" ]; then
	echo 'Gateway Java runtime not found' >&2
	exit 1
fi
printf '%s\n' "$java_bin"
