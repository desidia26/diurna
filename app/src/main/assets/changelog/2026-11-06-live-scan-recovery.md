# Keep successful live barcode reads
A blurry saved photo no longer throws away a valid live-camera read. If the saved photo cannot
verify it, check the printed numbers and explicitly select the matching candidate. Conflicting
reads also ask you to choose; retaking or leaving the scanner clears old candidates.
OCR now groups nearby aligned digit fragments without guessing digits. Debug reports distinguish
photo failures from live recovery and report detector/filter counts without retaining label text.
