# Writes frontend-parity.json: upstream NeuTTS phonemization (phonemizer 3.4.0 + the neutts 1.4.1 wheel's eSpeak NG)
# of sentences.txt, plus the raw eSpeak output of every text chunk so NeuTtsFrontendTest runs without eSpeak.
# "phones" uses LocalVoice's one deviation (chunks cut at the punctuation match, not the mark's first occurrence);
# "upstreamPhones" is unmodified phonemizer. Windows host (uses the wheel's espeak-ng.dll):
#   uv run --no-project --with phonemizer==3.4.0 python -I tools/models/neutts/phoneme_parity.py
import json, os, re, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
WHEEL_DIR = HERE / "build" / "wheel"
if not (WHEEL_DIR / "neutts" / "espeak-ng.dll").exists():
    zipfile.ZipFile(HERE / "build" / "neutts-1.4.1-cp312-cp312-win_amd64.whl").extractall(WHEEL_DIR)
os.environ["ESPEAK_DATA_PATH"] = str(WHEEL_DIR / "neutts" / "espeak-ng-data")
from phonemizer.backend.espeak.wrapper import EspeakWrapper
EspeakWrapper.set_library(str(WHEEL_DIR / "neutts" / "espeak-ng.dll"))
from phonemizer.backend import EspeakBackend
from phonemizer.punctuation import Punctuation, _MarkIndex

g2p = EspeakBackend(language="en-us", preserve_punctuation=True, with_stress=True,
                    words_mismatch="ignore", language_switch="remove-flags")


def to_phones(text):  # NeuTTS._to_phones
    return " ".join(g2p.phonemize([text])[0].split())


upstream_preserve = Punctuation._preserve_line


def positional_preserve(self, line, num):
    matches = list(re.finditer(self._marks_re, line))
    if not matches:
        return [line], []
    if len(matches) == 1 and matches[0].group() == line:
        return [], [_MarkIndex(num, line, "A")]
    marks, chunks, start = [], [], 0
    for i, m in enumerate(matches):
        position = "B" if i == 0 and line.startswith(m.group()) else "E" if i == len(matches) - 1 and line.endswith(m.group()) else "I"
        marks.append(_MarkIndex(num, m.group(), position))
        chunks.append(line[start:m.start()])
        start = m.end()
    return chunks + [line[start:]], marks


raw = {}
original = g2p._espeak.text_to_phonemes
g2p._espeak.text_to_phonemes = lambda text, tie=False: raw.setdefault(text, original(text, tie))
cases = []
for line in (HERE / "sentences.txt").read_text(encoding="utf-8").splitlines():
    if not line:
        continue
    Punctuation._preserve_line = upstream_preserve
    upstream = to_phones(line)
    Punctuation._preserve_line = positional_preserve
    cases.append({"text": line, "phones": to_phones(line), "upstreamPhones": upstream})
out = {"espeak": "neutts-1.4.1 wheel, eSpeak NG 1.52.0.1 (69bfd1ef), voice " + str(g2p._espeak.voice.identifier).replace("\\", "/"),
       "phonemizer": "3.4.0", "raw": raw, "cases": cases}
(HERE / "frontend-parity.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(len(cases), "cases,", sum(c["phones"] != c["upstreamPhones"] for c in cases), "differ from upstream")
