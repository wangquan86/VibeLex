package com.vibelex.candidatediscovery.domain;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 文档定义的确定性词形归一化器。
 *
 * <p>写入与识别共用同一实现，避免数据库 normalized_* 字段和运行时召回视图产生漂移。
 */
@Component
public class TermNormalizer {
  private static final Pattern CONTROL =
      Pattern.compile("[\\x00-\\x1F\\x7F\\u200B\\u200C\\u200D\\uFEFF]");
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

  public String normalize(String input, String languageCode) {
    return normalize(input, languageCode, NormalizationProfile.BASE);
  }

  public String normalize(String input, String languageCode, NormalizationProfile profile) {
    if (input == null) throw new IllegalArgumentException("词形不能为空");
    String value = Normalizer.normalize(input, Normalizer.Form.NFKC);
    value = CONTROL.matcher(value).replaceAll("");
    value = WHITESPACE.matcher(value).replaceAll(" ").trim();
    if ("zh-CN".equals(languageCode) || "mixed".equals(languageCode))
      value = ZhConverterUtil.toSimple(value);
    if (isLatinLowercaseLanguage(languageCode)) value = asciiLower(value);
    if ("en".equals(languageCode)) value = value.replace('\u2018', '\'').replace('\u2019', '\'');
    if (profile == NormalizationProfile.SPACING) value = value.replace(" ", "");
    if (profile == NormalizationProfile.PINYIN) value = normalizePinyin(value);
    if (value.isEmpty()) throw new IllegalArgumentException("归一化结果不能为空");
    return value;
  }

  public NormalizationProfile profileForVariant(String variantType) {
    if ("pinyin".equals(variantType)) return NormalizationProfile.PINYIN;
    if ("spacing_variant".equals(variantType)) return NormalizationProfile.SPACING;
    return NormalizationProfile.BASE;
  }

  private boolean isLatinLowercaseLanguage(String languageCode) {
    return "zh-CN".equals(languageCode)
        || "zh-TW".equals(languageCode)
        || "zh-HK".equals(languageCode)
        || "en".equals(languageCode)
        || "mixed".equals(languageCode);
  }

  private String asciiLower(String value) {
    StringBuilder out = new StringBuilder(value.length());
    value.codePoints().forEach(cp -> out.appendCodePoint(cp >= 'A' && cp <= 'Z' ? cp + 32 : cp));
    return out.toString();
  }

  private String normalizePinyin(String value) {
    String decomposed =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace('ü', 'v').replace('Ü', 'v');
    StringBuilder out = new StringBuilder();
    decomposed
        .codePoints()
        .filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK)
        .filter(cp -> cp != ' ' && cp != '-' && cp != '\'' && cp != '\u2019')
        .forEach(cp -> out.appendCodePoint(cp));
    return asciiLower(Normalizer.normalize(out, Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
  }
}
