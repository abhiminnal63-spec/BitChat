package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

object ImageUtils {

    fun compressAndEncodeImage(context: Context, imageUri: Uri, maxDimension: Int = 800): String? {
        return try {
            val input: InputStream? = context.contentResolver.openInputStream(imageUri)
            val originalBitmap = BitmapFactory.decodeStream(input)
            input?.close()
            if (originalBitmap == null) return null

            // Resize maintaining aspect ratio
            val width = originalBitmap.width
            val height = originalBitmap.height
            val largestDim = max(width, height)

            val scaledBitmap = if (largestDim > maxDimension) {
                val scale = maxDimension.toFloat() / largestDim
                val targetW = (width * scale).toInt()
                val targetH = (height * scale).toInt()
                Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)
            } else {
                originalBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun shareImage(context: Context, imageUriOrBase64: String, caption: String = "") {
        try {
            val cacheDir = File(context.cacheDir, "shared_images")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "easapp_share_${System.currentTimeMillis()}.jpg")

            if (imageUriOrBase64.startsWith("data:image")) {
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
