# Vantra Terminal V3 — Stage 1

## Hasil

Terminal kini memakai satu bidang dokumen editor. Baris tidak lagi diberi sudut dan kedalaman masing-masing. Panel kode terpisah, lantai perspektif, vignette dan glow luas tidak lagi digambar. Background tetap hitam.

- Hierarki monospace: lirik 18sp / kode 13sp di portrait; lirik 16sp / kode 12sp di landscape, sebelum penyesuaian viewport sempit dan proyeksi kamera.
- Nomor baris tetap tampil dalam mode Low. Gutter, indent guides, baseline, highlight aktif dan caret tipis mengikuti transformasi dokumen yang sama.
- Teks lirik asli tampil sebagai string/argumen dalam template kode deterministik. Kata yang belum diketik tetap terbaca; typing mengatur caret dan penegasan warna. Lirik sekitar ditampilkan lebih redup. Editor melipat scaffolding kode yang jauh untuk mempertahankan konteks lirik sebelumnya dan berikutnya pada landscape pendek; nomor baris yang melompat menunjukkan bagian yang dilipat.
- Wrapping mempertimbangkan pengukuran font Android, termasuk fallback glyph. Pemenggalan dan reveal tidak memotong surrogate pair atau combining sequence yang dikenali character iterator.
- Baris lirik yang membungkus normal ditahan pada fokus yang sama. Lirik yang lebih tinggi dari viewport mengikuti caret.
- Area dokumen menghindari header, inset dan kontrol playback. Landscape memakai dokumen lebih lebar tanpa memaksa orientasi seluruh aplikasi.
- Tanpa lirik sinkron, tampil dokumen status `no synced lyrics`. Judul/artis tetap metadata di header, bukan lirik buatan.
- Syntax palette lebih terkendali: deklarasi biru, flow coral, fungsi amber, lirik putih. Lexer mencakup tambahan `finally`, `override`, `private`, `public`.

## Source yang diperiksa

Source aktif adalah `app/src/main/kotlin/com/vantra/music`; dispatcher `ui/player/styles/AlternatePlayerStyles.kt` mengarahkan `PlayerStyle.TERMINAL` ke `TerminalPlayerStyle`.

- Renderer: `ui/player/terminal/TerminalRenderer.kt`, native `android.graphics.Canvas` di dalam Compose `drawBehind`.
- Kamera/proyeksi: `TerminalCamera.kt` (`Spring`, `TerminalCamera`, `TerminalProjector`), dipertahankan tanpa perubahan.
- Timing: `rememberStyleLyrics`, `rememberLivePosition().withLyricsOffset()`, `TerminalDocument.locate`, clock Media3 dan data `currentLyrics` yang sudah ada.
- Beat: `BeatTracker.kt` dan spectrum service yang sudah ada; tidak diganti.
- Pemilihan style: `PlayerStyle.TERMINAL`, pengaturan intensity/beat/typing/quality/orientation dalam PreferenceKeys dan TerminalOptions; tidak diganti.
- Orientasi: `StyleFrame`, `rememberDeviceSideways`, override rotasi dan immersive landscape yang sudah ada.
- Dependensi rendering: Compose + platform Android graphics/monospace, Coil untuk artwork. Tidak ada dependensi aplikasi baru.

Rekaman masukan 58,3 detik ditinjau sepanjang durasinya melalui rangkaian frame: masuk Terminal, playback landscape, pergantian lirik, typing/wrapping, kontrol yang menghilang/muncul, hingga keluar ke beranda. Rekaman ini menunjukkan versi sebelum perubahan; bukan bukti hasil render versi baru.

## Changed files

Di bawah root proyek `vantra--main/`:

1. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalRenderer.kt`
2. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalDocument.kt`
3. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalSyntax.kt`
4. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalPlayerStyle.kt`
5. `app/src/test/kotlin/com/vantra/music/ui/player/terminal/TerminalCoreTest.kt`

