package io.github.dohex.openrose.module.presentation

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject

internal class FocusNotificationTemplate(private val appContext: Context) {
    fun promptExtras(snapshot: RenderedSnapshot, title: String, durationMillis: Long): Bundle {
        val durationSeconds = ((durationMillis + 999L) / 1_000L).coerceIn(1L, 5L).toInt()
        val template = JSONObject()
            .put("protocol", 1)
            .put("business", STATUS_CARD_BUSINESS)
            .put("islandFirstFloat", true)
            .put("enableFloat", true)
            .put("ticker", title)
            .put("tickerPic", PROMPT_LEFT_PICTURE)
            .put("isShowNotification", false)
            .put("iconTextInfo", cardInfo(title, snapshot.batterySummary(), snapshot.knownNoiseMode()?.let(::modeLabel)))
            .put("param_island", promptIsland(snapshot).put("islandTimeout", durationSeconds))

        return Bundle().apply {
            putString(FOCUS_PARAM, JSONObject().put("param_v2", template).toString())
            putBundle(
                FOCUS_PICTURES,
                Bundle().apply {
                    putParcelable(STATUS_CARD_PICTURE, Icon.createWithBitmap(createStatusCardBitmap(statusCardColors(snapshot))))
                    putPromptPictures(this)
                },
            )
        }
    }

    private fun cardInfo(title: String, content: String, noiseText: String?): JSONObject = JSONObject()
        .put("title", title)
        .put("content", content)
        .put("animIconInfo", JSONObject().put("type", 0).put("src", STATUS_CARD_PICTURE))
        .apply { if (noiseText != null) put("subContent", noiseText) }

    private fun promptIsland(snapshot: RenderedSnapshot): JSONObject {
        val left = snapshot.batteryValue("LEFT_BATTERY")
        val right = snapshot.batteryValue("RIGHT_BATTERY")

        fun imageText(type: Int, pictureKey: String, value: Int?): JSONObject = JSONObject()
            .put("type", type)
            .put("picInfo", JSONObject().put("type", 1).put("pic", pictureKey))
            .put(
                "textInfo",
                JSONObject()
                    .put("title", value?.toString() ?: UNKNOWN_VALUE)
                    .put("content", if (value == null) "" else "%"),
            )

        val shareContent = listOfNotNull(
            left?.let { "L: $it%" },
            right?.let { "R: $it%" },
        ).joinToString("  ").ifBlank { "电量未知" }

        return JSONObject()
            .put("islandProperty", 1)
            .put(
                "bigIslandArea",
                JSONObject()
                    .put("imageTextInfoLeft", imageText(1, PROMPT_LEFT_PICTURE, left))
                    .put("imageTextInfoRight", imageText(2, PROMPT_RIGHT_PICTURE, right)),
            )
            .put(
                "shareData",
                JSONObject()
                    .put("title", "OpenRose")
                    .put("content", shareContent)
                    .put("shareContent", shareContent),
            )
    }

    private fun putPromptPictures(pictures: Bundle) {
        pictures.putParcelable(PROMPT_LEFT_PICTURE, Icon.createWithBitmap(createEarbudBitmap(leftSide = true)))
        pictures.putParcelable(PROMPT_RIGHT_PICTURE, Icon.createWithBitmap(createEarbudBitmap(leftSide = false)))
    }

