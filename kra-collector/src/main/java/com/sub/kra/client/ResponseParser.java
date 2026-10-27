package com.sub.kra.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공공데이터포털 응답 파싱.
 * 정상: {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[...]|{...}|""},"totalCount":N}}}
 * 오류: JSON header 의 resultCode, 또는 XML(OpenAPI_ServiceResponse) 의 returnReasonCode
 */
public class ResponseParser {

    private static final Pattern XML_CODE = Pattern.compile("<(?:returnReasonCode|resultCode)>\\s*(\\d+)\\s*</");
    private static final Pattern XML_MSG = Pattern.compile("<(?:returnAuthMsg|errMsg|resultMsg)>\\s*([^<]*)</");
    private final ObjectMapper om;

    public ResponseParser(ObjectMapper om) { this.om = om; }

    public static class Page {
        public final List<JsonNode> items;
        public final int totalCount;
        Page(List<JsonNode> items, int totalCount) { this.items = items; this.totalCount = totalCount; }
    }

    /** <response><body><items><item><필드>값</필드>...</item></items><totalCount>N</totalCount></body></response> */
    Page parseXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            List<JsonNode> out = new ArrayList<JsonNode>();
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                ObjectNode o = om.createObjectNode();
                NodeList ch = items.item(i).getChildNodes();
                for (int j = 0; j < ch.getLength(); j++) {
                    Node n = ch.item(j);
                    if (n.getNodeType() == Node.ELEMENT_NODE) o.put(((Element) n).getTagName(), n.getTextContent().trim());
                }
                out.add(o);
            }
            NodeList tc = doc.getElementsByTagName("totalCount");
            int total = tc.getLength() > 0 ? Integer.parseInt(tc.item(0).getTextContent().trim()) : out.size();
            return new Page(out, total);
        } catch (Exception e) {
            throw new KraApiException("PARSE", "XML 파싱 실패: " + e.getMessage());
        }
    }

    public Page parse(String body) {
        if (body == null || body.trim().isEmpty()) throw new KraApiException("EMPTY", "빈 응답");
        String b = body.trim();
        if (b.startsWith("<")) {
            Matcher c = XML_CODE.matcher(b), m = XML_MSG.matcher(b);
            String code = c.find() ? c.group(1) : "XML";
            String msg = m.find() ? m.group(1) : b.substring(0, Math.min(200, b.length()));
            if ("03".equals(code)) return new Page(new ArrayList<JsonNode>(), 0);
            if ("00".equals(code) || "0".equals(code)) return parseXml(b);   // XML 전용 API (예: 심판정보)
            throw new KraApiException(code, msg);
        }
        JsonNode root;
        try { root = om.readTree(b); } catch (IOException e) { throw new KraApiException("PARSE", e.getMessage()); }
        JsonNode resp = root.has("response") ? root.get("response") : root;
        JsonNode header = resp.path("header");
        String code = header.path("resultCode").asText("00");
        if ("03".equals(code)) return new Page(new ArrayList<JsonNode>(), 0); // NODATA
        if (!"00".equals(code) && !"0".equals(code)) throw new KraApiException(code, header.path("resultMsg").asText());

        JsonNode body2 = resp.path("body");
        JsonNode item = body2.path("items").path("item");
        List<JsonNode> out = new ArrayList<JsonNode>();
        if (item.isArray()) for (JsonNode n : item) out.add(n);
        else if (item.isObject()) out.add(item);
        int total = body2.path("totalCount").asInt(out.size());
        return new Page(out, total);
    }
}
