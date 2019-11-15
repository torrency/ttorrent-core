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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-encoding encoder.
 *
 * <p>
 * This class provides utility methods to encode objects and {@link BeValue}s to B-encoding into a
 * provided output stream.
 * </p>
 *
 * <p>
 * Inspired by Snark's implementation.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://en.wikipedia.org/wiki/Bencode">B-encoding specification</a>
 */
public class BeEncoder {

  @SuppressWarnings("unchecked")
  public static void bencode(Object o, final OutputStream out) throws IOException,
                                                                      IllegalArgumentException {
    if (o instanceof BeValue) {
      o = ((BeValue) o).getValue();
    }

    if (o instanceof String) {
      bencode((String) o, out);
    } else if (o instanceof byte[]) {
      bencode((byte[]) o, out);
    } else if (o instanceof Number) {
      bencode((Number) o, out);
    } else if (o instanceof List) {
      bencode((List<BeValue>) o, out);
    } else if (o instanceof Map) {
      bencode((Map<String, BeValue>) o, out);
    } else {
      throw new IllegalArgumentException(String.format("Unable to bencode: %s", o.getClass()));
    }
  }

  public static void bencode(final String s, final OutputStream out) throws IOException {
    final byte[] bs = s.getBytes("UTF-8");
    bencode(bs, out);
  }

  public static void bencode(final Number n, final OutputStream out) throws IOException {
    out.write('i');
    final String s = n.toString();
    out.write(s.getBytes("UTF-8"));
    out.write('e');
  }

  public static void bencode(final List<BeValue> l, final OutputStream out) throws IOException {
    out.write('l');
    for (BeValue value : l) {
      bencode(value, out);
    }
    out.write('e');
  }

  public static void bencode(final byte[] bs, final OutputStream out) throws IOException {
    final String l = Integer.toString(bs.length);
    out.write(l.getBytes("UTF-8"));
    out.write(':');
    out.write(bs);
  }

  public static void bencode(final Map<String, BeValue> m, final OutputStream out)
          throws IOException {
    out.write('d');

    // Keys must be sorted.
    final Set<String> s = m.keySet();
    final List<String> l = new ArrayList<>(s);
    Collections.sort(l);

    for (String key : l) {
      final Object value = m.get(key);
      bencode(key, out);
      bencode(value, out);
    }

    out.write('e');
  }

  public static ByteBuffer bencode(final Map<String, BeValue> m) throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BeEncoder.bencode(m, baos);
    baos.close();
    return ByteBuffer.wrap(baos.toByteArray());
  }
}
