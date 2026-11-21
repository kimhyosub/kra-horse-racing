package com.sub.gachima.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 가이드 글용 아주 작은 마크다운 → HTML. HTML 은 먼저 전부 이스케이프하므로 본문에 태그를 써도 그대로 글자로 나온다.
 * 지원: # ## ### 제목, 빈 줄로 문단, - / 1. 목록, > 인용, --- 구분선, | 표 |, **굵게**, `코드`, [글](주소)
 */
public final class Markdown {
    private Markdown() {}

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(((?:https?://|/)[^)\\s]*)\\)");

    public static String toHtml(String md) {
        if (md == null) return "";
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> para = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String l = lines[i], t = l.trim();
            if (t.isEmpty()) { flush(out, para); i++; continue; }
            if (t.matches("#{1,3} .+")) {
                flush(out, para);
                int lv = t.indexOf(' ');
                int h = lv + 1;   // # → h2 (페이지 제목이 h1)
                out.append("<h").append(h).append('>').append(inline(t.substring(lv + 1).trim())).append("</h").append(h).append(">\n");
                i++; continue;
            }
            if (t.matches("-{3,}")) { flush(out, para); out.append("<hr>\n"); i++; continue; }
            if (t.startsWith(">")) {
                flush(out, para);
                StringBuilder q = new StringBuilder();
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    if (q.length() > 0) q.append("<br>");
                    q.append(inline(lines[i].trim().substring(1).trim()));
                    i++;
                }
                out.append("<blockquote>").append(q).append("</blockquote>\n");
                continue;
            }
            if (t.matches("[-*] .+") || t.matches("\\d+\\. .+")) {
                flush(out, para);
                boolean ol = t.matches("\\d+\\. .+");
                out.append(ol ? "<ol>\n" : "<ul>\n");
                while (i < lines.length) {
                    String x = lines[i].trim();
                    if (ol ? !x.matches("\\d+\\. .+") : !x.matches("[-*] .+")) break;
                    out.append("<li>").append(inline(x.substring(x.indexOf(' ') + 1).trim())).append("</li>\n");
                    i++;
                }
                out.append(ol ? "</ol>\n" : "</ul>\n");
                continue;
            }
            if (t.startsWith("|") && i + 1 < lines.length && lines[i + 1].trim().matches("\\|?\\s*:?-{2,}.*")) {
                flush(out, para);
                out.append("<div class=\"post-table\"><table>\n<thead><tr>");
                for (String c : cells(t)) out.append("<th>").append(inline(c)).append("</th>");
                out.append("</tr></thead>\n<tbody>\n");
                i += 2;
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    out.append("<tr>");
                    for (String c : cells(lines[i].trim())) out.append("<td>").append(inline(c)).append("</td>");
                    out.append("</tr>\n");
                    i++;
                }
                out.append("</tbody></table></div>\n");
                continue;
            }
            para.add(t);
            i++;
        }
        flush(out, para);
        return out.toString();
    }

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> l = new ArrayList<>();
        for (String c : r.split("\\|", -1)) l.add(c.trim());
        return l;
    }

    private static void flush(StringBuilder out, List<String> para) {
        if (para.isEmpty()) return;
        out.append("<p>");
        for (int k = 0; k < para.size(); k++) { if (k > 0) out.append(' '); out.append(inline(para.get(k))); }
        out.append("</p>\n");
        para.clear();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String inline(String s) {
        String x = esc(s);
        x = CODE.matcher(x).replaceAll("<code>$1</code>");
        x = BOLD.matcher(x).replaceAll("<b>$1</b>");
        Matcher m = LINK.matcher(x);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String url = m.group(2), ext = url.startsWith("http") ? " rel=\"noopener\" target=\"_blank\"" : "";
            m.appendReplacement(b, Matcher.quoteReplacement("<a href=\"" + url + "\"" + ext + ">" + m.group(1) + "</a>"));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** 목록·검색 설명용: 마크다운 기호를 뺀 앞부분 */
    public static String plain(String md, int max) {
        if (md == null) return "";
        String s = md.replaceAll("(?m)^\\s*\\|?[\\s:|-]*-{2,}[\\s:|-]*$", "").replaceAll("(?m)^#{1,3} |^[-*>] |^\\d+\\. |\\*\\*|`|\\|", "").replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
