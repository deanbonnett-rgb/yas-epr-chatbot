package app.parkedvideo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Phone-side screen: grants permissions and explains how to enable the app in Android Auto. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val button = Button(this).apply {
            setText(R.string.grant_permissions)
            setOnClickListener { requestPermissions(PERMISSIONS, REQUEST_CODE) }
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val instructions = TextView(this).apply {
            setText(R.string.setup_instructions)
            textSize = 16f
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(button)
            addView(status)
            addView(instructions)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun updateStatus() {
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val carSpeed = granted(CAR_SPEED)
        status.text = "Car speed: ${if (carSpeed) "allowed" else "not allowed"}\n" +
            "Phone location: ${if (location) "allowed" else "not allowed"}"
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_CODE = 1
        const val CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED"
        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            CAR_SPEED,
        )
    }
}
