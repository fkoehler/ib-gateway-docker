# Automatic TOTP authentication

The standard IB Gateway image includes optional automatic entry for an IBKR
**Mobile Authenticator app** challenge. Enable it by setting `TOTP_SECRET_FILE`
to a mounted enrollment-secret file. No separate image, sidecar, or custom
entrypoint is required. Without that setting, authentication stays manual.

## Configure

The username must already be enrolled in IBKR Mobile Authenticator. This feature
cannot enroll/reset a secret, remove IB Key, or make a method available to an
ineligible username. `TWOFA_DEVICE` still uses IBC's enrolled-method chooser.

Mount a file containing the **Base32 enrollment secret**, not a current six-digit
code or an `otpauth://` setup URL. Set `TOTP_SECRET_FILE` to its container path.
For example, add this to your existing Gateway service:

```yaml
services:
  ib-gateway:
    environment:
      TOTP_SECRET_FILE: /run/secrets/ib_totp
    secrets:
      - ib_totp
secrets:
  ib_totp:
    file: ./secrets/ib_totp
```

The secret must be readable by the Gateway user (UID 1000 by default). Protect its
host permissions and provision it with your preferred secret manager. Docker
secrets or a read-only bind mount are supported. Whitespace, lowercase and valid
Base32 padding are accepted; malformed, short or oversized inputs are rejected.
Do not set `TOTP_SECRET` inline; it is deliberately rejected. The JVM reads the
file directly without copying the secret into an environment variable, command
line, IBC configuration file, or build layer. Seeds and codes are not logged.

With TOTP enabled, `TWOFA_DEVICE` defaults to the exact English label
`Mobile Authenticator app`; an explicitly conflicting selection fails startup.
With `CUSTOM_CONFIG=YES`, also set IBC `SecondFactorDevice` to that label.
Keep `LogStructureWhen=never` and `LogComponents=ignore`; automatic code entry
is disabled when verbose IBC UI logging is enabled. Synchronize the host clock.
A fresh code is submitted only when at least ten seconds remain in its period.

Use the normal entrypoint for a configuration-only check, without logging in:

```sh
docker compose run --rm --no-deps ib-gateway \
  /home/ibgateway/scripts/run.sh --check-totp-config
```

A complete [Compose example](compose.yaml) uses file-backed password and TOTP
secrets, localhost API ports, paper trading, and read-only API access by default.
It builds the standard `stable/` Dockerfile. Set `TRADING_MODE=live` and
`READ_ONLY_API=no` explicitly when appropriate. Keep persisted settings in a
separate directory using `TWS_SETTINGS_PATH`; mounting over
`/home/ibgateway/Jts` hides the Gateway binaries.

## Compatibility and failure handling

- Automatic TOTP supports the English Gateway prompt and one enrolled username
  per container in `live` or `paper` mode. TOTP with `TRADING_MODE=both`,
  `DUAL_MODE=yes`, or a TWS image is rejected before starting services.
  Existing TWS and dual-mode authentication remain available with TOTP unset.
- IBC 3.24.1 and 3.24.2 are supported. Their original second-factor handler is
  identical. The build verifies the original jar checksum before patching it;
  a different IBC version/checksum requires a source review and test update.
- Missing or invalid configured secret files fail startup without logging their
  contents or paths. Unset TOTP does not change the selected authentication method.
- Unsupported or ambiguous prompts, existing manual input, or verbose UI logging
  remain manual. The implementation does not type into IB Key, SMS, or
  security-card fields and does not simulate blind keystrokes.
- The in-process Swing handler watches in-place prompt changes and later login
  dialogs in the same JVM. It submits at most once per recognized dialog, so a
  rejected code does not trigger repeated submissions into that dialog.

A `TOTP: submitting` log entry means a code was entered, not that IBKR accepted it.
Verify completed login and a successful read-only API connection. Cold login,
daily restart, and weekly re-authentication should each be checked for the target
username; local dialog tests cannot establish IBKR's weekly session behavior.

Storing the password and TOTP seed on the same server reduces factor independence
if that server is compromised. Use separate secret access per login. An IP
allowlist adds a restriction but cannot prevent misuse from a compromised allowed
server. This feature does not imply IBKR endorsement.

## Development and tests

Use the repository's normal generation and image build workflow, without broker
credentials. For example, using the checked-in stable Gateway version:

```sh
./update.sh stable 10.45.1j
docker build -t ib-gateway:totp-test stable
python3 -m unittest discover -s image-files/totp/tests -p 'test_*.py'
```

`Dockerfile.template` compiles the Java patch in a build stage. The final image
has no added JDK, test fixtures, or alternative startup command. The build runs
RFC 6238/Base32/expiry tests and Swing integration tests under Xvfb with the
Gateway's actual Java runtime and networking disabled. Tests use public vectors,
exercise manual fallback and repeated dialogs, and never connect to IBKR or trade.
Set `TOTP_TEST_IMAGE` to test another locally built image.

PR CI regenerates `stable/` and `latest/` from source before building both CPU
architectures. Do not commit the generated directories. For an IBC update,
compare its second-factor handler to the source below, update the reviewed jar
checksum in `build.sh`, and repeat the build and integration tests.

## Source and licensing

The modified handler retains IBC's original copyright notices. Its original
source is identical at
[IBC 3.24.1](https://github.com/IbcAlpha/IBC/tree/2fee90c27a3c84615225c423ebb4275e55bf3d1f)
and [IBC 3.24.2](https://github.com/IbcAlpha/IBC/tree/2be2ecd05d7707f97479fda9ad098fdcc15ab807).
The three Java files in `src/ibcalpha/ibc` are GPL-3.0-or-later; their
[COPYING.txt](COPYING.txt) and modified sources ship at `${IBC_PATH}/totp/`.
The surrounding integration uses the repository's MIT license. IBKR Gateway
remains subject to its own license.
