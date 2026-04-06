package com.playon.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Response;

public class AudioRecordingService extends Service {
    private int sampleRate = 44100;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int numChannels = 1;
    private boolean vadEnabled = true;
    private float vadThreshold = 300f;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private File tempFile;
    private String selectedModel = "Gemini";
    private String language = "es";
    private LocalModelManager localModelManager;

    @Override
    public void onCreate() {
        super.onCreate();
        localModelManager = new LocalModelManager(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if ("START_RECORDING".equals(action)) {
            selectedModel = intent.getStringExtra("SELECTED_MODEL");
            language = intent.getStringExtra("LANGUAGE");
            sampleRate = intent.getIntExtra("SAMPLE_RATE", 44100);
            int bitDepth = intent.getIntExtra("BIT_DEPTH", 16);
            numChannels = intent.getIntExtra("CHANNELS", 1);
            
            channelConfig = (numChannels == 2) ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            audioFormat = (bitDepth == 8) ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
            
            startRecording();
        } else if ("STOP_RECORDING".equals(action)) {
            stopRecording();
        } else if ("PROCESS_AUDIO".equals(action)) {
            float trimStart = intent.getFloatExtra("TRIM_START", 0f);
            float trimEnd = intent.getFloatExtra("TRIM_END", 100f);
            processAudio(trimStart, trimEnd);
        }
        return START_STICKY;
    }

    private int vadHangoverFrames = 0;
    private static final int VAD_HANGOVER_LIMIT = 10; // ~200ms at 20ms frames

    private void startRecording() {
        SharedPreferences prefs = getSharedPreferences("PlayON_Prefs", MODE_PRIVATE);
        vadEnabled = prefs.getBoolean("VAD_Enabled", true);
        vadThreshold = prefs.getFloat("VAD_Threshold", 300f);

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "RECORDING_CHANNEL")
                .setContentTitle("PlayON Recording")
                .setContentText("Recording in progress...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        startForeground(1, notification);

        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize);

        isRecording = true;
        audioRecord.startRecording();

        executorService.execute(() -> {
            tempFile = new File(getExternalFilesDir(null), "temp_audio.pcm");
            try (FileOutputStream os = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[bufferSize];
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, bufferSize);
                    if (read > 0) {
                        if (!isSilent(buffer, read)) {
                            os.write(buffer, 0, read);
                            os.flush();
                            vadHangoverFrames = VAD_HANGOVER_LIMIT;
                        } else if (vadHangoverFrames > 0) {
                            os.write(buffer, 0, read);
                            os.flush();
                            vadHangoverFrames--;
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private double backgroundNoiseLevel = -1;
    private static final double NOISE_SMOOTHING = 0.99;

    private double backgroundZcrLevel = -1.0;
    private static final double ZCR_SMOOTHING = 0.95;

    private boolean isSilent(byte[] buffer, int read) {
        if (!vadEnabled) return false;
        long sum = 0;
        int zeroCrossings = 0;
        short lastSample = 0;

        for (int i = 0; i < read - 1; i += 2) {
            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            sum += sample * sample;
            
            if ((lastSample > 0 && sample <= 0) || (lastSample < 0 && sample >= 0)) {
                zeroCrossings++;
            }
            lastSample = sample;
        }
        
        double rms = Math.sqrt(sum / (read / 2.0));
        double zcr = (double) zeroCrossings / (read / 2.0);

        if (backgroundNoiseLevel < 0) {
            backgroundNoiseLevel = rms;
        } else {
            backgroundNoiseLevel = backgroundNoiseLevel * NOISE_SMOOTHING + rms * (1.0 - NOISE_SMOOTHING);
        }

        if (backgroundZcrLevel < 0) {
            backgroundZcrLevel = zcr;
        } else {
            backgroundZcrLevel = backgroundZcrLevel * ZCR_SMOOTHING + zcr * (1.0 - ZCR_SMOOTHING);
        }

        // Adjust threshold based on background noise
        double adaptiveThreshold = backgroundNoiseLevel + vadThreshold;
        
        // Speech typically has ZCR between 0.05 and 0.5. High ZCR is often noise.
        // We also check if the current ZCR is significantly different from background ZCR.
        boolean isLowEnergy = rms < adaptiveThreshold;
        boolean isHighZcr = zcr > 0.6 || zcr > (backgroundZcrLevel * 2.0); // Heuristic for noise

        return isLowEnergy || isHighZcr;
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        stopForeground(true);
        sendStatusUpdate("STOPPED", "Recording stopped. You can now trim and process.", null, null, null);
    }

    private void processAudio(float trimStart, float trimEnd) {
        executorService.execute(() -> {
            SharedPreferences prefs = getSharedPreferences("PlayON_Prefs", MODE_PRIVATE);
            String format = prefs.getString("Compression_Format", "AAC");
            String bitrateStr = prefs.getString("Compression_Bitrate", "128");
            int bitrate = Integer.parseInt(bitrateStr) * 1000;
            
            String ext = format.equals("AAC") ? ".m4a" : ".mp3";
            File compressedFile = new File(getExternalFilesDir(null), "audio_" + System.currentTimeMillis() + ext);
            
            try {
                File trimmedPcm = tempFile;
                if (trimStart > 0 || trimEnd < 100) {
                    trimmedPcm = new File(getExternalFilesDir(null), "trimmed_audio.pcm");
                    trimPcmFile(tempFile, trimmedPcm, trimStart, trimEnd);
                }
                
                if (format.equals("AAC")) {
                    encodePcmToAac(trimmedPcm, compressedFile, bitrate);
                } else {
                    // MP3 encoding not fully implemented, fallback to AAC for now
                    encodePcmToAac(trimmedPcm, compressedFile, bitrate);
                }
                processAudioWithAI(compressedFile);
            } catch (IOException e) {
                Log.e("AudioService", "Encoding failed", e);
                sendStatusUpdate("ERROR", "Processing failed: " + e.getMessage(), null, null, null);
            }
        });
    }

    private void trimPcmFile(File source, File dest, float startPct, float endPct) throws IOException {
        long totalLength = source.length();
        int frameSize = numChannels * (audioFormat == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2);
        
        long startByte = (long) (totalLength * (startPct / 100.0));
        startByte = (startByte / frameSize) * frameSize; // Align to frame
        
        long endByte = (long) (totalLength * (endPct / 100.0));
        endByte = (endByte / frameSize) * frameSize; // Align to frame
        
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest)) {
            fis.skip(startByte);
            long bytesToRead = endByte - startByte;
            byte[] buffer = new byte[8192];
            long totalRead = 0;
            while (totalRead < bytesToRead) {
                int toRead = (int) Math.min(buffer.length, bytesToRead - totalRead);
                int read = fis.read(buffer, 0, toRead);
                if (read <= 0) break;
                fos.write(buffer, 0, read);
                totalRead += read;
            }
        }
    }

    private void encodePcmToAac(File pcmFile, File m4aFile, int bitrate) throws IOException {
        FileInputStream fis = new FileInputStream(pcmFile);
        MediaMuxer muxer = new MediaMuxer(m4aFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, numChannels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        ByteBuffer[] inputBuffers = encoder.getInputBuffers();
        ByteBuffer[] outputBuffers = encoder.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        int trackIndex = -1;
        boolean isEOF = false;
        long presentationTimeUs = 0;

        byte[] pcmBuffer = new byte[8192];
        
        while (!isEOF || bufferInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
            if (!isEOF) {
                int inputBufferIndex = encoder.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                    inputBuffer.clear();
                    int read = fis.read(pcmBuffer);
                    if (read <= 0) {
                        isEOF = true;
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        inputBuffer.put(pcmBuffer, 0, read);
                        encoder.queueInputBuffer(inputBufferIndex, 0, read, presentationTimeUs, 0);
                        presentationTimeUs += (read * 1000000L) / (sampleRate * numChannels * (audioFormat == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2));
                    }
                }
            }

            int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex >= 0) {
                if (trackIndex == -1) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                }
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                encoder.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Should be handled by trackIndex == -1 check
            }
        }

        encoder.stop();
        encoder.release();
        muxer.stop();
        muxer.release();
        fis.close();
    }

    private void sendStatusUpdate(String status, String message, String audioPath, String transPath, String summaryPath) {
        Intent intent = new Intent("com.playon.recorder.STATUS_UPDATE");
        intent.putExtra("STATUS", status);
        intent.putExtra("MESSAGE", message);
        if (audioPath != null) intent.putExtra("AUDIO_PATH", audioPath);
        if (transPath != null) intent.putExtra("TRANS_PATH", transPath);
        if (summaryPath != null) intent.putExtra("SUMMARY_PATH", summaryPath);
        sendBroadcast(intent);
    }

    private void processAudioWithAI(File audioFile) {
        if ("Local".equals(selectedModel)) {
            processAudioLocally(audioFile);
            return;
        }
        sendStatusUpdate("PROCESSING", "Analyzing audio with AI...", null, null, null);
        
        SharedPreferences prefs = getSharedPreferences("PlayON_Prefs", MODE_PRIVATE);
        String apiKey = prefs.getString(selectedModel + "_Key", "");
        if (apiKey.isEmpty()) {
            sendStatusUpdate("ERROR", "API Key missing for " + selectedModel, null, null, null);
            return;
        }

        String baseUrl = prefs.getString(selectedModel + "_Endpoint", "https://generativelanguage.googleapis.com/");
        String endpoint = "";
        if (selectedModel.equals("Gemini")) {
            endpoint = "v1beta/models/gemini-3-flash-preview:generateContent";
        } else if (selectedModel.startsWith("Gemma")) {
            endpoint = "v1/chat/completions"; // Assuming OpenAI-compatible endpoint for Gemma
        }
        
        AiApiClient client = AiApiClient.create(baseUrl);
        
        String promptText = getString(R.string.ai_prompt);

        RequestBody requestFile = RequestBody.create(MediaType.parse("audio/mp4"), audioFile);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", audioFile.getName(), requestFile);
        RequestBody prompt = RequestBody.create(MediaType.parse("text/plain"), promptText);

        client.analyzeAudio(endpoint, "Bearer " + apiKey, body, prompt)
            .enqueue(new retrofit2.Callback<AiApiClient.AiResponse>() {
                @Override
                public void onResponse(retrofit2.Call<AiApiClient.AiResponse> call, Response<AiApiClient.AiResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        saveResultsAndNotify(audioFile, response.body());
                    } else {
                        handleApiError(response.code());
                    }
                }

                @Override
                public void onFailure(retrofit2.Call<AiApiClient.AiResponse> call, Throwable t) {
                    sendStatusUpdate("ERROR", "AI Analysis failed: " + t.getMessage(), null, null, null);
                }
            });
    }

