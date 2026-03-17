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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BinaryClassifierTest {

  @Test
  void plainAsciiText_shouldNotBeBinary() {
    assertThat(BinaryClassifier.isBinary("hello world".getBytes())).isFalse();
    assertThat(BinaryClassifier.isBinary("hello world\n".getBytes())).isFalse();
  }

  @Test
  void textWithTabCrLf_shouldNotBeBinary() {
    assertThat(BinaryClassifier.isBinary("line1\r\nline2\ttab".getBytes())).isFalse();
  }

  @Test
  void validUtf8WithUmlauts_shouldNotBeBinary() {
    assertThat(BinaryClassifier.isBinary("Ärzte und Übung".getBytes())).isFalse();
  }

  @Test
  void validUtf8WithEmoji_shouldNotBeBinary() {
    assertThat(BinaryClassifier.isBinary("hello 🌍".getBytes())).isFalse();
  }

  @Test
  void controlCharacters_shouldBeBinary() {
    assertThat(BinaryClassifier.isBinary(new byte[] {0x00, 0x01})).isTrue();
    assertThat(BinaryClassifier.isBinary(new byte[] {0x11, 0x7F})).isTrue();
  }

  @Test
  void invalidUtf8Sequences_shouldBeBinary() {
    // 0x82 alone is not a valid UTF-8 start byte
    assertThat(BinaryClassifier.isBinary(new byte[] {(byte) 0x82, (byte) 0x86})).isTrue();
    // 0xFF is never valid in UTF-8
    assertThat(BinaryClassifier.isBinary(new byte[] {(byte) 0xFF})).isTrue();
    // Truncated 2-byte sequence
    assertThat(BinaryClassifier.isBinary(new byte[] {(byte) 0xC0})).isTrue();
  }

  @Test
  void emptyArray_shouldNotBeBinary() {
    assertThat(BinaryClassifier.isBinary(new byte[0])).isFalse();
  }

  @Test
  void hpackPayload_shouldBeBinary() {
    // Typical HPACK-compressed HTTP/2 header payload
    assertThat(BinaryClassifier.isBinary(new byte[] {(byte) 0x82, (byte) 0x86, (byte) 0x84}))
        .isTrue();
  }

  @Test
  void jsonText_shouldNotBeBinary() {
    assertThat(BinaryClassifier.isBinary("{\"key\":\"value\"}".getBytes())).isFalse();
  }
}
