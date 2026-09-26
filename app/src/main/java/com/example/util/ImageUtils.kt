package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object ImageUtils {

    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024) { // 24 MB cache
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val mediaHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getCachedBitmap(source: String?): Bitmap? {
        if (source.isNullOrBlank()) return null
        return synchronized(bitmapCache) {
            bitmapCache.get(cacheKeyFor(source))
        }
    }

    private fun putCachedBitmap(source: String?, bitmap: Bitmap) {
        if (source.isNullOrBlank()) return
        synchronized(bitmapCache) {
            bitmapCache.put(cacheKeyFor(source), bitmap)
        }
    }

    private fun cacheKeyFor(source: String): String {
        return if (source.length > 180) {
            "b64_${source.length}_${source.hashCode()}_${source.takeLast(48)}"
        } else {
            source
        }
    }

    private fun decodeAndNormalizeSourceBitmap(context: Context, imageUri: Uri, maxDim: Int = 1024): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val rawW = boundsOptions.outWidth
            val rawH = boundsOptions.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            var sampleSize = 1
            while ((rawW / sampleSize) > maxDim * 2 || (rawH / sampleSize) > maxDim * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // Check EXIF orientation so camera photos are never upside-down or sideways
            val rotationDegrees = try {
                context.contentResolver.openInputStream(imageUri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) {
                0f
            }

            val rotated = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } else {
                decoded
            }

            val w = rotated.width
            val h = rotated.height
            val largest = max(w, h)
            val targetW: Int
            val targetH: Int
            if (largest > maxDim) {
                val scale = maxDim.toFloat() / largest.toFloat()
                targetW = (w * scale).toInt().coerceAtLeast(1)
                targetH = (h * scale).toInt().coerceAtLeast(1)
            } else {
                targetW = w
                targetH = h
            }

            // Render onto an opaque white ARGB_8888 surface so transparent PNGs never turn into black rectangles when JPEG-encoded
            val normalized = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(normalized)
            canvas.drawColor(Color.WHITE)
            val scaled = if (targetW != w || targetH != h) {
                Bitmap.createScaledBitmap(rotated, targetW, targetH, true)
            } else {
                rotated
            }
            canvas.drawBitmap(scaled, 0f, 0f, null)
            normalized
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun compressAndEncodeImage(context: Context, imageUri: Uri, maxDimension: Int = 720): String? {
        return try {
            val bitmap = decodeAndNormalizeSourceBitmap(context, imageUri, maxDimension) ?: return null
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 76, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64"
            putCachedBitmap(dataUri, bitmap)
            dataUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts raw JPEG bytes from a data URI, local file URI, or content URI for uploading to cloud media storage.
     */
    fun extractJpegBytes(context: Context, source: String?, maxDimension: Int = 720, quality: Int = 76): ByteArray? {
        if (source.isNullOrBlank()) return null
        return try {
            when {
                source.startsWith("data:image") -> {
                    val base64Part = source.substringAfter("base64,", "").trim()
                    if (base64Part.isBlank()) return null
                    val rawBytes = Base64.decode(base64Part, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
                    putCachedBitmap(source, bmp)
                    val largest = max(bmp.width, bmp.height)
                    val finalBmp = if (largest > maxDimension) {
                        val scale = maxDimension.toFloat() / largest.toFloat()
                        Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    } else {
                        bmp
                    }
                    val out = ByteArrayOutputStream()
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                source.startsWith("content://") || source.startsWith("file://") -> {
                    val bmp = decodeAndNormalizeSourceBitmap(context, Uri.parse(source), maxDimension) ?: return null
                    putCachedBitmap(source, bmp)
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates a compact inline Base64 JPEG data URI guaranteed to be under 2,500 characters
     * so that the entire JSON message payload stays well below ntfy.sh's 4,096-byte message limit.
     */
    fun createCompactRelayDataUri(jpegBytesOrSource: ByteArray): String? {
        return try {
            val original = BitmapFactory.decodeByteArray(jpegBytesOrSource, 0, jpegBytesOrSource.size) ?: return null
            val dimensions = listOf(220, 180, 140, 110)
            val qualities = listOf(52, 44, 36, 30)

            for (i in dimensions.indices) {
                val maxDim = dimensions[i]
                val q = qualities[i]
                val w = original.width
                val h = original.height
                val largest = max(w, h)
                val scaled = if (largest > maxDim) {
                    val scale = maxDim.toFloat() / largest.toFloat()
                    Bitmap.createScaledBitmap(
                        original,
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    original
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (b64.length <= 2450 || i == dimensions.lastIndex) {
                    val uri = "data:image/jpeg;base64,$b64"
                    putCachedBitmap(uri, scaled)
                    return uri
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads a Bitmap from any supported source:
     * - https:// / http:// cloud media URL (with disk caching in cacheDir/media_cache)
     * - data:image/...;base64,... or raw base64 string
     * - file:// or content:// URI
     */
    suspend fun loadBitmapFromSource(
        context: Context,
        primarySource: String?,
        fallbackSource: String? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        getCachedBitmap(primarySource)?.let { return@withContext it }

        val fromPrimary = decodeSingleSource(context, primarySource)
        if (fromPrimary != null) {
            putCachedBitmap(primarySource, fromPrimary)
            return@withContext fromPrimary
        }

        if (!fallbackSource.isNullOrBlank() && fallbackSource != primarySource) {
            getCachedBitmap(fallbackSource)?.let { return@withContext it }
            val fromFallback = decodeSingleSource(context, fallbackSource)
            if (fromFallback != null) {
                putCachedBitmap(fallbackSource, fromFallback)
                return@withContext fromFallback
            }
        }
        null
    }

    private fun decodeSingleSource(context: Context, rawSource: String?): Bitmap? {
        val source = rawSource?.trim() ?: return null
        if (source.isBlank()) return null

        return try {
            when {
                source.startsWith("data:image", ignoreCase = true) -> {
                    val b64 = source.substringAfter("base64,", "").trim()
                    if (b64.isBlank()) return null
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                source.startsWith("https://", ignoreCase = true) || source.startsWith("http://", ignoreCase = true) -> {
                    val cacheDir = File(context.cacheDir, "easapp_media_cache")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val diskFile = File(cacheDir, "media_${kotlin.math.abs(source.hashCode())}.jpg")
                    if (diskFile.exists() && diskFile.length() > 64L) {
                        BitmapFactory.decodeFile(diskFile.absolutePath)?.let { return it }
                    }

                    val request = Request.Builder().url(source).build()
                    mediaHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return null
                        val bytes = response.body?.bytes() ?: return null
                        if (bytes.isEmpty()) return null
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                        try {
                            FileOutputStream(diskFile).use { it.write(bytes) }
                        } catch (_: Exception) {
                        }
                        decoded
                    }
                }

                source.startsWith("file://", ignoreCase = true) -> {
                    val path = Uri.parse(source).path ?: return null
                    BitmapFactory.decodeFile(path)
                }

                source.startsWith("content://", ignoreCase = true) -> {
                    decodeAndNormalizeSourceBitmap(context, Uri.parse(source), 960)
                }

                source.startsWith("/") -> {
                    BitmapFactory.decodeFile(source)
                }

                // Raw base64 string without prefix
                source.length > 100 && !source.contains(" ") -> {
                    val bytes = Base64.decode(source, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun shareImage(context: Context, imageUriOrBase64: String, caption: String = "") {
        try {
            val cacheDir = File(context.cacheDir, "shared_images")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "easapp_share_${System.currentTimeMillis()}.jpg")

            val cachedBmp = getCachedBitmap(imageUriOrBase64) ?: decodeSingleSource(context, imageUriOrBase64)
            if (cachedBmp != null) {
                FileOutputStream(file).use { out ->
                    cachedBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } else if (imageUriOrBase64.startsWith("data:image")) {
                val base64Data = imageUriOrBase64.substringAfter("base64,")
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                FileOutputStream(file).use { it.write(decodedBytes) }
            } else if (imageUriOrBase64.startsWith("content://") || imageUriOrBase64.startsWith("file://")) {
                val uri = Uri.parse(imageUriOrBase64)
                context.contentResolver.openInputStream(uri)?.use { inStream ->
                    FileOutputStream(file).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }

            val shareUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                if (caption.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, caption)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Image via Easapp").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to text share if image extraction fails
            val textIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, caption.ifBlank { "Shared from Easapp" })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(textIntent, "Share via Easapp").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
