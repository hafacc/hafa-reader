package cc.hafa.reader.core.sync

private val GOOGLE_SIZE_SUFFIX = Regex("=s\\d+(-c)?$")
private const val GOOGLE_PHOTO_HOST = ".googleusercontent.com"

/**
 * The address to load an account photo from, [sizePx] pixels square: https
 * only (empty otherwise), and for a Google photo, its size suffix (such as
 * `=s96-c`) set to that size so no larger picture is downloaded.
 */
fun avatarPhotoAddress(photoUrl: String, sizePx: Int): String {
    val address = photoUrl.trim()
    val host = address.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
    return if (!address.startsWith("https://", ignoreCase = true) || host.isEmpty()) {
        ""
    } else if (!host.endsWith(GOOGLE_PHOTO_HOST) || '?' in address || '#' in address) {
        address
    } else if (GOOGLE_SIZE_SUFFIX.containsMatchIn(address)) {
        address.replace(GOOGLE_SIZE_SUFFIX, "=s$sizePx-c")
    } else if ('=' in address.substringAfterLast('/')) {
        address
    } else {
        "$address=s$sizePx-c"
    }
}
