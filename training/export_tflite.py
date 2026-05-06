"""Exports the fine-tuned BowlTrack checkpoint to TFLite fp16 and
deploys it into ``app/src/main/assets/ml/`` so the Android build picks
it up.

Run from the project root with the training venv:

    .venv-train/bin/python training/export_tflite.py
"""

from __future__ import annotations

import shutil
from pathlib import Path

from ultralytics import YOLO


PROJECT_ROOT = Path(__file__).resolve().parents[1]
TRAINING = PROJECT_ROOT / "training"
BEST_PT = TRAINING / "best.pt"

ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "ml"
DEPLOY_NAME = "yolov8n_float16.tflite"


def main() -> None:
    if not BEST_PT.exists():
        raise SystemExit(f"best.pt not found at {BEST_PT}; run train_yolo.py first")

    model = YOLO(str(BEST_PT))
    # half=True produces a float16 weight file (~6 MB for yolov8n) that
    # tflite-runtime / TFLite Java interpreter both consume directly.
    exported = model.export(format="tflite", half=True, imgsz=640)
    exported_path = Path(exported) if isinstance(exported, str) else Path(str(exported))

    if not exported_path.exists():
        # Some Ultralytics versions return a directory; pick the first
        # *_float16.tflite inside it.
        candidates = list(BEST_PT.parent.rglob("*_float16.tflite"))
        if not candidates:
            raise SystemExit(f"Could not locate exported TFLite file (looked at {exported_path})")
        exported_path = candidates[0]

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    deploy_target = ASSETS_DIR / DEPLOY_NAME
    shutil.copy2(exported_path, deploy_target)

    print(f"\nExported: {exported_path}")
    print(f"Deployed: {deploy_target}")
    print(f"Size:     {deploy_target.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
