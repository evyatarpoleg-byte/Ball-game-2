package com.ballgame.hills

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.resumeLoop()
    }

    @Deprecated("Back is handled by the game's own menus")
    override fun onBackPressed() {
        if (!view.onBack()) super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        view.pauseLoop()
    }
}
