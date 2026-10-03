package io.github.ethanbird.senseime.core

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class StableTopKTest {
    private data class Item(val score: Float, val text: String, val index: Int)
    private val order = Comparator<Item> { a, b ->
        val score = java.lang.Float.compare(b.score, a.score)
        if (score != 0) score else a.text.compareTo(b.text)
    }
    @Test fun heapSelectionMatchesTheStableFullSortIncludingTiesAndFloatOrdering() {
        val random = Random(7741)
        val special = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0f)
        repeat(200) { round ->
            val values = List(random.nextInt(300)) { index ->
                Item(if (round % 10 == 0) special[index % special.size] else random.nextInt(11).toFloat(),
                    "text${random.nextInt(4)}", index)
            }
            for (limit in listOf(0, 1, 6, 16, 64, 255, 320)) {
                assertEquals("round=$round limit=$limit", values.sortedWith(order).take(limit), stableTopK(values, limit, order))
            }
        }
    }
    @Test fun orderedReverseAndAllEqualInputsKeepTheirExactStableOrder() {
        for (values in listOf(List(255) { Item(it.toFloat(), "", it) }, List(255) { Item(-it.toFloat(), "", it) },
            List(255) { Item(1f, "same", it) })) {
            assertEquals(values.sortedWith(order).take(16), stableTopK(values, 16, order))
        }
    }
}