Added:

6. `.github/workflows/terminal-stage1-check.yml`
7. `docs/terminal-v3/STAGE1-PLAN.md`
8. `docs/terminal-v3/STAGE1-REPORT.md`

## Verifikasi

- **Lulus:** kompilasi Kotlin core Terminal, camera, beat tracker, ambient data dan renderer menggunakan compiler Kotlin **2.3.10** (sama dengan version catalog proyek) serta stub API Android. Ini bukan build Android/Compose lengkap.
- **Lulus:** **29 tes JUnit TerminalCoreTest**, meliputi preservasi lirik, batas baris/spans, determinisme, vocabulary, timing/seek, wrapping, Unicode, fokus, konteks tetangga pada landscape pendek, fallback, beat dan spring/projection.
- **Regresi dikonfirmasi:** menjalankan tiga kasus regresi pada source ZIP asli menghasilkan dua kegagalan: keyword `finally` tidak dikenali dan typing memotong Unicode. Keduanya lulus pada source Stage 1.
- **Build APK belum terverifikasi:** percobaan `./gradlew :app:compileUniversalFossDebugKotlin :app:testUniversalFossDebugUnitTest --no-daemon` berhenti saat wrapper mengunduh Gradle 9.3.1 (`java.net.SocketException: Network is unreachable`). Tidak ada Android SDK yang tersedia pada instalasi lokal yang diperiksa. Tidak dihasilkan APK.
- **Integrasi Compose belum dikompilasi:** `TerminalPlayerStyle.kt` ditinjau manual; kompilasi standalone hanya mencakup core/renderer dan tesnya. Semua tes aplikasi lain belum dijalankan.
- Workflow baru **Terminal V3 Stage 1 verification** mengompilasi `Arm64FossDebug` dan menjalankan unit tests aplikasi. Workflow ini belum dijalankan di GitHub dari sesi ini. Jalankan sesudah mengunggah proyek; laporan tes tersedia sebagai artifact.
- Audit ZIP: 5 file Kotlin Terminal berubah, 3 file baru ditambahkan, 0 file asli dihapus; 5.060 file asli lain tetap identik secara byte.
- Workflow release, applicationId, versionName/versionCode, manifest, keystore/signing scripts, backup/restore, GitHub integration dan source style lain tidak diubah. Kompatibilitas update tetap bergantung pada key release dan versionCode CI yang sudah ada.

## Batasan dan pemeriksaan perangkat

- Belum menjalankan aplikasi baru pada emulator/perangkat; hasil visual, kontrol sentuh, font fallback/RTL dan performa perangkat perlu pemeriksaan Android. Kompilasi stub tidak membuktikan render native atau integrasi Compose.
- Dokumen adalah presentasi kode TypeScript/Kotlin, bukan program yang dieksekusi. Hanya lirik asli yang menjadi string; potongan kode merupakan komposisi visual.
- Lirik luar biasa panjang tetap mengikuti caret. Konteks lirik tetangga yang membungkus banyak baris ditampilkan sebagai baris terakhir sebelumnya / baris pertama berikutnya; baris aktif memperoleh ruang utama. Grapheme/emoji kompleks mengikuti perilaku `java.text.BreakIterator` sistem; rangkaian emoji ZWJ dan RTL belum diverifikasi pada perangkat.
- Teks disesuaikan bila viewport/preview terlalu sempit untuk dokumen minimum 22 kolom. Pembesaran font sistem dapat dibatasi oleh batas minimum tersebut.
- Pemeriksaan perangkat: portrait/landscape, pause/resume, seek maju/mundur, lirik datang saat paused, lagu tanpa lirik, teks panjang/CJK/emoji, Low quality, motion Off/reduced motion, menu dan perubahan style. Pastikan player tetap berjalan.

Stage 2 tidak dimulai. Sistem kamera/motion, mascot Streak dan fitur Vantra lainnya tidak dirombak.
