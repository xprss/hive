import importlib.machinery
import importlib.util
import io
import pathlib
import struct
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).with_name("privcam")
loader = importlib.machinery.SourceFileLoader("privcam", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
privcam = importlib.util.module_from_spec(spec)
loader.exec_module(privcam)


class PrivcamTests(unittest.TestCase):
    @patch.object(privcam.subprocess, "run")
    def test_package_uid_exact_match(self, run):
        run.return_value.stdout = "package:io.xprss.hive uid:10123\n"
        self.assertEqual(privcam.app_uid(), 10123)
        run.return_value.stdout = "package:io.xprss.hive.fake uid:10123\n"
        with self.assertRaises(RuntimeError):
            privcam.app_uid()

    @patch.object(privcam.socket, "socket")
    @patch.object(privcam.subprocess, "run")
    @patch.object(privcam, "app_uid", return_value=10123)
    def test_rejects_socket_with_wrong_peer_uid(self, _uid, _run, make_socket):
        sock = make_socket.return_value
        sock.getsockopt.return_value = struct.pack("3i", 42, 99999, 0)
        with self.assertRaisesRegex(RuntimeError, "non appartiene"):
            privcam.connect()
        sock.close.assert_called()

    def test_status_has_only_aggregate_fields(self):
        output = io.StringIO()
        with redirect_stdout(output):
            privcam.print_status('{"state":"active","started":1700000000000,"photos":2,"videos":1,"bytes":1234}')
        value = output.getvalue()
        self.assertIn("Foto: 2", value)
        self.assertIn("Video: 1", value)
        self.assertIn("Spazio: 1234 byte", value)


if __name__ == "__main__":
    unittest.main()
