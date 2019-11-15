/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.turn.ttorrent.common;

public final class Utils {

  private static final char[] HEX_SYMBOLS = "0123456789ABCDEF".toCharArray();

  private Utils() {
  }

  /**
   * Convert a byte string to a string containing the hexadecimal representation of the original
   * data. http://stackoverflow.com/questions/332079
   *
   * @param bytes The byte array to convert.
   *
   * @return hex string
   */
  public static String bytesToHex(final byte[] bytes) {
    final char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      final int v = bytes[j] & 0xFF;
      hexChars[j * 2] = HEX_SYMBOLS[v >>> 4];
      hexChars[j * 2 + 1] = HEX_SYMBOLS[v & 0x0F];
    }
    return new String(hexChars);
  }
}
