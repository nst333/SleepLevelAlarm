"""Train the AI Conv1D–LSTM with inputs available on Wear OS."""

import argparse
import json
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import pandas as pd
import tensorflow as tf

from sleep_stage_pipeline import _fit_merged_subject_frames, load_subject_split_files, make_sliding_windows

EPOCH_SECONDS = 30
WINDOW_SIZE = 30
FEATURES = ("heart_rate_bpm", "acceleration_magnitude_std_g")
ASSET = Path(__file__).resolve().parents[1] / "wear/src/main/assets/sleep_stage.tflite"


def epoch_bins(timestamps):
    return (timestamps // EPOCH_SECONDS) * EPOCH_SECONDS


def load_epochs(root, subject_id):
    labels = pd.read_csv(root / "labels" / f"{subject_id}_labeled_sleep.csv")
    labels = labels[["epoch", "label"]].rename(columns={"epoch": "timestamp"})
    labels = labels.sort_values("timestamp").drop_duplicates("timestamp", keep="last")
    first, last = labels["timestamp"].min(), labels["timestamp"].max() + EPOCH_SECONDS

    heart = pd.read_csv(root / "heart_rate" / f"{subject_id}_heartrate.csv")
    heart = heart.loc[heart["hr"].between(25, 240)]
    heart_mean = heart.groupby(epoch_bins(heart["timestamp"]))["hr"].mean()

    motion = pd.read_csv(root / "motion" / f"{subject_id}_acceleration.csv")
    motion = motion.loc[(motion["timestamp"] >= first) & (motion["timestamp"] < last)]
    magnitude = np.linalg.norm(motion[["x", "y", "z"]].to_numpy(), axis=1)
    magnitude = pd.Series(magnitude, index=epoch_bins(motion["timestamp"])).dropna()
    motion_bins = magnitude.groupby(level=0)
    motion_std = motion_bins.std(ddof=0).where(motion_bins.size() >= 2)

    return (
        labels.assign(
            heart_rate_bpm=labels["timestamp"].map(heart_mean),
            acceleration_magnitude_std_g=labels["timestamp"].map(motion_std),
        )
        .replace([np.inf, -np.inf], np.nan)
        .dropna()
        .reset_index(drop=True)
    )


def export_model(trained, example, path):
    # Static, unrolled LSTMs use built-in LiteRT operators on Wear OS.
    def clone_layer(layer):
        config = layer.get_config()
        if isinstance(layer, tf.keras.layers.LSTM):
            config["unroll"] = True
        return layer.__class__.from_config(config)

    network = tf.keras.models.clone_model(trained.model, clone_function=clone_layer)
    network.set_weights(trained.model.get_weights())
    inputs = tf.keras.Input(batch_shape=(1, WINDOW_SIZE, len(FEATURES)))
    scale = 1 / trained.scaler.scale_
    normalized = tf.keras.layers.Rescaling(
        scale=scale.tolist(), offset=(-trained.scaler.mean_ * scale).tolist()
    )(inputs)
    deployable = tf.keras.Model(inputs, network(normalized))
    with TemporaryDirectory() as saved_model:
        deployable.export(saved_model, verbose=False)
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_model)
        data = converter.convert()

    runtime = tf.lite.Interpreter(model_content=data)
    runtime.allocate_tensors()
    input_index = runtime.get_input_details()[0]["index"]
    output_index = runtime.get_output_details()[0]["index"]
    for row in example[:10]:
        raw = row[None]
        scaled = ((raw - trained.scaler.mean_) / trained.scaler.scale_).astype(np.float32)
        expected = trained.model(scaled, training=False).numpy()
        runtime.set_tensor(input_index, raw)
        runtime.invoke()
        actual = runtime.get_tensor(output_index)
        np.testing.assert_allclose(actual, expected, atol=2e-5, rtol=2e-4)
        np.testing.assert_array_equal(actual.argmax(axis=1), expected.argmax(axis=1))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="Sleep-Accel directory with headered CSVs")
    parser.add_argument("--output", type=Path, default=ASSET)
    args = parser.parse_args()
    tf.keras.utils.set_random_seed(42)
    splits = load_subject_split_files(Path(__file__).resolve().parent)
    frames = {}
    for ids in splits.values():
        for subject_id in ids:
            frames[subject_id] = load_epochs(args.dataset, subject_id)
            print(f"{subject_id}: {len(frames[subject_id])} usable epochs", flush=True)

    trained, _, metrics, used_splits = _fit_merged_subject_frames(
        frames,
        splits,
        window_size=WINDOW_SIZE,
        epoch_seconds=EPOCH_SECONDS,
        max_gap_seconds=0,
        offsets={},
        excluded_subjects={},
        epochs=20,
        batch_size=64,
        verbose=2,
        callbacks=[tf.keras.callbacks.EarlyStopping(patience=4, restore_best_weights=True)],
    )
    test_windows = (
        make_sliding_windows(frames[subject_id], WINDOW_SIZE, FEATURES, epoch_seconds=EPOCH_SECONDS)[0]
        for subject_id in used_splits["test"]
    )
    example = next(windows for windows in test_windows if len(windows))
    export_model(trained, example, args.output)
    metrics["confusion_matrix"] = metrics["confusion_matrix"].tolist()
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
