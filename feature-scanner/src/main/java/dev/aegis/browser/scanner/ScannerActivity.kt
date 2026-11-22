package dev.aegis.browser.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class ScannerActivity : Activity() {

    /** 跟随应用内语言设置，否则扫码页会固定显示默认中文 */
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("aegis_language", Context.MODE_PRIVATE)
            .getString("lang", "zh-CN") ?: "zh-CN"
        val locale = java.util.Locale.forLanguageTag(tag)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))
        java.util.Locale.setDefault(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    companion object {
        const val EXTRA_RESULT = "scan_result"
        private const val REQ_CAMERA = 200
    }

    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.PDF_417,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.EAN_8
                ),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    @Volatile private var decoding = false
    @Volatile private var finished = false

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            previewSize?.let { configureTransform(it) }
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        textureView = findViewById(R.id.preview)
        val closeButton = findViewById<View>(R.id.btn_close)
        closeButton.setOnClickListener { finish() }
        // 用 topMargin 把按钮整体下移到状态栏下方（不挤压内部图标），额外保留 8dp 间距
        closeButton.setOnApplyWindowInsetsListener { view, insets ->
            val lp = view.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.topMargin = insets.systemWindowInsetTop + dpToPx(8)
            view.layoutParams = lp
            insets
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable) openCamera()
            } else {
                Toast.makeText(this, R.string.scan_need_camera, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun openCamera() {
        if (!hasCameraPermission()) return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val analysisSize = chooseAnalysisSize(streamMap?.getOutputSizes(ImageFormat.YUV_420_888))
        // 预览尺寸：从 SurfaceTexture 输出尺寸中选宽高比最接近视图、分辨率足够的，避免拉伸
        previewSize = choosePreviewSize(
            streamMap?.getOutputSizes(SurfaceTexture::class.java)
        )

        imageReader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ readerCallback ->
                val image = readerCallback.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!decoding && !finished) {
                    decoding = true
                    decodeImage(image)
                    image.close()
                    decoding = false
                } else {
                    image.close()
                }
            }, backgroundHandler)
        }

        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            },
            backgroundHandler
        )
    }

    /** 选择宽度不超过 1280 的最大可用尺寸，兼顾识别速度与清晰度 */
    private fun chooseAnalysisSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        return sizes.filter { it.width <= 1280 && it.height <= 1280 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width }
            ?: Size(1280, 720)
    }

    /**
     * 选择预览尺寸：优先短边不低于 720、且宽高比尽量接近竖屏视图的较大尺寸。
     * 相机传感器输出为横向，竖屏由 configureTransform 旋转并做 center-crop。
     */
    private fun choosePreviewSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        // 竖屏视图比例（高/宽），对应传感器横向尺寸的 width/height
        val viewRatio = if (textureView.height > 0 && textureView.width > 0) {
            textureView.height.toFloat() / textureView.width.toFloat()
        } else {
            16f / 9f
        }
        val candidates = sizes.filter { it.width >= 720 }
        val pool = candidates.ifEmpty { sizes.toList() }
        return pool.minByOrNull { size ->
            val sensorRatio = size.width.toFloat() / size.height.toFloat()
            // 比例差异 + 分辨率过大的轻微惩罚，取最接近的
            kotlin.math.abs(sensorRatio - viewRatio)
        } ?: sizes[0]
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val readerSurface = imageReader?.surface ?: return
        val size = previewSize ?: Size(1280, 720)

        val texture = textureView.surfaceTexture ?: return
        // 用相机预览尺寸而非视图尺寸，配合 transform 矩阵做 center-crop，避免比例拉伸
        texture.setDefaultBufferSize(size.width, size.height)
        val previewSurface = Surface(texture)

        val targets = listOf(previewSurface, readerSurface)
        camera.createCaptureSession(
            targets,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(readerSurface)
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                        )
                    }
                    session.setRepeatingRequest(request.build(), null, backgroundHandler)
                    // 配置完成后应用变换，确保按视图比例正确显示
                    configureTransform(size)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("Scanner", "capture session configure failed")
                }
            },
            backgroundHandler
        )
    }

    /**
     * 计算 TextureView 的变换矩阵：根据显示旋转处理竖屏 90° 旋转，
     * 并以 center-crop 方式缩放填满视图，保证画面不变形、不拉伸。
     */
    private fun configureTransform(size: Size) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth == 0 || viewHeight == 0) return
        val rotation = display.rotation
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // 传感器横向，竖屏参考矩形宽高互换
        val bufferRect = android.graphics.RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
            // center-crop：取较大缩放比，填满并裁掉多余部分
            val scale = maxOf(
                viewHeight.toFloat() / size.height,
                viewWidth.toFloat() / size.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun decodeImage(image: Image) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)

            if (!finished) {
                finished = true
                runOnUiThread {
                    setResult(
                        RESULT_OK,
                        Intent().apply { putExtra(EXTRA_RESULT, result.text) }
                    )
                    finish()
                }
            }
        } catch (_: com.google.zxing.NotFoundException) {
            // 未识别到内容，继续等待下一帧
        } catch (t: Throwable) {
            Log.e("Scanner", "decode failed", t)
        } finally {
            reader.reset()
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("scanner-background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (_: InterruptedException) {
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
