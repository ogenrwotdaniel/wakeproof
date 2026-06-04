package com.example.pomodorotimerapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

class MainActivity : AppCompatActivity() {
    
    private lateinit var timerTextView: TextView
    private lateinit var sessionTextView: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var soundSwitch: Switch
    
    private var isTimerRunning = false
    private var isWorkPeriod = true
    private var sessionCount = 0

    private val timerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val timeLeft = intent?.getLongExtra("time_left", 0L) ?: 0L
            val workPeriod = intent?.getBooleanExtra("isWorkPeriod", true) ?: true
            val sessionNum = intent?.getIntExtra("sessionCount", 0) ?: 0
            
            updateUI(timeLeft, workPeriod, sessionNum)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeViews()
        setupClickListeners()
        updateUI(25 * 60 * 1000L, true, 0) // Initial state: 25 minutes work period
    }
    
    private fun initializeViews() {
        timerTextView = findViewById(R.id.timerTextView)
        sessionTextView = findViewById(R.id.sessionTextView)
        startButton = findViewById(R.id.startButton)
        pauseButton = findViewById(R.id.pauseButton)
        stopButton = findViewById(R.id.stopButton)
        soundSwitch = findViewById(R.id.soundSwitch)
        
        // Set initial switch state
        soundSwitch.isChecked = true
    }
    
    private fun setupClickListeners() {
        startButton.setOnClickListener {
            if (!isTimerRunning) {
                startPomodoroService()
                isTimerRunning = true
                updateButtonStates()
            }
        }
        
        pauseButton.setOnClickListener {
            if (isTimerRunning) {
                pausePomodoroService()
                isTimerRunning = false
                updateButtonStates()
            }
        }
        
        stopButton.setOnClickListener {
            stopPomodoroService()
            isTimerRunning = false
            sessionCount = 0
            updateButtonStates()
            updateUI(25 * 60 * 1000L, true, 0)
        }
        
        soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, PomodoroService::class.java).apply {
                action = if (isChecked) "ENABLE_SOUND" else "DISABLE_SOUND"
            }
            startService(intent)
        }
    }
    
    private fun startPomodoroService() {
        val intent = Intent(this, PomodoroService::class.java).apply {
            action = "START_TIMER"
            putExtra("soundEnabled", soundSwitch.isChecked)
        }
        startService(intent)
    }
    
    private fun pausePomodoroService() {
        val intent = Intent(this, PomodoroService::class.java).apply {
            action = "PAUSE_TIMER"
        }
        startService(intent)
    }
    
    private fun stopPomodoroService() {
        val intent = Intent(this, PomodoroService::class.java).apply {
            action = "STOP_TIMER"
        }
        startService(intent)
    }
    
    private fun updateUI(timeLeft: Long, workPeriod: Boolean, sessionNum: Int) {
        timerTextView.text = formatTime(timeLeft)
        isWorkPeriod = workPeriod
        sessionCount = sessionNum
        
        sessionTextView.text = if (workPeriod) {
            "Work Session ${sessionNum + 1}/4"
        } else {
            if (sessionNum == 3) "Long Break" else "Short Break"
        }
        
        // Change timer color based on session type
        val colorRes = if (workPeriod) R.color.work_color else R.color.break_color
        timerTextView.setTextColor(resources.getColor(colorRes, null))
    }
    
    private fun updateButtonStates() {
        startButton.isEnabled = !isTimerRunning
        pauseButton.isEnabled = isTimerRunning
        stopButton.isEnabled = isTimerRunning
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(timerUpdateReceiver, IntentFilter("POMODORO_TIMER_UPDATE"))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(timerUpdateReceiver)
    }

    private fun formatTime(millis: Long): String {
        val minutes = (millis / 1000) / 60
        val seconds = (millis / 1000) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

