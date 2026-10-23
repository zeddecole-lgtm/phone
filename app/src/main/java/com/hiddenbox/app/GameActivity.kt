package com.hiddenbox.app

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.hiddenbox.app.databinding.ActivityGameBinding
import kotlin.random.Random

/**
 * A small, genuinely playable "quick math" game. It is the cover story for the
 * lock screen: anyone who answers 1 + 1 correctly lands here and sees nothing
 * but an innocent quiz. It has no path to the real app list.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding
    private var a = 0
    private var b = 0
    private var score = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        newQuestion()
        updateScore()

        binding.submit.setOnClickListener { check() }
        binding.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) {
                check(); true
            } else false
        }
    }

    private fun newQuestion() {
        a = Random.nextInt(1, 10)
        b = Random.nextInt(1, 10)
        binding.question.text = getString(R.string.game_question, a, b)
        binding.input.text?.clear()
    }

    private fun check() {
        val answer = binding.input.text?.toString()?.trim()?.toIntOrNull()
        if (answer == null) return
        if (answer == a + b) {
            score++
            binding.feedback.setText(R.string.game_correct)
            updateScore()
            newQuestion()
        } else {
            score = 0
            binding.feedback.setText(R.string.game_wrong)
            updateScore()
            binding.input.text?.clear()
        }
    }

    private fun updateScore() {
        binding.score.text = getString(R.string.game_score, score)
    }
}
