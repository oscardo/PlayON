package com.playon.recorder;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private boolean permissionToRecordAccepted = false;
    private String[] permissions = {Manifest.permission.RECORD_AUDIO};

    private MaterialButton btnRecord, btnStop, btnPlay, btnPause, btnProcess;
    private MaterialButton btnStart, btnEnd, btnForward, btnBackward, btnToggleSearch;
    private Spinner speedSpinner, aiModelSpinner;
    private TextView timerText, statusText;
    private MaterialButton btnLang, btnSettings;
    private ProgressBar aiProgressBar;
    private TextView transcriptionText;
    private Spinner sampleRateSpinner, bitDepthSpinner, channelsSpinner;
    private TextInputEditText etMeetingTitle, etAttendees, etMeetingDate;
    private MaterialButton btnPlaySummary;
    private MaterialButton btnSaveTemplate, btnLoadTemplate;
    private RangeSlider trimSlider;
    private LinearLayout trimLayout;
    private EditText etSearchTranscription;
    private MaterialButton btnTextDecrease, btnTextIncrease, btnSearchNext;
    private SwitchMaterial switchDarkMode;
    private LinearLayout transcriptionControlsLayout;

    private MediaPlayer mediaPlayer;
    private TextToSpeech tts;
    private String summaryText = "";
    private float currentTextSize = 14f;
    private boolean isDarkMode = false;
    private float trimStart = 0f;
    private float trimEnd = 100f;
    private String audioFilePath;
    private boolean isAudioReady = false;
    private float currentSpeed = 1.0f;
    private String currentLang = "es";
    private String selectedModel = "Gemini";
    
    private int selectedSampleRate = 44100;
    private int selectedBitDepth = 16;
    private int selectedChannels = 1;

    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private long startTime = 0L;
    private boolean isTimerRunning = false;
    
    private SharedPreferences prefs;
    private LocalModelManager localModelManager;

    private List<TranscriptionSegment> transcriptionSegments = new ArrayList<>();

    private static class TranscriptionSegment {
        int startTimeMs;
        String text;
        int startChar;
        int endChar;

        TranscriptionSegment(int startTimeMs, String text) {
            this.startTimeMs = startTimeMs;
            this.text = text;
        }
    }

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("STATUS");
            String message = intent.getStringExtra("MESSAGE");
            updateStatus(status, message);
            
            if ("READY".equals(status)) {
                String audioPath = intent.getStringExtra("AUDIO_PATH");
                String transPath = intent.getStringExtra("TRANS_PATH");
                String summaryPath = intent.getStringExtra("SUMMARY_PATH");
                onRecordingReady(audioPath, transPath, summaryPath);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("PlayON_Prefs", MODE_PRIVATE);
        localModelManager = new LocalModelManager(this);

        // Initialize UI
        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        btnProcess = findViewById(R.id.btnProcess);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnStart = findViewById(R.id.btnStart);
        btnEnd = findViewById(R.id.btnEnd);
        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnToggleSearch = findViewById(R.id.btnToggleSearch);
        
        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        speedSpinner = findViewById(R.id.speedSpinner);
        aiModelSpinner = findViewById(R.id.aiModelSpinner);
        btnLang = findViewById(R.id.btnLang);
        btnSettings = findViewById(R.id.btnSettings);
        aiProgressBar = findViewById(R.id.aiProgressBar);
        transcriptionText = findViewById(R.id.transcriptionText);
        sampleRateSpinner = findViewById(R.id.sampleRateSpinner);
        bitDepthSpinner = findViewById(R.id.bitDepthSpinner);
        channelsSpinner = findViewById(R.id.channelsSpinner);
        etMeetingTitle = findViewById(R.id.etMeetingTitle);
        etAttendees = findViewById(R.id.etAttendees);
        etMeetingDate = findViewById(R.id.etMeetingDate);
        etMeetingDate.setOnClickListener(v -> showDatePicker());
        
        // Set default date to today
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String today = String.format(java.util.Locale.getDefault(), "%04d-%02d-%02d", 
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH));
        etMeetingDate.setText(today);

        btnPlaySummary = findViewById(R.id.btnPlaySummary);
        btnSaveTemplate = findViewById(R.id.btnSaveTemplate);
        btnLoadTemplate = findViewById(R.id.btnLoadTemplate);
        trimSlider = findViewById(R.id.trimSlider);
        trimLayout = findViewById(R.id.trimLayout);
        etSearchTranscription = findViewById(R.id.etSearchTranscription);
        btnTextDecrease = findViewById(R.id.btnTextDecrease);
        btnTextIncrease = findViewById(R.id.btnTextIncrease);
        btnSearchNext = findViewById(R.id.btnSearchNext);
        switchDarkMode = findViewById(R.id.switchDarkMode);
        transcriptionControlsLayout = findViewById(R.id.transcriptionControlsLayout);

        tts = new TextToSpeech(this, this);

        btnPlaySummary.setOnClickListener(v -> {
            if (tts != null && !summaryText.isEmpty()) {
                tts.speak(summaryText, TextToSpeech.QUEUE_FLUSH, null, "SummaryTTS");
            }
        });

        btnSaveTemplate.setOnClickListener(v -> saveTemplate());
        btnLoadTemplate.setOnClickListener(v -> loadTemplate());

        trimSlider.addOnChangeListener((slider, value, fromUser) -> {
            List<Float> values = slider.getValues();
            trimStart = values.get(0);
            trimEnd = values.get(1);
        });

        btnToggleSearch.setOnClickListener(v -> {
            if (etSearchTranscription.getVisibility() == View.VISIBLE) {
                etSearchTranscription.setVisibility(View.GONE);
                btnSearchNext.setVisibility(View.GONE);
                searchTranscription(""); // Clear highlights
            } else {
                etSearchTranscription.setVisibility(View.VISIBLE);
                btnSearchNext.setVisibility(View.VISIBLE);
                etSearchTranscription.requestFocus();
            }
        });

        btnSearchNext.setOnClickListener(v -> searchNext());

        etSearchTranscription.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchTranscription(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnTextIncrease.setOnClickListener(v -> adjustTextSize(2f));
        btnTextDecrease.setOnClickListener(v -> adjustTextSize(-2f));
        switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> toggleDarkMode(isChecked));

        prefs = getSharedPreferences("PlayON_Prefs", MODE_PRIVATE);
        currentLang = prefs.getString("language", "es");
        selectedModel = prefs.getString("selected_model", "Gemini");
        
        // Apply language
        Locale locale = new Locale(currentLang);
        Locale.setDefault(locale);
        android.content.res.Configuration config = new android.content.res.Configuration();
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        btnLang.setText(currentLang.toUpperCase());

        // Initially disable playback controls
        setPlaybackControlsEnabled(false);

        // Setup Tooltips (Long press for 1 second)
        setupTooltips();

        // Setup Speed Spinner
        String[] speeds = {"0.5x", "1.0x", "1.5x", "2.0x", "3.0x"};
        ArrayAdapter<String> speedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, speeds);
        speedSpinner.setAdapter(speedAdapter);
        speedSpinner.setSelection(1); // Default 1.0x
        speedSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String speedStr = speeds[position].replace("x", "");
                currentSpeed = Float.parseFloat(speedStr);
                updatePlaybackSpeed();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Setup AI Model Spinner
        String[] models = {"Gemini", "DeepSeek", "Claude", "Llama", "Local", "Gemma 3.x", "Gemma 4.x"};
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
        aiModelSpinner.setAdapter(modelAdapter);
        aiModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedModel = models[position];
                prefs.edit().putString("selected_model", selectedModel).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        setupRecordingParamSpinners();

        btnLang.setOnClickListener(v -> toggleLanguage());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // Register receiver
        IntentFilter filter = new IntentFilter("com.playon.recorder.STATUS_UPDATE");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }

        // Request Permissions
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION);

        btnRecord.setOnClickListener(v -> {
            startRecordingService();
            startTimer();
            btnRecord.setVisibility(View.GONE);
            btnStop.setVisibility(View.VISIBLE);
            btnProcess.setVisibility(View.GONE);
            trimLayout.setVisibility(View.GONE);
        });
        btnStop.setOnClickListener(v -> {
            stopRecordingService();
            stopTimer();
            btnStop.setVisibility(View.GONE);
            btnRecord.setVisibility(View.VISIBLE);
            btnProcess.setVisibility(View.VISIBLE);
            trimLayout.setVisibility(View.VISIBLE);
        });
        btnProcess.setOnClickListener(v -> {
            startProcessingService();
            btnProcess.setVisibility(View.GONE);
            trimLayout.setVisibility(View.GONE);
        });
        
        // Playback listeners
        btnPlay.setOnClickListener(v -> {
            playAudio();
            startTimer();
        });
        btnPause.setOnClickListener(v -> {
            pauseAudio();
            stopTimer();
        });
        btnStart.setOnClickListener(v -> seekToStart());
        btnEnd.setOnClickListener(v -> seekToEnd());
        btnForward.setOnClickListener(v -> seekForward());
        btnBackward.setOnClickListener(v -> seekBackward());
    }

    private void setupRecordingParamSpinners() {
        String[] sampleRates = {"8000", "16000", "44100"};
        ArrayAdapter<String> srAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sampleRates);
        sampleRateSpinner.setAdapter(srAdapter);
        sampleRateSpinner.setSelection(2); // 44100
        sampleRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedSampleRate = Integer.parseInt(sampleRates[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] bitDepths = {"8", "16"};
        ArrayAdapter<String> bdAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, bitDepths);
        bitDepthSpinner.setAdapter(bdAdapter);
        bitDepthSpinner.setSelection(1); // 16
        bitDepthSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedBitDepth = Integer.parseInt(bitDepths[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] channels = {"Mono", "Stereo"};
        ArrayAdapter<String> chAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, channels);
        channelsSpinner.setAdapter(chAdapter);
        channelsSpinner.setSelection(0); // Mono
        channelsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedChannels = pos + 1;
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupTooltips() {
        TooltipCompat.setTooltipText(btnRecord, getString(R.string.tooltip_record));
        TooltipCompat.setTooltipText(btnStop, getString(R.string.tooltip_stop));
        TooltipCompat.setTooltipText(btnPlay, getString(R.string.tooltip_play));
        TooltipCompat.setTooltipText(btnPause, getString(R.string.tooltip_pause));
        TooltipCompat.setTooltipText(btnStart, getString(R.string.tooltip_start));
        TooltipCompat.setTooltipText(btnEnd, getString(R.string.tooltip_end));
        TooltipCompat.setTooltipText(btnForward, getString(R.string.tooltip_forward));
        TooltipCompat.setTooltipText(btnBackward, getString(R.string.tooltip_backward));
    }

    private void toggleLanguage() {
        currentLang = currentLang.equals("es") ? "en" : "es";
        prefs.edit().putString("language", currentLang).apply();
        updateLanguage(currentLang);
    }

    private void updateLanguage(String lang) {
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        android.content.res.Configuration config = new android.content.res.Configuration();
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        
        // Update button text
        btnLang.setText(lang.toUpperCase());
        
        // Recreate to apply changes to all strings
        recreate();
    }

    private void startTimer() {
        if (isTimerRunning) return;
        startTime = SystemClock.uptimeMillis();
        isTimerRunning = true;
        timerHandler.postDelayed(timerRunnable, 0);
    }

    private void stopTimer() {
        isTimerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    private int lastActiveIndex = -1;

    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long millis = SystemClock.uptimeMillis() - startTime;
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                millis = mediaPlayer.getCurrentPosition();
                highlightTranscription((int) millis);
            }
            int seconds = (int) (millis / 1000);
            int minutes = seconds / 60;
            int hours = minutes / 60;
            seconds = seconds % 60;
            minutes = minutes % 60;
            timerText.setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds));
            timerHandler.postDelayed(this, 100); // More frequent for smoother highlighting
        }
    };

    private void refreshTranscriptionSpans() {
        String text = transcriptionText.getText().toString();
        android.text.SpannableString spannable = new android.text.SpannableString(text);
        
        // 1. Apply Search Highlights
        String query = etSearchTranscription.getText().toString();
        if (!query.isEmpty()) {
            int index = text.toLowerCase().indexOf(query.toLowerCase());
            while (index >= 0) {
                int color = (index == currentSearchIndex) ? android.graphics.Color.MAGENTA : android.graphics.Color.CYAN;
                spannable.setSpan(new android.text.style.BackgroundColorSpan(color), 
                    index, index + query.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = text.toLowerCase().indexOf(query.toLowerCase(), index + query.length());
            }
        }
        
        // 2. Apply Active Segment Highlight
        if (lastActiveIndex != -1 && lastActiveIndex < transcriptionSegments.size()) {
            TranscriptionSegment active = transcriptionSegments.get(lastActiveIndex);
            int highlightColor = isDarkMode ? android.graphics.Color.parseColor("#444400") : android.graphics.Color.YELLOW;
            spannable.setSpan(new android.text.style.BackgroundColorSpan(highlightColor), 
                active.startChar, active.endChar, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            
            // Auto-scroll to active segment
            runOnUiThread(() -> {
                if (transcriptionText.getLayout() != null) {
                    int line = transcriptionText.getLayout().getLineForOffset(active.startChar);
                    int y = transcriptionText.getLayout().getLineTop(line);
                    findViewById(R.id.transcriptionContainer).scrollTo(0, y);
                }
            });
        }

        runOnUiThread(() -> transcriptionText.setText(spannable, TextView.BufferType.SPANNABLE));
    }

    private void highlightTranscription(int currentPosMs) {
        if (transcriptionSegments.isEmpty()) return;

        int activeIndex = -1;
        for (int i = 0; i < transcriptionSegments.size(); i++) {
            if (currentPosMs >= transcriptionSegments.get(i).startTimeMs) {
                activeIndex = i;
            } else {
                break;
            }
        }

        if (activeIndex != -1 && activeIndex != lastActiveIndex) {
            lastActiveIndex = activeIndex;
            refreshTranscriptionSpans();
        }
    }

    private void playAudio() {
        if (!isAudioReady) return;
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(audioFilePath);
                mediaPlayer.prepare();
                mediaPlayer.setOnCompletionListener(mp -> {
                    btnPlay.setVisibility(View.VISIBLE);
                    btnPause.setVisibility(View.GONE);
                });
            }
            updatePlaybackSpeed();
            mediaPlayer.start();
            startTimer(); // Ensure timer is running for highlighting
            btnPlay.setVisibility(View.GONE);
            btnPause.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "Error playing audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void pauseAudio() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            stopTimer();
            btnPlay.setVisibility(View.VISIBLE);
            btnPause.setVisibility(View.GONE);
        }
    }

    private void seekToStart() {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(0);
        }
    }

    private void seekToEnd() {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(mediaPlayer.getDuration());
        }
    }

    private void seekForward() {
        if (mediaPlayer != null) {
            int currentPosition = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            mediaPlayer.seekTo(Math.min(currentPosition + 10000, duration));
        }
    }

    private void seekBackward() {
        if (mediaPlayer != null) {
            int currentPosition = mediaPlayer.getCurrentPosition();
            mediaPlayer.seekTo(Math.max(currentPosition - 10000, 0));
        }
    }

    private void updatePlaybackSpeed() {
        if (mediaPlayer != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(currentSpeed);
                mediaPlayer.setPlaybackParams(params);
            } catch (Exception e) {
                // Some devices might not support speed change while paused
            }
        }
    }

    private void setPlaybackControlsEnabled(boolean enabled) {
        btnPlay.setEnabled(enabled);
        btnPause.setEnabled(enabled);
        btnStart.setEnabled(enabled);
        btnEnd.setEnabled(enabled);
        btnForward.setEnabled(enabled);
        btnBackward.setEnabled(enabled);
        speedSpinner.setEnabled(enabled);
        
        // Visual feedback
        float alpha = enabled ? 1.0f : 0.5f;
        btnPlay.setAlpha(alpha);
        btnPause.setAlpha(alpha);
        btnStart.setAlpha(alpha);
        btnEnd.setAlpha(alpha);
        btnForward.setAlpha(alpha);
        btnBackward.setAlpha(alpha);
    }

    public void onRecordingReady(String audioPath, String transPath, String summaryPath) {
        this.audioFilePath = audioPath;
        this.isAudioReady = true;
        
        // Load and parse transcription
        loadTranscription(transPath);
        loadSummary(summaryPath);

        runOnUiThread(() -> {
            setPlaybackControlsEnabled(true);
            btnPlay.setVisibility(View.VISIBLE);
            btnPause.setVisibility(View.GONE);
            btnPlaySummary.setVisibility(View.VISIBLE);
            trimLayout.setVisibility(View.GONE);
            transcriptionControlsLayout.setVisibility(View.VISIBLE);
            etSearchTranscription.setVisibility(View.VISIBLE);
            
            // Show option to send email
            new AlertDialog.Builder(this)
                .setTitle("Processing Complete")
                .setMessage("Transcription and summary are ready. Would you like to send them via email?")
                .setPositiveButton("Yes", (dialog, which) -> sendEmail(audioPath, transPath, summaryPath))
                .setNegativeButton("No", null)
                .show();
        });
    }

    private void showDatePicker() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            String date = String.format(java.util.Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            etMeetingDate.setText(date);
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void saveTemplate() {
        android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint(R.string.template_name_hint);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_template_title)
            .setView(etName)
            .setPositiveButton(R.string.save_button, (dialog, which) -> {
                String name = etName.getText().toString();
                if (name.isEmpty()) return;
                
                try {
                    org.json.JSONObject template = new org.json.JSONObject();
                    template.put("title", etMeetingTitle.getText().toString());
                    template.put("attendees", etAttendees.getText().toString());
                    template.put("date", etMeetingDate.getText().toString());
                    
                    String templatesJson = prefs.getString("meeting_templates", "{}");
                    org.json.JSONObject templates = new org.json.JSONObject(templatesJson);
                    templates.put(name, template);
                    
                    prefs.edit().putString("meeting_templates", templates.toString()).apply();
                    Toast.makeText(this, "Template saved", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            })
            .setNegativeButton(R.string.cancel_button, null)
            .show();
    }

    private void loadTemplate() {
        String templatesJson = prefs.getString("meeting_templates", "{}");
        try {
            org.json.JSONObject templates = new org.json.JSONObject(templatesJson);
            if (templates.length() == 0) {
                Toast.makeText(this, R.string.no_templates, Toast.LENGTH_SHORT).show();
                return;
            }
            
            String[] names = new String[templates.length()];
            java.util.Iterator<String> keys = templates.keys();
            int i = 0;
            while (keys.hasNext()) names[i++] = keys.next();
            
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_template_title)
                .setItems(names, (dialog, which) -> {
                    try {
                        org.json.JSONObject template = templates.getJSONObject(names[which]);
                        etMeetingTitle.setText(template.optString("title"));
                        etAttendees.setText(template.optString("attendees"));
                        etMeetingDate.setText(template.optString("date"));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int currentSearchIndex = -1;

    private void searchTranscription(String query) {
        currentSearchIndex = -1;
        refreshTranscriptionSpans();
    }

    private void searchNext() {
        String query = etSearchTranscription.getText().toString();
        if (query.isEmpty()) return;

        String text = transcriptionText.getText().toString().toLowerCase();
        int nextIndex = text.indexOf(query.toLowerCase(), currentSearchIndex + 1);
        
        if (nextIndex == -1) {
            // Wrap around
            nextIndex = text.indexOf(query.toLowerCase());
        }

        if (nextIndex != -1) {
            currentSearchIndex = nextIndex;
            // Scroll to search result
            if (transcriptionText.getLayout() != null) {
                int line = transcriptionText.getLayout().getLineForOffset(currentSearchIndex);
                int y = transcriptionText.getLayout().getLineTop(line);
                findViewById(R.id.transcriptionContainer).scrollTo(0, y);
            }
            // Optional: highlight current search result differently
            refreshTranscriptionSpans();
        }
    }

    private void adjustTextSize(float delta) {
        currentTextSize += delta;
        if (currentTextSize < 8f) currentTextSize = 8f;
        if (currentTextSize > 40f) currentTextSize = 40f;
        transcriptionText.setTextSize(currentTextSize);
    }

    private void toggleDarkMode(boolean enabled) {
        isDarkMode = enabled;
        int bgColor = enabled ? android.graphics.Color.BLACK : android.graphics.Color.parseColor("#F5F5F5");
        int textColor = enabled ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        
        findViewById(R.id.transcriptionContainer).setBackgroundColor(bgColor);
        transcriptionText.setTextColor(textColor);
        refreshTranscriptionSpans(); // Refresh highlights with new colors
    }

    private void loadSummary(String path) {
        try {
            File file = new File(path);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            this.summaryText = sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadTranscription(String path) {
        try {
            File file = new File(path);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            transcriptionSegments.clear();
            int currentCharCount = 0;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                // Check if it's word-level [MS|WORD] or segment-level [MS]
                if (line.contains("|") && line.contains("[")) {
                    // Word-level parsing
                    java.util.regex.Pattern wordPattern = java.util.regex.Pattern.compile("\\[(\\d+)\\|(.*?)\\]");
                    java.util.regex.Matcher wordMatcher = wordPattern.matcher(line);
                    
                    // Improved speaker label parsing: check for [Speaker X] at start
                    java.util.regex.Pattern speakerPattern = java.util.regex.Pattern.compile("^\\[(.*?)\\]:?\\s*");
                    java.util.regex.Matcher speakerMatcher = speakerPattern.matcher(line);
                    
                    if (speakerMatcher.find()) {
                        String speaker = speakerMatcher.group(1);
                        // Ensure it's not a timestamp [MS|WORD]
                        if (!speaker.contains("|")) {
                            sb.append(speaker).append(": ");
                            currentCharCount += speaker.length() + 2;
                        }
                    }

                    while (wordMatcher.find()) {
                        int startTimeMs = Integer.parseInt(wordMatcher.group(1));
                        String word = wordMatcher.group(2);
                        
                        TranscriptionSegment segment = new TranscriptionSegment(startTimeMs, word);
                        segment.startChar = currentCharCount;
                        segment.endChar = currentCharCount + word.length();
                        transcriptionSegments.add(segment);
                        
                        sb.append(word).append(" ");
                        currentCharCount += word.length() + 1;
                    }
                    sb.append("\n");
                    currentCharCount += 1;
                } else {
                    // Fallback to segment-level or plain text
                    int startTimeMs = 0;
                    String content = line;

                    if (line.startsWith("[")) {
                        int endBracket = line.indexOf("]");
                        if (endBracket != -1) {
                            String timeStr = line.substring(1, endBracket);
                            try {
                                if (timeStr.contains(":")) {
                                    String[] parts = timeStr.split(":");
                                    if (parts.length == 2) {
                                        startTimeMs = (Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1])) * 1000;
                                    }
                                } else {
                                    startTimeMs = Integer.parseInt(timeStr);
                                }
                            } catch (NumberFormatException e) {
                                startTimeMs = 0;
                            }
                            content = line.substring(endBracket + 1).trim();
                        }
                    }
                    
                    TranscriptionSegment segment = new TranscriptionSegment(startTimeMs, content);
                    segment.startChar = currentCharCount;
                    segment.endChar = currentCharCount + content.length();
                    transcriptionSegments.add(segment);
                    
                    sb.append(content).append("\n");
                    currentCharCount += content.length() + 1;
                }
            }
            reader.close();
            runOnUiThread(() -> {
                transcriptionText.setText(sb.toString());
                lastActiveIndex = -1; // Reset highlighting
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendEmail(String audioPath, String transPath, String summaryPath) {
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("message/rfc822");
        
        String title = etMeetingTitle.getText().toString();
        String attendees = etAttendees.getText().toString();
        String date = etMeetingDate.getText().toString();
        
        String subject = "PlayON: " + (title.isEmpty() ? "Meeting Results" : title);
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        
        StringBuilder body = new StringBuilder();
        body.append("Meeting Title: ").append(title).append("\n");
        body.append("Attendees: ").append(attendees).append("\n");
        body.append("Date: ").append(date).append("\n\n");
        body.append("Please find the audio, transcription, and summary attached.\n");
        
        intent.putExtra(Intent.EXTRA_TEXT, body.toString());
        
        ArrayList<Uri> uris = new ArrayList<>();
        try {
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(audioPath)));
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(transPath)));
            uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(summaryPath)));
        } catch (Exception e) {
            Toast.makeText(this, "Error preparing attachments", Toast.LENGTH_SHORT).show();
            return;
        }
        
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.email_chooser)));
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale(currentLang));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Language not supported for TTS", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(statusReceiver);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.release();
        }
    }

    private void updateStatus(String status, String message) {
        runOnUiThread(() -> {
            if (statusText != null) statusText.setText(message);
            if (aiProgressBar != null) {
                aiProgressBar.setVisibility("PROCESSING".equals(status) ? View.VISIBLE : View.GONE);
            }
            if ("STOPPED".equals(status)) {
                btnProcess.setVisibility(View.VISIBLE);
                trimLayout.setVisibility(View.VISIBLE);
            }
            if ("ERROR".equals(status)) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        
        EditText etGeminiKey = view.findViewById(R.id.etGeminiKey);
        EditText etGeminiEndpoint = view.findViewById(R.id.etGeminiEndpoint);
        EditText etDeepSeekKey = view.findViewById(R.id.etDeepSeekKey);
        EditText etDeepSeekEndpoint = view.findViewById(R.id.etDeepSeekEndpoint);
        EditText etClaudeKey = view.findViewById(R.id.etClaudeKey);
        EditText etLlamaKey = view.findViewById(R.id.etLlamaKey);
        EditText etGemmaKey = view.findViewById(R.id.etGemmaKey);
        EditText etGemmaEndpoint = view.findViewById(R.id.etGemmaEndpoint);
        EditText etLocalModelPath = view.findViewById(R.id.etLocalModelPath);
        Button btnDownloadModel = view.findViewById(R.id.btnDownloadModel);
        Spinner compressionFormatSpinner = view.findViewById(R.id.compressionFormatSpinner);
        Spinner bitrateSpinner = view.findViewById(R.id.bitrateSpinner);
        com.google.android.material.switchmaterial.SwitchMaterial switchVAD = view.findViewById(R.id.switchVAD);
        com.google.android.material.slider.Slider vadSensitivitySlider = view.findViewById(R.id.vadSensitivitySlider);
        
        String[] formats = {"AAC", "MP3"};
        ArrayAdapter<String> formatAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, formats);
        compressionFormatSpinner.setAdapter(formatAdapter);
        compressionFormatSpinner.setSelection(prefs.getString("Compression_Format", "AAC").equals("AAC") ? 0 : 1);

        String[] bitrates = {"64", "128", "192", "256", "320"};
        ArrayAdapter<String> bitrateAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, bitrates);
        bitrateSpinner.setAdapter(bitrateAdapter);
        String currentBitrate = prefs.getString("Compression_Bitrate", "128");
        for (int i = 0; i < bitrates.length; i++) {
            if (bitrates[i].equals(currentBitrate)) {
                bitrateSpinner.setSelection(i);
                break;
            }
        }

        etGeminiKey.setText(prefs.getString("Gemini_Key", ""));
        etGeminiEndpoint.setText(prefs.getString("Gemini_Endpoint", "v1beta/models/gemini-3-flash-preview:generateContent"));
        etDeepSeekKey.setText(prefs.getString("DeepSeek_Key", ""));
        etDeepSeekEndpoint.setText(prefs.getString("DeepSeek_Endpoint", "v1/chat/completions"));
        etClaudeKey.setText(prefs.getString("Claude_Key", ""));
        etLlamaKey.setText(prefs.getString("Llama_Key", ""));
        etGemmaKey.setText(prefs.getString("Gemma_Key", ""));
        etGemmaEndpoint.setText(prefs.getString("Gemma_Endpoint", ""));
        etLocalModelPath.setText(prefs.getString("LocalModel_Path", ""));
        switchVAD.setChecked(prefs.getBoolean("VAD_Enabled", true));
        vadSensitivitySlider.setValue(prefs.getFloat("VAD_Threshold", 300f));

        btnDownloadModel.setOnClickListener(v -> {
            btnDownloadModel.setEnabled(false);
            btnDownloadModel.setText("Downloading...");
            localModelManager.downloadModel("gemma-2b", new LocalModelManager.DownloadCallback() {
                @Override
                public void onProgress(int progress) {
                    runOnUiThread(() -> btnDownloadModel.setText("Downloading: " + progress + "%"));
                }

                @Override
                public void onSuccess(String path) {
                    runOnUiThread(() -> {
                        etLocalModelPath.setText(path);
                        Toast.makeText(MainActivity.this, "Model downloaded to: " + path, Toast.LENGTH_LONG).show();
                        btnDownloadModel.setEnabled(true);
                        btnDownloadModel.setText(getString(R.string.download_model_button));
                    });
                }

                @Override
                public void onFailure(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Download failed: " + error, Toast.LENGTH_SHORT).show();
                        btnDownloadModel.setEnabled(true);
                        btnDownloadModel.setText(getString(R.string.download_model_button));
                    });
                }
            });
        });
        
        builder.setView(view)
            .setTitle(getString(R.string.settings_title))
            .setPositiveButton(getString(R.string.save_button), null) // Set to null to override later for validation
            .setNegativeButton(getString(R.string.cancel_button), null);

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String gKey = etGeminiKey.getText().toString();
            String gEnd = etGeminiEndpoint.getText().toString();
            
            if (!gKey.isEmpty() && !gKey.startsWith("AIza")) {
                etGeminiKey.setError("Invalid Gemini Key format");
                return;
            }
            if (!gEnd.isEmpty() && !gEnd.startsWith("http")) {
                etGeminiEndpoint.setError("Invalid Endpoint URL");
                return;
            }

            prefs.edit()
                .putString("Gemini_Key", gKey)
                .putString("Gemini_Endpoint", gEnd)
                .putString("DeepSeek_Key", etDeepSeekKey.getText().toString())
                .putString("DeepSeek_Endpoint", etDeepSeekEndpoint.getText().toString())
                .putString("Claude_Key", etClaudeKey.getText().toString())
                .putString("Llama_Key", etLlamaKey.getText().toString())
                .putString("Gemma_Key", etGemmaKey.getText().toString())
                .putString("Gemma_Endpoint", etGemmaEndpoint.getText().toString())
                .putString("LocalModel_Path", etLocalModelPath.getText().toString())
                .putString("Compression_Format", compressionFormatSpinner.getSelectedItem().toString())
                .putString("Compression_Bitrate", bitrateSpinner.getSelectedItem().toString())
                .putBoolean("VAD_Enabled", switchVAD.isChecked())
                .putFloat("VAD_Threshold", vadSensitivitySlider.getValue())
                .apply();
            
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
    }

    private void startRecordingService() {
        Intent intent = new Intent(this, AudioRecordingService.class);
        intent.setAction("START_RECORDING");
        intent.putExtra("SELECTED_MODEL", selectedModel);
        intent.putExtra("LANGUAGE", currentLang);
        intent.putExtra("SAMPLE_RATE", selectedSampleRate);
        intent.putExtra("BIT_DEPTH", selectedBitDepth);
        intent.putExtra("CHANNELS", selectedChannels);
        startForegroundService(intent);
        Toast.makeText(this, "Recording started...", Toast.LENGTH_SHORT).show();
    }

    private void stopRecordingService() {
        Intent intent = new Intent(this, AudioRecordingService.class);
        intent.setAction("STOP_RECORDING");
        startService(intent);
    }

    private void startProcessingService() {
        Intent intent = new Intent(this, AudioRecordingService.class);
        intent.setAction("PROCESS_AUDIO");
        intent.putExtra("TRIM_START", trimStart);
        intent.putExtra("TRIM_END", trimEnd);
        startService(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            permissionToRecordAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
        }
        if (!permissionToRecordAccepted) finish();
    }
}
