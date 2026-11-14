package io.github.alexvishnevsky.flashlight3d

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

/** The views that sit on top of the 3D surface: settings and the crash report. */
object Panels {

    private const val STEPS = 1000

    private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun format(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(Locale.US, "%.2f", value)

    /** The small, dim gear in the corner. */
    fun gearButton(activity: Activity, onClick: () -> Unit): View =
        TextView(activity).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(110, 255, 255, 255))
            setOnClickListener { onClick() }
            layoutParams = FrameLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = activity.dp(36)
                rightMargin = activity.dp(8)
            }
        }

    /**
     * The admin panel: one control per [P], generated from the enum. It covers
     * the lower part of the screen so changes can be watched while dragging.
     */
    fun settings(
        activity: Activity,
        onChanged: (P?) -> Unit,
        onReport: () -> Unit,
        onClose: () -> Unit,
    ): View {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(32))
        }
        val refreshers = mutableListOf<() -> Unit>()

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun addButton(label: String, action: () -> Unit) {
            buttons.addView(
                Button(activity).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        addButton("Закрити") { onClose() }
        addButton("Скинути") {
            Settings.reset()
            refreshers.forEach { it() }
            onChanged(null)
        }
        addButton("Звіт") { onReport() }
        list.addView(buttons)

        var section = ""
        for (param in P.entries) {
            if (param.section != section) {
                section = param.section
                list.addView(
                    TextView(activity).apply {
                        text = section
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.rgb(255, 216, 107))
                        setPadding(0, activity.dp(18), 0, activity.dp(4))
                    },
                )
            }
            if (param.toggle) {
                val switch = Switch(activity).apply {
                    text = param.title
                    setTextColor(Color.WHITE)
                    isChecked = Settings.on(param)
                    setPadding(0, activity.dp(6), 0, activity.dp(6))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked != Settings.on(param)) {
                            Settings[param] = if (checked) 1f else 0f
                            onChanged(param)
                        }
                    }
                }
                refreshers += { switch.isChecked = Settings.on(param) }
                list.addView(switch)
            } else {
                val label = TextView(activity).apply {
                    setTextColor(Color.WHITE)
                    setPadding(0, activity.dp(8), 0, 0)
                }
                val bar = SeekBar(activity).apply { max = STEPS }
                fun show() {
                    label.text = "${param.title}: ${format(Settings[param])}"
                }
                fun sync() {
                    bar.progress = ((Settings[param] - param.min) / (param.max - param.min) * STEPS).toInt()
                    show()
                }
                sync()
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        Settings[param] = param.min + (param.max - param.min) * progress / STEPS
                        show()
                        onChanged(param)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
                refreshers += { sync() }
                list.addView(label)
                list.addView(bar)
            }
        }

        return ScrollView(activity).apply {
            addView(list)
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 16, 18, 22))
                cornerRadii = floatArrayOf(40f, 40f, 40f, 40f, 0f, 0f, 0f, 0f)
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.5f).toInt(),
            ).apply { gravity = Gravity.BOTTOM }
        }
    }

    /**
     * Full-screen text report with copy / share. [actions] adds extra buttons
     * (label to handler), e.g. "try again" on the crash screen.
     */
    fun report(activity: Activity, title: String, actions: List<Pair<String, () -> Unit>>): View {
        val body = TextView(activity).apply {
            text = "Збираю звіт…"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(210, 214, 220))
            setTextIsSelectable(true)
        }
        var reportText = ""
        thread {
            val text = try {
                Diagnostics.report(activity)
            } catch (e: Throwable) {
                "Не вдалося зібрати звіт: $e"
            }
            activity.runOnUiThread {
                reportText = text
                body.text = text
            }
        }

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun addButton(label: String, action: () -> Unit) {
            buttons.addView(
                Button(activity).apply {
                    text = label
                    isAllCaps = false
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        addButton("Копіювати") {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Flashlight 3D", reportText))
            Toast.makeText(activity, "Скопійовано", Toast.LENGTH_SHORT).show()
        }
        addButton("Поділитись") {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, reportText)
            activity.startActivity(Intent.createChooser(send, "Звіт Flashlight 3D"))
        }
        for ((label, action) in actions) addButton(label, action)

        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 11, 14))
            setPadding(activity.dp(14), activity.dp(44), activity.dp(14), activity.dp(24))
            addView(
                TextView(activity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, activity.dp(10))
                },
            )
            addView(buttons)
            addView(
                ScrollView(activity).apply { addView(body) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        return column
    }
}
