package io.github.ethanbird.senseime.brain.runtime

import java.util.ArrayDeque

/**
 * Multi-producer, single-consumer queue for Brain -> IME Messenger deliveries.
 *
 * Provider callbacks may arrive on transport threads while a frame-timed flush
 * runs on the Android main thread. The queue gives all of those producers one
 * total order and keeps exactly one main-thread drain scheduled. The consumer
 * deliberately leaves [drainScheduled] set while processing the last observed
 * item: a producer that races that processing is then picked up by the same
 * drain instead of being stranded without a callback.
 */
internal class BrainIpcSerialDeliveryQueue<T : Any> {
    private val queue = ArrayDeque<T>()
    private var drainScheduled = false

    /**
     * Returns true only when the caller must schedule the single consumer.
     */
    @Synchronized
    fun enqueue(delivery: T): Boolean {
        queue.addLast(delivery)
        if (drainScheduled) return false
        drainScheduled = true
        return true
    }

    /**
     * Returns the next item. Polling an empty queue transfers scheduling
     * responsibility back to the next producer.
     */
    @Synchronized
    fun poll(): T? {
        val delivery = queue.pollFirst()
        if (delivery == null) drainScheduled = false
        return delivery
    }

    @Synchronized
    fun removeAll(predicate: (T) -> Boolean) {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        queue.clear()
        drainScheduled = false
    }
}
