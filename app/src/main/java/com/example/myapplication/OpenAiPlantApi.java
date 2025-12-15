package com.example.myapplication;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OpenAiPlantApi {

    // Можно поменять модель. GPT-4o mini принимает image input и поддерживает structured outputs.
    private static final String MODEL = "gpt-4o-mini";
    private static final String API_URL = "https://api.openai.com/v1/responses";

    // Если хочешь быстро отключить реальные запросы (например, чтобы не тратить токены)
    public static final boolean ENABLE_OPENAI = true;

    private OpenAiPlantApi() {}

    public static DiagnosisResult analyzePlantImage(File jpegFile) throws Exception {
        if (!ENABLE_OPENAI) {
            return new DiagnosisResult("Тестовый режим", 50, "Это заглушка. Включи ENABLE_OPENAI=true.");
        }

        String apiKey = "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"; //вставить ключ
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return DiagnosisResult.fallback("Не задан OPENAI_API_KEY (см. свой код).");
        }

        String base64 = encodeFileToBase64(jpegFile);
        String dataUrl = "data:image/jpeg;base64," + base64;

        // PROMPT (как ты просил) — но мы просим вернуть строго JSON по схеме
        String prompt =
                "Определи, чем болеет комнатное растение по фото. " +
                        "Ответь ОЧЕНЬ кратко и строго в JSON по схеме: " +
                        "disease (название болезни), confidence_percent (0-100), recommendations (очень кратко). " +
                        "Если по фото нельзя уверенно определить — disease='Не удалось определить', confidence_percent=0, recommendations='Сфотографируйте при хорошем свете и ближе к поражённому участку'.";

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("temperature", 0.2);
        body.put("max_output_tokens", 200);
        body.put("store", false);

        // input = [{ role:"user", content:[{input_text},{input_image}] }]
        JSONArray input = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");

        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "input_text")
                .put("text", prompt));

        content.put(new JSONObject()
                .put("type", "input_image")
                .put("image_url", dataUrl)
                .put("detail", "low"));

        userMsg.put("content", content);
        input.put(userMsg);
        body.put("input", input);

        // Structured Outputs через text.format json_schema
        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("disease", new JSONObject().put("type", "string"))
                        .put("confidence_percent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100))
                        .put("recommendations", new JSONObject().put("type", "string"))
                )
                .put("required", new JSONArray().put("disease").put("confidence_percent").put("recommendations"))
                .put("additionalProperties", false);

        JSONObject text = new JSONObject();
        JSONObject format = new JSONObject();
        format.put("type", "json_schema");
        format.put("name", "plant_diagnosis");
        format.put("strict", true);
        format.put("schema", schema);
        text.put("format", format);
        body.put("text", text);

        String responseJson = postJson(API_URL, body.toString(), apiKey);

        return parseDiagnosisFromResponsesApi(responseJson);
    }

    private static DiagnosisResult parseDiagnosisFromResponsesApi(String responseJson) {
        try {
            JSONObject root = new JSONObject(responseJson);

            // В Responses API текст обычно лежит в output -> message -> content -> output_text -> text
            JSONArray output = root.optJSONArray("output");
            if (output == null) return DiagnosisResult.fallback("Пустой ответ от сервера.");

            String text = null;
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                if (!"message".equals(item.optString("type"))) continue;
                if (!"assistant".equals(item.optString("role"))) continue;

                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;

                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null) continue;
                    if ("output_text".equals(part.optString("type"))) {
                        text = part.optString("text", null);
                        break;
                    }
                }
            }

            if (text == null || text.trim().isEmpty()) {
                return DiagnosisResult.fallback("Не удалось прочитать output_text.");
            }

            // text должен быть JSON по нашей схеме
            JSONObject data = new JSONObject(text);

            String disease = data.optString("disease", "Не удалось определить");
            int conf = data.optInt("confidence_percent", 0);
            conf = Math.max(0, Math.min(100, conf));
            String recs = data.optString("recommendations", "—");

            return new DiagnosisResult(disease, conf, recs);
        } catch (Exception e) {
            return DiagnosisResult.fallback("Ошибка парсинга ответа: " + e.getMessage());
        }
    }

    private static String encodeFileToBase64(File file) throws Exception {
        byte[] bytes = readAllBytes(file);
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static byte[] readAllBytes(File file) throws Exception {
        try (InputStream is = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }

    private static String postJson(String url, String jsonBody, String apiKey) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey); //

            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readStream(is);

            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + resp);
            }
            return resp;

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toString("UTF-8");
    }
}
