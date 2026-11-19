package com.personal.dynamicpill

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * A small dialog for answering a message from the pill. The text goes back to the messaging app through the
 * notification's own reply action, exactly like replying from the notification shade.
 * It is a separate screen because the pill's overlay deliberately never takes keyboard focus.
 */
class ReplyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getParcelableExtra(EXTRA_ACTION, Notification.Action::class.java)
        val remoteInput = action?.remoteInputs?.firstOrNull()
        if (action == null || remoteInput == null) {
            finish()
            return
        }
        title = getString(R.string.reply_title, intent.getCharSequenceExtra(EXTRA_SENDER) ?: "")

        val padding = dp(PADDING_DP)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        // The messaging app's suggested quick replies, if it offers any, send with one tap.
        remoteInput.choices?.take(MAX_SUGGESTIONS)?.forEach { choice ->
            column.addView(
                Button(this).apply {
                    text = choice
                    isAllCaps = false
                    setOnClickListener { send(action, remoteInput, choice, RemoteInput.SOURCE_CHOICE) }
                },
            )
        }
        val field = EditText(this).apply {
            hint = remoteInput.label ?: getString(R.string.reply_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        column.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(
            Button(this).apply {
                setText(R.string.action_send)
                setOnClickListener {
                    val text = field.text.toString().trim()
                    if (text.isNotEmpty()) send(action, remoteInput, text, RemoteInput.SOURCE_FREE_FORM_INPUT)
                }
            },
        )
        setContentView(column)
        field.requestFocus()
    }

    private fun send(action: Notification.Action, remoteInput: RemoteInput, text: CharSequence, source: Int) {
        val results = Bundle().apply { putCharSequence(remoteInput.resultKey, text) }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fillIn, results)
        RemoteInput.setResultsSource(fillIn, source)
        val sent = try {
            action.actionIntent.send(this, 0, fillIn)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
        Toast.makeText(this, if (sent) R.string.reply_sent else R.string.reply_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_ACTION = "com.personal.dynamicpill.extra.REPLY_ACTION"
        private const val EXTRA_SENDER = "com.personal.dynamicpill.extra.REPLY_SENDER"
        private const val MAX_SUGGESTIONS = 3
        private const val PADDING_DP = 20

        fun start(context: Context, action: Notification.Action, sender: CharSequence) {
            val intent = Intent(context, ReplyActivity::class.java)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_SENDER, sender)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
