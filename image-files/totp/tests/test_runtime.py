"""Credential-free checks of the built image's startup boundary; no IB connection."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


IMAGE = os.environ.get("TOTP_TEST_IMAGE", "ib-gateway:totp-test")
PUBLIC_KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"  # RFC 6238, not a credential.


class RuntimeTest(unittest.TestCase):
    def run_preflight(self, extra=()):
        result = subprocess.run([
            "docker", "run", "--rm", "--network", "none", *extra, IMAGE,
            "/home/ibgateway/scripts/run.sh", "--check-totp-config",
        ], capture_output=True, text=True, timeout=30, check=False)
        self.assertNotIn(PUBLIC_KEY, result.stdout + result.stderr)
        return result

    def test_standard_entrypoint_and_no_build_tools(self):
        result = subprocess.run([
            "docker", "image", "inspect", IMAGE, "--format", "{{json .Config.Cmd}}",
        ], capture_output=True, text=True, timeout=30, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '["/home/ibgateway/scripts/run.sh"]')
        result = subprocess.run([
            "docker", "run", "--rm", "--network", "none", IMAGE, "bash", "-ec",
            'test -f "$IBC_PATH/totp/src/ibcalpha/ibc/Totp.java"; '
            'test -f "$IBC_PATH/totp/COPYING.txt"; '
            'test ! -e /tmp/totp-test-classes; ! command -v javac',
        ], capture_output=True, text=True, timeout=30, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_runtime_keeps_the_upstream_non_root_user(self):
        result = subprocess.run([
            "docker", "run", "--rm", "--network", "none", IMAGE, "id", "-u",
        ], capture_output=True, text=True, timeout=30, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotEqual(result.stdout.strip(), "0")

    def test_manual_mode_keeps_existing_method_selection(self):
        result = subprocess.run([
            "docker", "run", "--rm", "--network", "none", "-e", "TWOFA_DEVICE=IB Key",
            IMAGE, "bash", "-ec",
            'source "$SCRIPT_PATH/totp.sh"; configure_totp; test "$TWOFA_DEVICE" = "IB Key"',
        ], capture_output=True, text=True, timeout=30, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_tws_entrypoint_keeps_manual_auth_and_rejects_totp(self):
        script = Path(__file__).resolve().parents[2] / "tws-scripts" / "run_tws.sh"
        for configured in (False, True):
            with self.subTest(configured=configured):
                extra = ["-e", "TOTP_SECRET_FILE=/not-read-for-tws"] if configured else []
                result = subprocess.run([
                    "docker", "run", "--rm", "--network", "none", *extra,
                    "-e", "GATEWAY_OR_TWS=tws", "--mount",
                    f"type=bind,src={script},dst=/tmp/run-tws.sh,readonly",
                    IMAGE, "bash", "/tmp/run-tws.sh", "--check-totp-config",
                ], capture_output=True, text=True, timeout=30, check=False)
                self.assertEqual(result.returncode, 1 if configured else 0, result.stderr)
                if configured:
                    self.assertIn("IB Gateway only", result.stderr)

    def test_manual_mode(self):
        for mode in ("paper", "live", "both"):
            with self.subTest(mode=mode):
                self.assertEqual(self.run_preflight(["-e", "TRADING_MODE=" + mode]).returncode, 0)

    def test_valid_file_and_invalid_configurations(self):
        with tempfile.TemporaryDirectory(prefix="totp-public-fixture-") as directory:
            path = Path(directory) / "public-key"
            path.write_text(PUBLIC_KEY)
            path.chmod(0o444)  # Public vector, readable by the container test user.
            mount = ["--mount", f"type=bind,src={path},dst=/run/secrets/ib-totp,readonly",
                     "-e", "TOTP_SECRET_FILE=/run/secrets/ib-totp"]
            result = self.run_preflight(mount)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("validated", result.stdout)
            result = subprocess.run([
                "docker", "run", "--rm", "--network", "none", *mount,
                "-e", "TWS_USERID=public-test-user", "-e", "TWS_PASSWORD=public-test-password",
                "-e", "TRADING_MODE=paper", IMAGE, "bash", "-ec",
                'source "$SCRIPT_PATH/totp.sh"; configure_totp; '
                'source "$SCRIPT_PATH/common.sh"; set_ports; apply_settings; '
                'grep -q "^SecondFactorDevice=Mobile Authenticator app[[:space:]]*$" "$IBC_INI"',
            ], capture_output=True, text=True, timeout=30, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn(PUBLIC_KEY, result.stdout + result.stderr)
            for extra in [
                ["-e", "TRADING_MODE=both"],
                ["-e", "DUAL_MODE=yes"],
                ["-e", "GATEWAY_OR_TWS=tws"],
                ["-e", "TWOFA_DEVICE=IB Key"],
                ["-e", "TOTP_SECRET=public-test-value"],
            ]:
                with self.subTest(extra=extra):
                    self.assertNotEqual(self.run_preflight(mount + extra).returncode, 0)
            path.chmod(0o644)
            path.write_text("invalid-secret")
            path.chmod(0o444)
            self.assertNotEqual(self.run_preflight(mount).returncode, 0)

    def test_persistent_settings_do_not_hide_image_binaries(self):
        with tempfile.TemporaryDirectory(prefix="totp-public-settings-") as directory:
            path = Path(directory)
            path.chmod(0o777)  # Public fixture; the image runs as UID 1000.
            (path / "jts.ini").write_text("[IBGateway]\nLocalServerPort=4001\n")
            (path / "jts.ini").chmod(0o644)
            result = subprocess.run([
                "docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/bash",
                "--mount", f"type=bind,src={path},dst=/home/ibgateway/settings",
                "-e", "TWS_SETTINGS_PATH=/home/ibgateway/settings",
                "-e", "JAVA_HEAP_SIZE=4096", "-e", "TRADING_MODE=live",
                "-e", "TWS_USERID=public-test-user", "-e", "TWS_PASSWORD=public-test-password",
                IMAGE, "-ec", 'source "$SCRIPT_PATH/common.sh"; set_java_heap; set_ports; apply_settings; '
                'test -f "$TWS_PATH/ibgateway/$IB_GATEWAY_VERSION/ibgateway.vmoptions"; '
                'grep -q "^-Xmx4096m" "$TWS_PATH/ibgateway/$IB_GATEWAY_VERSION/ibgateway.vmoptions"; '
                'grep -q "^LocalServerPort=4001$" "$TWS_SETTINGS_PATH/jts.ini"',
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((path / "jts.ini").read_text(), "[IBGateway]\nLocalServerPort=4001\n")

    def test_missing_file(self):
        result = self.run_preflight(["-e", "TOTP_SECRET_FILE=/nonexistent-test-file"])
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("/nonexistent-test-file", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
