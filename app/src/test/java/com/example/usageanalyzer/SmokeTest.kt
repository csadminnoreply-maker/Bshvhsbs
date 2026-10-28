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
        assertEquals(3711.00, total, 0.01)
    }

    @Test fun modelTertinggiDiAwalSetelahSort() {
        val models = listOf(
            ModelData("Qwen3 Coder 30B A3B Instruct", 1450.20),
            ModelData("gpt-oss-120b", 980.50),
            ModelData("Laguna XS 2.1", 760.30),
            ModelData("Dots3-Note Preview", 520.00)
        )
        val sorted = models.sortedByDescending { it.usage }
        assertEquals("gpt-oss-120b", sorted[0].name)
        assertEquals("Dots3-Note Preview", sorted.last.name)
    }

    @Test fun persentaseDigunakanSamaDenganTotal100() {
        val models = listOf(
            ModelData("Qwen3 Coder 30B A3B Instruct", 1450.20),
            ModelData("gpt-oss-120b", 980.50),
            ModelData("Laguna XS 2.1", 760.30),
            ModelData("Dots3-Note Preview", 520.00)
        )
        val total = models.sumOf { it.usage }
        val persentase = models.map { (it.usage / total) * 100 }.sum()
        assertEquals(100.0, persentase, 0.01)
    }
}