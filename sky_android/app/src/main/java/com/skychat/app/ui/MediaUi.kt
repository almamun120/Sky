package com.skychat.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import com.skychat.app.SkyApp
import com.skychat.app.data.Msg
import com.skychat.app.media.openFile
import com.skychat.app.net.ChatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lets bubbles in the chat ask the chat screen to open the full-screen viewer (set by ChatScreen). */
object ViewerBus {
    @Volatile var open: (String) -> Unit = {}
    @Volatile var openOnce: (Msg) -> Unit = {}
}

/** Decrypts in memory only and shrinks so the longer side is at most [maxSide]. */
suspend fun loadBitmap(app: SkyApp, path: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val bytes = app.media.readBytes(File(path)) ?: return@withContext null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}

fun hasLocalFile(m: Msg) = m.localPath.isNotEmpty() && File(m.localPath).exists()

/** A photo, or the first frame of a video with a play mark. */
@Composable
fun MsgThumb(app: SkyApp, m: Msg, modifier: Modifier, scale: ContentScale = ContentScale.Crop, maxSide: Int = 480) {
    val path = if (m.kind == "video") m.localPath + ".thumb" else m.localPath
    val bmp by produceState<ImageBitmap?>(null, path) { value = if (File(path).exists()) loadBitmap(app, path, maxSide) else null }
    Box(modifier.background(Color(0xFF2B2B2B)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = scale, modifier = Modifier.fillMaxSize())
        if (m.kind == "video") Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 18.sp) }
    }
}

/** Full-screen viewer: swipe between the photos and videos of a chat; double-tap a photo to zoom, then pinch or drag. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(app: SkyApp, items: List<Msg>, startId: String, onClose: () -> Unit) {
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val ctx = LocalContext.current
    val pager = rememberPagerState(items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
                val m = items[page]
                if (m.kind == "video") Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MsgThumb(app, m, Modifier.fillMaxSize(), ContentScale.Fit, 1280)
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xAA000000))
                        .clickable { openFile(ctx, app.media, m.localPath, m.mime, m.fname) },
                        contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 32.sp) }
                } else ZoomableImage(app, m.localPath, page == pager.currentPage) { zoomed = it }
            }
            val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("←", color = Color.White, fontSize = 22.sp) }
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(timeText(cur.ts), color = Color(0xCCFFFFFF), fontSize = 13.sp)
            }
            if (cur.body.isNotBlank()) Text(cur.body, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0x99000000)).padding(16.dp), color = Color.White, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ZoomableImage(app: SkyApp, path: String, active: Boolean, onZoom: (Boolean) -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, path) { value = loadBitmap(app, path, 2048) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero; onZoom(false) } }
    val b = bmp
    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                onZoom(scale > 1f)
            })
        }
        .pointerInput(scale > 1f) {
            if (scale > 1f) detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
                onZoom(scale > 1f)
            }
        }, contentAlignment = Alignment.Center) {
        if (b != null) Image(b, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer(
            scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
        else Text("…", color = Color.White)
    }
}

// ---------------- preview before sending ----------------

private class PItem(val uri: Uri, val video: Boolean) {
    var caption by mutableStateOf("")
    var rotation by mutableIntStateOf(0)
    var aspect by mutableIntStateOf(0)
    var once by mutableStateOf(false)      // view once
}

private val Aspects = listOf(0f to "Original", 1f to "1:1", 4f / 3f to "4:3", 3f / 4f to "3:4", 16f / 9f to "16:9")

/**
 * Shown after the photos / videos were chosen (gallery or camera) and before anything is sent:
 * rotate, crop to a ratio, write a caption per picture, remove pictures, then Send.
 */
