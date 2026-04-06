package com.playon.recorder;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Url;

public interface AiApiClient {
    @Multipart
    @POST
    Call<AiResponse> analyzeAudio(
        @Url String url,
        @Header("Authorization") String authHeader,
        @Part MultipartBody.Part audioFile,
        @Part("prompt") RequestBody prompt
    );

    // Specific for Gemini if needed, or we can use a generic response
    class AiResponse {
        public String transcription;
        public String summary;
        
        // For Gemini compatibility
        public Candidate[] candidates;
        public static class Candidate {
            public Content content;
        }
        public static class Content {
            public Part[] parts;
        }
        public static class Part {
            public String text;
        }

        // For DeepSeek/OpenAI compatibility
        public Choice[] choices;
        public static class Choice {
            public Message message;
        }
        public static class Message {
            public String content;
        }

        // For Claude compatibility
        public ClaudeContent[] content;
        public static class ClaudeContent {
            public String text;
        }
    }

    static AiApiClient create(String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AiApiClient.class);
    }
}
