"""Fine-tunes YOLOv8n on the BowlTrack dataset.

Run from the project root with the training venv:

    .venv-train/bin/python training/train_yolo.py

Outputs land under ``training/runs/detect/bowltrack-v1/``. The best
checkpoint is symlinked to ``training/best.pt`` for the export script.
"""

from __future__ import annotations

import shutil
from pathlib import Path

from ultralytics import YOLO


PROJECT_ROOT = Path(__file__).resolve().parent
DATA_YAML = PROJECT_ROOT / "dataset" / "data.yaml"
RUNS_DIR = PROJECT_ROOT / "runs"
RUN_NAME = "bowltrack-v1"


def main() -> None:
    # Fine-tune from the official YOLOv8n weights. Using the nano
    # variant keeps the eventual TFLite export small enough to ship
    # in the APK without wrecking the install size budget.
    model = YOLO("yolov8n.pt")

    results = model.train(
        data=str(DATA_YAML),
        epochs=20,
        imgsz=640,
        batch=16,
        device="cpu",
        workers=4,
        project=str(RUNS_DIR),
        name=RUN_NAME,
        # exist_ok lets us overwrite the previous partial run instead
        # of creating bowltrack-v1-2/ etc. on retries.
        exist_ok=True,
        patience=10,
        cache=False,  # 2300+ imgs at 640px would balloon RAM
        plots=True,
        # Light augmentation only — the dataset is already
        # representative of the test environment.
        hsv_h=0.015,
        hsv_s=0.4,
        hsv_v=0.4,
        translate=0.05,
        scale=0.4,
        fliplr=0.0,    # bowling alleys have a left/right orientation
        mosaic=0.5,
        close_mosaic=5,
    )

    best_pt = Path(results.save_dir) / "weights" / "best.pt"
    deploy_pt = PROJECT_ROOT / "best.pt"
    if best_pt.exists():
        if deploy_pt.exists() or deploy_pt.is_symlink():
            deploy_pt.unlink()
        shutil.copy2(best_pt, deploy_pt)
        print(f"\nBest checkpoint copied to {deploy_pt}")
    else:
        print(f"\nbest.pt not found at {best_pt}; check the run output above")


if __name__ == "__main__":
    main()
