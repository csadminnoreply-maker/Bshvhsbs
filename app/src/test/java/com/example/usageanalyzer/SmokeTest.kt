package com.example.usageanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {

    @Test fun proyekDasarAktif() { assertEquals(true, true) }

    @Test fun hitungTotalUsageDenganBenar() {
        val models = listOf(
            ModelData("Qwen3 Coder 30B A3B Instruct", 1450.20),
            ModelData("gpt-oss-120b", 980.50),
            ModelData("Laguna XS 2.1", 760.30),
            ModelData("Dots3-Note Preview", 520.00)
        )
        val total = models.sumOf { it.usage }
        val sorted = models.sortedByDescending { it.usage }

        assertEquals(3711.0, total, 0.01)
        assertEquals("Qwen3 Coder 30B A3B Instruct", sorted[0].name)
        assertEquals("Dots3-Note Preview", sorted.last().name)
        assertEquals(1450.20, sorted[0].usage, 0.01)
        assertEquals(520.00, sorted.last().usage, 0.01)
    }

    @Test fun hitungRataRataUsage() {
        val models = listOf(
            ModelData("Qwen3 Coder 30B A3B Instruct", 1450.20),
            ModelData("gpt-oss-120b", 980.50),
            ModelData("Laguna XS 2.1", 760.30),
            ModelData("Dots3-Note Preview", 520.00)
        )
        val total = models.sumOf { it.usage }
        val rataRata = total / models.size
        assertEquals(927.75, rataRata, 0.01)
    }

    @Test fun hitungPerbedaanTertinggiTerendah() {
        val models = listOf(
            ModelData("Qwen3 Coder 30B A3B Instruct", 1450.20),
            ModelData("gpt-oss-120b", 980.50),
            ModelData("Laguna XS 2.1", 760.30),
            ModelData("Dots3-Note Preview", 520.00)
        )
        val sorted = models.sortedByDescending { it.usage }
        val selisih = sorted[0].usage - sorted.last().usage
        assertEquals(930.20, selisih, 0.01)
    }
}