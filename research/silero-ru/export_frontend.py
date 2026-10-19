"""Export the Silero v5_6_ru stress frontend (n-gram accentor + BERT homograph solver) for the Java port.

Usage: uv run python export_frontend.py <v5_6_ru.pt> <out_dir> [parity_fixture.json]

Writes <out_dir>/frontend.bin (tensors) and <out_dir>/frontend.json (dictionaries, vocabularies,
Java-compatible homograph phrase regexes). With a third argument also writes Python reference
outputs used by the Java parity test (SileroFrontendTest).

frontend.bin layout (little-endian): b"SLRF", u32 header_len, UTF-8 JSON header
{"tensors": {name: {"dtype": "f32"|"i8", "shape": [...], "offset": n}}}, then raw data; offsets
are relative to the data start and 4-byte aligned.
"""
import json
import re
import struct
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).parent))
from export_onnx import load_package  # noqa: E402

PARITY_SENTENCES = [
    "замок на двери был сломан, а старинный замок стоял на холме.",
    "все пришли домой, и все было хорошо.",
    "мама мыла раму.",
    "где ты был вчера вечером?",
    "ты пойдешь в кино или останешься дома?",
    "это правда, не так ли?",
    "какой чудесный день!",
    "мы видели белку на дереве и орел пролетел над лесом.",
    "в лесу родилась елочка, в лесу она росла.",
    "кому-то нужно сходить в магазин за молоком.",
    "мука для пирога и мука ожидания.",
    "передай мне, пожалуйста, ту большую синюю чашку.",
    "он сказал: «приходи завтра», и ушел.",
    "сорок два человека пришли на собрание.",
    "в н+едрах т+ундры выдры в гетрах тырят в ведра ядра кедров.",
    "ёлка стояла в углу; на ней горели огни…",
    "двадцать первого числа мы поедем на море – если погода позволит.",
]


def class_name(m):
    return m.original_name


