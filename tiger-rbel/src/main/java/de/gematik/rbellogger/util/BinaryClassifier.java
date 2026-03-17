/*
 *
 * Copyright 2021-2025 gematik GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */
package de.gematik.rbellogger.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import lombok.SneakyThrows;

/**
 * Classifies byte content as binary or text. Reads as UTF-8 char-by-char: if decoding fails or a
 * non-printable control character (except TAB, CR, LF) is encountered, the content is binary. Fails
 * fast on the first problematic character.
 */
public class BinaryClassifier {

  private BinaryClassifier() {}

  private static final int CHARS_TO_CHECK = 512;

  @SneakyThrows
  public static boolean isBinary(byte[] data) {
    try (var stream = new ByteArrayInputStream(data)) {
      return isBinary(stream);
    }
  }

  @SneakyThrows
  public static boolean isBinary(InputStream data) {
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try (var reader = new InputStreamReader(data, decoder)) {
      for (int pos = 0; pos < CHARS_TO_CHECK; pos++) {
        int ch = reader.read();
        if (ch == -1) {
          break;
        }
        if (Character.isISOControl(ch) && ch != '\t' && ch != '\n' && ch != '\r') {
          return true;
        }
      }
    } catch (java.io.IOException e) {
      return true; // malformed UTF-8
    }
    return false;
  }
}
