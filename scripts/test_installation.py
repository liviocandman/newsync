"""Contratos de recuperação que não precisam de Docker; T-19 cobre o percurso real."""
import json
import unittest
from pathlib import Path
from unittest.mock import patch

import backup


class ContainerIdentityTest(unittest.TestCase):
    def setUp(self):
        self.db = object.__new__(backup.Database)
        self.db.database, self.db.user = "news_personal", "news_personal"
        self.project = {"com.docker.compose.project": "isolated"}
        self.db.info = {"Config": {"Labels": {**self.project, "com.docker.compose.service": "db"},
                                   "Env": ["POSTGRES_USER=news_personal"]},
                        "NetworkSettings": {"Networks": {"own": {"Aliases": ["db"]}}}}
        self.app_id = "a" * 64
        self.app = {"Id": self.app_id, "State": {"Status": "exited", "Running": False},
                    "Image": "sha256:" + "b" * 64,
                    "Config": {"Labels": {**self.project, "com.docker.compose.service": "app"},
                               "Env": ["JDBC_URL=jdbc:postgresql://db:5432/news_personal",
                                       "JDBC_USERNAME=news_personal"]},
                    "NetworkSettings": {"Networks": {"own": {}}}}

    def inspect_rejected(self, pattern):
        with patch.object(backup, "run", return_value=json.dumps([self.app]).encode()):
            with self.assertRaisesRegex(backup.RecoveryError, pattern):
                self.db.stopped_container(self.app_id, Path("unused.jar"))

    def test_names_running_paused_and_restarting_are_not_stop_proof(self):
        with self.assertRaisesRegex(backup.RecoveryError, "imutável"):
            self.db.stopped_container("app", Path("unused"))
        for key in ("Running", "Paused", "Restarting"):
            self.app["State"][key] = True
            self.inspect_rejected("encerrado")
            self.app["State"][key] = False

    def test_foreign_stopped_app_and_wrong_database_are_rejected(self):
        self.app["Config"]["Labels"]["com.docker.compose.project"] = "foreign"
        self.inspect_rejected("mesmo projeto")
        self.app["Config"]["Labels"]["com.docker.compose.project"] = "isolated"
        self.app["Config"]["Env"][0] = "JDBC_URL=jdbc:postgresql://db:5432/foreign"
        self.inspect_rejected("diverge")


if __name__ == "__main__":
    unittest.main()
