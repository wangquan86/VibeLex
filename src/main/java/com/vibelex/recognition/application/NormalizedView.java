package com.vibelex.recognition.application;

import com.vibelex.candidatediscovery.domain.NormalizationProfile;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import java.util.ArrayList;
import java.util.List;

/** 带原文码点映射的归一化视图，用于把匹配位置无损映射回原始输入。 */
final class NormalizedView {
  final String text;
  final int[] starts;
  final int[] ends;

  private NormalizedView(String text, int[] starts, int[] ends) {
    this.text = text;
    this.starts = starts;
    this.ends = ends;
  }

  static NormalizedView of(
      String original, String language, NormalizationProfile profile, TermNormalizer normalizer) {
    StringBuilder out = new StringBuilder();
    List<Integer> s = new ArrayList<>(), e = new ArrayList<>();
    int cpOffset = 0;
    boolean pendingSpace = false;
    int spaceStart = 0, spaceEnd = 0;
    for (int i = 0; i < original.length(); ) {
      int cp = original.codePointAt(i), chars = Character.charCount(cp);
      String raw = new String(Character.toChars(cp));
      String piece;
      try {
        piece = normalizer.normalize(raw, language, profile);
      } catch (IllegalArgumentException ex) {
        piece = "";
      }
      boolean whitespace = Character.isWhitespace(cp) || cp == 0x00A0;
      if (profile == NormalizationProfile.BASE && whitespace) {
        if (out.length() > 0) {
          pendingSpace = true;
          spaceStart = cpOffset;
          spaceEnd = cpOffset + 1;
        }
        piece = "";
      }
      if (!piece.isEmpty()) {
        if (pendingSpace) {
          out.append(' ');
          s.add(spaceStart);
          e.add(spaceEnd);
          pendingSpace = false;
        }
        int origin = cpOffset;
        piece
            .codePoints()
            .forEach(
                x -> {
                  out.appendCodePoint(x);
                  s.add(origin);
                  e.add(origin + 1);
                });
      }
      i += chars;
      cpOffset++;
    }
    return new NormalizedView(
        out.toString(),
        s.stream().mapToInt(Integer::intValue).toArray(),
        e.stream().mapToInt(Integer::intValue).toArray());
  }

  Span span(int utf16Start, int utf16End) {
    int cpStart = text.codePointCount(0, utf16Start), cpEnd = text.codePointCount(0, utf16End);
    return new Span(starts[cpStart], ends[cpEnd - 1]);
  }

  record Span(int start, int end) {}
}
