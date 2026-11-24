"""Subject-independent sleep-stage classification from wearable sensor data.

Dependencies: numpy, pandas, scikit-learn, and tensorflow.

The sensor timestamps and labeled-sleep epochs must use the same time base.
If a sensor's timestamps need a constant shift, pass it in
``timestamp_offsets`` (in seconds). Unmapped labels such as -1 or 4 are
excluded as targets; the model predicts only stages 0, 1, 2, 3, and 5.

Notebook outline:
    splits = load_subject_split_files(DATA_DIR)
    trained, history, metrics, splits = train_sleep_stage_directory(
        DATA_DIR, split_ids=splits, window_size=30
    )
    predicted_stages = predict_new_instance(new_subject_data, trained)

``load_subject_data`` remains available when the nested raw-data dictionary is
needed for inspection. ``train_sleep_stage_directory`` reads one subject at a
time to keep peak memory manageable for large acceleration CSV files.
"""

from __future__ import annotations

import csv
import re
import warnings
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

import numpy as np
import pandas as pd


DATA_TYPES = ("acceleration", "heartrate", "steps", "labeled_sleep")
SENSOR_TYPES = ("acceleration", "heartrate", "steps")
SLEEP_STAGES = (0, 1, 2, 3, 5)
_FILE_PATTERN = re.compile(
    r"^(?P<subject_id>\d+)_(?P<data_type>acceleration|heartrate|steps|labeled_sleep)$",
    re.IGNORECASE,
)


class SubjectAlignmentError(ValueError):
    """Raised when a subject has no usable sensor/label timeline overlap."""


def _read_table(path: Path) -> pd.DataFrame:
    """Read comma-, tab-, semicolon-, or pipe-delimited CSV/TXT data."""
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        first_line = next((line for line in file if line.strip()), "")
    if not first_line:
        raise ValueError(f"Data file is empty: {path}")

    try:
        delimiter = csv.Sniffer().sniff(first_line, delimiters=",\t;|").delimiter
    except csv.Error:
        delimiter = ","
    fields = next(csv.reader([first_line], delimiter=delimiter))
    has_header = any(pd.isna(pd.to_numeric(field.strip(), errors="coerce")) for field in fields)
    return pd.read_csv(path, sep=delimiter, header=0 if has_header else None)


def _discover_subject_paths(data_dir: str | Path) -> Dict[str, Dict[str, Path]]:
    root = Path(data_dir)
    if not root.is_dir():
        raise NotADirectoryError(f"Data directory does not exist: {root}")

    subjects: Dict[str, Dict[str, Path]] = {}
    source_paths: Dict[Tuple[str, str], Path] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in {".csv", ".txt"}:
            continue
        match = _FILE_PATTERN.fullmatch(path.stem)
        if not match:
            continue
        subject_id = match.group("subject_id")
        data_type = match.group("data_type").lower()
        key = (subject_id, data_type)
        if key in source_paths:
            raise ValueError(
                f"Multiple files found for subject {subject_id} / {data_type}: "
                f"{source_paths[key]} and {path}"
            )
        subjects.setdefault(subject_id, {})[data_type] = path
        source_paths[key] = path

    if not subjects:
        raise FileNotFoundError(f"No matching subject data files found under {root}")

    incomplete = {
        subject_id: sorted(set(DATA_TYPES) - set(files))
        for subject_id, files in subjects.items()
        if set(files) != set(DATA_TYPES)
    }
    if incomplete:
        details = "; ".join(
            f"{subject_id}: missing {', '.join(missing)}"
            for subject_id, missing in sorted(incomplete.items())
        )
        raise ValueError(f"Every subject must have all four data types; {details}")

    return {
        subject_id: {data_type: files[data_type] for data_type in DATA_TYPES}
        for subject_id, files in subjects.items()
    }


