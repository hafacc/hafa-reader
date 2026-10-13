package cc.hafa.reader.core

import org.jsoup.Jsoup

/**
 * Whether an article's lead image is shown above its body: only when the
 * body does not already show it, compared after decoding entities in both
 * the stored address and the body's picture addresses.
 */
fun showsLeadImage(imageUrl: String, bodyHtml: String): Boolean {
    val address = decodeHtmlEntities(imageUrl)
    return if (address.isEmpty()) {
        false
    } else {
        val written = address in bodyHtml || address.replace("&", "&amp;") in bodyHtml
        val pictures = Jsoup.parseBodyFragment(bodyHtml).select("img[src], source[srcset], img[srcset]")
            .flatMap { picture -> listOf(picture.attr("src")) + srcsetAddresses(picture.attr("srcset")) }
        !written && pictures.none { picture -> picture == address }
    }
}

/** One picture a `srcset` offers: its address and its width or density descriptor. */
data class SrcsetCandidate(
    /** Picture address, which may hold commas (as `data:` addresses do). */
    val address: String,
    /** Width (`640w`) or density (`2x`); empty when none is given. */
    val descriptor: String,
)

/**
 * The candidates of a `srcset`, read as browsers do: an address is a run of
 * non-space characters (a trailing comma ends it), then an optional
 * descriptor up to the next comma.
 */
fun srcsetCandidates(srcset: String): List<SrcsetCandidate> {
    val found = mutableListOf<SrcsetCandidate>()
    var index = 0
    while (index < srcset.length) {
        while (index < srcset.length && (srcset[index].isWhitespace() || srcset[index] == ',')) {
            index += 1
        }
        val start = index
        while (index < srcset.length && !srcset[index].isWhitespace()) {
            index += 1
        }
        var address = srcset.substring(start, index)
        var descriptor = ""
        if (address.endsWith(",")) {
            address = address.trimEnd(',')
        } else {
            val end = srcset.indexOf(',', index).let { comma -> if (comma == -1) srcset.length else comma }
            descriptor = srcset.substring(index, end).trim()
            index = end
        }
        if (address.isNotEmpty()) {
            found.add(SrcsetCandidate(address, descriptor))
        }
    }
    return found
}

/** A `srcset` written back from its candidates. */
fun joinSrcset(candidates: List<SrcsetCandidate>): String =
    candidates.joinToString(", ") { candidate -> listOf(candidate.address, candidate.descriptor).filter(String::isNotEmpty).joinToString(" ") }

/** The addresses a `srcset` lists. */
fun srcsetAddresses(srcset: String): List<String> = srcsetCandidates(srcset).map { candidate -> candidate.address }

/** The largest picture a `srcset` offers, by its width or density; empty if none. */
fun bestSrcsetCandidate(srcset: String): String =
    srcsetCandidates(srcset)
        .filter { candidate -> !candidate.address.startsWith("data:") }
        .maxByOrNull { candidate ->
            val descriptor = candidate.descriptor.ifEmpty { "1x" }
            val size = descriptor.dropLast(1).toDoubleOrNull() ?: 1.0
            if (descriptor.endsWith("w")) size else size * 1000
        }
        ?.address ?: ""

/** Most pictures fetched ahead for one article, its lead picture included. */
const val PICTURES_PER_ARTICLE = 4

/**
 * The pictures an article shows first, at the addresses the article view
 * loads: its lead picture when the body does not show it, then the body's
 * first pictures (the largest `srcset` candidate, else `src`); https only,
 * at most [limit]. [body] must be the sanitized body the article shows.
 */
fun aheadPictures(imageUrl: String, body: String, limit: Int = PICTURES_PER_ARTICLE): List<String> {
    val lead = decodeHtmlEntities(imageUrl).takeIf { address -> showsLeadImage(imageUrl, body) && isSecure(address) }
    val pictures = Jsoup.parseBodyFragment(body).select("img").asSequence()
        .map { image -> bestSrcsetCandidate(image.attr("srcset")).ifEmpty { image.absUrl("src").ifEmpty { image.attr("src") } } }
        .filter(::isSecure)
    return (listOfNotNull(lead).asSequence() + pictures).take(limit).toList()
}

private fun isSecure(address: String): Boolean = address.startsWith("https://", ignoreCase = true)

/** The articles on either side of an open one. */
data class Neighbors(
    /** The next article in the way the reader is moving; null at the end. */
    val ahead: Entry?,
    /** The article on the other side; null at the end. */
    val behind: Entry?,
)

/** The rows on either side of [openId]; [heading] is 1 when moving down the list, -1 when moving up. */
fun neighborsOf(rows: List<Entry>, openId: String, heading: Int): Neighbors {
    val index = rows.indexOfFirst { row -> row.id == openId }
    return if (index == -1) {
        Neighbors(null, null)
    } else {
        Neighbors(rows.getOrNull(index + heading), rows.getOrNull(index - heading))
    }
}
