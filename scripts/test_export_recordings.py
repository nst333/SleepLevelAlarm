import contextlib
import gzip
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import export_recordings


class ExportTests(unittest.TestCase):
    def test_export_uses_flat_names_checks_gzip_and_never_overwrites(self):
        archive_bytes = io.BytesIO()
        valid = gzip.compress(b"recording\n")
        with tarfile.open(fileobj=archive_bytes, mode="w") as archive:
            for name, data in [("files/recordings/one.csv.gz", valid),
                               ("../../two.csv.gz", valid), ("partial.csv.gz", valid[:5]),
                               ("not-a-recording.txt", b"ignored")]:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "exports"
            out.mkdir()
            (out / "one.csv.gz").write_bytes(b"existing")
            result = subprocess.CompletedProcess([], 0, archive_bytes.getvalue(), b"")
            with patch.object(sys, "argv", ["export_recordings", "--serial", "test", "--out", str(out)]), \
                 patch.object(subprocess, "run", return_value=result) as command, \
                 contextlib.redirect_stdout(io.StringIO()):
                export_recordings.main()
            self.assertEqual((out / "one.csv.gz").read_bytes(), b"existing")
            self.assertEqual(gzip.decompress((out / "two.csv.gz").read_bytes()), b"recording\n")
            self.assertFalse((out / "partial.csv.gz").exists())
            self.assertFalse((Path(directory) / "two.csv.gz").exists())
            self.assertNotIn("shell", command.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
