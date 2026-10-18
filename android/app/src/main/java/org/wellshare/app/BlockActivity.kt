package org.wellshare.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/** Shown over an app whose daily timer has run out. Leaving it always goes to the home screen. */
class BlockActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_MINUTES = "minutes"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val name = intent.getStringExtra(EXTRA_NAME) ?: "This app"
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)

        val col = ui.column(28).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.bg)
        }
        col.addView(ui.small("APP TIMER"))
        col.addView(ui.gap(8))
        col.addView(ui.title("Time's up for $name today"))
        col.addView(ui.gap(12))
        col.addView(ui.body("Your mentor set a daily limit of ${Format.duration(minutes * 60L)} for $name. " +
            "It opens again after midnight."))
        col.addView(ui.gap(8))
        col.addView(ui.small("Calls, messages and Settings are never blocked."))
        col.addView(ui.gap(28))
        col.addView(ui.button("Go to home screen") { goHome() }, ui.fullWidth())
        col.addView(ui.gap(12))
        col.addView(ui.button("Open WellShare", primary = false) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, ui.fullWidth())
        col.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        setContentView(col)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Deprecated("Back always leads home, never back into the blocked app")
    override fun onBackPressed() {
        goHome()
    }
}