    private fun createEarbudBitmap(leftSide: Boolean): Bitmap {
        val bitmap = squareBitmap(72)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val direction = if (leftSide) 1f else -1f
        canvas.save()
        if (direction < 0f) canvas.scale(-1f, 1f, 36f, 36f)
        canvas.drawRoundRect(RectF(26f, 12f, 47f, 38f), 10f, 10f, paint)
        canvas.drawRoundRect(RectF(37f, 31f, 48f, 57f), 5f, 5f, paint)
        canvas.drawCircle(36.5f, 25f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303030.toInt() })
        canvas.restore()
        return bitmap
    }

    private fun squareBitmap(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    fun statusExtras(
        snapshot: RenderedSnapshot,
        card: StatusCard,
        buttons: List<Pair<String, Notification.Action>>,
        expandOnEntrance: Boolean,
    ): Bundle {
        val aodTitle = snapshot.aodTitle()
        val info = cardInfo(card.title, card.summary, card.noiseText)
        val template = JSONObject()
            .put("protocol", 1)
            .put("business", STATUS_CARD_BUSINESS)
            .put("ticker", card.title)
            .put("islandFirstFloat", expandOnEntrance)
            .put("enableFloat", false)
            .put("updatable", true)
            .put("iconTextInfo", info)
        if (aodTitle != null) template.put("aodTitle", aodTitle).put("aodPic", STATUS_CARD_PICTURE)
        template.put("param_island", if (snapshot.statusIsland) promptIsland(snapshot) else JSONObject().put("dismissIsland", true))
        val titled = buttons.filter { it.first.isNotBlank() }.take(MAX_CARD_BUTTONS)
        if (titled.isNotEmpty()) {
            val entries = JSONArray()
            titled.forEachIndexed { index, (label, _) ->
                entries.put(JSONObject().put("action", "$STATUS_CARD_ACTION_PREFIX$index").put("actionTitle", label))
            }
            template.put("textButton", entries)
        }
        return Bundle().apply {
            putString(FOCUS_PARAM, JSONObject().put("param_v2", template).toString())
            putBundle(
                FOCUS_PICTURES,
                Bundle().apply {
                    putParcelable(STATUS_CARD_PICTURE, Icon.createWithBitmap(createStatusCardBitmap(card.iconColors)))
                    if (snapshot.statusIsland) putPromptPictures(this)
                },
            )
            if (titled.isNotEmpty()) {
                val actions = Bundle()
                titled.forEachIndexed { index, (_, action) -> actions.putParcelable("$STATUS_CARD_ACTION_PREFIX$index", action) }
                putBundle(FOCUS_ACTIONS, actions)
            }
        }
    }

    private fun createStatusCardBitmap(colors: IconColors): Bitmap {
        val bitmap = squareBitmap(STATUS_CARD_PICTURE_SIZE)
        val canvas = Canvas(bitmap)
        val scale = STATUS_CARD_PICTURE_SIZE / APP_ICON_VIEWPORT
        canvas.scale(scale, scale)
        canvas.drawPath(
            PathParser.createPathFromPathData(APP_ICON_TILE_PATH),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.tile },
        )
        canvas.translate(APP_ICON_GLYPH_OFFSET, APP_ICON_GLYPH_OFFSET)
        canvas.scale(APP_ICON_GLYPH_SCALE, APP_ICON_GLYPH_SCALE)
        canvas.translate(0f, APP_ICON_VIEWPORT)
        canvas.drawPath(
            PathParser.createPathFromPathData(APP_ICON_GLYPH_PATH),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.glyph },
        )
        return bitmap
    }

    fun statusCardColors(snapshot: RenderedSnapshot): IconColors {
        if (!snapshot.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return IconColors(tile = APP_ICON_TILE_COLOR, glyph = APP_ICON_GLYPH_COLOR)
        }
        return IconColors(
            tile = appContext.getColor(android.R.color.system_accent1_100),
            glyph = appContext.getColor(android.R.color.system_accent1_700),
        )
    }
}

internal data class IconColors(val tile: Int, val glyph: Int)

private const val UNKNOWN_VALUE = "未知"
private const val PROMPT_LEFT_PICTURE = "key_pic_left"
private const val PROMPT_RIGHT_PICTURE = "key_pic_right"
private const val FOCUS_PARAM = "miui.focus.param"
private const val FOCUS_PICTURES = "miui.focus.pics"
private const val FOCUS_ACTIONS = "miui.focus.actions"
private const val STATUS_CARD_ACTION_PREFIX = "miui.focus.action_status_"
private const val MAX_CARD_BUTTONS = 2
private const val STATUS_CARD_PICTURE = "miui.focus.pic_status"
private const val STATUS_CARD_BUSINESS = "openrose_headset_status"
private const val STATUS_CARD_PICTURE_SIZE = 224
private const val APP_ICON_VIEWPORT = 960f
private const val APP_ICON_GLYPH_OFFSET = 192f
private const val APP_ICON_GLYPH_SCALE = 0.6f
private const val APP_ICON_TILE_COLOR = 0xFFFFD9DF.toInt()
private const val APP_ICON_GLYPH_COLOR = 0xFF8F003F.toInt()
private const val APP_ICON_TILE_PATH = "M240,0H720Q960,0 960,240V720Q960,960 720,960H240Q0,960 0,720V240Q0,0 240,0Z"
private const val APP_ICON_GLYPH_PATH = "M280-120h-80q-33 0-56.5-23.5T120-200v-280q0-75 28.5-140.5t77-114q48.5-48.5 " +
    "114-77T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480v280q0 33-23.5 56.5T760-120h-80q-33 " +
    "0-56.5-23.5T600-200v-160q0-33 23.5-56.5T680-440h80v-40q0-117-81.5-198.5T480-760q-117 0-198.5 81.5T200-480v40h80q33 " +
    "0 56.5 23.5T360-360v160q0 33-23.5 56.5T280-120Zm0-240h-80v160h80v-160Zm400 0v160h80v-160h-80Zm-400 0h-80 80Zm400 " +
    "0h80-80Z"
