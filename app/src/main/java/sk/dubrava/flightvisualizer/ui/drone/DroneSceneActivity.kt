package sk.dubrava.flightvisualizer.ui.drone

import android.content.ContentValues.TAG
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.View
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import sk.dubrava.flightvisualizer.R

class DroneSceneActivity : AppCompatActivity() {

    private lateinit var sceneView: SceneView
    private var droneNode: ModelNode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drone_scene)

        sceneView = findViewById(R.id.droneSceneView)

        setupDroneScene()

    }

    private fun setupDroneScene() {
        sceneView = findViewById(R.id.droneSceneView)

        sceneView.apply {
            setZOrderOnTop(true)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.TRANSLUCENT)

            scene.skybox = null

            renderer.clearOptions = renderer.clearOptions.apply {
                clear = true
                clearColor = floatArrayOf(0f, 0f, 0f, 0f) // alpha 0 = priehľadné
            }

            view.blendMode = View.BlendMode.TRANSLUCENT

            cameraNode.position = Position(0f, 0f, 4.0f)
            cameraNode.rotation = Rotation(0f, 0f, 0f)

            setOnTouchListener { _, _ -> true }
        }

        droneNode = ModelNode(
            position = Position(0f, 0f, 0f),
            rotation = Rotation(0f, 0f, 0f),
            scale = Scale(0.6f) // prípadne 1.0f ak je malý
        ).apply { isVisible = true }

        sceneView.addChild(droneNode!!)

        droneNode!!.loadModelAsync(
            context = this,
            lifecycle = lifecycle,
            glbFileLocation = "models/drone.glb", // alebo drone_lowpoly.glb podľa toho čo chceš
            onLoaded = { Log.i(TAG, "✔️ DRONE model načítaný") },
            onError = { e ->
                Log.e(TAG, "Chyba pri načítaní DRONE modelu", e)
                Toast.makeText(this, "Chyba modelu: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

}




