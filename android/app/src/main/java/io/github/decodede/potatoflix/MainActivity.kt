package io.github.decodede.potatoflix

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.CommonActivity
import io.github.decodede.potatoflix.ui.AppRoot
import io.github.decodede.potatoflix.ui.CloudStreamTheme
import io.github.decodede.potatoflix.ui.Nav
import io.github.decodede.potatoflix.ui.PendingRepo
import io.github.decodede.potatoflix.ui.Tab

/**
 * The app's screen. It is the official app's MainActivity type (an AppCompatActivity), since extensions' settings
 * screens expect one (supportFragmentManager) and some check `activity is MainActivity`.
 */
class MainActivity : com.lagradost.cloudstream3.MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light icons on dark bars, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        CommonActivity.activity = this
        handle(intent)
        setContent {
            CloudStreamTheme {
                AppRoot()
                // Over every screen, the player included.
                io.github.decodede.potatoflix.ui.GlitchDialog()
                io.github.decodede.potatoflix.ui.GitHubReportQr()
            }
        }
    }

    private val shake by lazy { ShakeToReport(this) }

    override fun onResume() {
        super.onResume()
        shake.start()
    }

    override fun onPause() {
        shake.stop()
        super.onPause()
    }

    override fun onStop() {
        // Google Drive backup: save what changed before the app is put away.
        CloudBackup.soon()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.activity = null
        super.onDestroy()
    }

    /** cloudstreamrepo://… and https://cs.repo/… links open the Add repository dialog. */
    private fun handle(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        PendingRepo.url = data
        Nav.select(Tab.EXTENSIONS)
    }
}
