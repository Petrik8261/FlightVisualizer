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
import sk.dubrava.flightvisualizer.ui.main.MainActivity

class ImportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IMPORT"
    }

    private lateinit var btnPickFile: Button
    private lateinit var btnOpenPlayer: Button
    private lateinit var btnClearSelection: Button
    private lateinit var tvSelectedFile: TextView

    private lateinit var vehicleType: String
    private var selectedUri: Uri? = null

    // SAF picker
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        selectedUri = uri

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        }

        val name = guessDisplayName(uri) ?: uri.toString()
        tvSelectedFile.text = name

        setPlayerButtonEnabled(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        // príde zo StartActivity
        vehicleType = intent.getStringExtra(MainActivity.EXTRA_VEHICLE_TYPE)
            ?: MainActivity.VEHICLE_PLANE

        btnPickFile = findViewById(R.id.btnPickFile)
        btnOpenPlayer = findViewById(R.id.btnOpenPlayer)
        btnClearSelection = findViewById(R.id.btnClearSelection)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)

        setPlayerButtonEnabled(false)
        tvSelectedFile.text = "(žiadny)"

        btnPickFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        btnClearSelection.setOnClickListener {
            selectedUri = null
            tvSelectedFile.text = "(žiadny)"
            setPlayerButtonEnabled(false)
        }

        btnOpenPlayer.setOnClickListener {
            val uri = selectedUri
            if (uri == null) {
                Toast.makeText(this, "Najprv vyber súbor.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val i = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_VEHICLE_TYPE, vehicleType)
                putExtra(MainActivity.EXTRA_FILE_URI, uri.toString())
            }
            startActivity(i)
        }
    }

    private fun setPlayerButtonEnabled(enabled: Boolean) {
        btnOpenPlayer.isEnabled = enabled
        btnOpenPlayer.alpha = if (enabled) 1.0f else 0.5f
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
}



