package com.jameslongo.geococcyx

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * The tile (widget) one swipe from the watch face: Run, Intervals and Pacer, each opening the app
 * where its chip on the start screen would ([MainActivity.EXTRA_LAUNCH]).
 */
class RunTile : TileService() {
    private val scope = MainScope()

    override fun onTileRequest(req: RequestBuilders.TileRequest) = scope.future {
        val dp = req.deviceConfiguration
        val layout = PrimaryLayout.Builder(dp)
            .setPrimaryLabelTextContent(
                Text.Builder(this@RunTile, getString(R.string.app_name))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(ACCENT)).build(),
            )
            .setContent(
                MultiButtonLayout.Builder()
                    .addButtonContent(text("Intervals", MainActivity.LAUNCH_INTERVALS))
                    .addButtonContent(
                        Button.Builder(this@RunTile, open(MainActivity.LAUNCH_RUN))
                            .setIconContent(ICON)
                            .setSize(ButtonDefaults.LARGE_SIZE)
                            .setButtonColors(ButtonColors(argb(ACCENT), argb(0xFF000000.toInt())))
                            .setContentDescription("Run")
                            .build(),
                    )
                    .addButtonContent(text("Pacer", MainActivity.LAUNCH_PACER))
                    .build(),
            )
            .build()
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest) = scope.future {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                ICON,
                ResourceBuilders.ImageResource.Builder().setAndroidResourceByResId(
                    ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_roadrunner).build(),
                ).build(),
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun text(label: String, launch: String) = Button.Builder(this, open(launch))
        .setTextContent(label, Typography.TYPOGRAPHY_CAPTION2)
        .setButtonColors(ButtonColors(argb(0xFF262626.toInt()), argb(0xFFFFFFFF.toInt())))
        .setContentDescription(label)
        .build()

    private fun open(launch: String) = ModifiersBuilders.Clickable.Builder()
        .setId(launch)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_LAUNCH, ActionBuilders.stringExtra(launch))
                    .build(),
            ).build(),
        )
        .build()

    private fun argb(c: Int) = androidx.wear.protolayout.ColorBuilders.argb(c)

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ICON = "roadrunner"
        private const val ACCENT = 0xFFC8F23A.toInt()
    }
}
