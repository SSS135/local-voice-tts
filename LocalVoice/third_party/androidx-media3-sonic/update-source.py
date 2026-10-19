"""Reproduce the narrowly adapted Sonic core from the immutable official source."""
from pathlib import Path
import hashlib
import urllib.request
import sys

COMMIT = "76088cd6af7f263aba238b7a48d64bd4f060cb8b"
EXPECTED = {
    "Sonic.java": "6396a43776a2fc8059b77e61de4413a34a6890424f2cb2ab284d28daa0886d3e",
    "LICENSE": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
}
root = Path(__file__).resolve().parents[2]
originals = Path(__file__).resolve().parent
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
check_only = "--check" in sys.argv
for name, source in [("Sonic.java", "libraries/common/src/main/java/androidx/media3/common/audio/Sonic.java"), ("LICENSE", "LICENSE")]:
    data = (originals / name).read_bytes() if check_only else opener.open(f"https://raw.githubusercontent.com/androidx/media/{COMMIT}/{source}", timeout=20).read()
    if hashlib.sha256(data).hexdigest() != EXPECTED[name]:
        raise RuntimeError(f"Unexpected upstream {name} checksum")
    if not check_only:
        (originals / name).write_bytes(data)
adapted = (originals / "Sonic.java").read_text(encoding="utf-8")
adapted = adapted.replace("package androidx.media3.common.audio;", "package org.localvoice.tts.backend;\n// Local adaptation: package, assertion, and AMDF product overflow fix; see THIRD_PARTY_SONIC_NOTICES.txt.")
adapted = adapted.replace("import static androidx.media3.common.util.Assertions.checkState;\n", "")
adapted = adapted.replace("/* package */ final class Sonic {", "/* package */ final class Sonic {\n  private static void checkState(boolean value) { if (!value) throw new IllegalStateException(\"Invalid Sonic state\"); }")
adapted = adapted.replace("diff * bestPeriod < minDiff * period", "(long) diff * bestPeriod < (long) minDiff * period")
adapted = adapted.replace("diff * worstPeriod > maxDiff * period", "(long) diff * worstPeriod > (long) maxDiff * period")
destination = root / "app/src/main/java/org/localvoice/tts/backend/Sonic.java"
if check_only:
    if destination.read_text(encoding="utf-8") != adapted:
        raise RuntimeError("Production Sonic differs from the documented adaptation")
    if (root / "app/src/main/assets/legal/SONIC-APACHE-2.0-LICENSE.txt").read_bytes() != (originals / "LICENSE").read_bytes():
        raise RuntimeError("Packaged Sonic license differs from upstream")
    print(f"Verified retained source, adaptation and license for official commit {COMMIT}; no network used")
else:
    destination.write_text(adapted, encoding="utf-8", newline="\n")
    (root / "app/src/main/assets/legal/SONIC-APACHE-2.0-LICENSE.txt").write_bytes((originals / "LICENSE").read_bytes())
    print(f"Reproduced Sonic from official commit {COMMIT}")