def load_subject_data(data_dir: str | Path) -> Dict[str, Dict[str, pd.DataFrame]]:
    """Load each subject's four modality files into the requested nested dict.

    Files may be in one directory or in modality subdirectories. The filename
    must end in ``_<data_type>.csv`` or ``_<data_type>.txt``. This loads all
    files into memory; use ``train_sleep_stage_directory`` for large datasets.
    """
    paths = _discover_subject_paths(data_dir)
    return {
        subject_id: {
            data_type: _read_table(path) for data_type, path in subject_paths.items()
        }
        for subject_id, subject_paths in paths.items()
    }


def load_subject_split_files(data_dir: str | Path) -> Dict[str, List[str]]:
    """Read train_ids.txt, val_ids.txt, and test_ids.txt from ``data_dir``."""
    root = Path(data_dir)
    file_names = {
        "train": "train_ids.txt",
        "validation": "val_ids.txt",
        "test": "test_ids.txt",
    }
    splits: Dict[str, List[str]] = {}
    for split_name, file_name in file_names.items():
        path = root / file_name
        if not path.is_file():
            raise FileNotFoundError(f"Missing subject split file: {path}")
        ids = []
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            value = line.strip()
            if value and value.lower() != "id":
                ids.append(value)
        if not ids:
            raise ValueError(f"No subject IDs found in {path}")
        splits[split_name] = ids
    return splits


def split_subjects(
    subject_ids: Iterable[str],
    *,
    split_ids: Optional[Mapping[str, Sequence[str]]] = None,
    test_size: float = 0.2,
    validation_size: float = 0.15,
    random_state: int = 42,
) -> Dict[str, List[str]]:
    """Create non-overlapping subject-level train/validation/test splits.

    Provide ``split_ids`` to use fixed subject lists; otherwise a seeded
    randomized group split is made. No windows are split across subjects.
    """
    all_ids = set(map(str, subject_ids))
    if len(all_ids) < 3:
        raise ValueError("At least three subjects are needed for train/validation/test splits.")

    if split_ids is not None:
        required = {"train", "validation", "test"}
        if set(split_ids) != required:
            raise ValueError(f"split_ids must have exactly these keys: {sorted(required)}")
        result = {name: list(map(str, split_ids[name])) for name in sorted(required)}
        flattened = [subject_id for values in result.values() for subject_id in values]
        if len(flattened) != len(set(flattened)):
            raise ValueError("Subject split lists overlap or contain duplicate IDs.")
        if set(flattened) != all_ids:
            missing = sorted(all_ids - set(flattened))
            unknown = sorted(set(flattened) - all_ids)
            raise ValueError(f"Split IDs must match loaded subjects; missing={missing}, unknown={unknown}")
        return result

    if not 0 < test_size < 1 or not 0 < validation_size < 1:
        raise ValueError("test_size and validation_size must both be between 0 and 1.")
    ids = np.asarray(sorted(all_ids), dtype=object)
    np.random.default_rng(random_state).shuffle(ids)
    test_count = max(1, int(round(len(ids) * test_size)))
    validation_count = max(1, int(round((len(ids) - test_count) * validation_size)))
    if test_count + validation_count >= len(ids):
        raise ValueError("Split fractions leave no subjects for training.")
    return {
        "train": sorted(ids[test_count + validation_count :].tolist()),
        "validation": sorted(ids[test_count : test_count + validation_count].tolist()),
        "test": sorted(ids[:test_count].tolist()),
    }


def _find_column(frame: pd.DataFrame, aliases: Sequence[str]) -> Optional[object]:
    normalized = {str(column).strip().lower().replace(" ", "_"): column for column in frame.columns}
    for alias in aliases:
        if alias in normalized:
            return normalized[alias]
    return None


