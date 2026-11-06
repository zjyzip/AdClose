"""Compile production Native tests using Gradle-cached nghttp2 and ShadowHook headers.

The Android build uses Prefab ShadowHook and a fixed nghttp2 release archive resolved by Gradle.
The host runner extracts that same archive into ignored build/ and uses the existing Zig/WSL path;
no vendored third-party source is required.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tomllib
import zipfile

NGHTTP2_SHA256 = "6abd7ab0a7f1580d5914457cb3c85eb80455657ee5119206edbd7f848c14f0b2"


def cached_archive(root, override):
    if override:
        return override.resolve()
    version = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["nghttp2"]
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    candidates = sorted((gradle_home / "caches/modules-2/files-2.1/org.nghttp2/nghttp2" / version).glob("*/*.tar.xz"))
    if not candidates:
        raise RuntimeError(f"nghttp2 {version} archive is not cached; build Android first or pass --nghttp2-archive")
    return candidates[0]


def extract_archive(archive, destination):
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != NGHTTP2_SHA256:
        raise RuntimeError(f"Unexpected nghttp2 archive SHA-256: {digest}")
    marker = destination / ".sha256"
    if marker.read_text().strip() == digest if marker.exists() else False:
        return next(destination.glob("nghttp2-*/lib"))
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    with tarfile.open(archive) as package:
        package.extractall(destination, filter="data")
    marker.write_text(digest, encoding="ascii")
    return next(destination.glob("nghttp2-*/lib"))


def shadowhook_header(root, build, aar_override):
    version = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["shadowhook"]
    if aar_override:
        aar = aar_override
    else:
        gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        candidates = sorted((gradle_home / "caches/modules-2/files-2.1/com.bytedance.android/shadowhook" / version).glob("*/*.aar"))
        if not candidates:
            raise RuntimeError(f"ShadowHook {version} AAR is not cached; build Android first or pass --shadowhook-aar")
        aar = candidates[0]
    include = build / "shadowhook-include"
    include.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as package:
        (include / "shadowhook.h").write_bytes(package.read("prefab/modules/shadowhook/include/shadowhook.h"))
    return include


def run(command, wsl, root):
    if wsl:
        translated = []
        for value in command:
            if str(value).startswith(str(root.drive)):
                path = Path(value).as_posix()
                translated.append("/mnt/" + path[0].lower() + path[2:])
            else:
                translated.append(str(value))
        subprocess.run(["wsl.exe", "-d", wsl, "--", *translated], check=True)
    else:
        subprocess.run(command, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--shadowhook-aar", type=Path)
    parser.add_argument("--nghttp2-archive", type=Path)
    parser.add_argument("--zig", type=Path)
    parser.add_argument("--wsl", metavar="DISTRO")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--replay", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.replay and not args.output:
        parser.error("--replay requires --output")
    root = Path(__file__).resolve().parents[4]
    build = root / "build/native-tests" / ("ubsan" if args.sanitize else "plain") / ("replay" if args.replay else "suite")
    build.mkdir(parents=True, exist_ok=True)
    source = root / "app/src/main/cpp"
    tests = root / "app/src/test/cpp"
    lib = extract_archive(cached_archive(root, args.nghttp2_archive), build / "nghttp2-source")
    include = shadowhook_header(root, build, args.shadowhook_aar)
    jni_include = build / "jni-include"
    jni_include.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.jni, jni_include / "jni.h")
    if args.zig:
        cc = [str(args.zig.resolve()), "cc"]
        cxx = [str(args.zig.resolve()), "c++"]
        target = ["-target", "x86_64-linux-musl"]
    else:
        cc = [os.environ.get("CC", "cc")]
        cxx = [os.environ.get("CXX", "c++")]
        target = []
    common = target + ["-O1", "-g", "-UNDEBUG", "-DNGHTTP2_STATICLIB",
                       "-I" + str(lib / "includes"), "-I" + str(lib)]
    if args.sanitize:
        common += ["-fsanitize=undefined", "-fno-sanitize-recover=all"]
    c_sources = ["nghttp2_hd", "nghttp2_hd_huffman", "nghttp2_hd_huffman_data", "nghttp2_buf",
                 "nghttp2_mem", "nghttp2_helper", "nghttp2_rcbuf", "sfparse"]
    objects = []
    for name in c_sources:
        output = build / (name + ".o")
        run(cc + common + ["-std=c11", "-include", "arpa/inet.h", "-c", str(lib / (name + ".c")), "-o", str(output)], None, root)
        objects.append(output)
    target_name = "grpc-replay" if args.replay else "native-tests"
    source_name = "grpc_replay.cpp" if args.replay else "native_tests.cpp"
    executable = build / target_name
    command = cxx + common + ["-std=c++17", "-pthread", "-I" + str(jni_include), "-I" + str(include),
        "-I" + str(source / "include"), "-I" + str(tests / "support"),
        str(tests / source_name), str(source / "http2_parser.cpp")]
    command += [str(obj) for obj in objects] + ["-ldl", "-o", str(executable)]
    run(command, None, root)
    run_args = [str(args.replay.resolve()), str(args.output.resolve())] if args.replay else []
    run([str(executable), *run_args], args.wsl, root)


if __name__ == "__main__":
    main()
