"""Export completed debug recordings using adb without changing app/alarm state."""
import argparse
import gzip
import io
from pathlib import Path
import subprocess
import tarfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", type=Path, default=Path("exports"))
    args = parser.parse_args()
    result = subprocess.run(
        [args.adb, "-s", args.serial, "exec-out", "run-as", "com.aestus.sleeplevelalarm",
         "tar", "-cf", "-", "files/recordings"], capture_output=True, check=True,
    )
    # Whitelist flat recording filenames; never extract device-controlled tar paths.
    with tarfile.open(fileobj=io.BytesIO(result.stdout)) as archive:
        for member in archive.getmembers():
            name = Path(member.name).name
            if not member.isfile() or not name.endswith(".csv.gz"):
                continue
            data = archive.extractfile(member).read()
            try:
                gzip.decompress(data)
            except (EOFError, OSError):
                print(f"Skipped unfinished recording: {name}. End tracking before export.")
                continue
            args.out.mkdir(parents=True, exist_ok=True)
            target = args.out / name
            if target.exists():
                print(f"Already exists: {target}")
                continue
            with target.open("xb") as output:
                output.write(data)
            print(target.resolve())


if __name__ == "__main__":
    main()
