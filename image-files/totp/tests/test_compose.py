"""Render the reusable example without credentials or an IBKR connection."""

import json
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ComposeTest(unittest.TestCase):
    def render(self, missing=None):
        environment = {
            key: value for key, value in os.environ.items()
            if not key.startswith(("TWS_", "TOTP_", "TRADING_MODE", "READ_ONLY_API"))
        }
        environment.update(
            TWS_USERID="public-test-user",
            TWS_PASSWORD_PATH="/public-test/password",
            TOTP_SECRET_PATH="/public-test/totp",
        )
        if missing:
            environment.pop(missing)
        return subprocess.run([
            "docker", "compose", "--env-file", "/dev/null", "-f", str(ROOT / "compose.yaml"),
            "config", "--format", "json",
        ], env=environment, capture_output=True, text=True, check=False, timeout=30)

    def test_file_secrets_and_local_api_ports(self):
        result = self.render()
        self.assertEqual(result.returncode, 0, result.stderr)
        config = json.loads(result.stdout)
        gateway = config["services"]["ib-gateway"]
        env = gateway["environment"]
        self.assertEqual(env["TOTP_SECRET_FILE"], "/run/secrets/ib_totp")
        self.assertEqual(env["TWS_PASSWORD_FILE"], "/run/secrets/ib_password")
        self.assertNotIn("TOTP_SECRET", env)
        self.assertNotIn("TWS_PASSWORD", env)
        self.assertEqual(env["TRADING_MODE"], "paper")
        self.assertEqual(env["READ_ONLY_API"], "yes")
        self.assertEqual(env["TWOFA_DEVICE"], "Mobile Authenticator app")
        self.assertEqual({p["host_ip"] for p in gateway["ports"]}, {"127.0.0.1"})
        self.assertEqual({s["source"] for s in gateway["secrets"]}, {"ib_password", "ib_totp"})
        self.assertEqual(config["secrets"]["ib_totp"]["file"], "/public-test/totp")
        self.assertEqual(gateway["build"]["context"], str(ROOT))
        self.assertEqual(gateway["pull_policy"], "never")

    def test_required_configuration_is_not_silently_empty(self):
        for key in ("TWS_USERID", "TWS_PASSWORD_PATH", "TOTP_SECRET_PATH"):
            with self.subTest(key=key):
                result = self.render(missing=key)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)


if __name__ == "__main__":
    unittest.main()
