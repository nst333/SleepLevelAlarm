import unittest

import numpy as np
import pandas as pd

from sleep_stage_pipeline import make_sliding_windows


class SlidingWindowsTests(unittest.TestCase):
    def test_gaps_and_unmapped_labels(self):
        frame = pd.DataFrame(
            {
                "timestamp": [0, 30, 60, 120, 150, 180, np.nan, 210, 240, 270],
                "label": [0, -1, 2, 3, 4, 5, 1, 2, 3, 4],
                "sensor": np.arange(10),
            }
        )

        windows, labels, times = make_sliding_windows(frame, 3, ["sensor"], return_times=True)

        np.testing.assert_array_equal(windows[:, :, 0], [[0, 1, 2], [3, 4, 5]])
        np.testing.assert_array_equal(labels.argmax(axis=1), [2, 4])
        np.testing.assert_array_equal(times, [60, 180])

        windows, _ = make_sliding_windows(frame, 1, ["sensor"])

        np.testing.assert_array_equal(windows[:, 0, 0], [0, 2, 3, 5, 6, 7, 8])
