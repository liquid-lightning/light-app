package app.light.wallet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A water tap with a drop under its spout - the testnet faucet button. */
val FaucetIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Faucet",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // Handle bar and its stem.
            moveTo(8f, 3f); lineTo(16f, 3f); lineTo(16f, 5f); lineTo(8f, 5f); close()
            moveTo(11f, 5f); lineTo(13f, 5f); lineTo(13f, 8f); lineTo(11f, 8f); close()
            // Wall flange.
            moveTo(2f, 7f); lineTo(4f, 7f); lineTo(4f, 13f); lineTo(2f, 13f); close()
            // Body, bending down into the spout.
            moveTo(4f, 8f); lineTo(15f, 8f)
            curveTo(16.66f, 8f, 18f, 9.34f, 18f, 11f)
            lineTo(18f, 15f); lineTo(14f, 15f); lineTo(14f, 12f); lineTo(4f, 12f); close()
            // Drop.
            moveTo(16f, 16.5f)
            curveTo(16f, 16.5f, 17.6f, 18.3f, 17.6f, 19.4f)
            curveTo(17.6f, 20.3f, 16.9f, 21f, 16f, 21f)
            curveTo(15.1f, 21f, 14.4f, 20.3f, 14.4f, 19.4f)
            curveTo(14.4f, 18.3f, 16f, 16.5f, 16f, 16.5f)
            close()
        }
    }.build()
}
