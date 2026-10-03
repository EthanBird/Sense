package io.github.ethanbird.senseime.core

/**
 * Exact records for successively longer prefixes in the existing sorted byte table.
 * A range sharing n characters only needs its next byte compared. No trie copy,
 * mutable dictionary index or retained editor text is needed. Returned record IDs
 * belong to [offsets], including a non-contiguous base-only view of the same bytes.
 */
internal fun sortedPinyinPrefixRecords(
    data: ByteArray, offsets: IntArray, query: String, start: Int, end: Int,
): IntArray {
    require(start >= 0 && end >= start && end <= query.length)
    val records = IntArray(end - start + 1) { -1 }
    var lower = 0
    var upper = offsets.size
    for (position in start until end) {
        DecodeWorkScope.checkpoint()
        if (lower == upper) break
        val characterIndex = position - start
        val wanted = query[position].code
        var low = lower
        var high = upper
        while (low < high) {
            val middle = (low + high).ushr(1)
            val offset = offsets[middle]
            val length = data[offset].toInt() and 0xFF
            val value = if (length <= characterIndex) -1 else data[offset + 1 + characterIndex].toInt() and 0xFF
            if (value < wanted) low = middle + 1 else high = middle
        }
        lower = low
        high = upper
        while (low < high) {
            val middle = (low + high).ushr(1)
            val offset = offsets[middle]
            val length = data[offset].toInt() and 0xFF
            val value = if (length <= characterIndex) -1 else data[offset + 1 + characterIndex].toInt() and 0xFF
            if (value <= wanted) low = middle + 1 else high = middle
        }
        upper = low
        // A shorter exact code precedes all its extensions in lexicographic order.
        if (lower < upper && (data[offsets[lower]].toInt() and 0xFF) == characterIndex + 1) {
            records[characterIndex + 1] = lower
        }
    }
    return records
}
