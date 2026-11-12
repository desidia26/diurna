# Eval image sources

Most images in this folder were **generated for this repo** (Python/Pillow drawing code in the PR that added them). The food-plate photo is a public-domain (CC0) photograph, cropped and re-encoded as described below.

| File | Purpose |
|------|---------|
| `food-plate-chicken-asparagus.jpg` | Realistic plated lunch (roast chicken, asparagus, mashed potatoes) for the food+photo case |
| `cardio-rower-synthetic.jpg` | Fake rower summary screen with known numbers |
| `cardio-treadmill-synthetic.jpg` | Fake treadmill summary with known numbers |
| `edge-abstract-shapes.jpg` | Non-food / non-workout / non-label edge case |
| `label-cereal-synthetic.jpg` | Fake FDA Nutrition Facts panel (140 kcal cereal) for LabelPrompt |
| `menu-chipotle-synthetic.jpg` | Short synthetic restaurant menu for menu-photo food estimate |

Generated images are CC0-equivalent — made for Diurna evals; free to reuse in this project.

## `food-plate-chicken-asparagus.jpg`

A CC0 photograph of a plated lunch: roast chicken with pan sauce, grilled asparagus, and mashed potatoes in a small skillet. Cropped to the plate so a person visible at the edge of the original is not in the committed file, resized (long side 960 px, in line with the other eval images), and re-encoded from raw pixels with Pillow so no EXIF, GPS, XMP, ICC or comment metadata carries over. `EvalImagePrivacyTest` fails if any JPEG under this folder has an APPn (other than JFIF) or comment segment.

| | |
|---|---|
| File page | https://commons.wikimedia.org/wiki/File:Chicken_and_asparagus_and_mashed_potatoes_served_on_a_plate_at_a_restaurant_in_Maryland.JPG |
| Author | Tomwsulcer |
| License | [CC0 1.0 Universal Public Domain Dedication](https://creativecommons.org/publicdomain/zero/1.0/), verified on the file page |
| Description on Commons | “Lunch dish at a restaurant at National Harbor in Maryland.” (21 July 2014) |
| Used by | `food-photo-plate-real` |

Calorie bands for that case are a wide estimate of this plate (skin-on roast chicken, gravy, a skillet of mashed potatoes, asparagus), not an exact restaurant menu value.
