"""Run subject-independent sleep-stage model training and evaluation."""

from pathlib import Path

from sleep_stage_pipeline import (
    TrainedSleepModel,
    load_subject_split_files,
    train_sleep_stage_directory,
)


DATA_DIR = Path(__file__).resolve().parent
WINDOW_SIZE = 30
EPOCHS = 20
BATCH_SIZE = 64


def main() -> TrainedSleepModel:
    """Train using the supplied subject IDs and print held-out test metrics."""
    split_ids = load_subject_split_files(DATA_DIR)
    trained_model, _, metrics, used_splits = train_sleep_stage_directory(
        DATA_DIR,
        split_ids=split_ids,
        window_size=WINDOW_SIZE,
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
    )

    print("\nSubject counts after alignment:")
    for split_name, subject_ids in used_splits.items():
        print(f"  {split_name}: {len(subject_ids)}")
    print("\nWindow counts:")
    for split_name, count in metrics["window_counts"].items():
        print(f"  {split_name}: {count}")
    print(f"\nTest accuracy: {metrics['accuracy']:.4f}")
    print(f"Test macro F1: {metrics['macro_f1']:.4f}")
    print(f"Confusion matrix (stage order {metrics['class_order']}):")
    print(metrics["confusion_matrix"])

    excluded = metrics["excluded_subjects"]
    if excluded:
        print("\nExcluded subjects (no reliable sensor/label alignment):")
        for subject_id, reason in sorted(excluded.items()):
            print(f"  {subject_id}: {reason}")

    print(
        "\nFor notebook use, assign the returned bundle with "
        "`trained_model = main()` and pass it to predict_new_instance()."
    )
    return trained_model


if __name__ == "__main__":
    main()