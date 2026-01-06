package sk.dubrava.flightvisualizer.ui.start

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.ui.importdata.ImportActivity
import sk.dubrava.flightvisualizer.ui.main.MainActivity


class StartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        val btnDrone = findViewById<Button>(R.id.btnDrone)
        val btnPlane = findViewById<Button>(R.id.btnPlane)

        btnDrone.setOnClickListener { openImport(MainActivity.VEHICLE_DRONE) }
        btnPlane.setOnClickListener { openImport(MainActivity.VEHICLE_PLANE) }


    }

    private fun openImport(vehicleType: String) {
        val intent = Intent(this, ImportActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_VEHICLE_TYPE, vehicleType)
        }
        startActivity(intent)
    }


}
