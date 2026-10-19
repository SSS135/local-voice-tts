"""Resolve only local checkpoint/tokenizer paths in the authentic pinned fast config."""
from pathlib import Path
import yaml

root = Path(__file__).resolve().parent
config = yaml.safe_load((root / "config_fast.yaml").read_text(encoding="utf-8"))
config["weights_path"] = (root / "model_fast.safetensors").as_posix()
config["flow_lm"]["lookup_table"]["tokenizer_path"] = (root / "tokenizer.model").as_posix()
(root / "config_local.yaml").write_text(yaml.safe_dump(config, allow_unicode=True), encoding="utf-8")
