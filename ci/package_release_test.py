import hashlib
import runpy
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path


PACKAGE = runpy.run_path(str(Path(__file__).with_name("package-release.py")))


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / "kmp/app"
        self.deb = self.app / "desktopApp/build/compose/binaries/main/deb/afm.deb"
        self.deb.parent.mkdir(parents=True)
        self.deb.write_bytes(b"desktop")
        self.apk = self.app / "androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
        self.apk.parent.mkdir(parents=True)
        self.write_apk(("arm64-v8a", "x86_64"))
        for target in ("js", "wasmJs"):
            index = self.app / f"webApp/build/dist/{target}/productionExecutable/index.html"
            index.parent.mkdir(parents=True)
            index.write_text("<html>AFM</html>")

    def write_apk(self, abis):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for abi in abis:
                archive.writestr(f"lib/{abi}/libspirit_ffi.so", b"native")

    def test_packages_both_web_targets_and_checksums(self):
        PACKAGE["package"](self.root)
        output = self.root / "dist"
        with tarfile.open(output / "afm-web.tar.gz") as archive:
            self.assertIn("js/index.html", archive.getnames())
            self.assertIn("wasmJs/index.html", archive.getnames())
        checksums = (output / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(len(checksums), 3)
        for line in checksums:
            digest, name = line.split("  ")
            self.assertEqual(digest, hashlib.sha256((output / name).read_bytes()).hexdigest())

    def test_missing_desktop_fails_without_output(self):
        self.deb.unlink()
        with self.assertRaises(ValueError):
            PACKAGE["package"](self.root)
        self.assertFalse((self.root / "dist").exists())

    def test_ambiguous_desktop_fails(self):
        self.deb.with_name("stale.deb").write_bytes(b"stale")
        with self.assertRaises(ValueError):
            PACKAGE["package"](self.root)

    def test_missing_android_abi_fails_without_output(self):
        self.write_apk(("arm64-v8a",))
        with self.assertRaises(KeyError):
            PACKAGE["package"](self.root)
        self.assertFalse((self.root / "dist").exists())

    def test_missing_web_target_fails_without_output(self):
        (self.app / "webApp/build/dist/wasmJs/productionExecutable/index.html").unlink()
        with self.assertRaises(ValueError):
            PACKAGE["package"](self.root)
        self.assertFalse((self.root / "dist").exists())


if __name__ == "__main__":
    unittest.main()
