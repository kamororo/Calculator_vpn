package com.renamer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/** QR scanner, like v2rayNG's scan: camera preview, ZXing
 *  decodes the Y plane of each frame, and the first QR
 *  text found is returned to the caller. */
class ScanActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var preview: TextureView
    private lateinit var hintText: TextView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val busy = AtomicBoolean(false)
    private val reader = MultiFormatReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        preview = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(preview)
        hintText = TextView(this).apply {
            text = L("QR کانفیگ را جلوی دوربین بگیر", "Hold the config QR in front of the camera")
            gravity = android.view.Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        root.addView(hintText)
        root.addView(Button(this).apply {
            text = L("لغو", "Cancel")
            setOnClickListener { finish() }
        })
        setContentView(root)
        root.fitsSystemWindows = true

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                startCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        bgThread = HandlerThread("scan").apply { start() }
        bgHandler = Handler(bgThread!!.looper)
        if (preview.isAvailable) startCamera()
    }

    override fun onPause() {
        super.onPause()
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        cameraDevice = null; captureSession = null; imageReader = null
        bgThread?.quitSafely(); bgThread = null; bgHandler = null
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (_: Exception) { null }
        if (camId == null) {
            hintText.text = L("دوربینی پیدا نشد.", "No camera found.")
            return
        }
        try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(camera)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    hintText.text = L("خطای دوربین: $error", "Camera error: $error")
                    camera.close()
                }
            }, bgHandler)
        } catch (e: Exception) {
            hintText.text = L("باز کردن دوربین ناموفق: ${e.message}", "Failed to open camera: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            hintText.text = L("برای اسکن، دسترسی دوربین لازم است.", "Camera permission is required to scan.")
        }
    }

    private fun startPreview(camera: CameraDevice) {
        val size = Size(1280, 720)
        imageReader = ImageReader.newInstance(size.width, size.height,
            android.graphics.ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader2 ->
                val image = try { reader2.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@setOnImageAvailableListener
                if (!busy.compareAndSet(false, true)) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                try {
                    val yPlane = image.planes[0]
                    val yBuf = yPlane.buffer
                    val yBytes = ByteArray(yBuf.remaining())
                    yBuf.get(yBytes)
                    val source = PlanarYUVLuminanceSource(
                        yBytes, image.width, image.height,
                        0, 0, image.width, image.height, false
                    )
                    val result = try {
                        reader.decode(BinaryBitmap(HybridBinarizer(source)))
                    } catch (_: Exception) { null }
                    if (result != null) {
                        val intent = Intent().apply { putExtra("scanned", result.text) }
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                } catch (_: Exception) {
                } finally {
                    image.close()
                    busy.set(false)
                }
            }, bgHandler)
        }
        val previewSurface = Surface(preview.surfaceTexture)
        try {
            camera.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(imageReader!!.surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        }
                        try {
                            session.setRepeatingRequest(req.build(), null, bgHandler)
                        } catch (_: Exception) {}
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        hintText.text = L("راه‌اندازی پیش‌نمایش ناموفق بود.", "Starting the preview failed.")
                    }
                }, bgHandler
            )
        } catch (e: Exception) {
            hintText.text = L("ساخت نشست دوربین ناموفق: ${e.message}", "Failed to create camera session: ${e.message}")
        }
    }
}
