package app.diurna.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.diurna.R
import app.diurna.data.FoodPhotos
import app.diurna.domain.PhotoSizing
import app.diurna.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Attaches a photo to a food entry, from the camera or the system photo picker. Neither needs
 * storage access: the picker hands over one picture, and the camera app writes into our own
 * FileProvider folder. Camera permission is asked for first, because Android refuses a camera
 * intent from an app that declares that permission (for barcode scanning) without holding it.
 */
@Stable
class PhotoAttacher internal constructor(
    val takePhoto: () -> Unit,
    val choosePhoto: () -> Unit,
    private val busyState: () -> Boolean,
    private val errorState: () -> String?,
) {
    val busy: Boolean get() = busyState()
    val error: String? get() = errorState()
}

/** [onAttached] gets the stored photo's name once it's imported. */
@Composable
fun rememberPhotoAttacher(vm: DayViewModel, onAttached: (String) -> Unit): PhotoAttacher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun import(block: suspend () -> String?) {
        busy = true
        error = null
        scope.launch {
            val name = block()
            busy = false
            if (name != null) onAttached(name) else error = "Couldn't read that photo"
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) import { vm.importCapture() }
    }
    fun launchCamera() {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", vm.captureFile)
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            error = "No camera app found"
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else error = "Camera permission is needed to take a photo"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) import { vm.importPhoto(uri) }
    }
    return remember {
        PhotoAttacher(
            takePhoto = {
                error = null
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) launchCamera() else askCamera.launch(Manifest.permission.CAMERA)
            },
            choosePhoto = {
                error = null
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            busyState = { busy },
            errorState = { error },
        )
    }
}

/** The camera icon next to the food box: take a photo or choose one. */
@Composable
fun PhotoMenuButton(attacher: PhotoAttacher, contentDescription: String = "Add photo") {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !attacher.busy) {
            Icon(painterResource(R.drawable.ic_photo), contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary)
        }
        PhotoMenu(open, attacher) { open = false }
    }
}

@Composable
private fun PhotoMenu(open: Boolean, attacher: PhotoAttacher, onClose: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        DropdownMenuItem(text = { Text("Take photo") }, onClick = { onClose(); attacher.takePhoto() })
        DropdownMenuItem(text = { Text("Choose photo") }, onClick = { onClose(); attacher.choosePhoto() })
    }
}

/** An attached photo before saving: thumbnail plus Replace and Remove. */
@Composable
fun AttachedPhoto(photos: FoodPhotos, name: String?, attacher: PhotoAttacher, onRemove: () -> Unit) {
    var viewing by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    when {
        attacher.busy -> Hint("Adding photo…")
        name != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoThumb(photos, name, 56.dp) { viewing = true }
            Box {
                TextButton(onClick = { replacing = true }) { Text("Replace") }
                PhotoMenu(replacing, attacher) { replacing = false }
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
    attacher.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn) }
    if (viewing && name != null) PhotoViewDialog(photos, name) { viewing = false }
}

/** A small square crop of the photo; nothing at all if the file is gone (e.g. after a restore). */
@Composable
fun PhotoThumb(photos: FoodPhotos, name: String, size: Dp, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = rememberPhoto(photos, name, px) ?: return
    Image(
        bitmap, contentDescription = "Food photo",
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClickLabel = "View photo", onClick = onClick),
        contentScale = ContentScale.Crop,
    )
}

/** The photo at screen size. */
@Composable
fun PhotoViewDialog(photos: FoodPhotos, name: String, onDismiss: () -> Unit) {
    val px = with(LocalDensity.current) { 480.dp.roundToPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            rememberPhoto(photos, name, px)?.let {
                Image(
                    it, contentDescription = "Food photo",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
            } ?: Hint("Photo not found")
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Decodes off the main thread, subsampled to about [targetPx], so a list of thumbnails stays light. */
@Composable
private fun rememberPhoto(photos: FoodPhotos, name: String, targetPx: Int): ImageBitmap? =
    produceState<ImageBitmap?>(null, photos, name, targetPx) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = photos.file(name)?.takeIf { it.isFile } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = PhotoSizing.sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                }
                BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }.value