    private void processAudioLocally(File audioFile) {
        sendStatusUpdate("PROCESSING", "Analyzing locally (llama.cpp)...", null, null, null);
        
        SharedPreferences prefs = getSharedPreferences("PlayON_Prefs", MODE_PRIVATE);
        String modelPath = prefs.getString("LocalModel_Path", "");
        
        if (!localModelManager.isModelAvailable(modelPath)) {
            sendStatusUpdate("ERROR", "Local model file not found at: " + modelPath, null, null, null);
            return;
        }

        localModelManager.loadModel(modelPath, new LocalModelManager.ModelLoadCallback() {
            @Override
            public void onSuccess() {
                localModelManager.runInference(audioFile, getString(R.string.ai_prompt), new LocalModelManager.InferenceCallback() {
                    @Override
                    public void onSuccess(String transcription, String summary) {
                        AiApiClient.AiResponse mockResponse = new AiApiClient.AiResponse();
                        mockResponse.transcription = transcription;
                        mockResponse.summary = summary;
                        saveResultsAndNotify(audioFile, mockResponse);
                    }

                    @Override
                    public void onFailure(String error) {
                        sendStatusUpdate("ERROR", "Local inference failed: " + error, null, null, null);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                sendStatusUpdate("ERROR", "Local model load failed: " + error, null, null, null);
            }
        });
    }

    private void saveResultsAndNotify(File audioFile, AiApiClient.AiResponse result) {
        String baseName = audioFile.getName().replace(".m4a", "");
        File transFile = new File(getExternalFilesDir(null), "transcripcion_" + baseName + ".txt");
        File summaryFile = new File(getExternalFilesDir(null), "resumen_" + baseName + ".txt");

        try {
            String fullText = "";
            
            // 1. Check direct fields
            if (result.transcription != null) {
                fullText = result.transcription + "\n\n" + (result.summary != null ? result.summary : "");
            } 
            // 2. Gemini format
            else if (result.candidates != null && result.candidates.length > 0) {
                fullText = result.candidates[0].content.parts[0].text;
            }
            // 3. DeepSeek/OpenAI format
            else if (result.choices != null && result.choices.length > 0) {
                fullText = result.choices[0].message.content;
            }
            // 4. Claude format
            else if (result.content != null && result.content.length > 0) {
                fullText = result.content[0].text;
            }

            // Clean up raw text (remove markdown code blocks)
            fullText = fullText.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").replaceAll("```", "").trim();

            // Simple split if JSON parsing is not available or failed
            String transcription = fullText;
            String summary = "Summary extracted from text.";
            
            // Try to parse JSON if it looks like JSON
            if (fullText.trim().startsWith("{")) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(fullText);
                    transcription = json.optString("transcription", fullText);
                    
                    Object summaryObj = json.opt("summary");
                    if (summaryObj instanceof org.json.JSONObject) {
                        org.json.JSONObject sJson = (org.json.JSONObject) summaryObj;
                        StringBuilder sb = new StringBuilder();
                        sb.append("EXECUTIVE SUMMARY:\n").append(sJson.optString("executive_summary")).append("\n\n");
                        
                        org.json.JSONArray decisions = sJson.optJSONArray("key_decisions");
                        if (decisions != null && decisions.length() > 0) {
                            sb.append("KEY DECISIONS:\n");
                            for (int i = 0; i < decisions.length(); i++) {
                                sb.append("- ").append(decisions.getString(i)).append("\n");
                            }
                            sb.append("\n");
                        }

                        org.json.JSONArray items = sJson.optJSONArray("action_items");
                        if (items != null && items.length() > 0) {
                            sb.append("ACTION ITEMS:\n");
                            for (int i = 0; i < items.length(); i++) {
                                org.json.JSONObject item = items.getJSONObject(i);
                                sb.append("- ").append(item.optString("item"))
                                  .append(" (Assignee: ").append(item.optString("assignee"))
                                  .append(", Deadline: ").append(item.optString("deadline")).append(")\n");
                            }
                            sb.append("\n");
                        }
                        
                        org.json.JSONArray risks = sJson.optJSONArray("risks");
                        if (risks != null && risks.length() > 0) {
                            sb.append("POTENTIAL RISKS & CHALLENGES:\n");
                            for (int i = 0; i < risks.length(); i++) {
                                sb.append("- ").append(risks.getString(i)).append("\n");
                            }
                        }
                        summary = sb.toString();
                    } else {
                        summary = json.optString("summary", "Summary not found in JSON.");
                    }
                } catch (org.json.JSONException e) {
                    // Not valid JSON, maybe it's just text that starts with {
                }
            } else if (fullText.contains("\"transcription\":") && fullText.contains("\"summary\":")) {
                // Try to find JSON block within text if it's not the whole thing
                try {
                    int start = fullText.indexOf("{");
                    int end = fullText.lastIndexOf("}");
                    if (start != -1 && end != -1 && end > start) {
                        String jsonStr = fullText.substring(start, end + 1);
                        org.json.JSONObject json = new org.json.JSONObject(jsonStr);
                        transcription = json.optString("transcription", transcription);
                        summary = json.optString("summary", summary);
                    }
                } catch (Exception e) {
                    // Ignore and use fullText
                }
            }

            writeToFile(transFile, transcription);
            writeToFile(summaryFile, summary);

            sendStatusUpdate("READY", "Processing complete", audioFile.getAbsolutePath(), transFile.getAbsolutePath(), summaryFile.getAbsolutePath());
        } catch (IOException e) {
            sendStatusUpdate("ERROR", "Failed to save results", null, null, null);
        }
    }

    private void writeToFile(File file, String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        }
    }

    private void handleApiError(int code) {
        String message;
        switch (code) {
            case 401: message = "Invalid API Key"; break;
            case 429: message = "Rate limit exceeded (429)"; break;
            case 500: message = "Server error (500)"; break;
            default: message = "API Error: " + code; break;
        }
        sendStatusUpdate("ERROR", message, null, null, null);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("RECORDING_CHANNEL", "Recording Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
