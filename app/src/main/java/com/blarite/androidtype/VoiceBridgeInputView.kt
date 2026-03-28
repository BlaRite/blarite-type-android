package com.blarite.androidtype

import android.content.Context
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// This file contains a traditional Android View-based IME UI.
// It is intentionally simple and robust because input methods can be more
// sensitive than normal activities when it comes to UI lifecycle issues.
class VoiceBridgeInputView(context: Context) : LinearLayout(context) {
    // The IME service assigns these callbacks after creating the view.
    // The view itself only knows when buttons are tapped; it does not decide what happens next.
    var onDone: (() -> Unit)? = null
    var onSwitchKeyboard: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null

    // These child views are stored as properties so `render()` can update them later.
    private val headerView: TextView
    private val transcriptView: TextView
    private val waveformView: WaveformBarsView
    private val configButton: Button
    private val switchButton: Button
    private val doneButton: Button

    init {
        // The root view is just a vertical stack with a small outer margin.
        orientation = VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        minimumHeight = dp(224)

        // This card is the visible panel that fills the keyboard area.
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#F0202530"), Color.parseColor("#F01F3A5F"))
            ).apply {
                cornerRadius = dp(20).toFloat()
            }
        }
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, dp(224)))

        // Top row: compact title/status text on the left, settings button on the right.
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // A single header line saves space compared with separate title and status lines.
        headerView = TextView(context).apply {
            text = "BlaRite Android Type"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerRow.addView(headerView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })

        // This opens the configuration activity without forcing the user to leave the IME manually.
        configButton = Button(context).apply {
            text = "Config"
            textSize = 12f
            minHeight = dp(32)
            minimumHeight = dp(32)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onOpenSettings?.invoke() }
        }
        headerRow.addView(configButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))

        // The transcript area is deliberately scrollable so long dictation does not get cut off.
        transcriptView = TextView(context).apply {
            text = "Speech will appear here as it is recognized."
            textSize = 15f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(Color.WHITE)
            minLines = 4
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1FFFFFFF"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
            scrollBarStyle = SCROLLBARS_INSIDE_INSET
        }
        card.addView(transcriptView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(10)
        })

        // Bottom row: live waveform plus the action buttons the user needs while dictating.
        val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(footerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        // The waveform is stretched to use the remaining space so buttons can stay compact.
        waveformView = WaveformBarsView(context)
        footerRow.addView(waveformView, LayoutParams(0, dp(30), 1f).apply {
            marginEnd = dp(8)
        })

        // `Switch` leaves this keyboard and asks Android to restore the previous one.
        switchButton = Button(context).apply {
            text = "Switch"
            textSize = 13f
            minHeight = dp(34)
            minimumHeight = dp(34)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onSwitchKeyboard?.invoke() }
        }
        footerRow.addView(switchButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        })

        // `Done` tells the IME to finalize recognition and commit the buffered text.
        doneButton = Button(context).apply {
            text = "Done"
            textSize = 13f
            minHeight = dp(34)
            minimumHeight = dp(34)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { onDone?.invoke() }
        }
        footerRow.addView(doneButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(
        isListening: Boolean,
        status: String,
        transcriptPreview: String,
        waveformLevel: Float,
        showDone: Boolean
    ) {
        // Merge the title and status into one compact line so the transcript gets more space.
        val title = if (isListening) "Listening..." else "BlaRite Android Type"
        headerView.text = if (status.isBlank()) title else "$title • $status"
        // Show placeholder text until Soniox has produced any preview text.
        transcriptView.text = if (transcriptPreview.isBlank()) {
            "Speech will appear here as it is recognized."
        } else {
            transcriptPreview
        }
        // `post {}` runs after layout is updated, which means the TextView knows its final height.
        // That lets us scroll to the newest line reliably after changing the text.
        transcriptView.post {
            val layout = transcriptView.layout ?: return@post
            val scrollY = layout.getLineTop(layout.lineCount) - transcriptView.height + transcriptView.paddingBottom
            transcriptView.scrollTo(0, scrollY.coerceAtLeast(0))
        }
        doneButton.visibility = if (showDone) View.VISIBLE else View.GONE
        waveformView.level = waveformLevel
        waveformView.isActive = isListening
        waveformView.invalidate()
    }

    private fun dp(value: Int): Int {
        // Android layout sizes are usually defined in density-independent pixels (dp).
        // This helper converts dp into real screen pixels for manual View code.
        return (value * resources.displayMetrics.density).toInt()
    }
}

// A tiny custom view that draws five animated-looking bars from a single level value.
class WaveformBarsView(context: Context) : View(context) {
    // The IME updates these values on every audio level change, then asks the view to redraw.
    var level: Float = 0f
    var isActive: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // A subtle gradient makes the bars look a little nicer than a flat solid color.
        val shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            intArrayOf(Color.WHITE, Color.parseColor("#D6FFFFFF")),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = shader

        val barCount = 5
        val spacing = width / 9f
        val barWidth = spacing
        val normalizedLevel = if (isActive) level.coerceIn(0.08f, 1f) else 0f

        repeat(barCount) { index ->
            // Each bar gets a slightly different bias so they do not all move identically.
            val normalizedBias = 0.35f + (index * 0.13f)
            val barHeight = height * (0.18f + normalizedLevel * normalizedBias)
            val left = index * spacing * 1.8f
            val top = (height - barHeight) / 2f
            rect.set(left, top, left + barWidth, top + barHeight)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }

        paint.shader = null
    }
}
