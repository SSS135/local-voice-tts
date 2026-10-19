"""Host A/B of Pocket English packs through sherpa-onnx 1.13.8 with the app's generation settings, plus Whisper WER.

synth (export venv: sherpa-onnx 1.13.8):  python -I host_eval.py synth OUT NAME PACK_DIR NUM_STEPS TEMPERATURE
asr   (venv with faster-whisper, jiwer):  python -I host_eval.py asr OUT
"""
import json
import re
import sys
import time
from pathlib import Path

SENTENCES = [
    "Hello world, this is a quick test of the new voice.",
    "Can you tell me how far it is to the nearest train station?",
    "The quick brown fox jumps over the lazy dog.",
    "Did you remember to lock the front door before leaving?",
    "She sells sea shells by the sea shore every summer.",
    "Our meeting has been moved to Thursday afternoon at three o'clock.",
    "Why would anyone want to read a book in the dark?",
    "Please turn left at the next intersection, then continue straight for two miles.",
    "From making twenty dollars to making one thousand dollars an hour.",
    "Is it going to rain tomorrow, or will the sun finally come out?",
    "A quiet morning begins beside the river.",
]
SEEDS = (1, 2)


def synth(out, name, pack, steps, temperature):
    import numpy as np
    import sherpa_onnx as sherpa
    from scipy.io import wavfile
    pack = Path(pack)
    start = time.monotonic()
    tts = sherpa.OfflineTts(sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
        pocket=sherpa.OfflineTtsPocketModelConfig(
            lm_flow=str(pack / "lm_flow.int8.onnx"), lm_main=str(pack / "lm_main.int8.onnx"), encoder=str(pack / "encoder.onnx"),
            decoder=str(pack / "decoder.int8.onnx"), text_conditioner=str(pack / "text_conditioner.onnx"),
            vocab_json=str(pack / "vocab.json"), token_scores_json=str(pack / "token_scores.json")),
        num_threads=2, provider="cpu")))
    load = time.monotonic() - start
    rows = []
    for seed in SEEDS:
        for index, text in enumerate(SENTENCES):
            config = sherpa.GenerationConfig()
            config.reference_audio = np.zeros(1920, np.float32)  # Same as PocketBackend fixedVoice.
            config.reference_sample_rate = 24000
            config.num_steps = int(steps)
            config.speed = 1.0
            config.silence_scale = 1.0
            config.extra = {"seed": str(seed), "temperature": str(temperature), "max_frames": "500",
                            "frames_after_eos": "3" if len(text.split()) <= 4 else "1", "max_reference_audio_len": "12",
                            "max_char_in_sentence": "4096", "min_char_in_sentence": "1", "chunk_size": "15"}
            first = []
            begin = time.monotonic()

            def callback(samples, progress):
                if not first:
                    first.append(time.monotonic() - begin)
                return 1
            audio = tts.generate(text, config, callback)
            seconds = time.monotonic() - begin
            samples = np.asarray(audio.samples, np.float32)
            path = Path(out) / name / f"s{seed}-{index:02d}.wav"
            path.parent.mkdir(parents=True, exist_ok=True)
            wavfile.write(path, audio.sample_rate, (np.clip(samples, -1, 1) * 32767).astype("<i2"))
            duration = len(samples) / audio.sample_rate
            rows.append({"seed": seed, "index": index, "wav": path.name, "audioSeconds": duration, "firstAudioSeconds": first[0] if first else None,
                         "computeSeconds": seconds, "rtf": seconds / duration if duration else None,
                         "rms": float(np.sqrt(np.mean(samples.astype(np.float64) ** 2))) if samples.size else 0.0})
            print(json.dumps(rows[-1]), flush=True)
    result = {"name": name, "pack": str(pack), "numSteps": int(steps), "temperature": float(temperature), "runtime": sherpa.__version__,
              "loadSeconds": load, "rows": rows}
    (Path(out) / (name + "-synth.json")).write_text(json.dumps(result, indent=2), encoding="utf-8")


NUMBERS = {"3": "three", "2": "two", "20": "twenty", "1000": "one thousand", "1,000": "one thousand", "$20": "twenty dollars",
           "$1,000": "one thousand dollars", "$1000": "one thousand dollars", "3:00": "three o'clock"}


def normalize(text):
    words = []
    for word in text.lower().replace("-", " ").split():
        word = word.strip(".,!?;:\"")
        words.append(NUMBERS.get(word, word))
    return re.sub(r"[^a-z' ]", "", " ".join(words)).replace("seashells", "sea shells").replace("seashore", "sea shore").split()


def asr(out):
    import jiwer
    from faster_whisper import WhisperModel
    model = WhisperModel("small.en", device="cpu", compute_type="int8")
    summary = {}
    for synth_file in sorted(Path(out).glob("*-synth.json")):
        result = json.loads(synth_file.read_text(encoding="utf-8"))
        references, hypotheses = [], []
        for row in result["rows"]:
            segments, _ = model.transcribe(str(Path(out) / result["name"] / row["wav"]), language="en", beam_size=5)
            row["transcript"] = " ".join(s.text.strip() for s in segments)
            references.append(" ".join(normalize(SENTENCES[row["index"]])))
            hypotheses.append(" ".join(normalize(row["transcript"])) or "<empty>")
            row["exact"] = references[-1] == hypotheses[-1]
        rows = result["rows"]
        summary[result["name"]] = {"numSteps": result["numSteps"], "temperature": result["temperature"], "syntheses": len(rows),
                                   "wer": jiwer.wer(references, hypotheses), "exact": sum(r["exact"] for r in rows),
                                   "medianFirstAudioSeconds": sorted(r["firstAudioSeconds"] for r in rows)[len(rows) // 2],
                                   "medianRtf": sorted(r["rtf"] for r in rows)[len(rows) // 2], "loadSeconds": result["loadSeconds"],
                                   "misses": [{"wav": r["wav"], "transcript": r["transcript"]} for r in rows if not r["exact"]]}
        synth_file.with_name(result["name"] + "-asr.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    (Path(out) / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    synth(*sys.argv[2:]) if sys.argv[1] == "synth" else asr(sys.argv[2])
