package com.flix.launcher

import android.app.AlertDialog
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Shows a dialog that asks for text with the on-screen keyboard already up. A dialog's
 * text box gets focus, but Android TV doesn't raise the keyboard for it by itself, and
 * pressing OK on the box did nothing either.
 */
fun AlertDialog.Builder.showWithKeyboard(input: EditText): AlertDialog {
    val dialog = create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    fun raise() {
        input.requestFocus()
        (input.context.getSystemService(InputMethodManager::class.java)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }
    input.setOnClickListener { raise() }
    dialog.setOnShowListener { input.post { raise() } }
    dialog.show()
    return dialog
}
