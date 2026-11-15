# Eval image sources

All images in this folder were **generated for this repo** (Python/Pillow drawing code in the PR that added them). None are photographs of real people, meals, or commercial device screenshots.

| File | Purpose |
|------|---------|
| `food-synthetic-plate.jpg` | Abstract “plate” of colored shapes for a food+photo case |
| `cardio-rower-synthetic.jpg` | Fake rower summary screen with known numbers |
| `cardio-treadmill-synthetic.jpg` | Fake treadmill summary with known numbers |
| `edge-abstract-shapes.jpg` | Non-food / non-workout / non-label edge case |
| `label-cereal-synthetic.jpg` | Fake FDA Nutrition Facts panel (140 kcal cereal) for LabelPrompt |
| `menu-chipotle-synthetic.jpg` | Short synthetic restaurant menu for menu-photo food estimate |

License: CC0-equivalent — generated for Diurna evals; free to reuse in this project.

## Real package photos (`real/`)

Photos of packages Larry owns, contributed by him for evals and barcode tests, with his consent to publish them here. Each was cropped to the package (no people or home interior) and re-encoded from raw pixels with Pillow, so no EXIF, GPS, XMP, ICC or other metadata carries over. `RealFixturePrivacyTest` fails if any file here has an APPn (other than JFIF) or comment segment. The package artwork and text belong to the brands; the photos are used only as test inputs.

| File | Shows | Used by |
|------|-------|---------|
| `real/pecans.jpg` | Member's Mark pecan halves: panel, ingredients, partial barcode | `package-pecans-real`; barcode (uncertain) |
| `real/craisins-rotated.jpg` | Ocean Spray Craisins, rotated 90°: small panel, ingredients, small barcode | `package-craisins-rotated-real`; barcode (uncertain) |
| `real/flax-label.jpg` | Roots Circle flax seeds panel and ingredients | `package-flax-label-real` |
| `real/flax-barcode.jpg` | Same jar, barcode side (UPC 860009719569), no panel | `package-flax-barcode-side-real` (`no_panel`); barcode (reliable) |
| `real/almonds.jpg` | Mariani sliced almonds: panel, ingredients, barcode (UPC 076991000411) | `package-almonds-real`; barcode (reliable) |

### Adding another real photo

Only with the owner's consent. Crop to the package, then re-encode without metadata:

```bash
python3 -c "from PIL import Image; import sys; im=Image.open(sys.argv[1]).convert('RGB').crop(tuple(map(int,sys.argv[3].split(',')))); Image.frombytes('RGB', im.size, im.tobytes()).save(sys.argv[2], 'JPEG', quality=88, optimize=True)" in.jpg ai-evals/images/real/name.jpg LEFT,TOP,RIGHT,BOTTOM
```

Then add a `package` case to `cases/default.json` with bands read from the photo (mark hard-to-read fields `"required": false`), and, if it shows a barcode, add it to `RealPackageBarcodeTest` as reliable or uncertain. Run `./gradlew testDebugUnitTest` (the privacy test covers the new file).
