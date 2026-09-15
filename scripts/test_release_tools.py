import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import configure_release


class ReleaseConfigurationTests(unittest.TestCase):
    def test_environment_only_setup_does_not_require_local_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            (root / "app/google-services.json").write_text('{"project_info": {"project_id": "example-project"}}')
            keystore = root / "release.keystore"
            keystore.write_bytes(b"synthetic test keystore")
            environment = {key: "synthetic-test-value" for key in (
                "RELEASE_KEYSTORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")}
            with patch.object(configure_release, "ROOT", root), \
                    patch.object(configure_release, "KEYSTORE", keystore), \
                    patch.object(configure_release, "environment", return_value=environment), \
                    patch.object(configure_release, "run") as run, \
                    patch.dict(os.environ, {"BACKEND_URL": "https://api.example.com"}):
                configure_release.upload("example/repository")
            self.assertFalse((root / "local.properties").exists())
            self.assertEqual(run.call_count, 6)
            uploaded = {call.args[0][3]: call.kwargs["input"] for call in run.call_args_list}
            self.assertEqual(uploaded["BACKEND_URL"], "https://api.example.com")


if __name__ == "__main__":
    unittest.main()
