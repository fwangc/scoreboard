package com.fanc.scorecard

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

/**
 * Full-screen scoreboard for 2–6 players.
 *
 * Each player gets a colored panel showing their name and score. Tapping the
 * upper part of a panel adds a point, tapping the lower part subtracts one.
 * The small control tap opens player setup (count + names); holding it resets
 * all scores.
 */
class MainActivity : AppCompatActivity() {

    private val scores = IntArray(MAX_PLAYERS)
    /** Custom names; blank means "use the default Player N name". */
    private val names = Array(MAX_PLAYERS) { "" }
    private var playerCount = MIN_PLAYERS

    private val scoreViews = mutableListOf<TextView>()
    private lateinit var board: LinearLayout
    private lateinit var resetButton: View
    private lateinit var prefs: SharedPreferences

    private var toneGenerator: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        enterImmersiveMode()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()

        board = findViewById(R.id.board)
        resetButton = findViewById(R.id.resetButton)

        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 70) }.getOrNull()

        resetButton.setOnClickListener { showPlayersDialog() }
        resetButton.setOnLongClickListener {
            resetScores()
            true
        }

        buildBoard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    /** Hides the status and navigation bars so the board fills the whole screen. */
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    // ---- Board layout ----

    /** Panels per row: up to 3 in one row, 4 as 2x2, 5 as 3+2, 6 as 3+3. */
    private fun rowSizes(count: Int): List<Int> = when (count) {
        in 1..3 -> listOf(count)
        4 -> listOf(2, 2)
        5 -> listOf(3, 2)
        else -> listOf(3, 3)
    }

    private fun buildBoard() {
        board.removeAllViews()
        scoreViews.clear()

        val rows = rowSizes(playerCount)
        var index = 0
        for (rowSize in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            board.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            repeat(rowSize) {
                val panel = layoutInflater.inflate(R.layout.player_panel, row, false)
                bindPanel(panel, index++)
                row.addView(panel)
            }
        }

        // Sit the control on a divider when there is one at the center; otherwise
        // tuck it at the bottom edge so it doesn't cover a score.
        val centeredOnDivider = rows.all { it % 2 == 0 }
        (resetButton.layoutParams as FrameLayout.LayoutParams).gravity =
            if (centeredOnDivider) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        resetButton.requestLayout()

        updateScoreViews()
    }

    private fun bindPanel(panel: View, player: Int) {
        val (topColor, bottomColor) = PLAYER_COLORS[player]
        panel.findViewById<View>(R.id.inc).apply {
            setBackgroundColor(getColor(topColor))
            setOnClickListener { change(player, 1) }
        }
        panel.findViewById<View>(R.id.dec).apply {
            setBackgroundColor(getColor(bottomColor))
            setOnClickListener { change(player, -1) }
        }
        panel.findViewById<TextView>(R.id.name).text = displayName(player)
        scoreViews += panel.findViewById<TextView>(R.id.score)
    }

    private fun displayName(player: Int): String =
        names[player].ifBlank { defaultName(player) }

    private fun defaultName(player: Int): String =
        getString(R.string.default_player_name, player + 1)

    // ---- Player setup ----

    private fun showPlayersDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_players, null)
        val countGroup = view.findViewById<RadioGroup>(R.id.playerCountGroup)
        val nameFields = NAME_FIELD_IDS.map { view.findViewById<EditText>(it) }

        nameFields.forEachIndexed { i, field ->
            field.setText(names[i])
            field.hint = defaultName(i)
        }
        fun showFields(count: Int) =
            nameFields.forEachIndexed { i, field -> field.isVisible = i < count }

        countGroup.check(COUNT_BUTTON_IDS[playerCount - MIN_PLAYERS])
        showFields(playerCount)
        countGroup.setOnCheckedChangeListener { _, checkedId ->
            showFields(COUNT_BUTTON_IDS.indexOf(checkedId) + MIN_PLAYERS)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.players_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                playerCount = (COUNT_BUTTON_IDS.indexOf(countGroup.checkedRadioButtonId) + MIN_PLAYERS)
                    .coerceIn(MIN_PLAYERS, MAX_PLAYERS)
                nameFields.forEachIndexed { i, field -> names[i] = field.text.toString().trim() }
                persist()
                buildBoard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Scoring ----

    private fun change(player: Int, delta: Int) {
        scores[player] = (scores[player] + delta).coerceAtLeast(0)
        updateScoreViews()
        persist()
        feedback()
    }

    private fun resetScores() {
        scores.fill(STARTING_SCORE)
        updateScoreViews()
        persist()
        feedback(longer = true)
    }

    private fun updateScoreViews() {
        scoreViews.forEachIndexed { i, view -> view.text = scores[i].toString() }
    }

    // ---- Persistence ----

    private fun loadState() {
        playerCount = prefs.getInt(KEY_PLAYER_COUNT, MIN_PLAYERS).coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        for (i in 0 until MAX_PLAYERS) {
            // Fall back to the scores saved by the old two-team version.
            val legacy = when (i) {
                0 -> prefs.getInt(LEGACY_KEY_SCORE_A, STARTING_SCORE)
                1 -> prefs.getInt(LEGACY_KEY_SCORE_B, STARTING_SCORE)
                else -> STARTING_SCORE
            }
            scores[i] = prefs.getInt(scoreKey(i), legacy)
            names[i] = prefs.getString(nameKey(i), "").orEmpty()
        }
    }

    private fun persist() {
        prefs.edit().apply {
            putInt(KEY_PLAYER_COUNT, playerCount)
            for (i in 0 until MAX_PLAYERS) {
                putInt(scoreKey(i), scores[i])
                putString(nameKey(i), names[i])
            }
        }.apply()
    }

    private fun feedback(longer: Boolean = false) {
        vibrate(if (longer) 120L else 30L)
        runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, if (longer) 150 else 60)
        }
    }

    private fun vibrate(ms: Long) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ms)
        }
    }

    companion object {
        private const val PREFS_NAME = "scoreboard"
        private const val KEY_PLAYER_COUNT = "playerCount"
        private const val LEGACY_KEY_SCORE_A = "scoreA"
        private const val LEGACY_KEY_SCORE_B = "scoreB"
        private const val STARTING_SCORE = 2
        private const val MIN_PLAYERS = 2
        private const val MAX_PLAYERS = 6

        private fun scoreKey(i: Int) = "score_$i"
        private fun nameKey(i: Int) = "name_$i"

        private val PLAYER_COLORS = listOf(
            R.color.player1_top to R.color.player1_bottom,
            R.color.player2_top to R.color.player2_bottom,
            R.color.player3_top to R.color.player3_bottom,
            R.color.player4_top to R.color.player4_bottom,
            R.color.player5_top to R.color.player5_bottom,
            R.color.player6_top to R.color.player6_bottom,
        )

        private val NAME_FIELD_IDS = listOf(
            R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5, R.id.name6,
        )

        private val COUNT_BUTTON_IDS = listOf(
            R.id.count2, R.id.count3, R.id.count4, R.id.count5, R.id.count6,
        )
    }
}