def _numeric_sensor_frame(
    frame: pd.DataFrame,
    data_type: str,
    timestamp_offset: float,
) -> pd.DataFrame:
    if frame.empty or frame.shape[1] < 2:
        raise ValueError(f"{data_type} data must contain timestamps and at least one feature.")

    timestamp_column = _find_column(frame, ("timestamp", "time", "epoch", "datetime"))
    if timestamp_column is None:
        timestamp_column = frame.columns[0]
    timestamps = pd.to_numeric(frame[timestamp_column], errors="coerce") + timestamp_offset

    features = frame.drop(columns=[timestamp_column])
    features = features.apply(pd.to_numeric, errors="coerce")
    features = features.loc[:, features.notna().any()]
    if features.empty:
        raise ValueError(f"No numeric sensor features found for {data_type}.")
    features.columns = [f"{data_type}_{str(name).strip().lower()}" for name in features.columns]
    features.insert(0, "timestamp", timestamps)
    features = features.dropna(subset=["timestamp"]).sort_values("timestamp")
    if features.empty:
        raise ValueError(f"No valid timestamps found for {data_type}.")
    return features


def _numeric_labels(frame: pd.DataFrame) -> pd.DataFrame:
    if frame.empty or frame.shape[1] < 2:
        raise ValueError("labeled_sleep data must contain epoch timestamps and labels.")
    time_column = _find_column(frame, ("epoch", "timestamp", "time", "datetime"))
    label_column = _find_column(frame, ("label", "stage", "sleep_stage", "sleepstage"))
    time_column = time_column if time_column is not None else frame.columns[0]
    label_column = label_column if label_column is not None else frame.columns[1]

    result = pd.DataFrame(
        {
            "timestamp": pd.to_numeric(frame[time_column], errors="coerce").astype(float),
            "label": pd.to_numeric(frame[label_column], errors="coerce"),
        }
    ).dropna()
    if result.empty:
        raise ValueError("No valid labeled_sleep epochs found.")
    if not np.equal(result["label"], np.floor(result["label"])).all():
        raise ValueError("Sleep-stage labels must be integer values.")
    result["label"] = result["label"].astype(int)
    return result.sort_values("timestamp").drop_duplicates("timestamp", keep="last")


def merge_subject_data(
    subject_data: Mapping[str, pd.DataFrame],
    *,
    subject_id: str = "unknown",
    epoch_seconds: int = 30,
    max_gap_seconds: float = 900,
    timestamp_offsets: Optional[Mapping[str, float]] = None,
) -> pd.DataFrame:
    """Align the three sensor streams to labeled-sleep epoch timestamps.

    Sensor samples are averaged into epoch-sized time bins, then matched to
    the label timeline. Small gaps are interpolated; data outside the allowed
    time gap are not silently carried across long periods.
    """
    if epoch_seconds <= 0 or max_gap_seconds < 0:
        raise ValueError("epoch_seconds must be positive and max_gap_seconds non-negative.")
    missing = set(DATA_TYPES) - set(subject_data)
    if missing:
        raise ValueError(f"Subject {subject_id} is missing data types: {sorted(missing)}")
    offsets = timestamp_offsets or {}
    labels = _numeric_labels(subject_data["labeled_sleep"])
    max_fill_epochs = max(1, int(np.ceil(max_gap_seconds / epoch_seconds)))

    sensor_frames = {}
    for data_type in SENSOR_TYPES:
        sensor = _numeric_sensor_frame(
            subject_data[data_type],
            data_type,
            float(offsets.get(data_type, 0.0)),
        )
        feature_columns = [column for column in sensor.columns if column != "timestamp"]

        # Bucket timestamps against the same zero point as the labeled epochs.
        sensor["timestamp"] = (
            np.floor(sensor["timestamp"] / epoch_seconds) * epoch_seconds
        )
        sensor_frames[data_type] = sensor.groupby(
            "timestamp", as_index=False
        )[feature_columns].mean()

    # Keep only labeled epochs within the common sensor coverage. This avoids
    # fabricating data by extrapolating a sensor stream before/after it exists.
    earliest = max(
        sensor["timestamp"].min() - max_gap_seconds for sensor in sensor_frames.values()
    )
    latest = min(
        sensor["timestamp"].max() + max_gap_seconds for sensor in sensor_frames.values()
    )
    target = labels.loc[
        labels["timestamp"].between(earliest, latest)
    ].copy()
    if target.empty:
        raise SubjectAlignmentError(
            f"No labeled epochs overlap all sensor timelines for subject {subject_id}. "
            f"Label range [{labels['timestamp'].min()}, {labels['timestamp'].max()}]; "
            f"the sensor intersection is empty (earliest supported epoch {earliest}, "
            f"latest supported epoch {latest}). Check timestamp origins."
        )
    # Filtering preserves label order, and groupby sorts the sensor bins.
    target_times = target[["timestamp"]]

    for data_type, sensor in sensor_frames.items():
        feature_columns = [column for column in sensor.columns if column != "timestamp"]
        aligned = pd.merge_asof(
            target_times,
            sensor,
            on="timestamp",
            direction="nearest",
            tolerance=max_gap_seconds,
        )
        values = aligned[feature_columns].interpolate(
            method="linear",
            limit=max_fill_epochs,
            limit_area="inside",
        )
        values = values.ffill(limit=max_fill_epochs).bfill(limit=max_fill_epochs)
        target[feature_columns] = values.to_numpy()

    feature_columns = [
        column for column in target.columns if column not in {"timestamp", "label"}
    ]
    target = target.dropna(subset=feature_columns)
    if target.empty:
        raise SubjectAlignmentError(
            f"No labeled epochs could be aligned to every sensor for subject {subject_id}. "
            f"Check timestamp origins and max_gap_seconds={max_gap_seconds}."
        )
    return target.reset_index(drop=True)