def main():
    pkg, out = Path(sys.argv[1]), Path(sys.argv[2])
    fixture = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    out.mkdir(parents=True, exist_ok=True)
    torch.set_grad_enabled(False)
    part = load_package(pkg)
    assert not part.q_model_unpacked  # BERT word embeddings must still be int8 here
    stress = part.accentor
    acc, homo = stress.accentor, stress.homosolver
    tensors = {}

    # n-gram accentor (embedding weights are stored already dequantized in the package)
    am = acc.model
    tensors["acc.embedding"] = am.embedding.weight
    for head, seq in (("stress", am.stress_clf), ("yo", am.yo_clf)):
        kinds = [class_name(getattr(seq, str(i))) for i in range(7)]
        assert kinds == ["Linear", "ReLU", "Linear", "ReLU", "Linear", "ReLU", "Linear"], kinds
        for i in (0, 2, 4, 6):
            tensors[f"acc.{head}.{i}.weight"] = getattr(seq, str(i)).weight
            tensors[f"acc.{head}.{i}.bias"] = getattr(seq, str(i)).bias

    # BERT homograph solver; word embeddings stay int8 with their dequantization constants
    hm = homo.model
    bert = hm.bert
    kinds = [class_name(getattr(hm.homo_clf, str(i))) for i in range(4)]
    print("homo_clf", kinds)
    word = bert.embeddings.word_embeddings.weight
    tensors["bert.word"] = word
    tensors["bert.word_scale"] = bert.scale.reshape(1).float()
    tensors["bert.word_zero"] = bert.zero_point.reshape(1).float()
    e = bert.embeddings
    tensors["bert.pos"] = e.position_embeddings.weight
    tensors["bert.type"] = e.token_type_embeddings.weight
    tensors["bert.emb_ln.weight"], tensors["bert.emb_ln.bias"] = e.LayerNorm.weight, e.LayerNorm.bias
    layers = 0
    for i in range(len(list(bert.encoder.layer.children()))):
        layer = getattr(bert.encoder.layer, str(i))
        layers += 1
        a = layer.attention
        for key, lin in (("q", a.self.query), ("k", a.self.key), ("v", a.self.value), ("o", a.output.dense),
                         ("inter", layer.intermediate.dense), ("out", layer.output.dense)):
            tensors[f"bert.{i}.{key}.weight"], tensors[f"bert.{i}.{key}.bias"] = lin.weight, lin.bias
        for key, ln in (("attn_ln", a.output.LayerNorm), ("out_ln", layer.output.LayerNorm)):
            tensors[f"bert.{i}.{key}.weight"], tensors[f"bert.{i}.{key}.bias"] = ln.weight, ln.bias
    for i in (0, 3):
        tensors[f"homo.{i}.weight"] = getattr(hm.homo_clf, str(i)).weight
        tensors[f"homo.{i}.bias"] = getattr(hm.homo_clf, str(i)).bias

    header, blobs, offset = {}, [], 0
    for name, t in tensors.items():
        t = t.detach().contiguous()
        if t.dtype == torch.int8:
            data, dtype = t.numpy().tobytes(), "i8"
        else:
            data, dtype = t.float().numpy().astype("<f4").tobytes(), "f32"
        header[name] = {"dtype": dtype, "shape": list(t.shape), "offset": offset}
        pad = (-len(data)) % 4
        blobs.append(data + b"\0" * pad)
        offset += len(data) + pad
    head = json.dumps({"tensors": header}, separators=(",", ":")).encode("utf-8")
    with open(out / "frontend.bin", "wb") as f:
        f.write(b"SLRF" + struct.pack("<I", len(head)) + head)
        for b in blobs:
            f.write(b)

    sa = getattr(bert.encoder.layer, "0").attention.self
    ngram_dict = am.embedding.ngram_dict
    ngrams = [None] * len(ngram_dict)
    for g, i in ngram_dict.items():
        ngrams[i] = g
    assert None not in ngrams
    vocab = [None] * len(homo.tokenizer.vocab)
    for tok, i in homo.tokenizer.vocab.items():
        vocab[i] = tok
    assert None not in vocab
    phrases = {}
    for w, pat in homo.compiled_phrases.items():
        names = re.findall(r"\(\?P<([^>]+)>", pat.pattern)
        variants = [n[: [c.isupper() for c in n].index(True)] + "+" + n[[c.isupper() for c in n].index(True):].lower()
                    for n in names]
        # Java named groups must be ASCII: use positional groups in variant order instead.
        java = re.sub(r"\(\?P<[^>]+>", "(", pat.pattern)
        phrases[w] = {"variants": variants, "regex": java}
    data = {
        "source": "silero v5_6_ru",
        "accentor": {"ngrams": ngrams, "exceptions": {k: list(v) for k, v in acc.exceptions.items()}},
        "homosolver": {
            "homodict": homo.homodict, "phrases": phrases, "vocab": vocab,
            "layers": layers, "heads": sa.num_attention_heads, "window": homo.window_size,
            "emb_ln_eps": float(bert.embeddings.LayerNorm.eps),
            "ln_eps": float(getattr(bert.encoder.layer, "0").output.LayerNorm.eps),
            "homo_clf": kinds,
        },
    }
    with open(out / "frontend.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print("wrote", out / "frontend.bin", out / "frontend.json")

    if fixture:
        part.unpack_q_model()  # what apply_tts does before its first inference
        part.q_model_unpacked = True
        classify_sentence = type(part).apply_tts.__globals__["classify_sentence"]
        rows = []
        for s in PARITY_SENTENCES:
            homo_out = homo(s)
            rows.append({"text": s, "homosolver": homo_out, "accented": stress(s),
                         "type": classify_sentence(s)})
        # One BERT logit for the Java numeric check.
        ids = homo.tokenizer("мама [HOMO] замок [/HOMO] на двери.")
        t = torch.tensor([ids])
        st = int((t[0] == homo.tokenizer.homo_start_id).nonzero()[0])
        en = int((t[0] == homo.tokenizer.homo_end_id).nonzero()[0])
        logit = float(hm(t, torch.tensor([st]), torch.tensor([en]))[0, 0])
        with open(fixture, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"source": "silero v5_6_ru Python frontend", "sentences": rows,
                       "bert": {"ids": ids, "logit": logit}}, f, ensure_ascii=False, indent=1)
        print("wrote", fixture)


if __name__ == "__main__":
    main()
