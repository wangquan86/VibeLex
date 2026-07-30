package com.vibelex.crawling;

import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.EnumerationResult;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class PopCidianConnector implements CrawlConnector {
  static final String SOURCE_CODE = "popcidian";
  static final String PARSER_VERSION = "popcidian-api-v1";
  private static final Map<String, String> CATEGORY_MAPPING =
      Map.ofEntries(
          Map.entry("互联网黑话", "slang"),
          Map.entry("网络用语", "slang"),
          Map.entry("网络流行语", "slang"),
          Map.entry("谐音梗", "homophone"),
          Map.entry("谐音表达", "homophone"),
          Map.entry("缩写", "abbreviation"),
          Map.entry("字母缩写", "abbreviation"),
          Map.entry("句式", "template_phrase"),
          Map.entry("模板", "template_phrase"),
          Map.entry("固定句式", "template_phrase"),
          Map.entry("模板句式", "template_phrase"));

  private final CrawlProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public PopCidianConnector(CrawlProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofSeconds(properties.getPopcidian().getRequestTimeoutSeconds()))
            .build();
  }

  @Override
  public String sourceCode() {
    return SOURCE_CODE;
  }

  @Override
  public String sourceName() {
    return "波普词典";
  }

  @Override
  public int maximumAttempts() {
    return properties.getPopcidian().getMaximumAttempts();
  }

  @Override
  public EnumerationResult enumerate(JsonNode checkpoint) {
    String xml = get(properties.getPopcidian().getSitemapUrl());
    return parseSitemap(xml, checkpoint);
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
      var urls = document.getElementsByTagName("url");
      List<CrawlPointer> all = new ArrayList<>();
      URI base = URI.create(properties.getPopcidian().getBaseUrl());
      for (int i = 0; i < urls.getLength(); i++) {
        Element element = (Element) urls.item(i);
        String location = text(element, "loc");
        if (location == null || location.isBlank()) continue;
        URI uri = URI.create(location.trim());
        if (!base.getHost().equalsIgnoreCase(uri.getHost()) || !uri.getPath().startsWith("/entry/"))
          continue;
        String key =
            URLDecoder.decode(
                uri.getRawPath().substring("/entry/".length()), StandardCharsets.UTF_8);
        String modified = text(element, "lastmod");
        Instant modifiedAt =
            modified == null || modified.isBlank() ? Instant.EPOCH : Instant.parse(modified.trim());
        all.add(new CrawlPointer(key, uri.toString(), modifiedAt));
      }
      all.sort(pointerComparator());
      if (all.size() > properties.getPopcidian().getMaximumDiscoveredItems()) {
        throw new IllegalStateException("波普 sitemap 词条数超过安全上限: " + all.size());
      }
      CrawlPointer maximum = all.isEmpty() ? null : all.get(all.size() - 1);
      JsonNode nextCheckpoint = maximum == null ? checkpoint : checkpoint(maximum);
      if (checkpoint == null || checkpoint.isNull()) {
        return new EnumerationResult(List.copyOf(all), nextCheckpoint);
      }
      Instant lastmod = Instant.parse(checkpoint.path("lastmod").asText(Instant.EPOCH.toString()));
      String key = checkpoint.path("sourceRecordKey").asText("");
      CrawlPointer cursor = new CrawlPointer(key, "", lastmod);
      return new EnumerationResult(
          all.stream().filter(pointer -> pointerComparator().compare(pointer, cursor) > 0).toList(),
          nextCheckpoint);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("无法解析波普 sitemap", e);
    }
  }

  @Override
  public CrawledEntry fetch(CrawlPointer pointer) {
    String endpoint =
        properties.getPopcidian().getBaseUrl()
            + "/api/v1/entries?name="
            + URLEncoder.encode(pointer.sourceRecordKey(), StandardCharsets.UTF_8);
    try {
      return parseEntry(get(endpoint), pointer);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("无法解析波普词条: " + pointer.sourceUrl(), e);
    }
  }

  CrawledEntry parseEntry(String json, CrawlPointer pointer) {
    try {
      JsonNode root = mapper.readTree(json);
      JsonNode result = root.path("result");
      if (!result.isArray() || result.isEmpty()) {
        throw new IgnoredPageException("波普词条页面不存在: " + pointer.sourceUrl());
      }
      JsonNode selected = result.get(0);
      for (JsonNode item : result) {
        if (pointer.sourceRecordKey().equals(item.path("term").asText())) {
          selected = item;
          break;
        }
      }
      String term = selected.path("term").asText().trim();
      String definition = selected.path("chineseExplanation").asText().trim();
      if (term.isBlank() || definition.isBlank()) {
        throw new IllegalStateException("波普词条缺少词名或中文释义: " + pointer.sourceUrl());
      }
      List<String> examples = strings(selected.path("examples"));
      String sourceCategory = categoryName(selected.path("category"));
      List<String> sourceTags = tagNames(selected.path("tags"));
      return new CrawledEntry(
          term,
          definition,
          examples,
          CATEGORY_MAPPING.getOrDefault(sourceCategory, "other"),
          sourceCategory,
          sourceTags,
          pointer.sourceUrl(),
          pointer.sourceRecordKey(),
          PARSER_VERSION);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("无法解析波普词条: " + pointer.sourceUrl(), e);
    }
  }

  private String get(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(properties.getPopcidian().getRequestTimeoutSeconds()))
              .header("Accept", "application/json, application/xml, text/xml, */*")
              .header("User-Agent", properties.getPopcidian().getUserAgent())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("波普服务响应 " + response.statusCode() + ": " + url);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("访问波普服务被中断", e);
    } catch (Exception e) {
      if (e instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("无法访问波普服务", e);
    }
  }

  private Comparator<CrawlPointer> pointerComparator() {
    return Comparator.comparing(CrawlPointer::sourceModifiedAt)
        .thenComparing(CrawlPointer::sourceRecordKey);
  }

  private ObjectNode checkpoint(CrawlPointer pointer) {
    return mapper
        .createObjectNode()
        .put("lastmod", pointer.sourceModifiedAt().toString())
        .put("sourceRecordKey", pointer.sourceRecordKey());
  }

  private String text(Element parent, String tag) {
    var values = parent.getElementsByTagName(tag);
    return values.getLength() == 0 ? null : values.item(0).getTextContent();
  }

  private List<String> strings(JsonNode values) {
    if (!values.isArray()) return List.of();
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      String text = value.asText("").trim();
      if (!text.isBlank()) result.add(text);
    }
    return List.copyOf(result);
  }

  private String categoryName(JsonNode category) {
    if (category.isTextual()) return category.asText().trim();
    return category.path("name").asText("").trim();
  }

  private List<String> tagNames(JsonNode tags) {
    if (!tags.isArray()) return List.of();
    List<String> result = new ArrayList<>();
    for (JsonNode tag : tags) {
      String name = tag.isTextual() ? tag.asText().trim() : tag.path("name").asText("").trim();
      if (!name.isBlank()) result.add(name);
    }
    return List.copyOf(result);
  }
}
