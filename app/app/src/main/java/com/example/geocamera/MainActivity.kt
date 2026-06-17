package com.example.geocamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * GeoCamera
 *
 * Takes a photo, tags it with the device's current GPS location
 * (written into the JPEG EXIF data so any gallery/maps app can read it),
 * stamps the coordinates + timestamp visibly on the image, and saves
 * the result straight into the system Photos/Gallery app via MediaStore
 * (Pictures/GeoCamera album) — no extra "save" step needed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var locationText: TextView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private var locationCallback: LocationCallback? = null

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permissionRequestCode = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        locationText = findViewById(R.id.locationText)
        val captureButton = findViewById<ImageButton>(R.id.captureButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
        }

        captureButton.setOnClickListener { takePhoto() }
    }

    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (allPermissionsGranted()) {
                startCamera()
                startLocationUpdates()
            } else {
                Toast.makeText(
                    this,
                    "Camera and location permissions are required to use GeoCamera",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    currentLocation = loc
                    locationText.text = String.format(
                        Locale.US, "Lat %.6f   Lon %.6f", loc.latitude, loc.longitude
                    )
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback as LocationCallback, Looper.getMainLooper()
        )
        fusedLocationClient.lastLocation.addOnSuccessListener { loc -> if (loc != null) currentLocation = loc }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        if (currentLocation == null) {
            Toast.makeText(this, "Still finding your location, try again in a moment", Toast.LENGTH_SHORT).show()
        }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "GEO_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GeoCamera")
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return
                    cameraExecutor.execute {
                        try {
                            val orientation = readOrientation(savedUri)
                            stampLocationOnImage(savedUri)
                            writeExif(savedUri, orientation)
                        } catch (e: Exception) {
                            Log.e(TAG, "Post-processing failed", e)
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    Toast.makeText(
                        this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /** Reads the orientation tag CameraX wrote, so we can restore it after recompressing. */
    private fun readOrientation(uri: Uri): Int {
        contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        return ExifInterface.ORIENTATION_NORMAL
    }

    /** Draws a readable lat/lon + timestamp stamp onto the bottom of the photo, like dedicated geo-camera apps. */
    private fun stampLocationOnImage(uri: Uri) {
        val loc = currentLocation

        val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        original.recycle()

        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = bitmap.width * 0.032f
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val locLine = if (loc != null) {
            String.format(Locale.US, "Lat %.6f, Lon %.6f (±%.0fm)", loc.latitude, loc.longitude, loc.accuracy)
        } else {
            "Location unavailable"
        }

        val padding = bitmap.width * 0.03f
        val lineHeight = paint.textSize * 1.35f
        canvas.drawText(locLine, padding, bitmap.height - lineHeight * 2f, paint)
        canvas.drawText(timestamp, padding, bitmap.height - lineHeight, paint)

        contentResolver.openOutputStream(uri, "wt")?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()
    }

    /** Writes GPS coordinates + restores orientation into the JPEG's EXIF metadata. */
    private fun writeExif(uri: Uri, orientation: Int) {
        val loc = currentLocation
        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            if (loc != null) exif.setGpsInfo(loc)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "GeoCamera"
    }
}