def make_sliding_windows(
    frame: pd.DataFrame,
    window_size: int,
    feature_columns: Sequence[str],
    *,
    epoch_seconds: int = 30,
    classes: Sequence[int] = SLEEP_STAGES,
    return_times: bool = False,
):
    """Make sequence-to-one windows; each target is the window's final epoch.

    Windows containing a timestamp gap are skipped. Epochs with labels outside
    ``classes`` remain usable as context but are excluded as target labels.
    """
    if window_size <= 0:
        raise ValueError("window_size must be a positive integer.")
    if not feature_columns:
        raise ValueError("At least one feature column is required.")
    missing = set(feature_columns) - set(frame.columns)
    if missing:
        raise ValueError(f"Missing feature columns: {sorted(missing)}")
    if not {"timestamp", "label"}.issubset(frame.columns):
        raise ValueError("frame must contain 'timestamp' and 'label' columns.")

    values = frame[list(feature_columns)].to_numpy(dtype=np.float32)
    labels = pd.to_numeric(frame["label"], errors="coerce").to_numpy()
    timestamps = pd.to_numeric(frame["timestamp"], errors="coerce").to_numpy()
    if len(frame) < window_size:
        empty_x = np.empty((0, window_size, len(feature_columns)), dtype=np.float32)
        empty_y = np.empty((0, len(classes)), dtype=np.float32)
        empty_times = np.empty((0,), dtype=np.float64)
        return (empty_x, empty_y, empty_times) if return_times else (empty_x, empty_y)

    windows = np.lib.stride_tricks.sliding_window_view(
        values, window_shape=window_size, axis=0
    ).transpose(0, 2, 1)
    if window_size == 1:
        contiguous = np.ones(len(timestamps), dtype=bool)
    else:
        # A window is contiguous if its endpoints have the same cumulative gap count.
        gaps = np.diff(timestamps) != epoch_seconds
        gap_counts = np.cumsum(np.r_[False, gaps])
        contiguous = gap_counts[window_size - 1 :] == gap_counts[: len(windows)]
    target_labels = labels[window_size - 1 :]
    class_indices = {int(stage): index for index, stage in enumerate(classes)}
    target_indices = np.asarray(
        [class_indices.get(int(value), -1) if np.isfinite(value) else -1 for value in target_labels]
    )
    keep = contiguous & (target_indices >= 0)
    selected_x = np.asarray(windows[keep], dtype=np.float32)
    selected_y = np.eye(len(classes), dtype=np.float32)[target_indices[keep]]
    if return_times:
        return selected_x, selected_y, timestamps[window_size - 1 :][keep]
    return selected_x, selected_y


