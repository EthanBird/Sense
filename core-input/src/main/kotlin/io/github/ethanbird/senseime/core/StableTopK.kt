package io.github.ethanbird.senseime.core

/** Exactly sortedWith(order).take(limit), including stable ties; O(k) retained heap indices. */
internal fun <T> stableTopK(values: List<T>, limit: Int, order: Comparator<in T>): List<T> {
    if (limit <= 0 || values.isEmpty()) return emptyList()
    if (values.size <= limit) return values.sortedWith(order)
    val heap = IntArray(limit)
    var size = 0
    fun compare(a: Int, b: Int): Int {
        val comparison = order.compare(values[a], values[b])
        return if (comparison != 0) comparison else a.compareTo(b)
    }
    fun sink() {
        var parent = 0
        while (parent * 2 + 1 < size) {
            var child = parent * 2 + 1
            if (child + 1 < size && compare(heap[child + 1], heap[child]) > 0) child++
            if (compare(heap[parent], heap[child]) >= 0) return
            val swap = heap[parent]; heap[parent] = heap[child]; heap[child] = swap
            parent = child
        }
    }
    values.indices.forEach { index ->
        if (size < limit) {
            var child = size++
            heap[child] = index
            while (child > 0) {
                val parent = (child - 1) / 2
                if (compare(heap[child], heap[parent]) <= 0) break
                val swap = heap[parent]; heap[parent] = heap[child]; heap[child] = swap
                child = parent
            }
        } else if (compare(index, heap[0]) < 0) {
            heap[0] = index
            sink()
        }
    }
    val output = ArrayList<T>(size)
    while (size > 0) {
        output += values[heap[0]]
        size--
        if (size > 0) { heap[0] = heap[size]; sink() }
    }
    output.reverse()
    return output
}
