package com.sub.gachima.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 텔레그램 봇 API 를 직접 부르는 얇은 클라이언트 (Java 8, 외부 라이브러리 없음).
 * 토큰은 환경변수 TELEGRAM_BOT_TOKEN 에서만 읽고, 오류 메시지에서는 가린다.
 */
@Component
public class TelegramClient {
    private final ObjectMapper om;
    private final String token;
    private final String api = "https://api.telegram.org";

    public TelegramClient(ObjectMapper om, @Value("${gachima.telegram.token:}") String token) {
        this.om = om; this.token = token == null ? "" : token.trim();
    }

    public boolean isEnabled() { return !token.isEmpty(); }

    /** long polling. 새 메시지가 없으면 timeoutSec 동안 기다렸다가 빈 배열 */
    public JsonNode getUpdates(long offset, int timeoutSec) throws IOException {
        String q = "offset=" + offset + "&timeout=" + timeoutSec + "&allowed_updates=" + enc("[\"message\"]");
        return call("getUpdates?" + q, null, (timeoutSec + 15) * 1000).path("result");
    }

    public void sendMessage(long chatId, String html, Long replyTo) throws IOException {
        ObjectNode b = message(html);
        b.put("chat_id", chatId);
        if (replyTo != null) b.putObject("reply_parameters").put("message_id", replyTo).put("allow_sending_without_reply", true);
        call("sendMessage", b, 20_000);
    }

    /** 채널(@이름 또는 -100… ID)로 보내고 메시지 번호를 돌려준다 */
    public long sendMessage(String chatId, String html) throws IOException {
        ObjectNode b = message(html);
        b.put("chat_id", chatId);
        return call("sendMessage", b, 20_000).path("result").path("message_id").asLong();
    }

    /** 채널 글에 답글 (알림 없이), 메시지 번호를 돌려준다 */
    public long sendReply(String chatId, String html, long replyTo) throws IOException {
        ObjectNode b = message(html);
        b.put("chat_id", chatId);
        b.put("disable_notification", true);
        b.putObject("reply_parameters").put("message_id", replyTo).put("allow_sending_without_reply", true);
        return call("sendMessage", b, 20_000).path("result").path("message_id").asLong();
    }

    /** 보낸 메시지 고치기. 내용이 같으면 텔레그램이 오류를 주는데 무시한다 */
    public void editMessage(String chatId, long messageId, String html) throws IOException {
        ObjectNode b = message(html);
        b.put("chat_id", chatId);
        b.put("message_id", messageId);
        try {
            call("editMessageText", b, 20_000);
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().contains("not modified")) throw e;
        }
    }

    private ObjectNode message(String html) {
        ObjectNode b = om.createObjectNode();
        b.put("text", html.length() > 4000 ? html.substring(0, 4000) + "…" : html);
        b.put("parse_mode", "HTML");
        b.put("disable_web_page_preview", true);
        return b;
    }

    public void typing(long chatId) {
        try {
            ObjectNode b = om.createObjectNode();
            b.put("chat_id", chatId);
            b.put("action", "typing");
            call("sendChatAction", b, 10_000);
        } catch (IOException ignore) { /* 표시용 */ }
    }

    /** file_id → 파일 바이트 */
    public byte[] download(String fileId) throws IOException {
        String path = call("getFile?file_id=" + enc(fileId), null, 20_000).path("result").path("file_path").asText("");
        if (path.isEmpty()) throw new IOException("텔레그램 파일 경로를 받지 못했습니다");
        HttpURLConnection c = (HttpURLConnection) new URL(api + "/file/bot" + token + "/" + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IOException(mask(e.getMessage()));
        }
    }

    private JsonNode call(String method, ObjectNode body, int readTimeout) throws IOException {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(api + "/bot" + token + "/" + method).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(readTimeout);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(om.writeValueAsBytes(body)); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            JsonNode res = in == null ? om.createObjectNode() : om.readTree(in);
            if (status >= 400 || !res.path("ok").asBoolean(false))
                throw new IOException("텔레그램 " + method.split("\\?")[0] + " 오류 " + status + ": " + res.path("description").asText(""));
            return res;
        } catch (IOException e) {
            throw new IOException(mask(e.getMessage()), e.getCause());
        }
    }

    /** 오류 메시지 속 주소에 토큰이 들어가므로 가린다 */
    String mask(String s) {
        return s == null || token.isEmpty() ? s : s.replace(token, "***");
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
}