@dataclass
class TrainedSleepModel:
    """Model plus fitted preprocessing metadata required for safe inference."""

    model: object
    scaler: object
    feature_columns: Tuple[str, ...]
    window_size: int
    epoch_seconds: int
    max_gap_seconds: float
    timestamp_offsets: Dict[str, float]
    classes: Tuple[int, ...] = SLEEP_STAGES


def build_lstm_model(
    input_shape: Tuple[int, int],
    num_classes: int = len(SLEEP_STAGES),
):
    """Build and compile a Conv1D + stacked-LSTM Keras classifier."""
    try:
        import tensorflow as tf
    except ImportError as exc:
        raise ImportError(
            "TensorFlow is required to build the model. Install tensorflow in this environment."
        ) from exc

    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=input_shape),
            tf.keras.layers.Conv1D(filters=64, kernel_size=3, padding="same", activation="relu"),
            tf.keras.layers.LSTM(64, return_sequences=True),
            tf.keras.layers.LSTM(32),
            tf.keras.layers.Dense(num_classes, activation="softmax"),
        ]
    )
    model.compile(
        optimizer=tf.keras.optimizers.Adam(),
        loss="categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def _scale_subject_frames(
    frames: Mapping[str, pd.DataFrame],
    train_ids: Sequence[str],
):
    try:
        from sklearn.preprocessing import StandardScaler
    except ImportError as exc:
        raise ImportError(
            "scikit-learn is required for StandardScaler. Install scikit-learn in this environment."
        ) from exc

    feature_columns = tuple(
        column for column in frames[train_ids[0]].columns if column not in {"timestamp", "label"}
    )
    if not feature_columns:
        raise ValueError("No sensor features were produced during preprocessing.")
    for subject_id, frame in frames.items():
        columns = tuple(column for column in frame.columns if column not in {"timestamp", "label"})
        if columns != feature_columns:
            raise ValueError(f"Sensor feature columns differ for subject {subject_id}.")

    scaler = StandardScaler()
    scaler.fit(
        pd.concat(
            [frames[subject_id][list(feature_columns)] for subject_id in train_ids],
            ignore_index=True,
        )
    )
    scaled_frames = {}
    for subject_id, frame in frames.items():
        scaled = frame.copy()
        scaled.loc[:, feature_columns] = scaler.transform(frame[list(feature_columns)])
        scaled_frames[subject_id] = scaled
    return scaled_frames, scaler, feature_columns


def _collect_windows(
    subject_ids: Sequence[str],
    frames: Mapping[str, pd.DataFrame],
    window_size: int,
    feature_columns: Sequence[str],
    epoch_seconds: int,
    classes: Sequence[int],
):
    x_parts, y_parts = [], []
    for subject_id in subject_ids:
        x_subject, y_subject = make_sliding_windows(
            frames[subject_id],
            window_size,
            feature_columns,
            epoch_seconds=epoch_seconds,
            classes=classes,
        )
        if len(x_subject):
            x_parts.append(x_subject)
            y_parts.append(y_subject)
    if not x_parts:
        return (
            np.empty((0, window_size, len(feature_columns)), dtype=np.float32),
            np.empty((0, len(classes)), dtype=np.float32),
        )
    if len(x_parts) == 1:
        return x_parts[0], y_parts[0]
    return np.concatenate(x_parts), np.concatenate(y_parts)


