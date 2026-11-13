package cn.com.omnimind.androidgui.fixture

import android.app.Activity
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 合成验收夹具内存状态中心
 * 严格只维护测试状态，不读取或暴露真实App数据、设备标识或剪贴板。
 */
object FixtureStateHolder {

    const val SCHEMA_VERSION = "omnibot.gui-fixture.v1"

    @Volatile
    var marker: String = ""
        private set

    @Volatile
    var scenario: String = "home"

    @Volatile
    var displayId: Int = -1

    @Volatile
    var persistentLoading: Boolean = false

    @Volatile
    var entryDisabled: Boolean = false

    @Volatile
    var generation: Long = 0L

    val statusFlags: MutableList<String> = mutableListOf()

    val counts: MutableMap<String, Int> = mutableMapOf(
        "tap" to 0,
        "long_press" to 0,
        "load" to 0,
        "confirm" to 0,
        "checkout" to 0,
        "submit" to 0,
        "canvas" to 0,
    )

    val formValues: MutableMap<String, String> = mutableMapOf(
        "first" to "",
        "second" to "",
    )

    private val MARKER_REGEX = Regex("^OOB_LIVE_GUI_[A-Za-z0-9_.-]+$")
    private val SYNTHETIC_VALUE_REGEX = Regex("^(OOB_LIVE_GUI_[A-Za-z0-9_.-]+|SYNTHETIC_[A-Za-z0-9_.-]+)$")

    fun isValidMarker(candidate: String): Boolean {
        return candidate.isNotBlank() && MARKER_REGEX.matches(candidate)
    }

    fun isValidSyntheticValue(candidate: String): Boolean {
        return candidate.isNotBlank() && SYNTHETIC_VALUE_REGEX.matches(candidate)
    }

    @Synchronized
    fun setMarker(newMarker: String): Boolean {
        if (!isValidMarker(newMarker)) {
            return false
        }
        marker = newMarker
        resetScenarioStateLocked()
        return true
    }

    @Synchronized
    fun reset() {
        resetScenarioStateLocked()
    }

    @Synchronized
    private fun resetScenarioStateLocked() {
        generation++
        statusFlags.clear()
        counts["tap"] = 0
        counts["long_press"] = 0
        counts["load"] = 0
        counts["confirm"] = 0
        counts["checkout"] = 0
        counts["submit"] = 0
        counts["canvas"] = 0
        formValues["first"] = ""
        formValues["second"] = ""
        persistentLoading = false
        entryDisabled = false
    }

    @Synchronized
    fun incrementCount(action: String) {
        val current = counts[action] ?: 0
        counts[action] = current + 1
    }

    @Synchronized
    fun addStatusFlag(flag: String) {
        if (!statusFlags.contains(flag)) {
            statusFlags.add(flag)
        }
    }

    @Synchronized
    fun setFormInput(first: String, second: String): Boolean {
        if (!isValidSyntheticValue(first) || !isValidSyntheticValue(second)) {
            return false
        }
        formValues["first"] = first
        formValues["second"] = second
        return true
    }

    fun updateDisplayId(activity: Activity) {
        val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.displayId ?: @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.displayId
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.displayId
        }
        displayId = id
    }

    @Synchronized
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("schema_version", SCHEMA_VERSION)
        root.put("marker", marker)
        root.put("scenario", scenario)
        root.put("display_id", displayId)

        val flagsArray = JSONArray()
        for (flag in statusFlags) {
            flagsArray.put(flag)
        }
        root.put("status_flags", flagsArray)

        val countsObj = JSONObject()
        countsObj.put("tap", counts["tap"] ?: 0)
        countsObj.put("long_press", counts["long_press"] ?: 0)
        countsObj.put("load", counts["load"] ?: 0)
        countsObj.put("confirm", counts["confirm"] ?: 0)
        countsObj.put("checkout", counts["checkout"] ?: 0)
        countsObj.put("submit", counts["submit"] ?: 0)
        countsObj.put("canvas", counts["canvas"] ?: 0)
        root.put("counts", countsObj)

        val formObj = JSONObject()
        formObj.put("first", formValues["first"] ?: "")
        formObj.put("second", formValues["second"] ?: "")
        root.put("form_values", formObj)

        root.put("persistent_loading", persistentLoading)
        root.put("entry_disabled", entryDisabled)

        return root.toString()
    }
}