@Composable
fun MediaPreview(app: SkyApp, uris: List<Uri>, title: String, allowOnce: Boolean = false, onClose: () -> Unit, onSend: (List<ChatClient.OutMedia>) -> Unit) {
    val ctx = LocalContext.current
    val items = remember {
        mutableStateListOf<PItem>().also { l ->
            uris.forEach { u -> l.add(PItem(u, (ctx.contentResolver.getType(u) ?: "").startsWith("video/"))) }
        }
    }
    var cur by remember { mutableIntStateOf(0) }
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val cu = items[cur.coerceIn(0, items.lastIndex)]
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 32.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 22.sp) }
                Spacer(Modifier.weight(1f))
                if (allowOnce) TextButton(onClick = { cu.once = !cu.once }) {
                    Text(if (cu.once) "🔥 View once: on" else "🔥 View once", color = if (cu.once) Accent else Color.White)
                }
                if (!cu.video) {
                    TextButton(onClick = { cu.rotation = (cu.rotation + 90) % 360 }) { Text("⟳ Rotate", color = Color.White) }
                    TextButton(onClick = { cu.aspect = (cu.aspect + 1) % Aspects.size }) { Text("▣ " + Aspects[cu.aspect].second, color = Color.White) }
                }
            }
            val bmp by produceState<ImageBitmap?>(null, cu.uri, cu.rotation) { value = withContext(Dispatchers.IO) { previewBitmap(ctx, cu.uri, cu.rotation, cu.video) } }
            val ratio = Aspects[cu.aspect].first
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) {
                    val mod = if (ratio > 0f) Modifier.aspectRatio(ratio).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                              else Modifier.fillMaxSize()
                    Image(b, null, contentScale = if (ratio > 0f) ContentScale.Crop else ContentScale.Fit, modifier = mod)
                } else Text("…", color = Color.White)
                if (cu.video) Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White, fontSize = 28.sp)
                }
            }
            if (items.size > 1) LazyRow(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { i, p ->
                    val t by produceState<ImageBitmap?>(null, p.uri) { value = withContext(Dispatchers.IO) { previewBitmap(ctx, p.uri, 0, p.video, 160) } }
                    Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .border(if (i == cur) 2.dp else 0.dp, Accent, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clickable { cur = i }) {
                        t?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        if (i == cur) Text("✕", Modifier.align(Alignment.TopEnd).clickable {
                            items.removeAt(i); cur = cur.coerceAtMost(items.lastIndex).coerceAtLeast(0)
                        }.padding(4.dp), color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(cu.caption, { v -> if (v.length <= 1000) cu.caption = v }, Modifier.weight(1f),
                    placeholder = { Text(if (items.size > 1) "Caption for this picture" else "Add a caption") }, maxLines = 3,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF1F2C34), unfocusedContainerColor = Color(0xFF1F2C34),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedPlaceholderColor = Color.Gray, unfocusedPlaceholderColor = Color.Gray))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onSend(items.map { p -> ChatClient.OutMedia(p.uri, p.caption.trim(), p.rotation, Aspects[p.aspect].first, p.once) })
                }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Send" + if (items.size > 1) " (${items.size})" else "") }
            }
            Text("To: $title", Modifier.padding(start = 16.dp, bottom = 12.dp), color = Color.Gray, fontSize = 12.sp)
        }
    }
}

/** A small picture of a chosen photo (upright, rotated as asked) or of the first frame of a video. */
private fun previewBitmap(ctx: android.content.Context, uri: Uri, rotation: Int, video: Boolean, maxSide: Int = 1280): ImageBitmap? = try {
    var bmp: Bitmap? = null
    if (video) {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(ctx, uri); bmp = r.getFrameAtTime(0) } finally { r.release() }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
        bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        val orient = ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orient) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 }
        val total = (deg + rotation) % 360
        if (bmp != null && total != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(total.toFloat()) }, true)
    }
    bmp?.asImageBitmap()
} catch (e: Exception) { null }

/**
 * Opens a view-once picture or video: the screen cannot be captured (FLAG_SECURE), and when this window closes the picture is
 * deleted from this phone and the sender is told "opened".
 */
@Composable
fun OnceViewer(app: SkyApp, m: Msg, onClose: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    var tmp by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) {
        onDispose { tmp?.let { app.media.shred(it) }; app.client.viewOnceDone(m) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SideEffect {
            ((view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                ?: (view as? androidx.compose.ui.window.DialogWindowProvider)?.window)
                ?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (m.kind == "image") {
                val bmp by produceState<ImageBitmap?>(null, m.localPath) { value = loadBitmap(app, m.localPath, 2048) }
                bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            } else {
                val f by produceState<File?>(null, m.localPath) {
                    value = withContext(Dispatchers.IO) { app.media.toTemp(File(m.localPath), m.fname.ifEmpty { "video.mp4" }) }
                    tmp = value
                }
                f?.let { file ->
                    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
                        android.widget.VideoView(c).apply {
                            setVideoPath(file.absolutePath)
                            setOnPreparedListener { it.start() }
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = 32.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 22.sp) }
                Text("🔥 View once. It is deleted when you close it.", color = Color(0xCCFFFFFF), fontSize = 13.sp)
            }
            if (m.body.isNotBlank()) Text(m.body, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0x99000000)).padding(16.dp), color = Color.White, textAlign = TextAlign.Center)
        }
    }
}