def _validated_offsets(timestamp_offsets: Optional[Mapping[str, float]]) -> Dict[str, float]:
    offsets = {name: float(value) for name, value in (timestamp_offsets or {}).items()}
    unexpected_offsets = set(offsets) - set(SENSOR_TYPES)
    if unexpected_offsets:
        raise ValueError(f"Offsets may only be set for sensors: {SENSOR_TYPES}")
    return offsets


def _drop_unaligned_subjects(
    merged_frames: Dict[str, pd.DataFrame],
    splits: Dict[str, List[str]],
    excluded_subjects: Mapping[str, str],
):
    if not merged_frames:
        raise ValueError("No subjects have usable sensor/label alignment.")
    filtered_splits = {
        split_name: [subject_id for subject_id in ids if subject_id in merged_frames]
        for split_name, ids in splits.items()
    }
    empty_splits = [name for name, ids in filtered_splits.items() if not ids]
    if empty_splits:
        raise ValueError(
            f"Alignment exclusions left empty subject split(s): {', '.join(empty_splits)}. "
            "Review the timestamp offsets and source files."
        )
    if excluded_subjects:
        warnings.warn(
            "Subjects excluded from training/evaluation due to unusable time alignment: "
            + ", ".join(sorted(excluded_subjects)),
            RuntimeWarning,
            stacklevel=2,
        )
    return filtered_splits


def _fit_merged_subject_frames(
    merged_frames: Mapping[str, pd.DataFrame],
    splits: Dict[str, List[str]],
    *,
    window_size: int,
    epoch_seconds: int,
    max_gap_seconds: float,
    offsets: Dict[str, float],
    excluded_subjects: Mapping[str, str],
    epochs: int,
    batch_size: int,
    verbose: int,
    callbacks=None,
):
    splits = _drop_unaligned_subjects(dict(merged_frames), splits, excluded_subjects)
    scaled_frames, scaler, feature_columns = _scale_subject_frames(
        merged_frames, splits["train"]
    )
    x_train, y_train = _collect_windows(
        splits["train"], scaled_frames, window_size, feature_columns, epoch_seconds, SLEEP_STAGES
    )
    x_validation, y_validation = _collect_windows(
        splits["validation"],
        scaled_frames,
        window_size,
        feature_columns,
        epoch_seconds,
        SLEEP_STAGES,
    )
    x_test, y_test = _collect_windows(
        splits["test"], scaled_frames, window_size, feature_columns, epoch_seconds, SLEEP_STAGES
    )
    if not len(x_train):
        raise ValueError("No valid training windows were produced.")
    if not len(x_test):
        raise ValueError("No valid test windows were produced.")

    model = build_lstm_model((window_size, len(feature_columns)))
    validation_data = (x_validation, y_validation) if len(x_validation) else None
    history = model.fit(
        x_train,
        y_train,
        validation_data=validation_data,
        epochs=epochs,
        batch_size=batch_size,
        verbose=verbose,
        shuffle=True,
        callbacks=callbacks,
    )
    trained = TrainedSleepModel(
        model=model,
        scaler=scaler,
        feature_columns=feature_columns,
        window_size=window_size,
        epoch_seconds=epoch_seconds,
        max_gap_seconds=max_gap_seconds,
        timestamp_offsets=offsets,
    )
    metrics = evaluate_sleep_stage_model(trained, x_test, y_test)
    metrics["excluded_subjects"] = dict(excluded_subjects)
    metrics["window_counts"] = {
        "train": int(len(x_train)),
        "validation": int(len(x_validation)),
        "test": int(len(x_test)),
    }
    return trained, history, metrics, splits


