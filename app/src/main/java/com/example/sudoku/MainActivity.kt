package com.example.sudoku

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*

class MainActivity : Activity() {
    private val ink = Color.rgb(30, 30, 30)
    private data class Palette(val name: String, val color: Int, val theme: Int)
    private val palettes = listOf(
        Palette("Розовая", Color.rgb(205, 111, 119), R.style.PaletteRose),
        Palette("Мятная", Color.rgb(119, 164, 145), R.style.PaletteMint),
        Palette("Лавандовая", Color.rgb(156, 143, 191), R.style.PaletteLavender),
        Palette("Голубая", Color.rgb(125, 157, 188), R.style.PaletteBlue)
    )
    private var paletteIndex = 0
    private val accent get() = palettes[paletteIndex].color
    private val accentSoft get() = Color.rgb(
        (Color.red(accent) * .14f + 255 * .86f).toInt(),
        (Color.green(accent) * .14f + 255 * .86f).toInt(),
        (Color.blue(accent) * .14f + 255 * .86f).toInt()
    )
    private val errorColor = Color.rgb(205, 111, 119)
    private val paper = Color.rgb(247, 248, 250)
    private val muted = Color.rgb(140, 140, 140)
    private val kanji = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private var notes = IntArray(81)
    private enum class Tool { PEN, PENCIL, ERASER }
    private var tool = Tool.PEN
    private lateinit var pencilButton: ImageButton
    private lateinit var eraserButton: ImageButton
    private lateinit var haikus: List<Haiku>
    private var haikuIndex = 0
    private var cells = IntArray(81)
    private var fixed = IntArray(81)
    private var solution = IntArray(81)
    private var selected = -1
    private var difficulty = 0
    private var seconds = 0
    private var completed = false
    private var playing = false
    private var collectionOpen = false
    private var collectionTab = 0
    private val unlockedHaikus = mutableSetOf<Int>()
    private var modal: Dialog? = null
    private lateinit var board: Board
    private lateinit var timer: TextView
    private lateinit var level: TextView
    private val numberButtons = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (playing && !completed && modal == null) seconds++
            refreshLabels()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        paletteIndex = getPreferences(MODE_PRIVATE).getInt("palette", 0).coerceIn(palettes.indices)
        setTheme(palettes[paletteIndex].theme)
        super.onCreate(savedInstanceState)
        haikus = loadHaikus(this)
        val prefs = getPreferences(MODE_PRIVATE)
        unlockedHaikus.addAll(prefs.getStringSet("unlocked_haikus", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() })
        fun read(key: String) = prefs.getString(key, null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 81 && it.all { n -> n in 0..9 } }?.toIntArray()
        val savedCells = read("cells")
        val savedFixed = read("fixed")
        val savedSolution = read("solution")
        if (savedCells != null && savedFixed != null && savedSolution != null) {
            cells = savedCells; fixed = savedFixed; solution = savedSolution
            difficulty = prefs.getInt("difficulty", 0).coerceIn(0, 2)
            seconds = prefs.getInt("seconds", 0)
            notes = read("notes") ?: IntArray(81)
            haikuIndex = prefs.getInt("haiku", 0).coerceIn(haikus.indices)
            completed = cells.contentEquals(solution)
        } else newPuzzle()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (completed) unlockCurrentHaiku()
        collectionTab = savedInstanceState?.getInt("collectionTab", 0)?.coerceIn(0, 3) ?: 0
        when {
            savedInstanceState?.getBoolean("playing") == true -> showGame()
            savedInstanceState?.getBoolean("collectionOpen") == true -> showCollection()
            else -> showMenu()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                navigateBack()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("playing", playing)
        outState.putBoolean("collectionOpen", collectionOpen)
        outState.putInt("collectionTab", collectionTab)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy navigation for Android 12 and earlier")
    // API 33+ uses the OnBackInvokedDispatcher callback registered in onCreate.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() { navigateBack() }

    private fun navigateBack() {
        when {
            playing -> confirmExit()
            collectionOpen -> showMenu()
            else -> finish()
        }
    }

    private fun showMenu() {
        playing = false
        collectionOpen = false
        numberButtons.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        applyInsets(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("数独", 32, ink), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_palette, "Выбрать цветовую палитру") { choosePalette() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        root.addView(iconButton(R.drawable.ic_list, "Открытые хайку") { showCollection() }.apply {
            background = keyBackground(); elevation = dp(4).toFloat()
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(24) })
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        center.addView(label("Классическое судоку", 22, ink).apply { gravity = Gravity.CENTER })
        center.addView(actionButton("Новая игра") { chooseDifficulty() }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(24) })
        if (!completed && hasProgress()) {
            center.addView(actionButton("Продолжить игру", false) { showGame() }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        }
        root.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showCollection() {
        playing = false
        collectionOpen = true
        numberButtons.clear()
        val scroll = ScrollView(this).apply { setBackgroundColor(paper); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        applyInsets(root)
        scroll.addView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Открытые хайку", 24, ink), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_close, "Вернуться в главное меню") { showMenu() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        val tabs = LinearLayout(this).apply { setPadding(0, dp(24), 0, dp(20)) }
        val grid = object : GridLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val side = MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
            }
        }.apply { columnCount = 5; rowCount = 5 }
        val tabButtons = mutableListOf<Button>()
        fun renderTab() {
            tabButtons.forEachIndexed { index, button ->
                button.isSelected = index == collectionTab
                button.background = keyBackground(index == collectionTab)
                button.setTextColor(if (index == collectionTab) accent else ink)
            }
            grid.removeAllViews()
            repeat(25) { offset ->
                val id = collectionTab * 25 + offset
                val haiku = haikus.firstOrNull { it.id == id }
                val unlocked = id in unlockedHaikus && haiku != null
                val cell = Button(this).apply {
                    text = (id + 1).toString(); textSize = 20f; isAllCaps = false
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(0, 0, 0, 0); gravity = Gravity.CENTER
                    setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    stateListAnimator = null
                    background = keyBackground(unlocked)
                    setTextColor(if (unlocked) ink else muted)
                    isEnabled = unlocked
                    contentDescription = "Хайку ${id + 1}, " + if (unlocked) "открыто" else "закрыто"
                    setOnClickListener { if (haiku != null && unlocked) showHaiku(haiku) }
                }
                grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(offset / 5, 1f), GridLayout.spec(offset % 5, 1f)).apply {
                    width = 0; height = 0; setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
        }
        repeat(4) { index ->
            val button = actionButton((index + 1).toString(), false) { collectionTab = index; renderTab() }
            button.contentDescription = "Вкладка ${index + 1}, хайку ${index * 25 + 1}–${index * 25 + 25}"
            tabButtons.add(button)
            tabs.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        root.addView(tabs)
        root.addView(grid, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("Решайте судоку, чтобы открывать хайку", 14, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0) })
        renderTab()
        setContentView(scroll)
    }

    private fun unlockCurrentHaiku() {
        if (unlockedHaikus.add(haikus[haikuIndex].id)) {
            getPreferences(MODE_PRIVATE).edit().putStringSet("unlocked_haikus", unlockedHaikus.map { it.toString() }.toSet()).apply()
        }
    }

    private fun appendHaiku(panel: LinearLayout, haiku: Haiku) {
        panel.addView(HaikuView(this, haiku, ink, muted), LinearLayout.LayoutParams(-1, dp(132)))
        panel.addView(label(haiku.russian, 17, ink).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(12)) })
        panel.addView(label("Мацуо Басё", 12, muted).apply { gravity = Gravity.END; setPadding(0, 0, 0, dp(24)) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showHaiku(haiku: Haiku) {
        if (modal != null) return
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12)); background = rounded(paper, 24)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Хайку ${haiku.id + 1}", 22, ink), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_close, "Закрыть хайку") { dismissDialog(dialog) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        appendHaiku(panel, haiku)
        dialog.setContentView(ScrollView(this).apply { addView(panel) })
        trackDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(resources.displayMetrics.widthPixels - dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun choosePalette() {
        if (modal != null) return
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = rounded(paper, 24)
        }
        panel.addView(label("Цветовая палитра", 24, ink).apply { setPadding(0, 0, 0, dp(20)) })
        palettes.forEachIndexed { index, palette ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(if (index == paletteIndex) accentSoft else Color.WHITE, 12).apply {
                    setStroke(dp(1), if (index == paletteIndex) accent else Color.rgb(225, 225, 225))
                }
                isFocusable = true
                contentDescription = palette.name + if (index == paletteIndex) ", выбрана" else ""
                setOnClickListener {
                    dismissDialog(dialog)
                    if (paletteIndex != index) {
                        getPreferences(MODE_PRIVATE).edit().putInt("palette", index).apply()
                        recreate()
                    }
                }
            }
            row.addView(View(this).apply { background = rounded(palette.color, 10) }, LinearLayout.LayoutParams(dp(32), dp(32)))
            row.addView(label(palette.name, 17, ink).apply { setPadding(dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (index == paletteIndex) row.addView(label("✓", 20, accent))
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(10) })
        }
        dialog.setContentView(panel)
        trackDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(resources.displayMetrics.widthPixels - dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(24) + insets.systemWindowInsetLeft, dp(20) + insets.systemWindowInsetTop, dp(24) + insets.systemWindowInsetRight, dp(24) + insets.systemWindowInsetBottom)
            insets
        }
    }

    private fun showGame() {
        playing = true
        collectionOpen = false
        numberButtons.clear()
        val scroll = ScrollView(this).apply { setBackgroundColor(paper); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        scroll.addView(root)
        applyInsets(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        level = label("", 24, ink)
        header.addView(level, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_close, "Прервать игру") { confirmExit() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        root.addView(HaikuView(this, haikus[haikuIndex], ink, muted), LinearLayout.LayoutParams(-1, dp(132)).apply { topMargin = dp(8) })
        root.addView(label("松尾芭蕉 · Мацуо Басё", 12, muted).apply { gravity = Gravity.END })
        timer = label("", 14, ink).apply { gravity = Gravity.END; typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, dp(12)) }
        root.addView(timer)
        board = Board()
        root.addView(board, LinearLayout.LayoutParams(-1, -2))
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)); clipChildren = false; clipToPadding = false }
        pencilButton = iconButton(R.drawable.ic_pencil, "Карандаш") { tool = if (tool == Tool.PENCIL) Tool.PEN else Tool.PENCIL; refreshTools() }
        eraserButton = iconButton(R.drawable.ic_eraser, "Ластик") { tool = if (tool == Tool.ERASER) Tool.PEN else Tool.ERASER; if (tool == Tool.ERASER) eraseSelected(); refreshTools() }
        tools.addView(pencilButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(24) })
        tools.addView(eraserButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(24) })
        val hintButton = iconButton(R.drawable.ic_bulb, "Использовать подсказку") { confirmHint() }
        listOf(pencilButton, eraserButton, hintButton).forEach { button ->
            button.background = keyBackground()
            button.elevation = dp(4).toFloat()
            button.stateListAnimator = android.animation.StateListAnimator().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.animation.ObjectAnimator.ofFloat(button, "translationZ", -dp(2).toFloat()).setDuration(100))
                addState(intArrayOf(), android.animation.ObjectAnimator.ofFloat(button, "translationZ", 0f).setDuration(100))
            }
        }
        tools.addView(hintButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(tools)
        val numbers = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }
        (1..9).forEach { n ->
            numbers.addView(Button(this).apply {
                text = kanji[n]; textSize = 22f; setTextColor(ink); isAllCaps = false
                minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                minHeight = 0; minimumHeight = 0
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = true
                setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                elevation = dp(4).toFloat()
                val key = this
                stateListAnimator = android.animation.StateListAnimator().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), android.animation.ObjectAnimator.ofFloat(key, "translationZ", -dp(2).toFloat()).setDuration(100))
                    addState(intArrayOf(), android.animation.ObjectAnimator.ofFloat(key, "translationZ", 0f).setDuration(100))
                }
                background = keyBackground()
                contentDescription = "Ввести ${kanji[n]} ($n)"
                setOnClickListener { enter(n) }
                numberButtons.add(this)
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        root.addView(numbers)
        setContentView(scroll)
        refreshTools()
        refreshLabels()
        if (completed) showVictory()
    }

    override fun onResume() { super.onResume(); handler.postDelayed(ticker, 1000) }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        getPreferences(MODE_PRIVATE).edit().putString("cells", cells.joinToString(","))
            .putString("fixed", fixed.joinToString(",")).putString("solution", solution.joinToString(","))
            .putInt("seconds", seconds).putInt("difficulty", difficulty).apply()
        getPreferences(MODE_PRIVATE).edit().putString("notes", notes.joinToString(",")).putInt("haiku", haikuIndex).apply()
        super.onPause()
    }

    private fun chooseDifficulty() {
        if (modal != null) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.BottomSheetAnimation)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            background = rounded(paper, 24)
        }
        panel.addView(View(this).apply { background = rounded(Color.LTGRAY, 2) }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
        panel.addView(label("Новая игра", 28, ink))
        panel.addView(label("Выберите уровень сложности", 15, accent).apply { setPadding(0, dp(8), 0, dp(20)) })
        arrayOf("Лёгкая", "Средняя", "Сложная").forEachIndexed { which, title ->
            panel.addView(actionButton(title, false) {
                dismissDialog(dialog)
                fun start() { difficulty = which; newPuzzle(); showGame() }
                if (hasProgress() && !completed) {
                    val confirm = AlertDialog.Builder(this).setTitle("Начать заново?").setMessage("Текущая партия будет заменена.")
                        .setNegativeButton("Отмена", null).setPositiveButton("Новая игра") { _, _ -> start() }.create()
                    trackDialog(confirm)
                } else start()
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })
        }
        dialog.setContentView(panel)
        trackDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.BottomSheetAnimation)
        }
        panel.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(24) + insets.systemWindowInsetLeft, dp(16), dp(24) + insets.systemWindowInsetRight, dp(24) + insets.systemWindowInsetBottom)
            insets
        }
        panel.requestApplyInsets()
    }

    private fun trackDialog(dialog: Dialog) {
        modal = dialog
        dialog.setOnDismissListener { if (modal === dialog) modal = null }
        dialog.show()
    }

    private fun dismissDialog(dialog: Dialog) {
        if (modal === dialog) modal = null
        dialog.dismiss()
    }

    private fun showVictory() {
        unlockCurrentHaiku()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            clipChildren = false; clipToPadding = false
            background = rounded(paper, 24)
        }
        panel.addView(label("Поздравляем!", 28, accent).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        panel.addView(label("Судоку решено за ${formatTime()}", 16, ink).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(24))
        })
        appendHaiku(panel, haikus[haikuIndex])
        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(scroll); setCancelable(false) }
        panel.addView(actionButton("Новая игра") { dismissDialog(dialog); showMenu(); chooseDifficulty() }, LinearLayout.LayoutParams(-1, dp(54)))
        panel.addView(actionButton("Вернуться в главное меню", false) { dismissDialog(dialog); showMenu() }.apply {
            elevation = dp(4).toFloat()
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        trackDialog(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(resources.displayMetrics.widthPixels - dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun actionButton(title: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 16f
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary)
            accent
        else
            Color.rgb(239, 239, 239), 16)
        setOnClickListener { action() }
    }

    private fun hasProgress() = cells.indices.any { fixed[it] == 0 && (cells[it] != 0 || notes[it] != 0) }

    private fun confirmExit() {
        if (modal != null) return
        if (completed) { showMenu(); return }
        val dialog = AlertDialog.Builder(this).setTitle("Прервать игру?")
            .setMessage("Вы действительно хотите прервать игру и выйти на главный экран?")
            .setNegativeButton("Остаться", null)
            .setPositiveButton("Выйти") { _, _ -> showMenu() }.create()
        trackDialog(dialog)
    }

    private fun iconButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(ink); contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(Color.TRANSPARENT, 12)
        setOnClickListener { action() }
    }

    private fun refreshTools() {
        listOf(pencilButton to Tool.PENCIL, eraserButton to Tool.ERASER).forEach { (button, mode) ->
            val active = tool == mode
            button.isSelected = active
            button.setColorFilter(if (active) accent else ink)
            button.background = keyBackground(active)
        }
    }

    private fun keyBackground(active: Boolean = false): android.graphics.drawable.RippleDrawable {
        val surface = rounded(if (active) accentSoft else Color.WHITE, 8).apply {
            setStroke(dp(1), if (active) accent else Color.rgb(218, 220, 224))
        }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent))), surface, null)
    }

    private fun eraseSelected() {
        if (completed || selected < 0 || isLocked(selected)) return
        if (notes[selected] != 0 || cells[selected] != 0 && cells[selected] != solution[selected]) {
            cells[selected] = 0; notes[selected] = 0
            board.invalidate(); refreshLabels()
        }
    }

    private fun confirmHint() {
        if (modal != null || completed) return
        val target = if (selected >= 0 && fixed[selected] == 0 && cells[selected] != solution[selected]) selected
            else cells.indices.firstOrNull { cells[it] != solution[it] } ?: return
        val dialog = AlertDialog.Builder(this).setTitle("Использовать подсказку?")
            .setMessage("В выбранной клетке появится правильный иероглиф.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Использовать") { _, _ ->
                modal?.let { dismissDialog(it) }
                selected = target; cells[target] = solution[target]; notes[target] = 0
                tool = Tool.PEN; refreshTools()
                completed = cells.contentEquals(solution)
                board.invalidate(); refreshLabels()
                if (completed) showVictory()
            }.create()
        // Select the actual hint target so the confirmation always refers to a visible cell.
        selected = target; board.invalidate()
        trackDialog(dialog)
    }

    override fun onDestroy() {
        modal?.dismiss()
        super.onDestroy()
    }

    private fun newPuzzle() {
        // A known unique puzzle; Sudoku-preserving permutations create new boards.
        val puzzle = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
        val answer = "534678912672195348198342567859761423426853791713924856961537284287419635345286179"
        val digits = (1..9).shuffled()
        val rows = (0..2).shuffled().flatMap { band -> (0..2).shuffled().map { band * 3 + it } }
        val cols = (0..2).shuffled().flatMap { stack -> (0..2).shuffled().map { stack * 3 + it } }
        val transpose = kotlin.random.Random.nextBoolean()
        solution = IntArray(81)
        fixed = IntArray(81) { i ->
            val r = rows[i / 9]; val c = cols[i % 9]
            val source = if (transpose) c * 9 + r else r * 9 + c
            solution[i] = digits[answer[source].digitToInt() - 1]
            val n = puzzle[source].digitToInt()
            if (n == 0) 0 else digits[n - 1]
        }
        val extra = when (difficulty) { 0 -> 14; 1 -> 7; else -> 0 }
        fixed.indices.filter { fixed[it] == 0 }.shuffled().take(extra).forEach { fixed[it] = solution[it] }
        cells = fixed.copyOf(); seconds = 0; selected = -1; completed = false
        notes = IntArray(81); tool = Tool.PEN
        val unopened = haikus.indices.filter { haikus[it].id !in unlockedHaikus }
        haikuIndex = (unopened.ifEmpty { haikus.indices.toList() }).random()
    }

    private fun enter(n: Int) {
        if (selected < 0 || isLocked(selected) || completed) return
        if (tool == Tool.ERASER) return
        if (tool == Tool.PENCIL) {
            if (cells[selected] != 0) return
            notes[selected] = if (notes[selected] == n) 0 else n
        } else {
            cells[selected] = n
            notes[selected] = 0
        }
        completed = cells.contentEquals(solution)
        board.invalidate(); refreshLabels()
        if (completed) showVictory()
    }

    // Correct pen entries, including hints, become as immutable as the original clues.
    private fun isLocked(index: Int) = fixed[index] != 0 || cells[index] != 0 && cells[index] == solution[index]

    private fun refreshLabels() {
        if (!playing || !::timer.isInitialized) return
        timer.text = formatTime()
        level.text = arrayOf("Лёгкая", "Средняя", "Сложная")[difficulty]
        numberButtons.forEachIndexed { index, button ->
            // INVISIBLE preserves the key's width and its neighbours' positions.
            button.visibility = if (cells.count { it == index + 1 } >= 9) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun formatTime() = "%02d:%02d".format(seconds / 60, seconds % 60)

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private inner class Board : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = "Поле судоку, 9 строк и 9 столбцов"; isFocusable = true }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec); setMeasuredDimension(size, size)
        }
        override fun onDraw(canvas: Canvas) {
            val unit = width / 9f
            val selectedValue = if (selected >= 0) cells[selected].takeIf { it != 0 } ?: notes[selected] else 0
            val highlight = Color.rgb(
                (Color.red(accent) * .40f + 255 * .60f).toInt(),
                (Color.green(accent) * .40f + 255 * .60f).toInt(),
                (Color.blue(accent) * .40f + 255 * .60f).toInt()
            )
            canvas.drawColor(Color.WHITE)
            for (i in 0..80) {
                val row = i / 9; val col = i % 9
                val related = selectedValue != 0 && (row == selected / 9 || col == selected % 9 || row / 3 == selected / 27 && col / 3 == selected % 9 / 3)
                val value = cells[i].takeIf { it != 0 } ?: notes[i]
                paint.color = when {
                    selectedValue != 0 && value == selectedValue -> highlight
                    related -> Color.rgb(
                        (Color.red(accent) * .25f + 255 * .75f).toInt(),
                        (Color.green(accent) * .25f + 255 * .75f).toInt(),
                        (Color.blue(accent) * .25f + 255 * .75f).toInt()
                    )
                    else -> Color.WHITE
                }
                canvas.drawRect(col * unit, row * unit, (col + 1) * unit, (row + 1) * unit, paint)
                if (cells[i] != 0 || notes[i] != 0) {
                    val penciled = cells[i] == 0
                    paint.color = if (penciled) muted else if (isError(i)) errorColor else ink
                    paint.textSize = unit * if (penciled) .40f else .51f
                    paint.typeface = if (fixed[i] != 0) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(kanji[if (penciled) notes[i] else cells[i]], (col + .5f) * unit, (row + .5f) * unit - (paint.ascent() + paint.descent()) / 2, paint)
                }
            }
            // Thin lines first, then block separators, and the outer frame last.
            paint.color = Color.rgb(218, 218, 218)
            paint.strokeWidth = resources.displayMetrics.density
            val edge = dp(2) / 2f
            for (line in 1..8) {
                if (line % 3 == 0) continue
                val pos = line * unit
                canvas.drawLine(pos, edge, pos, height - edge, paint)
                canvas.drawLine(edge, pos, width - edge, pos, paint)
            }
            paint.color = ink
            paint.strokeWidth = dp(2).toFloat()
            for (line in listOf(3, 6)) {
                val pos = line * unit
                canvas.drawLine(pos, edge, pos, height - edge, paint)
                canvas.drawLine(edge, pos, width - edge, pos, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            val frameInset = paint.strokeWidth / 2
            canvas.drawRect(frameInset, frameInset, width - frameInset, height - frameInset, paint)
            paint.style = Paint.Style.FILL
            // Draw after grid lines so the scarlet error border stays visible on block edges.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = errorColor
            for (i in cells.indices.filter { isError(it) }) {
                val inset = paint.strokeWidth / 2
                canvas.drawRect(i % 9 * unit + inset, i / 9 * unit + inset, (i % 9 + 1) * unit - inset, (i / 9 + 1) * unit - inset, paint)
            }
            if (selected >= 0 && !isError(selected)) {
                paint.color = accent
                val row = selected / 9
                val col = selected % 9
                // Inset only the sides next to black lines, preserving the full black stroke.
                fun inset(boundary: Int): Float = paint.strokeWidth / 2 + when (boundary) {
                    0, 9 -> dp(3).toFloat()
                    3, 6 -> dp(2) / 2f
                    else -> 0f
                }
                canvas.drawRect(col * unit + inset(col), row * unit + inset(row), (col + 1) * unit - inset(col + 1), (row + 1) * unit - inset(row + 1), paint)
            }
            paint.style = Paint.Style.FILL
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) return true
            if (event.action == MotionEvent.ACTION_UP) {
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    selected = (event.y / (width / 9f)).toInt().coerceIn(0, 8) * 9 + (event.x / (width / 9f)).toInt().coerceIn(0, 8)
                    if (tool == Tool.ERASER) eraseSelected()
                    invalidate(); performClick()
                }
                return true
            }
            return super.onTouchEvent(event)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun isError(i: Int) = fixed[i] == 0 && cells[i] != 0 && cells[i] != solution[i]
    }
}
