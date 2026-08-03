package com.vibelex.crawling;

import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import com.vibelex.crawling.CrawlConnector.EnumerationResult;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RegengBaikeConnector implements CrawlConnector {
  public static final String SOURCE_CODE = "regengbaike";
  public static final String PARSER_VERSION = "regengbaike-html-v1";
  private static final Pattern ARCHIVE_PATH = Pattern.compile("^/(\\d+)\\.html$");
  private final CrawlProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public RegengBaikeConnector(CrawlProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  @Override
  public String sourceCode() {
    return SOURCE_CODE;
  }

  @Override
  public String sourceName() {
    return "热梗百科";
  }

  @Override
  public int maximumAttempts() {
    return properties.getRegengbaike().getMaximumAttempts();
  }

  @Override
  public EnumerationResult enumerate(JsonNode checkpoint) {
    return parseSitemap(get(properties.getRegengbaike().getSitemapUrl()), checkpoint);
  }

  EnumerationResult parseSitemap(String xml, JsonNode checkpoint) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
      var locations = document.getElementsByTagName("loc");
      URI base = URI.create(properties.getRegengbaike().getBaseUrl());
      List<CrawlPointer> all = new ArrayList<>();
      for (int i = 0; i < locations.getLength(); i++) {
        URI uri = URI.create(locations.item(i).getTextContent().trim());
        Matcher matcher = ARCHIVE_PATH.matcher(uri.getPath());
        if (!base.getHost().equalsIgnoreCase(uri.getHost()) || !matcher.matches()) continue;
        all.add(new CrawlPointer(matcher.group(1), uri.toString(), Instant.EPOCH));
      }
      all.sort(Comparator.comparingLong(item -> Long.parseLong(item.sourceRecordKey())));
      if (all.size() > properties.getRegengbaike().getMaximumDiscoveredItems())
        throw new IllegalStateException("热梗百科 sitemap 词条数超过安全上限: " + all.size());
      long cursor =
          checkpoint == null || checkpoint.isNull()
              ? 0
              : checkpoint.path("maximumArchiveId").asLong();
      long maximum =
          all.isEmpty() ? cursor : Long.parseLong(all.get(all.size() - 1).sourceRecordKey());
      JsonNode next =
          all.isEmpty() ? checkpoint : mapper.createObjectNode().put("maximumArchiveId", maximum);
      return new EnumerationResult(
          all.stream().filter(item -> Long.parseLong(item.sourceRecordKey()) > cursor).toList(),
          next);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("无法解析热梗百科 sitemap", e);
    }
  }

  @Override
  public FetchedCrawlEntry fetch(CrawlPointer pointer) {
    return parseEntry(get(pointer.sourceUrl()), pointer);
  }

  FetchedCrawlEntry parseEntry(String html, CrawlPointer pointer) {
    Document document = Jsoup.parse(html, pointer.sourceUrl());
    String term = text(document.selectFirst("h1"));
    Element bodyElement = document.selectFirst(".article-text");
    String body = bodyElement == null ? "" : bodyElement.text().replaceAll("\\s+", " ").trim();
    if (term.isBlank() || body.isBlank())
      throw new IllegalStateException("热梗百科页面缺少词名或正文: " + pointer.sourceUrl());
    String summary = document.select("meta[name=description]").attr("content").trim();
    String canonical = document.select("link[rel=canonical]").attr("href").trim();
    if (canonical.isBlank()) canonical = pointer.sourceUrl();
    String category = "";
    for (Element item : document.select(".breadcrumb a, .breadcrumbs a")) {
      String value = item.text().trim();
      if (!value.isBlank() && !value.equals("首页")) category = value;
    }
    Instant publishedAt = null;
    String datetime = document.select("time[datetime]").attr("datetime").trim();
    if (!datetime.isBlank()) {
      try {
        publishedAt = OffsetDateTime.parse(datetime).toInstant();
      } catch (Exception ignored) {
        try {
          publishedAt = Instant.parse(datetime);
        } catch (Exception ignoredAgain) {
          try {
            publishedAt =
                LocalDateTime.parse(datetime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.of("Asia/Shanghai"))
                    .toInstant();
          } catch (Exception ignoredLocalTime) {
            // Source publication time is optional; malformed values are kept out of the payload.
          }
        }
      }
    }
    return new FetchedCrawlEntry(
        term,
        summary,
        body,
        List.of(),
        category,
        List.of(),
        canonical,
        pointer.sourceRecordKey(),
        publishedAt,
        PARSER_VERSION);
  }

  private String text(Element element) {
    return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
  }

  private String get(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(properties.getRegengbaike().getRequestTimeoutSeconds()))
              .header("Accept", "text/html, application/xml, text/xml, */*")
              .header("User-Agent", properties.getRegengbaike().getUserAgent())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) throw new IgnoredPageException("热梗百科页面不存在: " + url);
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException("热梗百科响应 " + response.statusCode() + ": " + url);
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("访问热梗百科被中断", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("无法访问热梗百科", e);
    }
  }
}
