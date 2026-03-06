/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.pearconnect

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.metrolist.music.LocalPearConnectClient
import com.metrolist.music.R
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class PearQrResult(
    val ip: String,
    val port: Int,
    val pairingCode: String,
    val localIp: String? = null,
    val localPort: Int? = null
)

/**
 * Parses a Pear Desktop QR URL. Supports local IP:PORT and Localtunnel URLs.
 * When a tunnel is active the QR also embeds &localIp=X&localPort=Y as
 * a fallback for on-network connections.
 */
fun parsePearQrUrl(raw: String): PearQrResult? {
    return try {
        val uri = android.net.Uri.parse(raw)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        
        // Use default port 80 for HTTP, 443 for HTTPS if not specified
        var port = uri.port
        if (port <= 0) {
            port = if (uri.scheme == "https") 443 else 80
        }
        
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null

        // Optional fallback local IP/port embedded by desktop when tunnel is active
        val localIp = uri.getQueryParameter("localIp")?.takeIf { it.isNotBlank() }
        val localPort = uri.getQueryParameter("localPort")?.toIntOrNull()

        PearQrResult(ip = host, port = port, pairingCode = code, localIp = localIp, localPort = localPort)
    } catch (e: Exception) {
        null
    }
}

/**
 * Decode a QR code from a CameraX ImageProxy (YUV_420_888).
 *
 * Pear Desktop renders a blue-on-black QR (inverted polarity).
 * ZXing expects dark modules on light background, so we must try:
 *   1. Normal  + HybridBinarizer      (standard QR codes)
 *   2. Inverted + HybridBinarizer     (blue-on-black like Pear Desktop)
 *   3. Normal  + GlobalHistogramBinarizer  (fallback for low contrast)
 *   4. Inverted + GlobalHistogramBinarizer (extra fallback)
 */
private fun tryDecodeQrFromImage(imageProxy: ImageProxy): String? {
    return try {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer.also { it.rewind() }
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        // rowStride may be > width due to memory alignment padding
        val rowStride = plane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height

        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )

        val source = PlanarYUVLuminanceSource(
            data, rowStride, height,
            0, 0, width, height,
            false
        )

        // Four passes: (normal + inverted) × (Hybrid + GlobalHistogram)
        val bitmaps = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(HybridBinarizer(source.invert())),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source.invert()))
        )

        for (bitmap in bitmaps) {
            try {
                return MultiFormatReader().decode(bitmap, hints).text
            } catch (_: NotFoundException) {
                // try next pass
            }
        }
        null
    } catch (e: Exception) {
        Timber.w(e, "QrScanner: decode error")
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pearConnectClient = LocalPearConnectClient.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var statusText by remember { mutableStateOf("") }

    // Use AtomicBoolean — accessed from background analyzer thread, not safe with Compose state
    val scanned = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) statusText = "Camera permission is required to scan the QR code."
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Animated scanner sweep line
    val scanLineAnim by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLine"
    )

    val primary = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // --- Camera Preview ---
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val executor = Executors.newSingleThreadExecutor()
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val provider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()

                        analysis.setAnalyzer(executor) { imageProxy ->
                            if (!scanned.get()) {
                                val text = tryDecodeQrFromImage(imageProxy)
                                if (text != null) {
                                    Timber.d("QrScanner: raw text = $text")
                                    val result = parsePearQrUrl(text)
                                    if (result != null && scanned.compareAndSet(false, true)) {
                                        Timber.d("QrScanner: connecting to ${result.ip}:${result.port} (fallback=${result.localIp}:${result.localPort})")
                                        pearConnectClient?.connectWithQr(
                                            ip = result.ip,
                                            port = result.port,
                                            pairingCode = result.pairingCode,
                                            fallbackIp = result.localIp,
                                            fallbackPort = result.localPort
                                        )
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            onDismiss()
                                        }
                                    } else if (result == null) {
                                        // QR was detected but isn't a PearConnect URL
                                        Timber.d("QrScanner: unrecognized QR: $text")
                                    }
                                }
                            }
                            imageProxy.close()
                        }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "QrScanner: camera bind failed")
                        }

                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Semi-transparent overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // --- Top bar ---
        TopAppBar(
            title = { Text("Scan Pear Desktop QR", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // --- Viewfinder + labels ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
        ) {
            Spacer(Modifier.weight(0.15f))

            // Viewfinder box with animated scan line & corner brackets
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .drawBehind {
                        val w = size.width
                        val h = size.height
                        val cornerLen = 44.dp.toPx()
                        val sw = 4.dp.toPx()
                        val r = 14.dp.toPx()
                        val stroke = Stroke(width = sw)

                        // Animated sweep line
                        val lineY = scanLineAnim * h
                        drawLine(
                            color = primary.copy(alpha = 0.9f),
                            start = Offset(sw, lineY),
                            end = Offset(w - sw, lineY),
                            strokeWidth = 3.dp.toPx()
                        )

                        // Corner brackets
                        fun drawCorner(x: Float, y: Float, xDir: Float, yDir: Float) {
                            // Vertical arm
                            drawLine(primary, Offset(x, y + r * yDir), Offset(x, y + cornerLen * yDir), sw)
                            // Horizontal arm
                            drawLine(primary, Offset(x + r * xDir, y), Offset(x + cornerLen * xDir, y), sw)
                            // Arc
                            drawArc(
                                color = primary,
                                startAngle = if (xDir > 0 && yDir > 0) 180f
                                             else if (xDir < 0 && yDir > 0) 270f
                                             else if (xDir > 0 && yDir < 0) 90f
                                             else 0f,
                                sweepAngle = 90f,
                                useCenter = false,
                                topLeft = Offset(
                                    x + (if (xDir > 0) 0f else -r * 2),
                                    y + (if (yDir > 0) 0f else -r * 2)
                                ),
                                size = ComposeSize(r * 2, r * 2),
                                style = stroke
                            )
                        }
                        drawCorner(sw / 2, sw / 2, 1f, 1f)          // top-left
                        drawCorner(w - sw / 2, sw / 2, -1f, 1f)     // top-right
                        drawCorner(sw / 2, h - sw / 2, 1f, -1f)     // bottom-left
                        drawCorner(w - sw / 2, h - sw / 2, -1f, -1f) // bottom-right
                    }
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = if (hasCameraPermission)
                    "Point your camera at the QR code\nshown in Pear Desktop"
                else
                    "Camera permission is required",
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )

            if (statusText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = statusText,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            // Hint pill
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.10f)
            ) {
                Text(
                    "Open Pear Desktop → click the 🍐 button\nto show the QR code",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.weight(0.15f))
        }
    }
}
