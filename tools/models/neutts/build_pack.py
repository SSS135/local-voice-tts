# Builds the neutts-nano-model release assets from build/ inputs (download-inputs.ps1) into build/pack/ and
# rewrites runtime-manifest.json. The int8-conv decoder is built here from the upstream int8 decoder by quant_conv.py (mode mm).
# Run: uv run --no-project --python 3.12 --with numpy --with onnx==1.19.1 python -I tools/models/neutts/build_pack.py
import hashlib, io, json, pickle, shutil, subprocess, sys, zipfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
BUILD, PACK = HERE / "build", HERE / "build" / "pack"
NEUTTS = "neutts-ac69851f28fc63a487917e7c2e27f0d75c759cba"
WHEEL = "neutts-1.4.1-cp312-cp312-win_amd64.whl"
VOICES = ["dave", "jo", "emily", "paul", "sophie", "steven"]  # README "English" references
ESPEAK = ["phontab", "phonindex", "phondata", "intonations", "en_dict", "lang/gmw/en-US"]  # en-us voice closure
TAG, V1_TAG = "neutts-nano-model-v2", "neutts-nano-model-v1"
NEW_IN_V2 = {"neucodec-decoder-int8-conv.onnx", "ATTRIBUTION.txt"}  # other assets are byte-identical and stay on the v1 release


def tensor(data: bytes):
    """torch.save()d int tensor -> numpy, without torch (only rebuild_tensor_v2, typed storages and OrderedDict are allowed)."""
    z = zipfile.ZipFile(io.BytesIO(data))
    root = z.namelist()[0].split("/")[0]

    class Unpickler(pickle.Unpickler):
        def find_class(self, module, name):
            if (module, name) == ("torch._utils", "_rebuild_tensor_v2"):
                return lambda storage, offset, size, stride, *rest: storage[offset:offset + int(np.prod(size))].reshape(size)
            if module == "torch" and name in ("LongStorage", "IntStorage"):
                return name
            if (module, name) == ("collections", "OrderedDict"):  # empty backward-hooks argument
                return dict
            raise pickle.UnpicklingError(f"refusing {module}.{name}")

        def persistent_load(self, pid):
            dtype = {"LongStorage": "<i8", "IntStorage": "<i4"}[pid[1]]
            return np.frombuffer(z.read(f"{root}/data/{pid[2]}"), dtype=dtype)

    return Unpickler(z.open(f"{root}/data.pkl")).load()


def put(asset: str, path: str, data: bytes, producer: str, files: list):
    (PACK / asset).write_bytes(data)
    files.append({"path": path, "asset": asset, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "producer": producer})


def main():
    if PACK.exists():
        shutil.rmtree(PACK)
    PACK.mkdir(parents=True)
    files = []
    put("neutts-nano-Q4_0.gguf", "neutts-nano-Q4_0.gguf", (BUILD / "neutts-nano-Q4_0.gguf").read_bytes(), "upstream, unchanged", files)
    decoder = PACK / "neucodec-decoder-int8-conv.onnx"
    subprocess.run([sys.executable, "-I", str(HERE / "quant_conv.py"), str(BUILD / "neucodec-decoder-int8.onnx"), str(decoder), "mm"], check=True)
    put(decoder.name, decoder.name, decoder.read_bytes(), "neucodec-decoder-int8.onnx @ d4b449d3, quant_conv.py mm", files)
    repo = zipfile.ZipFile(BUILD / f"{NEUTTS}.zip")
    for voice in VOICES:
        codes = tensor(repo.read(f"{NEUTTS}/samples/{voice}.pt"))
        assert codes.ndim == 1 and codes.min() >= 0 and codes.max() < 65536, voice
        put(f"voice-{voice}.codes", f"voices/{voice}.codes", (" ".join(map(str, codes.tolist())) + "\n").encode(), f"samples/{voice}.pt @ ac69851f", files)
        put(f"voice-{voice}.txt", f"voices/{voice}.txt", repo.read(f"{NEUTTS}/samples/{voice}.txt").strip() + b"\n", f"samples/{voice}.txt @ ac69851f", files)
    wheel = zipfile.ZipFile(BUILD / WHEEL)
    for name in ESPEAK:
        put("espeak-" + name.replace("/", "-"), f"espeak-ng-data/{name}", wheel.read(f"neutts/espeak-ng-data/{name}"), f"{WHEEL} (eSpeak NG 69bfd1ef)", files)
    put("LICENCE-NeuTTS-Open-1.0.txt", "LICENCE-NeuTTS-Open-1.0.txt", (BUILD / "neutts-nano-q4-gguf-LICENCE").read_bytes(), "neutts-nano-q4-gguf LICENCE @ d775fb6", files)
    put("ATTRIBUTION.txt", "ATTRIBUTION.txt", (HERE / "ATTRIBUTION.txt").read_bytes(), "this directory", files)
    for f in files:
        f["release"] = TAG if f["asset"] in NEW_IN_V2 else V1_TAG
    manifest = {"catalogId": "neutts-nano-en", "revision": "neutts-nano-q4-ac69851f-v2",
                "release": f"https://github.com/SSS135/local-voice-tts/releases/tag/{TAG}", "files": files}
    (HERE / "runtime-manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
    catalog = [{"path": f["path"], "url": f"https://github.com/SSS135/local-voice-tts/releases/download/{f['release']}/{f['asset']}",
                "bytes": f["bytes"], "sha256": f["sha256"]} for f in files]
    (BUILD / "catalog-files.json").write_text(json.dumps(catalog, indent=1) + "\n", encoding="utf-8")
    print(f"{len(files)} assets, {sum(f['bytes'] for f in files)} bytes -> {PACK}")


if __name__ == "__main__":
    sys.exit(main())
