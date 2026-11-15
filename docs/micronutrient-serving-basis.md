# OFF micronutrient units

Meal nutrient rows remain per OffSnapshot.basisGrams, not per serving. Raw `_100g` micros are normalized from 100 g; raw `_serving` micros are normalized from servingGrams(). Logging/backfill/explicit serving edits derive the eaten profile from the frozen entry snapshot using servingGrams × servings. They do not blindly scale cached meal rows, including old preview rows with ambiguous units. No AI micros are generated.

Saving/editing a meal does not overwrite existing meal profiles or historical entries. New logs use the new meal snapshot, while servings edits retain the entry snapshot. Backfill is idempotent and never replaces existing entry rows. Deleting a meal still permits explicit servings edits by scaling the entry's existing rows, preserving source and missing-versus-zero.

No Room schema or migration changes are needed; v17-v20 lineage is unchanged. Previously logged incorrect preview amounts are not silently repaired: existing history remains frozen until an explicit entry serving edit/relog. A bulk repair would need a separately approved history policy.

JVM tests exercise units, non-100g bases, portions, missing/zero and snapshot encode/decode. Room tests exercise saved-meal logging, edits, deletion, relogging, backfill, stale cached meal rows and frozen snapshots; these execute on CI emulators only.
