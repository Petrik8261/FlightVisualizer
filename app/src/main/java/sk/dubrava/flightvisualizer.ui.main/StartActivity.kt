package sk.dubrava.flightvisualizer.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.ui.importdata.DataSummaryActivity
import java.util.Locale

class StartActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "START"
    }

    private lateinit var tvSelectedFile: TextView
    private lateinit var btnClear: Button

    private var selectedUri: Uri? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        }

        val displayName = guessDisplayName(uri) ?: uri.toString()
        if (!isSupportedFile(displayName)) {
            selectedUri = null
            tvSelectedFile.text = "(nepodporovaný súbor)"
            updateClearButtonState()
            Toast.makeText(this, "Podporované súbory: .kml, .txt, .csv", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        selectedUri = uri
        tvSelectedFile.text = displayName
        updateClearButtonState()

        openSummary(uri, displayName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        tvSelectedFile = findViewById(R.id.tvSelectedFile)
        btnClear = findViewById(R.id.btnClearSelection)

        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            pickFileLauncher.launch(
                arrayOf(
                    "application/vnd.google-earth.kml+xml",
                    "text/plain",
                    "text/csv",
                    "application/csv",
                    "*/*"
                )
            )
        }

        btnClear.setOnClickListener {
            selectedUri = null
            tvSelectedFile.text = "(žiadny)"
            updateClearButtonState()
        }

        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAffinity()
        }

        tvSelectedFile.text = "(žiadny)"
        updateClearButtonState()
    }

    private fun updateClearButtonState() {
        val enabled = selectedUri != null
        btnClear.isEnabled = enabled
        btnClear.alpha = if (enabled) 1.0f else 0.45f
    }

    private fun openSummary(uri: Uri, displayName: String) {
        val vehicleType = AppNav.VEHICLE_PLANE

        val i = Intent(this, DataSummaryActivity::class.java).apply {
            putExtra(AppNav.EXTRA_VEHICLE_TYPE, vehicleType)
            putExtra(AppNav.EXTRA_FILE_URI, uri.toString())
            putExtra(DataSummaryActivity.EXTRA_FILENAME, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
    }

    private fun guessDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "guessDisplayName failed: ${e.message}")
            null
        }
    }

    private fun isSupportedFile(nameOrUri: String): Boolean {
        val s = nameOrUri.lowercase(Locale.ROOT)
        return s.endsWith(".kml") || s.endsWith(".txt") || s.endsWith(".csv")
    }
}