package com.jirabot.slack.controller;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class ArtifactEmbedder {

    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*(['\"]?)([^'\"\\)]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "(@import\\s+)(['\"])([^'\"]+)\\2", Pattern.CASE_INSENSITIVE);

    private ArtifactEmbedder() {}

    static String inline(long id, String html, List<ArtifactAssetEntity> assets) {
        Map<String, ArtifactAssetEntity> byPath = new HashMap<>();
        for (ArtifactAssetEntity asset : assets) {
            byPath.put(asset.getPath(), asset);
        }
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        if (doc.selectFirst("base[href]") == null) {
            doc.head().prependElement("base").attr("href", "/artifacts/view/" + id + "/");
        }
        for (Element element : doc.getAllElements()) {
            for (String attribute : List.of("src", "href", "poster", "data")) {
                if (!element.hasAttr(attribute) || ("href".equals(attribute)
                        && ("a".equals(element.normalName()) || "base".equals(element.normalName())))) {
                    continue;
                }
                String uri = dataUri(element.attr(attribute), "", byPath, new HashSet<>());
                if (uri != null) {
                    element.attr(attribute, uri);
                }
            }
            if (element.hasAttr("srcset") && !element.attr("srcset").contains("data:")) {
                element.attr("srcset", inlineSrcset(element.attr("srcset"), byPath));
            }
            if (element.hasAttr("style")) {
                element.attr("style", inlineCss(element.attr("style"), "", byPath, new HashSet<>()));
            }
            if ("style".equals(element.normalName())) {
                element.html(inlineCss(element.data(), "", byPath, new HashSet<>()));
            }
        }
        return doc.outerHtml();
    }

    private static String inlineSrcset(String srcset, Map<String, ArtifactAssetEntity> assets) {
        String[] candidates = srcset.split(",", -1);
        for (int i = 0; i < candidates.length; i++) {
            String value = candidates[i].trim();
            int end = 0;
            while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
                end++;
            }
            String uri = dataUri(value.substring(0, end), "", assets, new HashSet<>());
            candidates[i] = uri == null ? value : uri + value.substring(end);
        }
        return String.join(", ", candidates);
    }

    private static String inlineCss(String css, String base, Map<String, ArtifactAssetEntity> assets,
                                    Set<String> visited) {
        Matcher urls = CSS_URL.matcher(css);
        StringBuffer out = new StringBuffer();
        while (urls.find()) {
            String uri = dataUri(urls.group(2), base, assets, visited);
            urls.appendReplacement(out, Matcher.quoteReplacement(uri == null
                    ? urls.group() : "url(\"" + uri + "\")"));
        }
        urls.appendTail(out);

        Matcher imports = CSS_IMPORT.matcher(out.toString());
        StringBuffer withImports = new StringBuffer();
        while (imports.find()) {
            String uri = dataUri(imports.group(3), base, assets, visited);
            imports.appendReplacement(withImports, Matcher.quoteReplacement(uri == null
                    ? imports.group() : imports.group(1) + imports.group(2) + uri + imports.group(2)));
        }
        imports.appendTail(withImports);
        return withImports.toString();
    }

    private static String dataUri(String value, String base, Map<String, ArtifactAssetEntity> assets,
                                  Set<String> visited) {
        if (value.isBlank() || value.startsWith("/") || value.startsWith("#")
                || value.startsWith("//") || value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            return null;
        }
        String rawPath = value.split("[?#]", 2)[0];
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }

        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String segment : (base + decoded).split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(segment);
            }
        }
        String path = String.join("/", parts);
        ArtifactAssetEntity asset = assets.get(path);
        if (asset == null || visited.contains(path)) return null;

        byte[] bytes = asset.getData();
        if (asset.getContentType().startsWith("text/css")) {
            Set<String> nested = new HashSet<>(visited);
            nested.add(path);
            String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
            String css = inlineCss(new String(bytes, StandardCharsets.UTF_8), parent, assets, nested);
            bytes = css.getBytes(StandardCharsets.UTF_8);
        }
        return "data:" + asset.getContentType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