def train_sleep_stage_pipeline(
    subject_data: Mapping[str, Mapping[str, pd.DataFrame]],
    *,
    split_ids: Optional[Mapping[str, Sequence[str]]] = None,
    window_size: int = 30,
    epoch_seconds: int = 30,
    max_gap_seconds: float = 900,
    timestamp_offsets: Optional[Mapping[str, float]] = None,
    test_size: float = 0.2,
    validation_size: float = 0.15,
    random_state: int = 42,
    epochs: int = 20,
    batch_size: int = 64,
    verbose: int = 1,
):
    """Preprocess, train, and evaluate using subject-level data splits.

    Returns ``(trained_bundle, history, test_metrics, splits)``. The scaler is
    fitted only on training subjects, and test subjects are never used in model
    fitting or scaler estimation.
    """
    splits = split_subjects(
        subject_data.keys(),
        split_ids=split_ids,
        test_size=test_size,
        validation_size=validation_size,
        random_state=random_state,
    )
    offsets = _validated_offsets(timestamp_offsets)

    merged_frames = {}
    excluded_subjects = {}
    for subject_id, raw_frame in subject_data.items():
        try:
            merged_frames[subject_id] = merge_subject_data(
                raw_frame,
                subject_id=subject_id,
                epoch_seconds=epoch_seconds,
                max_gap_seconds=max_gap_seconds,
                timestamp_offsets=offsets,
            )
        except SubjectAlignmentError as exc:
            excluded_subjects[subject_id] = str(exc)
            warnings.warn(
                f"Excluding subject {subject_id} because its sensor data cannot be "
                f"aligned to the labels: {exc}",
                RuntimeWarning,
                stacklevel=2,
            )
    return _fit_merged_subject_frames(
        merged_frames,
        splits,
        window_size=window_size,
        epoch_seconds=epoch_seconds,
        max_gap_seconds=max_gap_seconds,
        offsets=offsets,
        excluded_subjects=excluded_subjects,
        epochs=epochs,
        batch_size=batch_size,
        verbose=verbose,
    )


def train_sleep_stage_directory(
    data_dir: str | Path,
    *,
    split_ids: Optional[Mapping[str, Sequence[str]]] = None,
    window_size: int = 30,
    epoch_seconds: int = 30,
    max_gap_seconds: float = 900,
    timestamp_offsets: Optional[Mapping[str, float]] = None,
    test_size: float = 0.2,
    validation_size: float = 0.15,
    random_state: int = 42,
    epochs: int = 20,
    batch_size: int = 64,
    verbose: int = 1,
):
    """Train from a data directory while loading one subject at a time."""
    paths = _discover_subject_paths(data_dir)
    splits = split_subjects(
        paths.keys(),
        split_ids=split_ids,
        test_size=test_size,
        validation_size=validation_size,
        random_state=random_state,
    )
    offsets = _validated_offsets(timestamp_offsets)
    merged_frames = {}
    excluded_subjects = {}
    for subject_id, subject_paths in paths.items():
        raw_subject = {
            data_type: _read_table(subject_paths[data_type])
            for data_type in DATA_TYPES
        }
        try:
            merged_frames[subject_id] = merge_subject_data(
                raw_subject,
                subject_id=subject_id,
                epoch_seconds=epoch_seconds,
                max_gap_seconds=max_gap_seconds,
                timestamp_offsets=offsets,
            )
        except SubjectAlignmentError as exc:
            excluded_subjects[subject_id] = str(exc)
            warnings.warn(
                f"Excluding subject {subject_id} because its sensor data cannot be "
                f"aligned to the labels: {exc}",
                RuntimeWarning,
                stacklevel=2,
            )
        del raw_subject

    return _fit_merged_subject_frames(
        merged_frames,
        splits,
        window_size=window_size,
        epoch_seconds=epoch_seconds,
        max_gap_seconds=max_gap_seconds,
        offsets=offsets,
        excluded_subjects=excluded_subjects,
        epochs=epochs,
        batch_size=batch_size,
        verbose=verbose,
    )


