package com.bitcoinobsessed.livewallpaper

import org.junit.Assert.*
import org.junit.Test

class IndicatorsTest {
    @Test fun wilderReference() {
        val closes=listOf(44.34,44.09,44.15,43.61,44.33,44.83,45.10,45.42,45.84,46.08,45.89,46.03,45.61,46.28,46.28)
        assertEquals(70.464135,Indicators.rsi(closes),0.00001)
        assertEquals(66.249619,Indicators.rsi(closes+46.00),0.00001)
    }
    @Test fun flatAndTrending() {
        assertEquals(50.0,Indicators.rsi(List(30){100.0}),0.0)
        assertEquals(100.0,Indicators.rsi(List(30){it+1.0}),0.0)
        assertEquals(0.0,Indicators.rsi(List(30){100.0-it}),0.0)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectShortHistory() { Indicators.rsi(listOf(1.0)) }
}
