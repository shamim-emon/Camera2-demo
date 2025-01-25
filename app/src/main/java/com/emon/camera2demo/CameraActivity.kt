package com.emon.camera2demo


import android.content.ContentValues
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class CameraActivity : ComponentActivity() {

    private  var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var mediaRecorder: MediaRecorder
    private var isRecording by mutableStateOf(false)

    private lateinit var previewSize: Size
    private lateinit var cameraId: String
    private var videoUri: Uri? = null

    private lateinit var backgroundHandlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraScreen()
        }
    }

    @Composable
    fun CameraScreen() {
        var textureView by remember { mutableStateOf<TextureView?>(null) }

        LaunchedEffect(textureView) {
            textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    setupCamera()
                    textureView?.let { openCamera(it) }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            }

        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {

                        this@apply.layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    textureView = view
                    // Ensure the listener is set after the view is initialized
                    textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            setupCamera()
                            openCamera(textureView!!)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    }
                }
            )


            Button(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = {
                    if (isRecording) {
                        stopRecording(textureView!!)
                    } else {
                        startRecording(textureView!!)
                    }
                }
            ) {
                Text(text = if (isRecording) "Stop Recording" else "Start Recording")
            }
        }
    }

    private fun setupCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                this.cameraId = cameraId
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                previewSize = map.getOutputSizes(SurfaceTexture::class.java)[0]
                break
            }
        }
    }

    private fun openCamera(textureView: TextureView) {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(textureView)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startPreview(textureView: TextureView) {
        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(surfaceTexture)

        try {
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder?.build()?.let {
                            cameraCaptureSession.setRepeatingRequest(
                                it, null, backgroundHandler)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@CameraActivity, "Preview Failed", Toast.LENGTH_SHORT).show()
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startRecording(textureView: TextureView) {
        try {
            setupMediaRecorder()
            val surfaceTexture = textureView.surfaceTexture!!
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(surfaceTexture)
            val recorderSurface = mediaRecorder.surface

            val captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder?.addTarget(previewSurface)
            captureRequestBuilder?.addTarget(recorderSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        captureRequestBuilder?.build()?.let {
                            cameraCaptureSession.setRepeatingRequest(
                                it, null, backgroundHandler)
                        }
                        mediaRecorder.start()
                        isRecording = true
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@CameraActivity, "Recording Failed", Toast.LENGTH_SHORT).show()
                    }
                },
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecording(textureView: TextureView) {
        mediaRecorder.stop()
        mediaRecorder.reset()
        startPreview(textureView)
        isRecording = false
    }

    private fun setupMediaRecorder() {
        try {
            videoUri = getVideoUri()

            mediaRecorder = MediaRecorder()
            mediaRecorder.reset()
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(contentResolver.openFileDescriptor(videoUri!!, "w")!!.fileDescriptor)
            mediaRecorder.setVideoEncodingBitRate(10000000)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoSize(1920, 1080)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.prepare()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "MediaRecorder prepare failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getVideoUri(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "video_no_audio.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/MyApp")
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
    }

    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundHandlerThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundHandlerThread.quitSafely()
        try {
            backgroundHandlerThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
    }

    override fun onPause() {
        super.onPause()
        stopBackgroundThread()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        mediaRecorder.release()
    }
}