def evaluate_sleep_stage_model(
    trained_model: TrainedSleepModel,
    x_test: np.ndarray,
    y_test: np.ndarray,
) -> Dict[str, object]:
    """Report accuracy, macro F1, and a confusion matrix in stage order."""
    try:
        from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
    except ImportError as exc:
        raise ImportError(
            "scikit-learn is required for evaluation metrics. Install scikit-learn."
        ) from exc
    if not len(x_test) or not len(y_test):
        raise ValueError("Cannot evaluate an empty test set.")

    predicted_indices = np.asarray(trained_model.model.predict(x_test, verbose=0)).argmax(axis=1)
    true_indices = np.asarray(y_test).argmax(axis=1)
    true_stages = np.asarray(trained_model.classes)[true_indices]
    predicted_stages = np.asarray(trained_model.classes)[predicted_indices]
    labels = list(trained_model.classes)
    return {
        "accuracy": float(accuracy_score(true_stages, predicted_stages)),
        "macro_f1": float(
            f1_score(true_stages, predicted_stages, labels=labels, average="macro", zero_division=0)
        ),
        "confusion_matrix": confusion_matrix(true_stages, predicted_stages, labels=labels),
        "class_order": labels,
    }


def _inference_timeline(
    new_instance_dict: Mapping[str, pd.DataFrame],
    trained_model: TrainedSleepModel,
) -> pd.DataFrame:
    missing = set(SENSOR_TYPES) - set(new_instance_dict)
    if missing:
        raise ValueError(f"New subject data is missing sensors: {sorted(missing)}")
    ranges = []
    for data_type in SENSOR_TYPES:
        sensor = _numeric_sensor_frame(
            new_instance_dict[data_type],
            data_type,
            trained_model.timestamp_offsets.get(data_type, 0.0),
        )
        ranges.append((sensor["timestamp"].min(), sensor["timestamp"].max()))
    start = max(start for start, _ in ranges)
    end = min(end for _, end in ranges)
    first_epoch = np.ceil(start / trained_model.epoch_seconds) * trained_model.epoch_seconds
    last_epoch = np.floor(end / trained_model.epoch_seconds) * trained_model.epoch_seconds
    if first_epoch > last_epoch:
        raise ValueError(
            "The new subject's sensor streams have no shared timestamp range. "
            "Verify their time bases or set timestamp offsets when training."
        )
    return pd.DataFrame(
        {"epoch": np.arange(first_epoch, last_epoch + trained_model.epoch_seconds, trained_model.epoch_seconds),
         "label": 0}
    )


def predict_new_instance(
    new_instance_dict: Mapping[str, pd.DataFrame],
    trained_model: TrainedSleepModel,
) -> List[int]:
    """Predict an ordered sleep-stage sequence for a new subject.

    ``trained_model`` is the bundle returned by either training function;
    it carries the training-only scaler and window/preprocessing settings.
    Predictions correspond to each window's final epoch, so the first
    ``window_size - 1`` epochs do not have a prediction.
    """
    if not isinstance(trained_model, TrainedSleepModel):
        raise TypeError(
            "trained_model must be the TrainedSleepModel bundle returned by "
            "train_sleep_stage_pipeline, not a bare Keras model."
        )
    labels = _inference_timeline(new_instance_dict, trained_model)
    raw_subject = {
        **new_instance_dict,
        "labeled_sleep": labels,
    }
    merged = merge_subject_data(
        raw_subject,
        subject_id="new_subject",
        epoch_seconds=trained_model.epoch_seconds,
        max_gap_seconds=trained_model.max_gap_seconds,
        timestamp_offsets=trained_model.timestamp_offsets,
    )
    merged.loc[:, trained_model.feature_columns] = trained_model.scaler.transform(
        merged[list(trained_model.feature_columns)]
    )
    windows, _ = make_sliding_windows(
        merged,
        trained_model.window_size,
        trained_model.feature_columns,
        epoch_seconds=trained_model.epoch_seconds,
        classes=trained_model.classes,
    )
    if not len(windows):
        return []
    predicted_indices = np.asarray(trained_model.model.predict(windows, verbose=0)).argmax(axis=1)
    return np.asarray(trained_model.classes)[predicted_indices].astype(int).tolist()
