# UsageAnalyzer

Proyek Android untuk menganalisis dan membandingkan Total Usage ($) berbagai model AI, berdasarkan data dari screenshot lampiran.

## Data Model yang Dianalisis
| Model | Total Usage ($) |
|-------|-----------------|
| Qwen3 Coder 30B A3B Instruct | 1450.20 |
| gpt-oss-120b | 980.50 |
| Laguna XS 2.1 | 760.30 |
| Dots3-Note Preview | 520.00 |

## Fitur
- Menampilkan total penggunaan semua model
- Peringkat model dari penggunaan tertinggi ke terendah
- Persentase kontribusi tiap model terhadap total
- Visualisasi sederhana dengan karakter blok (█)
- Analisis perbedaan usage antar model

## Struktur
- `app/src/main/java/com/example/usageanalyzer/MainActivity.kt` : source Kotlin utama
- `app/src/test/java/com/example/usageanalyzer/SmokeTest.kt` : unit test
- `.github/workflows/build-apk.yml` : build APK debug di GitHub Actions
- `info.json` : metadata

## Build
Pastikan `gradle/wrapper/gradle-wrapper.properties` dan `gradle/wrapper/gradle-wrapper.jar` tersedia, kemudian jalankan `./gradlew assembleDebug` atau gunakan tool `build_apk`.