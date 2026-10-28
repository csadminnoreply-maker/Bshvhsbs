package com.example.usageanalyzer

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = buildUsageReport()
            textSize = 16f
            setPadding(48, 48, 48, 48)
        })
    }

    private fun buildUsageReport(): String {
        val models = listOf(
            ModelData("Qwen3 Coder 30B A3B Instruct", 1450.20),
            ModelData("gpt-oss-120b", 980.50),
            ModelData("Laguna XS 2.1", 760.30),
            ModelData("Dots3-Note Preview", 520.00)
        )

        val total = models.sumOf { it.usage }
        val sorted = models.sortedByDescending { it.usage }

        val sb = StringBuilder()
        sb.appendLine("UsageAnalyzer - Perbandingan Penggunaan Model AI")
        sb.appendLine("=".repeat(50))
        sb.appendLine("")
        sb.appendLine("Total Usage: $%.2f".format(total))
        sb.appendLine("")
        sb.appendLine("Peringkat Model (dari tertinggi ke terendah):")
        sb.appendLine("")

        sorted.forEachIndexed { index, model ->
            val percentage = (model.usage / total) * 100
            val bar = "█".repeat((percentage / 5).toInt())
            sb.appendLine("${index + 1}. ${model.name}")
            sb.appendLine("   Usage: $%.2f (%.1f%%)".format(model.usage, percentage))
            sb.appendLine("   $bar")
            sb.appendLine("")
        }

        sb.appendLine("=".repeat(50))
        sb.appendLine("Analisis:")
        sb.appendLine("- Model dengan penggunaan tertinggi: ${sorted[0].name} (${String.format("%.1f%%", sorted[0].usage / total * 100)})")
        sb.appendLine("- Perbedaan antara tertinggi dan terendah: $%.2f".format(sorted[0].usage - sorted.last().usage))
        sb.appendLine("- Rata-rata penggunaan per model: $%.2f".format(total / models.size))

        return sb.toString()
    }
}

data class ModelData(val name: String, val usage: Double)