# Opt-in derivative; the standard Gateway and TWS images are unchanged.
# hadolint global ignore=DL3008
ARG UPSTREAM_IMAGE=ghcr.io/gnzsnz/ib-gateway:stable@sha256:91165c0752ca534c0dad3c40683ae7c2745974d4d277651a90e90411ca609d8d
FROM ${UPSTREAM_IMAGE} AS upstream

FROM upstream AS build
# Root is limited to this build stage; runtime stages inherit the upstream user.
# hadolint ignore=DL3002
USER 0
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN apt-get update && apt-get install --no-install-recommends -y openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
# Review the IBC patch before changing this checksum or accepting another version.
RUN echo '65bef00fb783ab32e11d7e272eeae1a6940dc9057d2cd4e0ca082d3521a6795a  /home/ibgateway/ibc/IBC.jar' | sha256sum -c -
COPY src/ src/
COPY tests/ibcalpha/ tests/ibcalpha/
RUN mkdir classes test-classes \
    && javac --release 8 -cp /home/ibgateway/ibc/IBC.jar -d classes src/ibcalpha/ibc/*.java \
    && cp /home/ibgateway/ibc/IBC.jar IBC.jar \
    && jar uf IBC.jar -C classes . \
    && javac --release 8 -cp IBC.jar -d test-classes tests/ibcalpha/ibc/*.java

FROM upstream AS verified
COPY --chown=ibgateway:ibgateway --from=build /build/IBC.jar /home/ibgateway/ibc/IBC.jar
COPY --from=build /build/test-classes /tmp/totp-test-classes
COPY --chmod=755 find-java.sh run-ui-tests.sh /opt/ib-gateway-totp/
# Test the patched handler with the actual Gateway JRE; no credentials or IB access.
RUN --network=none /opt/ib-gateway-totp/run-ui-tests.sh

FROM upstream AS final
COPY --chown=ibgateway:ibgateway --from=verified /home/ibgateway/ibc/IBC.jar /home/ibgateway/ibc/IBC.jar
COPY --chmod=755 find-java.sh run-gateway.sh /opt/ib-gateway-totp/
COPY src /opt/ib-gateway-totp/src
COPY COPYING.txt README.md /opt/ib-gateway-totp/
LABEL org.opencontainers.image.title="IB Gateway with optional file-backed TOTP" \
      org.opencontainers.image.licenses="MIT AND GPL-3.0-or-later"
CMD ["/opt/ib-gateway-totp/run-gateway.sh"]
