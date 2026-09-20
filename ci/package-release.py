import hashlib
import shutil
import tarfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ("afm-linux-x86_64.deb", "afm-android-unsigned.apk", "afm-web.tar.gz")


def single_file(directory, pattern):
    files = list(directory.glob(pattern))
    if len(files) != 1 or files[0].stat().st_size == 0:
        raise ValueError(f"Expected exactly one nonempty {pattern} in {directory}")
    return files[0]


def package(root):
    app = root / "kmp/app"
    desktop = single_file(app / "desktopApp/build/compose/binaries/main/deb", "*.deb")
    apk = single_file(app / "androidApp/build/outputs/apk/release", "*-unsigned.apk")
    with zipfile.ZipFile(apk) as archive:
        for abi in ("arm64-v8a", "x86_64"):
            library = f"lib/{abi}/libspirit_ffi.so"
            if archive.getinfo(library).file_size == 0:
                raise ValueError(f"Empty Android native library: {library}")
    web = app / "webApp/build/dist"
    for target in ("js", "wasmJs"):
        index = web / target / "productionExecutable/index.html"
        if not index.is_file() or index.stat().st_size == 0:
            raise ValueError(f"Missing web entry point: {index}")
    output = root / "dist"
    output.mkdir(exist_ok=True)
    shutil.copyfile(desktop, output / ASSETS[0])
    shutil.copyfile(apk, output / ASSETS[1])
    with tarfile.open(output / ASSETS[2], "w:gz") as archive:
        for target in ("js", "wasmJs"):
            archive.add(web / target / "productionExecutable", arcname=target)
    checksums = []
    for name in ASSETS:
        with (output / name).open("rb") as asset:
            digest = hashlib.file_digest(asset, "sha256").hexdigest()
        checksums.append(f"{digest}  {name}\n")
    (output / "SHA256SUMS").write_text("".join(checksums))


if __name__ == "__main__":
    package(ROOT)
