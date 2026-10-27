package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.*

class PublicActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = L("بخش عمومی", "Public section"); textSize = 22f
        })
        root.addView(Button(this).apply {
            text = L("بازگشت", "Back")
            setOnClickListener { finish() }
        })
        setContentView(root)
        root.fitsSystemWindows = true
    }
}
