/**
 * Copyright (C) 2016 Philipp Henkel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UtilsTest {

  @Test
  public void testBytesToHexWithNull() {
    Assertions.assertThrows(NullPointerException.class, () -> {
                      Utils.bytesToHex(null);
                    });
  }

  @Test
  public void testBytesToHexWithEmptyByteArray() {
    Assertions.assertEquals("", Utils.bytesToHex(new byte[0]));
  }

  @Test
  public void testBytesToHexWithSingleByte() {
    Assertions.assertEquals("BC", Utils.bytesToHex(new byte[]{
      (byte) 0xBC
    }));
  }

  @Test
  public void testBytesToHexWithZeroByte() {
    Assertions.assertEquals("00", Utils.bytesToHex(new byte[1]));
  }

  @Test
  public void testBytesToHexWithLeadingZero() {
    Assertions.assertEquals("0053FF", Utils.bytesToHex(new byte[]{
      (byte) 0x00, (byte) 0x53, (byte) 0xFF
    }));
  }

  @Test
  public void testBytesToHexTrailingZero() {
    Assertions.assertEquals("AA004500", Utils.bytesToHex(new byte[]{
      (byte) 0xAA, (byte) 0x00, (byte) 0x45, (byte) 0x00
    }));
  }

  @Test
  public void testBytesToHexAllSymbols() {
    Assertions.assertEquals("0123456789ABCDEF", Utils.bytesToHex(new byte[]{
      (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
      (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF
    }));
  }

}
