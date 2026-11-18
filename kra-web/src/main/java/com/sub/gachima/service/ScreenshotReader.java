package com.sub.gachima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 경마 앱 스크린샷에서 단승 배당·마체중 증감·주로 상태를 읽는다 (Claude API, 이미지 입력 + 도구 호출로 JSON 받기).
 * 인증키는 환경변수 ANTHROPIC_API_KEY 에서만 읽고, 로그에 남기지 않는다.
 */
@Service
public class ScreenshotReader {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotReader.class);
    private static final int MAX_BYTES = 4_500_000;   // API 이미지 한 장 한도(5MB) 아래로

    private final ObjectMapper om;
    private final String apiKey, model, url;

    public ScreenshotReader(ObjectMapper om,
                            @Value("${gachima.claude.api-key:}") String apiKey,
                            @Value("${gachima.claude.model:claude-sonnet-5-5}") String model,
                            @Value("${gachima.claude.url:https://api.anthropic.com/v1/messages}") String url) {
        this.om = om; this.apiKey = apiKey == null ? "" : apiKey.trim(); this.model = model; this.url = url;
    }

    public boolean isEnabled() { return !apiKey.isEmpty(); }

    public static class Image {
        public final byte[] data; public final String mediaType;
        public Image(byte[] data, String mediaType) { this.data = data; this.mediaType = mediaType; }
    }

    /** 읽은 값. 못 읽은 칸은 맵에 없다 */
    public static class Reading {
        public Integer raceNoSeen;
        public String track, notes;
        public final Map<Integer, Double> odds = new TreeMap<>(), bwd = new TreeMap<>();
    }

    public Reading read(List<Image> images, String meetName, int rcNo, Map<Integer, String> horses) throws IOException {
        if (!isEnabled()) throw new IllegalStateException("ANTHROPIC_API_KEY 가 없습니다");
        if (images.isEmpty()) throw new IllegalArgumentException("이미지가 없습니다");
        String body = om.writeValueAsString(request(images, meetName, rcNo, horses));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(120_000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        if (status >= 400) {
            String msg = text;
            try { msg = om.readTree(text).path("error").path("message").asText(text); } catch (IOException ignore) { }
            throw new IOException("Claude API 오류 " + status + ": " + msg);
        }
        return parse(om.readTree(text));
    }

    ObjectNode request(List<Image> images, String meetName, int rcNo, Map<Integer, String> horses) throws IOException {
        ObjectNode req = om.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", 4000);

        ObjectNode tool = req.putArray("tools").addObject();
        tool.put("name", "record_race_screen");
        tool.put("description", "스크린샷에서 읽은 마번별 단승 배당, 마체중 증감, 주로 상태를 기록한다");
        ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("race_no_seen").put("type", "integer").put("description", "화면에 보이는 경주 번호. 안 보이면 생략");
        props.putObject("track").put("type", "string").put("description", "건조/양호/다습/포화/불량 중 하나. 안 보이면 생략");
        ObjectNode hs = props.putObject("horses");
        hs.put("type", "array");
        ObjectNode item = hs.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("no").put("type", "integer").put("description", "마번(출전번호)");
        ip.putObject("odds").put("type", "number").put("description", "단승 배당. 안 보이거나 확실하지 않으면 생략");
        ip.putObject("bwd").put("type", "number").put("description", "마체중 증감 kg (478(+3) 이면 3). 안 보이면 생략");
        ip.putObject("scratched").put("type", "boolean").put("description", "출전 취소·제외로 표시되어 있으면 true");
        item.putArray("required").add("no");
        props.putObject("notes").put("type", "string").put("description", "읽기 어려웠던 점 (짧게, 한국어)");
        schema.putArray("required").add("horses");

        // 최신 모델 중에는 tool_choice 로 도구를 강제할 수 없는 것이 있어 auto 로 두고 프롬프트로 요청한다.
        // 도구를 안 부르고 글로 JSON 을 답해도 parse() 가 받아 준다.
        req.putObject("tool_choice").put("type", "auto");

        ArrayNode content = req.putArray("messages").addObject().put("role", "user").putArray("content");
        for (Image img : images) {
            Image fit = fit(img);
            ObjectNode src = content.addObject().put("type", "image").putObject("source");
            src.put("type", "base64");
            src.put("media_type", fit.mediaType);
            src.put("data", Base64.getEncoder().encodeToString(fit.data));
        }
        content.addObject().put("type", "text").put("text", prompt(meetName, rcNo, horses));
        return req;
    }

    static String prompt(String meetName, int rcNo, Map<Integer, String> horses) {
        StringBuilder b = new StringBuilder();
        b.append("한국 경마 앱(더비온, KRA 등) 화면을 찍은 스크린샷입니다. 여러 장이면 같은 경주의 다른 화면입니다.\n");
        b.append("찾는 경주: ").append(meetName).append(' ').append(rcNo).append("경주. 출전마:\n");
        for (Map.Entry<Integer, String> e : horses.entrySet()) b.append("  ").append(e.getKey()).append("번 ").append(e.getValue()).append('\n');
        b.append("\n마번마다 아래 값을 읽어 record_race_screen 도구로 기록하세요.\n");
        b.append("- odds: 단승(단승식) 배당만. 연승·복승·쌍승·삼복승 배당은 쓰지 마세요. 예: 5.3\n");
        b.append("- bwd: 마체중 옆 괄호 안의 증감. 478(+3) → 3, 501(-4) → -4, 465(0) → 0. 마체중 자체(478)는 쓰지 마세요.\n");
        b.append("- scratched: 출전 취소·제외·주행 중지 등으로 표시된 말.\n");
        b.append("- track: 주로 상태(건조/양호/다습/포화/불량). 함수율만 보이면 5% 이하 건조, 6~9% 양호, 10~14% 다습, 15~19% 포화, 20% 이상 불량.\n");
        b.append("- race_no_seen: 화면에 보이는 경주 번호.\n");
        b.append("화면에 없거나 흐려서 확실하지 않은 값은 넣지 마세요. 추측하지 마세요. 마명이 위 목록과 다르면 notes 에 적으세요.\n");
        b.append("반드시 record_race_screen 도구를 한 번 호출해서 답하세요. 도구를 쓸 수 없으면 같은 모양의 JSON 하나만 답하세요.");
        return b.toString();
    }

    Reading parse(JsonNode resp) throws IOException {
        JsonNode input = null;
        StringBuilder text = new StringBuilder();
        for (JsonNode c : resp.path("content")) {
            String type = c.path("type").asText();
            if ("tool_use".equals(type) && input == null) input = c.path("input");
            else if ("text".equals(type)) text.append(c.path("text").asText()).append('\n');
        }
        if (input == null) input = jsonInText(text.toString());
        if (input == null) throw new IOException("Claude 응답에 읽은 값이 없습니다: " + abbreviate(text.toString()));
        Reading r = new Reading();
        if (input.path("race_no_seen").isNumber()) r.raceNoSeen = input.path("race_no_seen").asInt();
        String t = input.path("track").asText("").trim();
        if (RaceService.TRACKS.contains(t)) r.track = t;
        r.notes = input.path("notes").asText(null);
        for (JsonNode h : input.path("horses")) {
            if (!h.path("no").isNumber()) continue;
            int no = h.path("no").asInt();
            if (h.path("scratched").asBoolean(false)) continue;
            if (h.path("odds").isNumber() && h.path("odds").asDouble() >= 1.0) r.odds.put(no, h.path("odds").asDouble());
            if (h.path("bwd").isNumber() && Math.abs(h.path("bwd").asDouble()) <= 40) r.bwd.put(no, h.path("bwd").asDouble());
        }
        return r;
    }

    /** 글 답변 속 { ... } JSON (코드 블록 포함) */
    private JsonNode jsonInText(String t) {
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode n = om.readTree(t.substring(a, b + 1));
            return n.has("horses") ? n : (n.path("input").has("horses") ? n.path("input") : null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String abbreviate(String s) {
        s = s.trim().replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 5MB 를 넘으면 줄여서 JPEG 로 */
    static Image fit(Image img) throws IOException {
        if (img.data.length <= MAX_BYTES) return img;
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(img.data));
        if (src == null) throw new IOException("이미지를 읽지 못했습니다. PNG/JPG 로 올려 주세요");
        double k = Math.min(1.0, 2000.0 / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * k), h = (int) Math.round(src.getHeight() * k);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "jpg", out);
        log.info("스크린샷 줄임 {} → {} bytes", img.data.length, out.size());
        return new Image(out.toByteArray(), "image/jpeg");
    }

    private static String readAll(InputStream in) throws IOException {
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder b = new StringBuilder(); char[] buf = new char[8192]; int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
            return b.toString();
        }
    }
}
