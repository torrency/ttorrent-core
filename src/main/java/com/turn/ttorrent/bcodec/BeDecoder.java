/*
 * Copyright (C) 2011-2012 Turn, Inc.
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

package com.turn.ttorrent.bcodec;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.input.AutoCloseInputStream;

/**
 * B-encoding decoder.
 *
 * <p>
 * A b-encoded byte stream can represent byte arrays, numbers, lists and maps (dictionaries). This
 * class implements a decoder of such streams into {@link BeValue}s.
 * </p>
 *
 * <p>
 * Inspired by Snark's implementation.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://en.wikipedia.org/wiki/Bencode">B-encoding specification</a>
 */
public class BeDecoder {

  // The InputStream to BDecode.
  private final InputStream in;

  /**
   * The last indicator read.<BR>
   * Zero if unknown.<BR>
   * '0'..'9' indicates a byte[].<BR>
   * 'i' indicates an Number.<BR>
   * 'l' indicates a List.<BR>
   * 'd' indicates a Map.<BR>
   * 'e' indicates end of Number, List or Map (only used internally).<BR>
   * -1 indicates end of stream.<BR>
   * Call getNextIndicator to get the current value (will never return zero).
   */
  private int indicator = 0;

  /**
   * Initializes a new BDecoder.
   *
   * <p>
   * Nothing is read from the given <code>InputStream</code> yet.
   * </p>
   *
   * @param in The input stream to read from.
   */
  public BeDecoder(final InputStream in) {
    this.in = in;
  }

  /**
   * Decode a B-encoded stream.<BR>
   * Automatically instantiates a new BDecoder for the provided input stream and decodes its root
   * member.
   *
   * @param in The input stream to read from.
   *
   * @return create BeValue from InputStream
   *
   * @throws java.io.IOException Unable to decode stream
   */
  public static BeValue bdecode(final InputStream in) throws IOException {
    return new BeDecoder(in).bdecode();
  }

  /**
   * Decode a B-encoded byte buffer.
   *
   * <p>
   * Automatically instantiates a new BDecoder for the provided buffer and decodes its root member.
   * </p>
   *
   * @param data The {@link ByteBuffer} to read from.
   *
   * @return create BeValue from Bytes
   *
   * @throws java.io.IOException unable to decode from bytes
   */
  public static BeValue bdecode(final ByteBuffer data) throws IOException {
    return BeDecoder.bdecode(new AutoCloseInputStream(
            new ByteArrayInputStream(data.array())));
  }

  /**
   * Gets the next indicator and returns either null when the stream has ended or b-decodes the rest
   * of the stream and returns the appropriate BEValue encoded object.
   *
   * @return Get next BeValue
   *
   * @throws java.io.IOException unable to decode
   */
  public BeValue bdecode() throws IOException {
    if (this.getNextIndicator() == -1) {
      return null;
    }

    if (this.indicator >= '0' && this.indicator <= '9') {
      return this.bdecodeBytes();
    }

    switch (this.indicator) {
      case 'i':
        return this.bdecodeNumber();
      case 'l':
        return this.bdecodeList();
      case 'd':
        return this.bdecodeMap();
      default:
    }
    throw new InvalidBEncodingException("Unknown indicator '" + this.indicator + "'");
  }

  /**
   * Returns what the next b-encoded object will be on the stream or -1 when the end of stream has
   * been reached.<BR>
   * Can return something unexpected (not '0' .. '9', 'i', 'l' or 'd') when the stream isn't
   * b-encoded.<BR>
   * This might or might not read one extra byte from the stream.
   */
  private int getNextIndicator() throws IOException {
    if (this.indicator == 0) {
      this.indicator = this.in.read();
    }
    return this.indicator;
  }

  /**
   * Returns the next b-encoded value on the stream and makes sure it is a byte array.
   *
   * @return decode into bytes
   *
   * @throws java.io.IOException If it is not a b-encoded byte array.
   */
  public BeValue bdecodeBytes() throws IOException {
    int c = this.getNextIndicator();
    int num = c - '0';
    if (num < 0 || num > 9) {
      throw new InvalidBEncodingException(String.format("Number expected, not '%c'", (char) c));
    }
    this.indicator = 0;

    c = this.read();
    int i = c - '0';
    while (i >= 0 && i <= 9) {
      // This can overflow!
      num = num * 10 + i;
      c = this.read();
      i = c - '0';
    }

    if (c != ':') {
      throw new InvalidBEncodingException(String.format("Colon expected, not '%c'", (char) c));
    }

    return new BeValue(this.read(num));
  }

