# Aplikasi Android CBT SMAN 2 Tanggul

Aplikasi HP (APK) berupa "exam browser" yang membuka web CBT Anda. Soal, nilai, dan pengaturan tetap dikelola dari web. Kalau web diperbarui, aplikasi ikut berubah tanpa perlu membuat APK baru.

## Fitur aplikasi

| Fitur | Keterangan |
|---|---|
| Blokir screenshot & rekam layar | Layar tampil hitam jika direkam atau di-screenshot |
| Layar penuh & layar tetap menyala | Bilah status/navigasi disembunyikan, layar tidak mati sendiri |
| Mode ujian otomatis | Aktif saat siswa mulai ujian, nonaktif saat jawaban dikumpulkan |
| Kunci layar (screen pinning) | Siswa tidak bisa pindah aplikasi selama ujian (bisa dimatikan di `config.xml`) |
| Tombol kembali dimatikan | Selama ujian |
| Deteksi pelanggaran | Keluar aplikasi, buka panel notifikasi, dan layar terbagi dicatat sebagai pelanggaran |
| Tautan luar diblokir | Selama ujian |
| Kamera | Untuk Scan QR kartu ujian di halaman login |
| Pemilih file | Untuk scan QR dari galeri dan impor Excel |

Syarat: Android 8.0 ke atas.

---

## Langkah 1: Isi alamat web CBT

Buka `app/src/main/res/values/config.xml`, lalu ganti:

```xml
<string name="cbt_url" translatable="false">https://NAMA-BLOG-ANDA.blogspot.com/</string>
```

Isi dengan alamat web CBT Anda, misalnya `https://cbt-smandatanggul.blogspot.com/`, atau alamat halaman Blogger tempat kode CBT dipasang.

## Langkah 2: Buat APK otomatis lewat GitHub (gratis, tanpa install apa pun)

1. Buat akun di <https://github.com> jika belum punya.
2. Klik **New repository**, beri nama `cbt-sman2-tanggul-apk`, pilih **Public**, lalu **Create repository**.
3. Klik **uploading an existing file**, lalu seret **seluruh isi** folder `CBT-SMAN2-Tanggul-Android` ke halaman itu. Klik **Commit changes**.
4. Pastikan folder `.github/workflows/build-apk.yml` ikut terunggah. Folder yang diawali titik kadang tidak ikut. Jika tidak ada:
   - Klik **Add file → Create new file**.
   - Nama file: `.github/workflows/build-apk.yml`.
   - Tempel isi file `build-apk.yml` dari folder ini, lalu **Commit**.
5. Buka tab **Actions**. Proses **Build APK CBT** berjalan otomatis sekitar 3–6 menit.
6. Jika berhasil (tanda centang hijau), buka tab **Releases** di halaman repository dan unduh `CBT-SMAN2-Tanggul.apk`.

**Link unduh tetap untuk siswa**, yang bisa diisikan ke menu *Identitas Web → Link Download Aplikasi HP*:

```
https://github.com/USERNAME-ANDA/cbt-sman2-tanggul-apk/releases/latest/download/CBT-SMAN2-Tanggul.apk
```

Setiap kali ada file yang diubah di GitHub (misalnya `config.xml`), APK baru terbentuk otomatis dan bisa dipasang menimpa versi lama.

> Alternatif: buka folder ini di **Android Studio** lalu pilih **Build → Build App Bundle(s)/APK(s) → Build APK(s)**.

## Langkah 3: Pasang di HP siswa

1. Buka link unduh di HP dan unduh APK-nya.
2. Saat diminta, izinkan **"Instal aplikasi tidak dikenal"** untuk Chrome atau File Manager.
3. Jika Google Play Protect memberi peringatan, pilih **Detail lainnya → Tetap instal**. Peringatan ini wajar untuk aplikasi di luar Play Store.
4. Saat ujian dimulai, Android menampilkan konfirmasi **"Sematkan aplikasi?"**. Siswa memilih **Mengerti/OK**. Sematan lepas otomatis setelah jawaban dikumpulkan.

---

## Catatan penting

- **Fitur admin sebaiknya lewat browser (Chrome di laptop).** Ekspor Excel, unduh template, cetak kartu, dan cetak PDF tidak berjalan di dalam aplikasi. Aplikasi ini ditujukan untuk siswa.
- **Kunci keystore bawaan.** File `app/cbt-release.jks` beserta passwordnya tersimpan di proyek. Ini supaya setiap APK baru selalu bisa dipasang menimpa APK lama. Kalau repository Public, orang lain bisa memakai kunci ini untuk menandatangani APK tiruan. Opsi yang lebih aman: buat keystore sendiri, lalu simpan di **Settings → Secrets and variables → Actions**:
  - `CBT_KEYSTORE_BASE64`: isi file `.jks` dalam bentuk base64
  - `CBT_KEYSTORE_PASS`, `CBT_KEY_ALIAS`, `CBT_KEY_PASS`

  Mengganti keystore berarti aplikasi lama di HP siswa harus dihapus dulu, satu kali saja.
- **Kunci layar (screen pinning) bukan kunci mutlak.** Siswa yang tahu caranya tetap bisa melepas sematan (tahan tombol Kembali + Ringkasan). Tindakan itu membuat aplikasi keluar dan langsung tercatat sebagai pelanggaran. Pengawasan ruang tetap diperlukan.
- **Mematikan kunci layar:** ubah `<bool name="kunci_layar_saat_ujian">` menjadi `false`.
- **Mengganti nama aplikasi:** ubah `app/src/main/res/values/strings.xml`.
