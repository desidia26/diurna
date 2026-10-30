package app.diurna.ai

/**
 * Shared copy for models that can't take images. When the Setup toggle is off, photo food
 * estimates fall back to text; cardio photo reading refuses. When a 400 from the API still says
 * the model can't take images, [isUnsupportedImageError] maps it to a clear message.
 */
object VisionSupport {
    const val PHOTO_FEATURES_DISABLED =
        "This model can't read photos. Turn on \"Model supports images\" in Setup for a vision model, or enter the numbers by hand."

    const val API_REJECTED_IMAGE =
        "This model rejected the photo. Turn off \"Model supports images\" in Setup, or pick a vision model."

    fun noteWithoutPhoto(existing: String): String {
        val tag = "Photo not sent (model has no vision)"
        return listOf(existing.trim(), tag).filter { it.isNotEmpty() }.joinToString("; ")
    }

    fun isUnsupportedImageError(status: Int, message: String?): Boolean {
        if (status != 400 && status != 415 && status != 422) return false
        val m = message.orEmpty().lowercase()
        if (m.isEmpty()) return false
        val mentionsImage = "image" in m || "vision" in m || "multimodal" in m || "photo" in m
        val unsupported = "not support" in m || "unsupported" in m || "does not support" in m ||
            "can't support" in m || "cannot support" in m || "no vision" in m ||
            "invalid_image" in m || "image_url" in m && ("invalid" in m || "unknown" in m) ||
            "modality" in m
        return mentionsImage && unsupported || "invalid_image" in m || "image inputs" in m && "not" in m
    }
}