  /**
   * Returns the next b-encoded value on the stream and makes sure it is a number.
   *
   * @return decode into number
   *
   * @throws java.io.IOException If it is not a number
   */
  public BeValue bdecodeNumber() throws IOException {
    int c = this.getNextIndicator();
    if (c != 'i') {
      throw new InvalidBEncodingException(String.format("Expect 'i', not '%c'", (char) c));
    }
    this.indicator = 0;

    c = this.read();
    if (c == '0') {
      c = this.read();
      if (c != 'e') {
        throw new InvalidBEncodingException(String.format("'e' expected after zero, not '%c'",
                                                          (char) c));
      }
      return new BeValue(BigInteger.ZERO);
    }

    // We don't support more the 255 char big integers
    final char[] chars = new char[256];
    int off = 0;

    if (c == '-') {
      c = this.read();
      if (c == '0') {
        throw new InvalidBEncodingException("Negative zero not allowed");
      }
      chars[off] = '-';
      off++;
    }

    if (c < '1' || c > '9') {
      throw new InvalidBEncodingException(String.format("Invalid integer start '%c'", (char) c));
    }
    chars[off] = (char) c;
    off++;

    c = this.read();
    int i = c - '0';
    while (i >= 0 && i <= 9) {
      chars[off] = (char) c;
      off++;
      c = this.read();
      i = c - '0';
    }

    if (c != 'e') {
      throw new InvalidBEncodingException("Integer should end with 'e'");
    }

    return new BeValue(new BigInteger(new String(chars, 0, off)));
  }

  /**
   * Returns the next b-encoded value on the stream and makes sure it is a list.
   *
   * @return decode into list
   *
   * @throws java.io.IOException If it is not a list.
   */
  public BeValue bdecodeList() throws IOException {
    int c = this.getNextIndicator();
    if (c != 'l') {
      throw new InvalidBEncodingException(String.format("Expect 'l', not '%c'", (char) c));
    }
    this.indicator = 0;

    final List<BeValue> result = new ArrayList<>();
    c = this.getNextIndicator();
    while (c != 'e') {
      result.add(this.bdecode());
      c = this.getNextIndicator();
    }
    this.indicator = 0;

    return new BeValue(result);
  }

  /**
   * Returns the next b-encoded value on the stream and makes sure it is a map (dictionary).
   *
   * @return decode into map
   *
   * @throws java.io.IOException If it is not a map.
   */
  public BeValue bdecodeMap() throws IOException {
    int c = this.getNextIndicator();
    if (c != 'd') {
      throw new InvalidBEncodingException(String.format("Expect 'd', not '%c'", (char) c));
    }
    this.indicator = 0;

    final Map<String, BeValue> result = new HashMap<>();
    c = this.getNextIndicator();
    while (c != 'e') {
      // Dictionary keys are always strings.
      result.put(this.bdecode().getString(), this.bdecode());

      c = this.getNextIndicator();
    }
    this.indicator = 0;

    return new BeValue(result);
  }

  /**
   * Returns the next byte read from the InputStream (as int).
   *
   * @throws EOFException If InputStream.read() returned -1.
   */
  private int read() throws IOException {
    final int c = this.in.read();
    if (c == -1) {
      throw new EOFException();
    }
    return c;
  }

  /**
   * Returns a byte[] containing length valid bytes starting at offset zero.
   *
   * @throws EOFException If InputStream.read() returned -1 before all requested bytes could be
   *                      read. Note that the byte[] returned might be bigger then requested but
   *                      will only contain length valid bytes. The returned byte[] will be reused
   *                      when this method is called again.
   */
  private byte[] read(final int length) throws IOException {
    final byte[] result = new byte[length];

    int read = 0;
    while (read < length) {
      final int i = this.in.read(result, read, length - read);
      if (i == -1) {
        throw new EOFException();
      }
      read += i;
    }

    return result;
  }
}
