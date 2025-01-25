package com.emon.camera2demo


import android.content.ContentValues
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity

class CameraActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var recordButton: Button
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var mediaRecorder: MediaRecorder
    private var isRecording = false

    private lateinit var previewSize: Size
    private lateinit var cameraId: String
    private var videoUri: Uri? = null

    private lateinit var backgroundHandlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.camera_activity)

        textureView = findViewById(R.id.texture_view)
        recordButton = findViewById(R.id.btn_record)

        setupTextureListener()

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun setupTextureListener() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                setupCamera()
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
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

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
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

    private fun startPreview() {
        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(surfaceTexture)

        try {
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
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

    private fun startRecording() {
        try {
            setupMediaRecorder()
            val surfaceTexture = textureView.surfaceTexture!!
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(surfaceTexture)
            val recorderSurface = mediaRecorder.surface

            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            captureRequestBuilder.addTarget(previewSurface)
            captureRequestBuilder.addTarget(recorderSurface)

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        mediaRecorder.start()
                        isRecording = true
                        recordButton.text = "Stop Recording"
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@CameraActivity, "Recording Failed", Toast.LENGTH_SHORT).show()
                    }
                },
                null//backgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        mediaRecorder.stop()
        mediaRecorder.reset()
        startPreview()
        isRecording = false
        recordButton.text = "Start Recording"
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
