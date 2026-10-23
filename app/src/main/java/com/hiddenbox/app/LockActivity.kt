package com.hiddenbox.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hiddenbox.app.databinding.ActivityLockBinding
import java.security.MessageDigest

/**
 * Disguise lock. This is the launcher screen, so it is the first (and only)
 * thing a casual observer sees: a plain "1 + 1 = ?" math prompt.
 *
 *  - Typing the correct answer (2) opens a harmless little math game — the
 *    cover story, so the app looks like an ordinary quiz.
 *  - Typing the secret password opens the real HiddenBox app list.
 *  - On first launch there is no password yet, so the screen asks the user to
 *    set one. The password is stored only as a SHA-256 hash; it cannot be
 *    recovered if forgotten.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: android.content.SharedPreferences
    private var setupMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setupMode = prefs.getString(KEY_PWD, null) == null

        render()
        binding.submit.setOnClickListener { onSubmit() }
        binding.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) {
                onSubmit(); true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        binding.input.text?.clear()
    }

    private fun render() {
        if (setupMode) {
            binding.question.setText(R.string.lock_setup_title)
            binding.hint.setText(R.string.lock_setup_hint)
            binding.hint.visibility = android.view.View.VISIBLE
            binding.submit.setText(R.string.lock_save)
        } else {
            binding.question.setText(R.string.lock_question)
            binding.hint.visibility = android.view.View.GONE
            binding.submit.setText(R.string.lock_check)
        }
    }

    private fun onSubmit() {
        val value = binding.input.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) return

        if (setupMode) {
            // Don't let the secret be the trivial cover answer.
            if (value == COVER_ANSWER) {
                Toast.makeText(this, R.string.lock_setup_bad, Toast.LENGTH_LONG).show()
                return
            }
            prefs.edit().putString(KEY_PWD, sha256(value)).apply()
            setupMode = false
            render()
            Toast.makeText(this, R.string.lock_saved, Toast.LENGTH_SHORT).show()
            openBox()
            return
        }

        when {
            // Secret password -> the real app list.
            sha256(value) == prefs.getString(KEY_PWD, null) -> openBox()
            // Correct math answer -> the decoy game.
            value == COVER_ANSWER -> startActivity(Intent(this, GameActivity::class.java))
            else -> {
                binding.hint.visibility = android.view.View.VISIBLE
                binding.hint.setText(R.string.lock_wrong)
            }
        }
        binding.input.text?.clear()
    }

    private fun openBox() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    companion object {
        private const val PREFS = "hb_lock"
        private const val KEY_PWD = "pwd_hash"
        private const val COVER_ANSWER = "2"

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
