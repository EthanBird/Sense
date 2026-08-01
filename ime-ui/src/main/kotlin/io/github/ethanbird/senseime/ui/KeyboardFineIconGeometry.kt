package io.github.ethanbird.senseime.ui

/**
 * Normalized line geometry shared by the renderer and JVM tests.
 *
 * Callers provide retained scratch storage, so resolving and drawing frequent
 * editing/navigation icons remains allocation-free.
 */
internal object KeyboardFineIconGeometry {
    const val MAX_SEGMENTS = 9

    fun writeSegments(icon: Icon, output: FloatArray): Int {
        require(output.size >= MAX_SEGMENTS * FLOATS_PER_SEGMENT)
        return when (icon) {
            Icon.DELETE -> delete(output)
            Icon.ENTER -> enter(output)
            Icon.UP -> up(output)
            Icon.DOWN -> down(output)
            Icon.LEFT -> left(output)
            Icon.RIGHT -> right(output)
            Icon.HOME -> home(output)
            Icon.END -> end(output)
            else -> 0
        }
    }

    private fun delete(out: FloatArray): Int {
        segment(out, 0, -7.5f, 0f, -3f, -5.5f)
        segment(out, 1, -3f, -5.5f, 7f, -5.5f)
        segment(out, 2, 7f, -5.5f, 8f, -4.5f)
        segment(out, 3, 8f, -4.5f, 8f, 4.5f)
        segment(out, 4, 8f, 4.5f, 7f, 5.5f)
        segment(out, 5, 7f, 5.5f, -3f, 5.5f)
        segment(out, 6, -3f, 5.5f, -7.5f, 0f)
        segment(out, 7, 1.1f, -2.5f, 5f, 2.5f)
        segment(out, 8, 5f, -2.5f, 1.1f, 2.5f)
        return 9
    }

    private fun enter(out: FloatArray): Int {
        segment(out, 0, 6.5f, -6f, 6.5f, 1f)
        segment(out, 1, 6.5f, 1f, 2.5f, 4.5f)
        segment(out, 2, 2.5f, 4.5f, -6.5f, 4.5f)
        segment(out, 3, -2.5f, 0.7f, -6.5f, 4.5f)
        segment(out, 4, -6.5f, 4.5f, -2.5f, 8.2f)
        return 5
    }

    private fun up(out: FloatArray): Int {
        segment(out, 0, -4.5f, -1.5f, 0f, -6f)
        segment(out, 1, 0f, -6f, 4.5f, -1.5f)
        segment(out, 2, 0f, -6f, 0f, 6.5f)
        return 3
    }

    private fun down(out: FloatArray): Int {
        segment(out, 0, -4.5f, 1.5f, 0f, 6f)
        segment(out, 1, 0f, 6f, 4.5f, 1.5f)
        segment(out, 2, 0f, 6f, 0f, -6.5f)
        return 3
    }

    private fun left(out: FloatArray): Int {
        segment(out, 0, -1.5f, -4.5f, -6f, 0f)
        segment(out, 1, -6f, 0f, -1.5f, 4.5f)
        segment(out, 2, -6f, 0f, 6.5f, 0f)
        return 3
    }

    private fun right(out: FloatArray): Int {
        segment(out, 0, 1.5f, -4.5f, 6f, 0f)
        segment(out, 1, 6f, 0f, 1.5f, 4.5f)
        segment(out, 2, 6f, 0f, -6.5f, 0f)
        return 3
    }

    private fun home(out: FloatArray): Int {
        segment(out, 0, -6.5f, -6f, -6.5f, 6f)
        segment(out, 1, -1.5f, -4.2f, -5.7f, 0f)
        segment(out, 2, -5.7f, 0f, -1.5f, 4.2f)
        segment(out, 3, -5.7f, 0f, 6.5f, 0f)
        return 4
    }

    private fun end(out: FloatArray): Int {
        segment(out, 0, 6.5f, -6f, 6.5f, 6f)
        segment(out, 1, 1.5f, -4.2f, 5.7f, 0f)
        segment(out, 2, 5.7f, 0f, 1.5f, 4.2f)
        segment(out, 3, 5.7f, 0f, -6.5f, 0f)
        return 4
    }

    private fun segment(
        output: FloatArray,
        index: Int,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        val offset = index * FLOATS_PER_SEGMENT
        output[offset] = x1
        output[offset + 1] = y1
        output[offset + 2] = x2
        output[offset + 3] = y2
    }

    private const val FLOATS_PER_SEGMENT = 4
}
