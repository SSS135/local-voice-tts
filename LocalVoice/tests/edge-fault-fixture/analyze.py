"""Summarize hash-only fixture trace; application PCM commits need Android evidence."""
import argparse
import json
from pathlib import Path


def analyze(rows, expected_completions=None, expected_hashes=None):
    attempts = [row for row in rows if row["event"] == "attempt"]
    ids = [row["attempt_id"] for row in attempts]
    if ids != sorted(set(ids)):
        raise AssertionError("attempt identifiers are not unique/increasing")
    complete = [row for row in rows if row["event"] == "served_complete"]
    by_id = {row["attempt_id"]: row for row in attempts}
    completed_hashes = [by_id[row["attempt_id"]]["speak_sha256"] for row in complete]
    if expected_completions is not None and len(complete) != expected_completions:
        raise AssertionError("unexpected successful response count")
    if expected_hashes is not None and completed_hashes != expected_hashes:
        raise AssertionError("successful chunk hash sequence differs")
    groups = {}
    for row in attempts:
        groups.setdefault(row["speak_sha256"], []).append(row)
    for group in groups.values():
        if len({row["text_sha256"] for row in group}) != 1:
            raise AssertionError("retry changed text identity")
        if [row["chunk_attempt"] for row in group] != list(range(1, len(group) + 1)):
            raise AssertionError("per-chunk attempt counter skipped")
    return {"attempts": len(attempts), "servedComplete": len(complete),
            "chunks": [{"speakSha256": key, "attempts": len(group)} for key, group in groups.items()],
            "completedSpeakHashes": completed_hashes,
            "note": "Served responses are not evidence of application PCM commits."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--expected-completions", type=int)
    parser.add_argument("--expected-hashes", type=Path,
                        help="JSON array of expected complete speak SHA256 values in chunk order")
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.trace.read_text(encoding="utf-8").splitlines()]
    expected = json.loads(args.expected_hashes.read_text()) if args.expected_hashes else None
    print(json.dumps(analyze(rows, args.expected_completions, expected), indent=2))
