package app.diurna.scan

import app.diurna.domain.ProductCode
import app.diurna.domain.ScanDiagnostics
import app.diurna.domain.ScanOutcome
import app.diurna.domain.LiveScanSession
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import android.util.Size
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Food-only scanner. A valid live read survives a worse still, but live fallback needs confirmation. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun ProductScanner(
    onResult: (ScanOutcome) -> Unit,
    onProblem: (String) -> Unit,
    modifier: Modifier = Modifier,
    torch: Boolean = false,
    diagnostics: ScanDiagnostics = LocalScanDiagnostics.current,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember { PreviewView(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val session = remember(owner, attempt) { LiveScanSession() }
    var busy by remember(session) { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var liveProblem by remember { mutableStateOf<String?>(null) }
    val pending = remember(session) { AtomicReference<File?>(null) }
    val latestResult by rememberUpdatedState(onResult)
    val latestProblem by rememberUpdatedState(onProblem)
    val latestDiagnostics by rememberUpdatedState(diagnostics)

    fun finish(outcome: ScanOutcome, problem: String? = null) {
        val resolution = session.complete(outcome) ?: return
        latestDiagnostics.onResolved(resolution)
        if (resolution.source != "none" || problem == null) latestResult(resolution.outcome)
        else latestProblem(problem)
    }
    val takePhoto: () -> Unit = takePhoto@{
        val shot = capture ?: return@takePhoto
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !session.beginCapture()) return@takePhoto
        latestDiagnostics.onEvent("capture", "start")
        busy = true
        liveProblem = null
        val raw = try {
            val dir = File(context.cacheDir, "barcode-capture").apply { mkdirs() }
            // Sweep interrupted captures from an earlier process, never another active capture.
            dir.listFiles()?.filter { it.name.startsWith("barcode-") && it.lastModified() < System.currentTimeMillis() - 60 * 60 * 1000 }
                ?.forEach { it.delete() }
            File.createTempFile("barcode-raw-", ".jpg", dir)
        } catch (e: Exception) {
            latestDiagnostics.onEvent("capture", "failed", mapOf("exception" to e.javaClass.simpleName))
            busy = false
            finish(ScanOutcome.Unreadable, "Couldn't save the barcode photo. Check free space and try again.")
            return@takePhoto
        }
        pending.set(raw)
        shot.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        try {
            shot.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (!session.isActive()) { raw.delete(); return }
                        scope.launch {
                            try {
                                val result = readCapturedBarcode(raw, latestDiagnostics)
                                finish(result)
                            } catch (_: TimeoutCancellationException) {
                                latestDiagnostics.onEvent("image_error", "timeout")
                                finish(ScanOutcome.Unreadable, "Reading the barcode photo took too long. Try again.")
                            } catch (e: CancellationException) {
                                session.cancel()
                                throw e
                            } catch (e: Exception) {
                                latestDiagnostics.onEvent("image_error", "failed", mapOf("exception" to e.javaClass.simpleName))
                                finish(ScanOutcome.Unreadable, "Couldn't process the saved barcode photo. Try again.")
                            } finally {
                                raw.delete()
                                busy = false
                            }
                        }.invokeOnCompletion { raw.delete() }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        latestDiagnostics.onEvent("capture", "failed", mapOf("exception" to exception.javaClass.simpleName, "message" to (exception.message ?: "")))
                        raw.delete(); busy = false
                        finish(ScanOutcome.Unreadable, "Couldn't take the barcode photo. Try again.")
                    }
                })
        } catch (e: Exception) {
            latestDiagnostics.onEvent("capture", "failed", mapOf("exception" to e.javaClass.simpleName))
            raw.delete(); busy = false
            finish(ScanOutcome.Unreadable, "Couldn't take the barcode photo. Try again.")
        }
    }

    DisposableEffect(owner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { session.cancel(); busy = false }
            if (event == Lifecycle.Event.ON_START && !session.isActive()) attempt++
        }
        owner.lifecycle.addObserver(observer)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) session.cancel()
        liveProblem = null
        val disposed = AtomicBoolean(false)
        val frames = AtomicInteger(0)
        val candidateLogged = AtomicBoolean(false)
        val failureLogged = AtomicBoolean(false)
        latestDiagnostics.onEvent("camera_bind", "start")
        val worker = Executors.newSingleThreadExecutor()
        val reader = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(PRODUCT_FORMATS.first(), *PRODUCT_FORMATS.drop(1).toIntArray()).build())
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val selector = ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val still = ImageCapture.Builder().setResolutionSelector(selector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        val analysis = ImageAnalysis.Builder().setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(worker) { proxy ->
            val frame = if (latestDiagnostics.enabled) frames.incrementAndGet() else 0
            if (latestDiagnostics.enabled && (frame == 1 || frame % 120 == 0)) latestDiagnostics.onEvent("frames", "summary", mapOf("frames" to frame.toString()))
            val media = proxy.image
            if (media == null || disposed.get() || !session.canAnalyze()) {
                proxy.close()
            } else {
                try {
                    reader.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            if (!disposed.get() && latestDiagnostics.enabled && codes.isNotEmpty() && (!candidateLogged.getAndSet(true) || frame % 30 == 0)) {
                                codes.take(8).forEach { code -> latestDiagnostics.onEvent("live", "candidate", mapOf(
                                    "format" to code.format.toString(), "checksum" to (code.rawValue?.let {
                                        ProductCode(it, code.format == Barcode.FORMAT_UPC_E).isValid()
                                    } == true).toString())) }
                            }
                            if (!disposed.get() && session.canAnalyze()) {
                                val rawCodes = codes.mapNotNull { code -> code.rawValue?.let { ProductCode(it, code.format == Barcode.FORMAT_UPC_E) } }
                                val accepted = rawCodes.filter { it.isValid() }
                                if (latestDiagnostics.enabled && codes.isNotEmpty() && (accepted.isNotEmpty() || frame == 1 || frame % 120 == 0)) latestDiagnostics.onEvent("live_counts", "summary",
                                    mapOf("raw_candidates" to codes.size.toString(), "accepted_candidates" to accepted.size.toString()))
                                if (session.observe(accepted)) takePhoto()
                            }
                        }
                        .addOnFailureListener {
                            if (!disposed.get()) {
                                if (latestDiagnostics.enabled && (!failureLogged.getAndSet(true) || frame % 120 == 0)) latestDiagnostics.onEvent("live", "failed", mapOf("exception" to it.javaClass.simpleName))
                                liveProblem = "Live scanning failed. Tap Capture barcode to try a saved photo."
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } catch (e: Exception) {
                    proxy.close()
                    executor.execute {
                        if (!disposed.get()) {
                            if (latestDiagnostics.enabled && (!failureLogged.getAndSet(true) || frame % 120 == 0)) latestDiagnostics.onEvent("live", "failed", mapOf("exception" to e.javaClass.simpleName))
                            liveProblem = "Live scanning failed. Try Capture barcode."
                        }
                    }
                }
            }
        }
        future.addListener({
            if (!disposed.get()) {
                try {
                    provider = future.get()
                    camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, still)
                    capture = still
                    failed = false
                    latestDiagnostics.onEvent("camera_bind", "ok")
                } catch (e: Exception) {
                    latestDiagnostics.onEvent("camera_bind", "failed", mapOf("exception" to e.javaClass.simpleName, "message" to (e.message ?: "")))
                    failed = true
                    capture = null
                }
            }
        }, executor)
        onDispose {
            latestDiagnostics.onEvent("frames", "summary", mapOf("frames" to frames.get().toString()))
            disposed.set(true)
            owner.lifecycle.removeObserver(observer)
            session.cancel()
            analysis.clearAnalyzer()
            pending.getAndSet(null)?.delete()
            provider?.unbind(preview, analysis, still)
            reader.close()
            worker.shutdown()
            capture = null
            camera = null
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier, contentAlignment = Alignment.Center) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize(), update = { view ->
                view.contentDescription = "Barcode camera preview. Tap to focus."
                view.setOnTouchListener { target, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        target.performClick()
                        try {
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(
                                view.meteringPointFactory.createPoint(event.x, event.y)).build())
                        } catch (_: Exception) {
                            liveProblem = "Couldn't focus. Tap again or try a saved photo."
                        }
                    }
                    true
                }
            })
            if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The camera didn't start.")
                TextButton(onClick = { failed = false; attempt++ }) { Text("Try again") }
            }
        }
        if (busy) Text("Reading saved photo…")
        else {
            liveProblem?.let { Text(it) }
            TextButton(onClick = takePhoto, enabled = capture != null) { Text("Capture barcode") }
            Text("Tap to focus. Fill the frame without glare.")
        }
    }
}
