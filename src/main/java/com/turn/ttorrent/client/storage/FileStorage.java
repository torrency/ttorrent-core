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

package com.turn.ttorrent.client.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

/**
 * Single-file torrent byte data storage.
 *
 * <p>
 * This implementation of TorrentByteStorageFile provides a torrent byte data storage relying on a
 * single underlying file and uses a RandomAccessFile FileChannel to expose thread-safe read/write
 * methods.
 * </p>
 *
 * @author mpetazzoni
 */
@Slf4j
public class FileStorage implements TorrentByteStorage {

  private final File target;

  private final File partial;

  private final long offset;

  private final long size;

  private RandomAccessFile raf;

  private FileChannel channel;

  private File current;

  public FileStorage(final File file, final long size) throws IOException {
    this(file, 0, size);
  }

  /**
   * Instantiate file storage object.
   *
   * @param file   torrent file
   * @param offset current offset
   * @param size   size of file
   *
   * @throws IOException unable to open this file
   */
  public FileStorage(final File file, final long offset, final long size) throws IOException {
    this.target = file;
    this.offset = offset;
    this.size = size;

    this.partial = new File(this.target.getAbsolutePath()
                            + TorrentByteStorage.PARTIAL_FILE_NAME_SUFFIX);

    if (this.partial.exists()) {
      LOG.debug("Partial download found at {}. Continuing...",
                this.partial.getAbsolutePath());
      this.current = this.partial;
    } else if (!this.target.exists()) {
      LOG.debug("Downloading new file to {}...",
                this.partial.getAbsolutePath());
      this.current = this.partial;
    } else {
      LOG.debug("Using existing file {}.",
                this.target.getAbsolutePath());
      this.current = this.target;
    }

    this.raf = new RandomAccessFile(this.current, "rw");

    if (file.length() != this.size) {
      // Set the file length to the appropriate size, eventually truncating
      // or extending the file if it already exists with a different size.
      this.raf.setLength(this.size);
    }

    this.channel = this.raf.getChannel();
    LOG.info("Initialized byte storage file at {} ({}+{} byte(s)).",
             this.current.getAbsolutePath(),
             this.offset,
             this.size);
  }

  protected long offset() {
    return this.offset;
  }

  @Override
  public long size() {
    return this.size;
  }

  @Override
  public int read(final ByteBuffer buffer, final long offset) throws IOException {
    final int requested = buffer.remaining();

    if (offset + requested > this.size) {
      throw new IllegalArgumentException("Invalid storage read request!");
    }

    final int bytes = this.channel.read(buffer, offset);
    if (bytes < requested) {
      throw new IOException("Storage underrun!");
    }

    return bytes;
  }

  @Override
  public int write(final ByteBuffer buffer, final long offset) throws IOException {
    final int requested = buffer.remaining();

    if (offset + requested > this.size) {
      throw new IllegalArgumentException("Invalid storage write request!");
    }

    return this.channel.write(buffer, offset);
  }

  /**
   * Close torrent file channel.
   *
   * @throws IOException unable to close
   */
  @Override
  public synchronized void close() throws IOException {
    LOG.debug("Closing file channel to " + this.current.getName() + "...");
    if (this.channel.isOpen()) {
      this.channel.force(true);
    }
    this.raf.close();
  }

  /**
   * Move the partial file to its final location.
   *
   * @throws IOException unable to manage file
   */
  @Override
  public synchronized void finish() throws IOException {
    LOG.debug("Closing file channel to {} (download complete).", this.current.getName());
    if (this.channel.isOpen()) {
      this.channel.force(true);
    }

    // Nothing more to do if we're already on the target file.
    if (this.isFinished()) {
      return;
    }

    this.raf.close();
    FileUtils.deleteQuietly(this.target);
    FileUtils.moveFile(this.current, this.target);

    LOG.debug("Re-opening torrent byte storage at {}.", this.target.getAbsolutePath());

    this.raf = new RandomAccessFile(this.target, "rw");
    this.raf.setLength(this.size);
    this.channel = this.raf.getChannel();
    this.current = this.target;

    FileUtils.deleteQuietly(this.partial);
    LOG.info("Moved torrent data from {} to {}.",
             this.partial.getName(),
             this.target.getName());
  }

  @Override
  public boolean isFinished() {
    return this.current.equals(this.target);
  }
}
