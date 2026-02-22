package sk.dubrava.flightvisualizer.ui.importdata

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
import java.util.Locale

class ImportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IMPORT"
        private const val STATE_URI = "state_uri"
        private const val STATE_VEHICLE = "state_vehicle"
    }

    private lateinit var btnPickFile: Button
    private lateinit var btnClearSelection: Button
    private lateinit var tvSelectedFile: TextView

    private lateinit var vehicleType: String
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
        val ok = isSupportedFile(displayName)

        if (!ok) {
            selectedUri = null
            tvSelectedFile.text = "(nepodporovaný súbor)"
            Toast.makeText(this, "Podporované súbory: .kml, .txt, .csv", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        selectedUri = uri
        tvSelectedFile.text = displayName

        // ✅ NOVÉ: po výbere hneď otvor Summary obrazovku
        openSummary(uri, displayName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        findViewById<Button>(R.id.btnBackToDevice).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnExitImport).setOnClickListener { finishAffinity() }

        if (savedInstanceState != null) {
            vehicleType = savedInstanceState.getString(STATE_VEHICLE) ?: AppNav.VEHICLE_PLANE
            selectedUri = savedInstanceState.getString(STATE_URI)?.let { Uri.parse(it) }
        } else {
            vehicleType = intent.getStringExtra(AppNav.EXTRA_VEHICLE_TYPE) ?: AppNav.VEHICLE_PLANE
        }

        btnPickFile = findViewById(R.id.btnPickFile)
        btnClearSelection = findViewById(R.id.btnClearSelection)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)

        if (selectedUri != null) {
            val name = guessDisplayName(selectedUri!!) ?: selectedUri.toString()
            tvSelectedFile.text = name
        } else {
            tvSelectedFile.text = "(žiadny)"
        }

        btnPickFile.setOnClickListener {
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

        btnClearSelection.setOnClickListener {
            selectedUri = null
            tvSelectedFile.text = "(žiadny)"
        }

        Log.i(TAG, "onCreate saved=${savedInstanceState != null} vehicleType=$vehicleType selectedUri=$selectedUri")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_VEHICLE, vehicleType)
        outState.putString(STATE_URI, selectedUri?.toString())
    }

    private fun openSummary(uri: Uri, displayName: String) {
        val i = Intent(this, DataSummaryActivity::class.java).apply {
            putExtra(AppNav.EXTRA_VEHICLE_TYPE, vehicleType)
            putExtra(AppNav.EXTRA_FILE_URI, uri.toString())
            putExtra(DataSummaryActivity.EXTRA_FILENAME, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
        // voliteľné: aby sa import už nevracal do hry s vybraným súborom
        // finish()
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