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

package com.turn.ttorrent.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Callable;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.TorrentByteStorage;
import com.turn.ttorrent.common.Torrent;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * A torrent piece.
 *
 * <p>
 * This class represents a torrent piece. Torrents are made of pieces, which are in turn made of
 * blocks that are exchanged using the peer protocol. The piece length is defined at the torrent
 * level, but the last piece that makes the torrent might be smaller.
 * </p>
 *
 * <p>
 * If the torrent has multiple files, pieces can spread across file boundaries. The
 * TorrentByteStorage abstracts this problem to give Piece objects the impression of a contiguous,
 * linear byte storage.
 * </p>
 *
 * @author mpetazzoni
 */
@EqualsAndHashCode
@Slf4j
public class Piece implements Comparable<Piece> {

  private final TorrentByteStorage bucket;

  /**
   * The index of this piece in the torrent.
   */
  @Getter
  private final int index;

  private final long offset;

  private final long length;

  private final byte[] hash;

  private final boolean seeder;

  /**
   * Tells whether this piece's data is valid or not.
   */
  @Getter
  private volatile boolean valid;

  private int seen;

  private ByteBuffer data;

  /**
   * Initialize a new piece in the byte bucket.
   *
   * @param bucket The underlying byte storage bucket.
   * @param index  This piece index in the torrent.
   * @param offset This piece offset, in bytes, in the storage.
   * @param length This piece length, in bytes.
   * @param hash   This piece 20-byte SHA1 hash sum.
   * @param seeder Whether we're seeding this torrent or not (disables piece validation).
   */
  public Piece(final TorrentByteStorage bucket,
               final int index,
               final long offset,
               final long length,
               final byte[] hash,
               final boolean seeder) {
    this.bucket = bucket;
    this.index = index;
    this.offset = offset;
    this.length = length;
    this.hash = hash;
    this.seeder = seeder;

    // Piece is considered invalid until first check.
    this.valid = false;

    // Piece start unseen
    this.seen = 0;

    this.data = null;
  }

  /**
   * Returns the size, in bytes, of this piece.
   *
   * <p>
   * All pieces, except the last one, are expected to have the same size.
   * </p>
   *
   * @return size of this piece
   */
  public long size() {
    return this.length;
  }

  /**
   * Tells whether this piece is available in the current connected peer swarm.
   *
   * @return if this piece has more to seen
   */
  public boolean available() {
    return this.seen > 0;
  }

  /**
   * Mark this piece as being seen at the given peer.
   *
   * @param peer The sharing peer this piece has been seen available at.
   */
  public void seenAt(final SharingPeer peer) {
    this.seen++;
  }

  /**
   * Mark this piece as no longer being available at the given peer.
   *
   * @param peer The sharing peer from which the piece is no longer available.
   */
  public void noLongerAt(final SharingPeer peer) {
    this.seen--;
  }

  /**
   * Validates this piece.
   *
   * @return Returns true if this piece, as stored in the underlying byte storage, is valid, i.e.
   *         its SHA1 sum matches the one from the torrent meta-info.
   *
   * @throws IOException Unable to read buffer
   */
  public synchronized boolean validate() throws IOException {
    if (this.seeder) {
      LOG.trace("Skipping validation of {} (seeder mode).", this);
      this.valid = true;
      return true;
    }

    LOG.trace("Validating {}...", this);
    this.valid = false;

    final ByteBuffer buffer = this.doRead(0, this.length);
    final byte[] data = new byte[(int) this.length];
    buffer.get(data);
    try {
      this.valid = Arrays.equals(Torrent.hash(data), this.hash);
    } catch (final NoSuchAlgorithmException e) {
      this.valid = false;
    }

    return this.isValid();
  }

