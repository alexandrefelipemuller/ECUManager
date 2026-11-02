package com.speeduino.manager.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class BaseMapAdapterTest {
    @Test fun reshapeKeepsSameShape() {
        val values = listOf(listOf(1, 2), listOf(3, 4))
        val r = BaseMapAdapter.reshape(listOf(1000, 2000), listOf(30, 60), values, 2, 2)
        assertEquals(values, r.third)
    }

    @Test fun reshapeResamplesAxesAndInterpolates() {
        val r = BaseMapAdapter.reshape(listOf(1000, 3000), listOf(20, 100), listOf(listOf(10, 30), listOf(50, 70)), 3, 3)
        assertEquals(listOf(1000, 2000, 3000), r.first)
        assertEquals(listOf(20, 60, 100), r.second)
        assertEquals(10, r.third.first().first())
        assertEquals(70, r.third.last().last())
        assertEquals(40, r.third[1][1]) // centro = média dos quatro cantos
    }
}
