package io.github.ethanbird.senseime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class Wubi86KeyLegendTest {
    @Test
    fun zoneRootsAndReverseLookupKeyHaveStableLegends() {
        assertEquals("金", Wubi86KeyLegend.forKey('q'))
        assertEquals("工", Wubi86KeyLegend.forKey('a'))
        assertEquals("山", Wubi86KeyLegend.forKey('m'))
        assertEquals("反查", Wubi86KeyLegend.forKey('z'))
    }
}
