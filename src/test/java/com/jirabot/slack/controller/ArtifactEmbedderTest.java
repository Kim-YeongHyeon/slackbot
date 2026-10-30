package com.jirabot.slack.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jirabot.slack.entity.ArtifactAssetEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class ArtifactEmbedderTest {

    private static ArtifactAssetEntity asset(String path, String type, String content) {
        return new ArtifactAssetEntity(1, path, type, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void embedsRelativeImagesStylesheetsScriptsAndNestedCssAssets() {
        String html = "<!doctype html><html><head><link rel='stylesheet' href='page_files/main.css'>"
                + "<script src='page_files/main.js'></script></head><body>"
                + "<img src='page_files/a.png'><div style='background:url(page_files/a.png)'></div></body></html>";
        String bundled = ArtifactEmbedder.inline(1, html, List.of(
                asset("page_files/main.css", "text/css",
                        ".logo{background:url(../page_files/a.png)}"),
                asset("page_files/main.js", "text/javascript", "window.loaded=true"),
                asset("page_files/a.png", "image/png", "png")));
        var doc = Jsoup.parse(bundled);
        assertThat(doc.selectFirst("link").attr("href")).startsWith("data:text/css;base64,");
        assertThat(doc.selectFirst("script[src]").attr("src")).startsWith("data:text/javascript;base64,");
        assertThat(doc.selectFirst("img").attr("src")).startsWith("data:image/png;base64,");
        assertThat(doc.selectFirst("div[style]").attr("style")).contains("data:image/png;base64,");
        assertThat(new String(java.util.Base64.getDecoder().decode(
                doc.selectFirst("link").attr("href").split(",", 2)[1]), StandardCharsets.UTF_8))
                .contains("data:image/png;base64,");
    }

    @Test
    void preservesExternalUrlsAndLinksButResolvesEncodedLocalPaths() {
        String html = "<img src='https://example.org/image.png'>"
                + "<img src='images/hello%20world.png?width=1'>"
                + "<a href='reports/next.html'>next</a>";
        var doc = Jsoup.parse(ArtifactEmbedder.inline(3, html,
                List.of(asset("images/hello world.png", "image/png", "bytes"))));
        assertThat(doc.select("img").first().attr("src")).isEqualTo("https://example.org/image.png");
        assertThat(doc.select("img").last().attr("src")).startsWith("data:image/png;base64,");
        assertThat(doc.selectFirst("a").attr("href")).isEqualTo("reports/next.html");
        assertThat(doc.selectFirst("base").attr("href")).isEqualTo("/artifacts/view/3/");
    }

    @Test
    void doesNotLoopOnCircularCssImports() {
        String html = "<link href='a.css' rel='stylesheet'>";
        String bundled = ArtifactEmbedder.inline(1, html, List.of(
                asset("a.css", "text/css", "@import 'b.css';"),
                asset("b.css", "text/css", "@import 'a.css';")));
        assertThat(bundled).contains("data:text/css;base64,");
    }
}
