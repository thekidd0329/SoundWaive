"""Check the actual native payload in a universal APK or release App Bundle."""
import struct
import sys
from pathlib import Path
from zipfile import ZipFile

# Expected ELF class (32/64-bit) and e_machine for each Android ABI.
ARCHITECTURES = {"armeabi-v7a": (1, 40), "arm64-v8a": (2, 183), "x86": (1, 3), "x86_64": (2, 62)}


def verify(path):
    prefix = "base/" if path.suffix == ".aab" else ""
    with ZipFile(path) as package:
        for abi, (word_size, machine) in ARCHITECTURES.items():
            libraries = ["crownpulse_engine"]
            if abi == "x86":
                libraries.extend(["soundwaive_x86_sharpen", "tensorflowlite_jni"])
            else:
                libraries.extend(["LiteRt", "litert_jni", "LiteRtClGlAccelerator"])
            for library in libraries:
                name = f"{prefix}lib/{abi}/lib{library}.so"
                with package.open(name) as binary:
                    header = binary.read(20)
                if (header[:6] != b"\x7fELF" + bytes([word_size, 1])
                        or struct.unpack_from("<H", header, 18)[0] != machine):
                    raise ValueError(f"Wrong ELF architecture in {path}: {name}")
        package.getinfo(f"{prefix}assets/models/real_esrgan_general_x4v3.tflite")
    print(f"{path.name}: verified engine and sharpening libraries for all four ABIs")


if __name__ == "__main__":
    for filename in sys.argv[1:]:
        verify(Path(filename))
