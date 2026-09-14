# Optional IB Gateway TOTP image

This opt-in image adds automatic entry for an IBKR **Mobile Authenticator app**
challenge. It extends the existing Gateway image and patches IBC in-process;
it does not use a sidecar, screen coordinates, or simulated keystrokes.
The standard Gateway and TWS images are unchanged.

## Compatibility and scope

- The default base is pinned to Gateway **10.45.1j**, IBC **3.24.1**.
  The Dockerfile verifies the original IBC jar checksum before patching it.
- Only the English Gateway Mobile Authenticator prompt is supported.
- Use one username and one TOTP secret per container, in `live` or `paper` mode.
  TOTP with `TRADING_MODE=both` or a TWS image is rejected at startup.
  Existing manual authentication remains available when TOTP is unset.
- The username must already be enrolled in IBKR Mobile Authenticator.
  This implementation cannot enroll/reset a secret or remove IB Key.
- `TWOFA_DEVICE` selects an enrolled method using IBC's existing chooser.
  It does not make that method available to an ineligible username. Unsupported
  or ambiguous challenges remain manual; there is no fallback to typing into
  an IB Key, SMS, or security-card field.
- The handler stays available for subsequent authentication dialogs in the same
  JVM. A rejected code is submitted only once per dialog, avoiding a retry loop.
  IBKR can still require manual action for other login dialogs.

## Build

From the repository root, without any broker credentials:

```sh
docker build -t ib-gateway-totp:local image-files/totp
python3 -m unittest discover -s image-files/totp/tests -p 'test_*.py'
```

The image build runs the Java tests and Swing dialog integration tests under
Xvfb with networking disabled, using the Gateway image's actual Java runtime.
No test connects to IBKR, uses a real enrollment secret, or places orders.
The tests use the public RFC 6238 test vector.

The default image pin is intentional. Passing a different `UPSTREAM_IMAGE`
whose IBC jar differs makes the build fail. To support another version, first
review the upstream handler changes, update the source/checksum, and repeat the
build and integration tests. Do not replace the checksum simply to skip this
review. Newer Gateway/IBC releases, TWS, and dual-session TOTP are not claimed
as validated configurations.

## Configure

The only new runtime setting is `TOTP_SECRET_FILE`. Point it at a readable,
read-only mounted file containing the **Base32 enrollment secret**, not a current
six-digit code and not an `otpauth://` setup URL. Whitespace, lowercase and valid
Base32 padding are accepted; malformed, short or oversized inputs are rejected.
The file must be readable by the Gateway user (UID 1000 in the default image).
Protect its host permissions and provision it with your preferred secret manager.

Do not supply `TOTP_SECRET` inline; it is deliberately rejected. The secret is
read directly by the JVM and is never copied into an environment variable,
command-line argument, IBC configuration file, or build layer. Generated codes
and secret contents are not logged.

With `TOTP_SECRET_FILE` set, `TWOFA_DEVICE` defaults to the exact English label
`Mobile Authenticator app`; an explicitly conflicting selection is rejected.
With `CUSTOM_CONFIG=YES`, also set IBC `SecondFactorDevice` to that exact label.
Keep IBC `LogStructureWhen=never` and `LogComponents=ignore`. The handler refuses
automatic entry when those verbose UI logging settings are enabled.
Synchronize the host clock. Codes are generated only when at least ten seconds
remain in the current 30-second period.

For a minimal Compose example:

```sh
cd image-files/totp
cp .env.example .env
# Edit .env: set the username and paths to existing, securely provisioned files.
docker compose build
docker compose run --rm --no-deps ib-gateway \
  /opt/ib-gateway-totp/run-gateway.sh --check-totp-config
docker compose up -d
```

The preflight validates TOTP configuration only; it does not log in. The example
uses Docker secrets for both the password and TOTP seed, exposes API ports on
localhost, and defaults to paper trading with read-only API access. Set
`TRADING_MODE=live` and `READ_ONLY_API=no` explicitly when appropriate.

To add this to an existing Compose deployment, use the locally built image,
mount its enrollment file read-only, and set `TOTP_SECRET_FILE` to that container
path. Continue using the upstream settings for API access, restart policy, VNC,
time zone and other features. If settings are persisted, use `TWS_SETTINGS_PATH`
and a separate settings directory; mounting over `/home/ibgateway/Jts` hides the
Gateway binaries included in the image.

## Failure handling and verification

Missing or invalid configured secret files fail startup without logging the
contents or path. If TOTP is unset, the wrapper invokes the normal upstream
startup script and the IBC handler leaves authentication manual. An unknown
prompt, multiple inputs/buttons, existing user-entered text, disabled controls,
or verbose UI logging will not trigger automatic submission.

A `TOTP: submitting` message means that a code was entered, not that IBKR
accepted it. Verify completed login and a successful read-only API connection.
Cold login, daily restart, and weekly re-authentication should each be checked
for the target username. The automated tests verify repeated dialogs and code
freshness, but cannot prove IBKR's weekly session behavior.

Storing both the password and TOTP seed on a server removes the independence of
those factors if that server is compromised. Use separate secrets and access
controls per login; an IP allowlist can provide an additional restriction but
does not protect against misuse from an already compromised allowed server.
This is an optional automation extension, not a claim of IBKR endorsement.

## Source and licensing

The original IBC handler is from
[IBC commit 2fee90c](https://github.com/IbcAlpha/IBC/tree/2fee90c27a3c84615225c423ebb4275e55bf3d1f).
The three Java source files in `src/ibcalpha/ibc` contain the handler modifications
and the TOTP implementation. They are GPL-3.0-or-later; the original copyright
notices and [COPYING.txt](COPYING.txt) are retained. The final image also includes
these modified sources and the license at `/opt/ib-gateway-totp/`.
The surrounding Docker/shell integration uses the repository's MIT license.
IBKR Gateway remains subject to its own license.

Related upstream discussions:
[optional patched IBC image (#387)](https://github.com/gnzsnz/ib-gateway-docker/issues/387)
and [the earlier TOTP PR (#356)](https://github.com/gnzsnz/ib-gateway-docker/pull/356).
This separate build path does not change the default release pipeline or
require maintainers to publish patched images.
