/*
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. * You may obtain a copy of the License at
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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * A type-agnostic container for B-encoded values.
 *
 * @author mpetazzoni
 */
@Data
public class BeValue {

  private static final String ENCODE = "UTF-8";

  /**
   * The B-encoded value can be a byte array, a Number, a List or a Map. Lists and Maps contains
   * BEValues too.
   */
  private final Object value;

  public BeValue(final byte[] value) {
    this.value = value;
  }

  public BeValue(final String value) throws UnsupportedEncodingException {
    this.value = value.getBytes(ENCODE);
  }

  public BeValue(final String value, final String enc) throws UnsupportedEncodingException {
    this.value = value.getBytes(enc);
  }

  public BeValue(final int value) {
    this.value = value;
  }

  public BeValue(final long value) {
    this.value = value;
  }

  public BeValue(final Number value) {
    this.value = value;
  }

  public BeValue(final List<BeValue> value) {
    this.value = value;
  }

  public BeValue(final Map<String, BeValue> value) {
    this.value = value;
  }

  /**
   * Returns this BEValue as a String, interpreted as UTF-8.
   *
   * @return get string
   *
   * @throws InvalidBEncodingException If the value is not a byte[].
   */
  public String getString() throws InvalidBEncodingException {
    return this.getString(ENCODE);
  }

  /**
   * Returns this BEValue as a String, interpreted with the specified encoding.
   *
   * @param encoding The encoding to interpret the bytes as when converting them into a
   *                 {@link String}.
   *
   * @return get string with encoding
   *
   * @throws InvalidBEncodingException If the value is not a byte[].
   */
  public String getString(final String encoding) throws InvalidBEncodingException {
    try {
      return new String(this.getBytes(), encoding);
    } catch (final ClassCastException cce) {
      throw new InvalidBEncodingException(cce.toString());
    } catch (final UnsupportedEncodingException uee) {
      throw new InternalError(uee.toString());
    }
  }

  /**
   * Returns this BEValue as a byte[].
   *
   * @return get bytes
   *
   * @throws InvalidBEncodingException If the value is not a byte[].
   */
  public byte[] getBytes() throws InvalidBEncodingException {
    try {
      return (byte[]) this.value;
    } catch (final ClassCastException cce) {
      throw new InvalidBEncodingException(cce.toString());
    }
  }

  /**
   * Returns this BEValue as a Number.
   *
   * @return get in number
   *
   * @throws InvalidBEncodingException If the value is not a {@link Number}.
   */
  public Number getNumber() throws InvalidBEncodingException {
    try {
      return (Number) this.value;
    } catch (final ClassCastException cce) {
      throw new InvalidBEncodingException(cce.toString());
    }
  }

  /**
   * Returns this BEValue as short.
   *
   * @return get short
   *
   * @throws InvalidBEncodingException If the value is not a {@link Number}.
   */
  public short getShort() throws InvalidBEncodingException {
    return this.getNumber().shortValue();
  }

  /**
   * Returns this BEValue as int.
   *
   * @return get int
   *
   * @throws InvalidBEncodingException If the value is not a {@link Number}.
   */
  public int getInt() throws InvalidBEncodingException {
    return this.getNumber().intValue();
  }

  /**
   * Returns this BEValue as long.
   *
   * @return get long
   *
   * @throws InvalidBEncodingException If the value is not a {@link Number}.
   */
  public long getLong() throws InvalidBEncodingException {
    return this.getNumber().longValue();
  }

  /**
   * Returns this BEValue as a List of BEValues.
   *
   * @return get list
   *
   * @throws InvalidBEncodingException If the value is not an {@link ArrayList}.
   */
  public List<BeValue> getList() throws InvalidBEncodingException {
    if (this.value instanceof ArrayList) {
      return (ArrayList<BeValue>) this.value;
    } else {
      throw new InvalidBEncodingException("Excepted List<BEvalue> !");
    }
  }

  /**
   * Returns this BEValue as a Map of String keys and BEValue values.
   *
   * @return get map
   *
   * @throws InvalidBEncodingException If the value is not a {@link HashMap}.
   */
  public Map<String, BeValue> getMap() throws InvalidBEncodingException {
    if (this.value instanceof HashMap) {
      return (Map<String, BeValue>) this.value;
    } else {
      throw new InvalidBEncodingException("Expected Map<String, BEValue> !");
    }
  }
}
