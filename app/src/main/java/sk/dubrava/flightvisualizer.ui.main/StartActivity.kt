package sk.dubrava.flightvisualizer.ui.start

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import sk.dubrava.flightvisualizer.R
import sk.dubrava.flightvisualizer.core.AppNav
import sk.dubrava.flightvisualizer.ui.importdata.ImportActivity

class StartActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)
        findViewById<Button>(R.id.btnExit).setOnClickListener {
            finishAffinity()   // zavrie všetky aktivity
        }


        val btnDrone = findViewById<Button>(R.id.btnDrone)
        val btnPlane = findViewById<Button>(R.id.btnPlane)

        btnDrone.setOnClickListener { openImport(AppNav.VEHICLE_DRONE) }
        btnPlane.setOnClickListener { openImport(AppNav.VEHICLE_PLANE) }
    }

    private fun openImport(vehicleType: String) {
        startActivity(Intent(this, ImportActivity::class.java).apply {
            putExtra(AppNav.EXTRA_VEHICLE_TYPE, vehicleType)
        })
    }

}
