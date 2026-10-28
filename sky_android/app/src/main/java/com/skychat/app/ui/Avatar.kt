package com.skychat.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.skychat.app.SkyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Round profile / group photo. [path] is the vault-encrypted copy; it is decrypted in memory only. */
@Composable
fun Avatar(app: SkyApp, path: String, size: Dp, version: Int = 0) {
    val bmp by produceState<ImageBitmap?>(null, path, version) {
        value = if (path.isEmpty()) null else withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.exists()) null
            else app.media.readBytes(f)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFB0BEC5)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
