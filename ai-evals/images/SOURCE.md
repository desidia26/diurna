# Eval image sources

Images in this folder were **generated for this repo**, except the `*-real.jpg` files listed under *Real photos* below (Python/Pillow drawing code in the PR that added them). None are photographs of real people, meals, or commercial device screenshots.

| File | Purpose |
|------|---------|
| `food-synthetic-plate.jpg` | Abstract “plate” of colored shapes for a food+photo case |
| `cardio-rower-synthetic.jpg` | Fake rower summary screen with known numbers |
| `cardio-treadmill-synthetic.jpg` | Fake treadmill summary with known numbers |
| `edge-abstract-shapes.jpg` | Non-food / non-workout / non-label edge case |
| `label-cereal-synthetic.jpg` | Fake FDA Nutrition Facts panel (140 kcal cereal) for LabelPrompt |
| `menu-chipotle-synthetic.jpg` | Short synthetic restaurant menu for menu-photo food estimate |

## Real photos

Cropped from photos in debug report ea12351c (Nov 2026), which Larry sent from his own phone and which reproduces the bug,, with all metadata (EXIF, GPS, timestamps) stripped. They show retail packaging only: no people, places or other personal details.

| File | Purpose |
|------|---------|
| `links-barcode-real.jpg` | Barcode side of a plant-based link package; no nutrition text. Sent first, as in the report |
| `links-label-real.jpg` | The same package's Nutrition Facts panel: 1 link (43 g), 60 kcal. `food-photos-barcode-then-label` expects 3 links ≈ 180 kcal |

License (generated images): CC0-equivalent — generated for Diurna evals; free to reuse in this project.
