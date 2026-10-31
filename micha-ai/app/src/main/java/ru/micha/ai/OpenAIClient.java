package ru.micha.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OpenAIClient {
    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final String MODEL = "gpt-6-luna";

    public static final class Result {
        public final String responseId;
        public final String text;
        public Result(String responseId, String text) {
            this.responseId = responseId;
            this.text = text;
        }
    }

    public Result send(String apiKey, String userText, String previousResponseId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("instructions", "Ты MICHa AI, личный ИИ-агент Михаила. Отвечай преимущественно на русском языке. Пиши ясно, естественно и без лишнего официоза. Для актуальных вопросов используй веб-поиск, если он полезен. Не выдумывай факты. Не выполняй опасные или необратимые действия без явного подтверждения. Твоя задача не просто болтать, а помогать доводить задачи до результата.");
        body.put("input", userText);
        body.put("store", true);
        if (previousResponseId != null && !previousResponseId.trim().isEmpty()) body.put("previous_response_id", previousResponseId);
        body.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String raw = readAll(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new Exception(readError(raw, code));

        JSONObject response = new JSONObject(raw);
        String id = response.optString("id", null);
        String text = extractTextWithSources(response);
        if (text == null || text.trim().isEmpty()) text = "Ответ получен, но текст не удалось прочитать.";
        return new Result(id, text);
    }

    private static String extractTextWithSources(JSONObject response) {
        StringBuilder text = new StringBuilder();
        Set<String> sources = new LinkedHashSet<>();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return response.optString("output_text", "");
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null || !"output_text".equals(part.optString("type"))) continue;
                String piece = part.optString("text", "");
                if (!piece.trim().isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(piece);
                }
                JSONArray annotations = part.optJSONArray("annotations");
                if (annotations != null) {
                    for (int k = 0; k < annotations.length(); k++) {
                        JSONObject ann = annotations.optJSONObject(k);
                        if (ann == null) continue;
                        String url = ann.optString("url", "");
                        String title = ann.optString("title", "Источник");
                        if (!url.trim().isEmpty()) sources.add(title + " — " + url);
                    }
                }
            }
        }
        if (!sources.isEmpty()) {
            text.append("\n\nИсточники:");
            for (String source : sources) text.append("\n• ").append(source);
        }
        return text.toString();
    }

    private static String readError(String raw, int code) {
        try {
            JSONObject error = new JSONObject(raw).optJSONObject("error");
            if (error != null) return "OpenAI API: " + error.optString("message", "Ошибка API") + " (HTTP " + code + ")";
        } catch (Exception ignored) { }
        return "Ошибка OpenAI API (HTTP " + code + ")";
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }
}
