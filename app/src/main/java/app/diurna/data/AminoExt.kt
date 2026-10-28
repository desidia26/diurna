package app.diurna.data

import app.diurna.domain.AminoAcids

fun FoodEntryEntity.aminos(): AminoAcids = AminoAcids(
    hisMg, ileMg, leuMg, lysMg, metMg, pheMg, thrMg, trpMg, valMg, cysMg, tyrMg,
)

fun MealEntity.aminos(): AminoAcids = AminoAcids(
    hisMg, ileMg, leuMg, lysMg, metMg, pheMg, thrMg, trpMg, valMg, cysMg, tyrMg,
)

fun DateMacros.aminos(): AminoAcids = AminoAcids(
    hisMg, ileMg, leuMg, lysMg, metMg, pheMg, thrMg, trpMg, valMg, cysMg, tyrMg,
)
