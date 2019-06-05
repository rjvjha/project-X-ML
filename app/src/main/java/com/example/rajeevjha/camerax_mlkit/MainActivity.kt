package com.example.rajeevjha.camerax_mlkit

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 42
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private lateinit var mViewFinder: TextureView
    private lateinit var mLabelTextView: TextView
    private var rotation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mViewFinder = view_finder
        mLabelTextView = labelTextView

        if (allPermissionsGranted()) {
            view_finder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        mViewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

    }

    private fun startCamera() {

        val previewConfig = PreviewConfig.Builder().apply {
            setTargetAspectRatio(Rational(1, 1))
        }.build()

        val preview = Preview(previewConfig)

        preview.setOnPreviewOutputUpdateListener {
            mViewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            val analyzerThread = HandlerThread(
                "LabelAnalysis"
            ).apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))

            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE
            )
        }
            .build()

        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            analyzer = LabelAnalyzer(mLabelTextView)
        }
        CameraX.bindToLifecycle(this, preview, analyzerUseCase)
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Find the center
        val centerX = mViewFinder.width / 2f
        val centerY = mViewFinder.height / 2f

        // Get correct rotation
        rotation = when (mViewFinder.display.rotation) {

            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }

        matrix.postRotate(-rotation.toFloat(), centerX, centerY)
        mViewFinder.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                view_finder.post { startCamera() }

            }
        }
    }

    // checks whether required permissions are granted or not
    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true

    }

    // custom LabelAnalyzer class
    private class LabelAnalyzer(val textView: TextView) : ImageAnalysis.Analyzer {

        private var lastAnalyzedTimestamp = 0L

        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            val currentTimeStamp = System.currentTimeMillis()

            if (currentTimeStamp - lastAnalyzedTimestamp >=
                TimeUnit.SECONDS.toMillis(1)
            ) {
                lastAnalyzedTimestamp = currentTimeStamp
            }

            // get the three planes in YUV format
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]

            // no of pixels in each plane
            val Yb = y.buffer.remaining()
            val Ub = u.buffer.remaining()
            val Vb = v.buffer.remaining()

            // convert into single YUV formatted ByteArray
            val data = ByteArray(Yb + Ub + Vb)
            y.buffer.get(data, 0, Yb)
            u.buffer.get(data, Yb, Ub)
            v.buffer.get(data, Yb + Ub, Vb)

            val metaData = FirebaseVisionImageMetadata.Builder()
                .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_YV12)
                .setHeight(image.height)
                .setWidth(image.width)
                .setRotation(getRotation(rotationDegrees))
                .build()

            val labelImage = FirebaseVisionImage.fromByteArray(data, metaData)

            val labeler = FirebaseVision.getInstance().onDeviceImageLabeler

            labeler.processImage(labelImage)
                .addOnSuccessListener { labels ->
                    textView.run {
                        if (labels.size >= 1) {
                            text = labels[0].text + " " + labels[0].confidence
                        }

                    }
                }

        }

        private fun getRotation(rotationCompensation: Int): Int {
            val result: Int
            when (rotationCompensation) {
                0 -> result = FirebaseVisionImageMetadata.ROTATION_0
                90 -> result = FirebaseVisionImageMetadata.ROTATION_90
                180 -> result = FirebaseVisionImageMetadata.ROTATION_180
                270 -> result = FirebaseVisionImageMetadata.ROTATION_270
                else -> {
                    result = FirebaseVisionImageMetadata.ROTATION_0
                }
            }
            return result
        }

    }

}
