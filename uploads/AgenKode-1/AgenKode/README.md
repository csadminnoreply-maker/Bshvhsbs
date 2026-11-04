# AgenKode

Aplikasi Android berbentuk chat: Anda ngobrol dengan AI, AI menulis kode, mengujinya sendiri,
lalu membangun APK yang bisa langsung dipasang. Bisa memakai Claude, GPT, Gemini, DeepSeek, Groq,
Mistral, OpenRouter, atau Ollama (pilih saat chat).

## Cara membangun APK AgenKode (pilih salah satu)

### A. Android Studio (paling mudah, perlu komputer)
1. Ekstrak ZIP ini, buka Android Studio, pilih **Open** lalu pilih folder `AgenKode`.
2. Tunggu sinkronisasi Gradle selesai (pertama kali agak lama, perlu internet).
3. Menu **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. File ada di `app/build/outputs/apk/debug/app-debug.apk`. Kirim ke HP lalu pasang.

### B. GitHub Actions (tanpa Android Studio)
1. Buat repo baru di GitHub, unggah seluruh isi folder ini (pastikan folder `.github` ikut).
2. Buka tab **Actions** > **Build AgenKode APK** > **Run workflow**.
3. Setelah hijau, unduh **AgenKode-APK** di bagian Artifacts, ekstrak, pasang `app-debug.apk`.

## Pengaturan pertama di aplikasi
1. Ikon gerigi > isi API key provider AI Anda dan pilih satu sebagai aktif.
2. Untuk fitur Build APK: buat token GitHub (classic) di github.com/settings/tokens
   dengan izin **repo** dan **workflow**, tempel di Pengaturan > GitHub.
3. Proyek baru > ceritakan aplikasi yang diinginkan.

## Cara kerja build APK dari dalam aplikasi
Kode proyek diunggah ke repo PRIVAT milik Anda (dibuat otomatis, default `agenkode-builds`),
GitHub Actions membangunnya, lalu APK diunduh dan muncul tombol **Install** di chat.
Keystore penanda tangan dibuat sekali dan dipakai terus, jadi update aplikasi tidak perlu uninstall.

## Catatan
- Pertama kali memasang APK, Android meminta izin "pasang aplikasi dari sumber ini".
- Biarkan AgenKode terbuka saat build berjalan (2-6 menit).
- Jenis proyek bawaan: aplikasi web (HTML/CSS/JS) dibungkus WebView. Proyek Kotlin native
  didukung, tetapi build-nya lebih rawan error.
