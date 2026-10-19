"""Export actual Shtorm SentencePiece vocabulary and public normalization parity cases."""
import json
from pathlib import Path
import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2

ROOT = Path(__file__).resolve().parent
sp = spm.SentencePieceProcessor(model_file=str(ROOT / "tokenizer.model"))
proto = sentencepiece_model_pb2.ModelProto()
proto.ParseFromString((ROOT / "tokenizer.model").read_bytes())
vocab = {sp.id_to_piece(i): i for i in range(sp.get_piece_size())}
scores = {sp.id_to_piece(i): sp.get_score(i) for i in range(sp.get_piece_size())}
(ROOT / "onnx").mkdir(exist_ok=True)
(ROOT / "onnx" / "vocab.json").write_text(json.dumps(vocab, indent=2, ensure_ascii=False), encoding="utf-8")
(ROOT / "onnx" / "token_scores.json").write_text(json.dumps(scores, indent=2, ensure_ascii=False), encoding="utf-8")
(ROOT / "onnx" / "normalizer.bin").write_bytes(proto.normalizer_spec.precompiled_charsmap)
cases = ["Привет, мир.", "Приве́т, мир.", "Ёжик идёт домой.", "Е\u0308жик иде\u0308т домои\u0306.",
         "  Привет   мир  ", "Привет\tмир\nкак\rдела?", "Привет\u00a0мир\u202fснова.",
         "ПРИВЕТ, МИР!", "номер 123, цена 12.50 руб.", "«Да» — ответил он…", "проверка (тест): №42; 5% + 2 = 7.",
         "текст ＡＢＣ１２３！", "Русский text café Straße.", "мама мы́ла ра́му.", "подъём, вьюга, счастье.",
         "Привет🙂мир", "Привет你好мир", "Привет\u200bмир", "Привет\ufeffмир", "\u0000Привет", "", " \t\n"]
for separator in [chr(i) for i in range(33)] + ["\u0085", "\u00a0", "\u1680", "\u2000", "\u2001", "\u2002", "\u2003", "\u2004", "\u2005", "\u2006", "\u2007", "\u2008", "\u2009", "\u200a", "\u200b", "\u2028", "\u2029", "\u202f", "\u205f", "\u3000", "\ufeff"]:
    cases.extend(["При́вет" + separator + "мир.", separator + "  Ёжик   идёт." + separator])
for phrase in ["молоко́", "за́мок замо́к", "е́ ё е\u0308", "съешь ещё этих мягких французских булок", "длинное слово", "ма-ма, да-да", "из-за дома", "всё хорошо", "тридцать три", "12345"]:
    cases.extend([phrase, phrase + " " + phrase, phrase.upper()])
fixtures = []
for text in cases:
    ids = sp.encode(text, out_type=int)
    normalized = sp.normalize(text)
    fixtures.append({"text": text, "normalized": normalized,
                     "normalizedPlain": normalized.replace("▁", " ").strip(),
                     "ids": ids, "pieces": sp.encode(text, out_type=str),
                     "hasUnknown": sp.unk_id() in ids})
normalizer = proto.normalizer_spec
piece_types = {}
for piece in proto.pieces:
    name = sentencepiece_model_pb2.ModelProto.SentencePiece.Type.Name(piece.type)
    piece_types[name] = piece_types.get(name, 0) + 1
report = {"fixtureVersion": 2, "pieceTypes": piece_types,
          "modelPin": "33f6c328d62c4da5c1227bbe081e89b574a6183a", "vocabSize": sp.get_piece_size(),
          "normalizer": {"name": normalizer.name, "addDummyPrefix": normalizer.add_dummy_prefix,
                         "removeExtraWhitespaces": normalizer.remove_extra_whitespaces,
                         "escapeWhitespaces": normalizer.escape_whitespaces,
                         "precompiledCharsMapBytes": len(normalizer.precompiled_charsmap)},
          "modelType": "BPE", "treatWhitespaceAsSuffix": proto.trainer_spec.treat_whitespace_as_suffix,
          "sentencePieceVersion": spm.__version__,
          "byteFallback": proto.trainer_spec.byte_fallback, "unkId": sp.unk_id(), "fixtures": fixtures}
(ROOT / "tokenizer-parity-fixtures.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps({k: v for k, v in report.items() if k != "fixtures"}, ensure_ascii=False))
print("Wrote " + str(len(fixtures)) + " real SentencePiece cases")