  /**
   * Internal piece data read function.
   *
   * <p>
   * This function will read the piece data without checking if the piece has been validated. It is
   * simply meant at factoring-in the common read code from the validate and read functions.
   * </p>
   *
   * @param offset Offset inside this piece where to start reading.
   * @param length Number of bytes to read from the piece.
   *
   * @return A byte buffer containing the piece data.
   *
   * @throws IllegalArgumentException If <em>offset + length</em> goes over the piece boundary.
   * @throws IOException              If the read can't be completed (I/O error, or EOF reached,
   *                                  which can happen if the piece is not complete).
   */
  private ByteBuffer doRead(final long offset, final long length) throws IOException {
    if (offset + length > this.length) {
      throw new IllegalArgumentException(String.format("Piece#%d overrun (%d + %d > %d) !",
                                                       this.index,
                                                       offset,
                                                       length,
                                                       this.length));
    }

    // TODO: remove cast to int when large ByteBuffer support is implemented in Java.
    final ByteBuffer buffer = ByteBuffer.allocate((int) length);
    final int bytes = this.bucket.read(buffer, this.offset + offset);
    buffer.rewind();
    buffer.limit(bytes >= 0 ? bytes : 0);
    return buffer;
  }

  /**
   * Read a piece block from the underlying byte storage.
   *
   * <p>
   * This is the public method for reading this piece's data, and it will only succeed if the piece
   * is complete and valid on disk, thus ensuring any data that comes out of this function is valid
   * piece data we can send to other peers.
   * </p>
   *
   * @param offset Offset inside this piece where to start reading.
   * @param length Number of bytes to read from the piece.
   *
   * @return A byte buffer containing the piece data.
   *
   * @throws IllegalArgumentException If <em>offset + length</em> goes over the piece boundary.
   * @throws IllegalStateException    If the piece is not valid when attempting to read it.
   * @throws IOException              If the read can't be completed (I/O error, or EOF reached,
   *                                  which can happen if the piece is not complete).
   */
  public ByteBuffer read(final long offset, final int length) throws IllegalArgumentException,
                                                                     IllegalStateException,
                                                                     IOException {
    if (!this.valid) {
      throw new IllegalStateException("Attempting to read an known-to-be invalid piece!");
    }

    return this.doRead(offset, length);
  }

  /**
   * Record the given block at the given offset in this piece.
   *
   * <p>
   * <b>Note:</b> this has synchronized access to the underlying byte storage.
   * </p>
   *
   * @param block  The ByteBuffer containing the block data.
   * @param offset The block offset in this piece.
   *
   * @throws IOException Unable to write to piece
   */
  public synchronized void record(final ByteBuffer block, final int offset) throws IOException {
    if (this.data == null || offset == 0) {
      // TODO: remove cast to int when large ByteBuffer support is
      // implemented in Java.
      this.data = ByteBuffer.allocate((int) this.length);
    }

    final int pos = block.position();
    this.data.position(offset);
    this.data.put(block);
    block.position(pos);

    if (block.remaining() + offset == this.length) {
      this.data.rewind();
      LOG.trace("Recording {}...", this);
      this.bucket.write(this.data, this.offset);
      this.data = null;
    }
  }

  /**
   * Return a human-readable representation of this piece.
   *
   * @return human readable representation
   */
  @Override
  public String toString() {
    return String.format("piece#%4d%s", this.index, this.isValid() ? "+" : "-");
  }

  /**
   * Piece comparison function for ordering pieces based on their availability.
   *
   * @param other The piece to compare with, should not be <em>null</em>.
   *
   * @return regular comparison between piece
   */
  @Override
  public int compareTo(final Piece other) {
    return this.seen != other.seen
           ? this.seen < other.seen ? -1 : 1
           : this.index == other.index ? 0 : (this.index < other.index ? -1 : 1);
  }

  /**
   * A {@link Callable} to call the piece validation function.
   *
   * <p>
   * This {@link Callable} implementation allows for the calling of the piece validation function in
   * a controlled context like a thread or an executor. It returns the piece it was created for.
   * Results of the validation can easily be extracted from the {@link Piece} object after it is
   * returned.
   * </p>
   *
   * @author mpetazzoni
   */
  @Value
  public static class CallableHasher implements Callable<Piece> {

    private Piece piece;

    @Override
    public Piece call() throws IOException {
      this.piece.validate();
      return this.piece;
    }
  }
}
