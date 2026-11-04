"""Compile the production parser and hook wrappers into a standalone POSIX regression executable.

Linux: python app/src/test/cpp/run_native_tests.py --jni /path/to/ndk/sysroot/usr/include/jni.h
Windows: add --zig /path/to/zig.exe --wsl Ubuntu (cross-compiles a Linux musl executable).
No device, JVM, or Xposed runtime is required. JNI dispatch is covered by Android compilation;
these tests exercise real HPACK, parser state, fd identity, wrappers and retry accounting.
"""
import argparse
import concurrent.futures
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jni", type=Path, required=True)
    parser.add_argument("--zig", type=Path)
    parser.add_argument("--wsl", metavar="DISTRO")
    parser.add_argument("--sanitize", action="store_true", help="Enable compiler undefined-behavior sanitizer")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    source = root / "app/src/main/cpp"
    tests = root / "app/src/test/cpp"
    build = root / "build/native-tests"
    includes = build / "include"
    includes.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.jni, includes / "jni.h")
    if args.zig:
        cc, cxx = [str(args.zig.resolve()), "cc"], [str(args.zig.resolve()), "c++"]
        target = ["-target", "x86_64-linux-musl"]
    else:
        cc, cxx = [os.environ.get("CC", "cc")], [os.environ.get("CXX", "c++")]
        target = []
    common = target + ["-O1", "-g", "-UNDEBUG", "-DNGHTTP2_STATICLIB", "-I" + str(source / "nghttp2")]
    if args.sanitize:
        common += ["-fsanitize=undefined", "-fno-sanitize-recover=all"]
    c_sources = ["nghttp2_hd", "nghttp2_hd_huffman", "nghttp2_hd_huffman_data", "nghttp2_buf",
                 "nghttp2_mem", "nghttp2_helper", "nghttp2_rcbuf", "sfparse"]

    def compile_c(name):
        output = build / (name + ".o")
        command = cc + common + ["-std=c11", "-include", "arpa/inet.h", "-c",
                                str(source / "nghttp2" / (name + ".c")), "-o", str(output)]
        subprocess.run(command, check=True)
        return output

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        objects = list(pool.map(compile_c, c_sources))
    executable = build / "native-tests"
    command = cxx + common + ["-std=c++17", "-pthread", "-I" + str(includes),
        "-I" + str(source / "include"), "-I" + str(tests / "support"),
        str(tests / "native_tests.cpp"), str(source / "http2_parser.cpp")]
    command += [str(obj) for obj in objects] + ["-ldl", "-o", str(executable)]
    subprocess.run(command, check=True)
    if args.wsl:
        # Translate a Windows drive path without interpolating it into shell text.
        path = executable.as_posix()
        wsl_path = "/mnt/" + path[0].lower() + path[2:]
        subprocess.run(["wsl.exe", "-d", args.wsl, "--", wsl_path], check=True)
    else:
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
