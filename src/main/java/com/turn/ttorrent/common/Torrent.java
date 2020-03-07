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

package com.turn.ttorrent.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.turn.ttorrent.bcodec.BeDecoder;
import com.turn.ttorrent.bcodec.BeEncoder;
import com.turn.ttorrent.bcodec.BeValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

/**
 * A torrent file tracked by the controller's BitTorrent tracker.
 *
 * <p>
 * This class represents an active torrent on the tracker. The torrent information is kept
 * in-memory, and is created from the byte blob one would usually find in a {@code .torrent} file.
 * </p>
 *
 * <p>
 * Each torrent also keeps a repository of the peers seeding and leeching this torrent from the
 * tracker.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Metainfo_File_Structure">Torrent
 * meta-info file structure specification</a>
 */
@Slf4j
public class Torrent {

  /**
   * Torrent file piece length (in bytes), we use 512 kB.
   */
  public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;

  public static final int PIECE_HASH_SIZE = 20;

  /**
   * The query parameters encoding when parsing byte strings.
   */
  public static final String BYTE_ENCODING = "ISO-8859-1";

  /**
   * Torrent file class.
   *
   * @author dgiffin
   * @author mpetazzoni
   */
  @Value
  public static class TorrentFile {

    public File file;

    public long size;
  }

  /**
   * The B-encoded meta-info of this torrent.
   */
  @Getter
  protected final byte[] encoded;

  protected final byte[] encodedInfo;

  protected final Map<String, BeValue> decoded;

  protected final Map<String, BeValue> decodedInfo;

  /**
   * The hash of the B-encoded meta-info structure of this torrent.
   */
  @Getter
  private final byte[] infoHash;

  /**
   * This torrent's info hash (as an hexadecimal-coded string).
   */
  @Getter
  private final String hexInfoHash;

  private final List<List<URI>> trackers;

  private final Set<URI> allTrackers;

  private final Date creationDate;

  /**
   * This torrent's comment string.
   */
  @Getter
  private final String comment;

  /**
   * This torrent's creator (user, software, whatever...).
   */
  @Getter
  private final String createdBy;

  /**
   * Get this torrent's name.
   *
   * <p>
   * For a single-file torrent, this is usually the name of the file. For a multi-file torrent, this
   * is usually the name of a top-level directory containing those files.
   * </p>
   */
  @Getter
  private final String name;

  /**
   * The total size of this torrent.
   */
  @Getter
  private final long size;

  @Getter
  private final int pieceLength;

  protected final List<TorrentFile> files;

  private final boolean seeder;

  /**
   * Parses the announce information from the decoded meta-info structure.
   *
   * <p>
   * If the torrent doesn't define an announce-list, use the mandatory announce field value as the
   * single tracker in a single announce tier. Otherwise, the announce-list must be parsed and the
   * trackers from each tier extracted.
   * </p>
   *
   * @see <a href="http://bittorrent.org/beps/bep_0012.html">BitTorrent BEP#0012 "Multitracker
   * Metadata Extension"</a>
   */
  private void init() throws IOException {
    try {
      if (this.decoded.containsKey("announce-list")) {
        final List<BeValue> tiers = this.decoded.get("announce-list").getList();
        for (BeValue tv : tiers) {
          final List<BeValue> ts = tv.getList();
          if (ts.isEmpty()) {
            continue;
          }

          final List<URI> tier = new ArrayList<>();
          for (BeValue tracker : ts) {
            final URI uri = new URI(tracker.getString());

            // Make sure we're not adding duplicate trackers.
            if (!this.allTrackers.contains(uri)) {
              tier.add(uri);
              this.allTrackers.add(uri);
            }
          }

          // Only add the tier if it's not empty.
          if (!tier.isEmpty()) {
            this.trackers.add(tier);
          }
        }
      } else if (this.decoded.containsKey("announce")) {
        final URI tracker = new URI(this.decoded.get("announce").getString());
        this.allTrackers.add(tracker);

        // Build a single-tier announce list.
        final List<URI> tier = new ArrayList<>();
        tier.add(tracker);
        this.trackers.add(tier);
      }
    } catch (final URISyntaxException use) {
      throw new IOException(use);
    }
  }

  private void parseMultiFiles() throws InvalidBEncodingException {
    for (BeValue file : this.decodedInfo.get("files").getList()) {
      final Map<String, BeValue> fileInfo = file.getMap();
      final StringBuilder path = new StringBuilder();
      // Try UTF-8 path first, but fallback to regular path
      BeValue beValue = fileInfo.get("path.utf-8");
      if (beValue == null) {
        beValue = fileInfo.get("path");
      }
      for (BeValue pathElement : beValue.getList()) {
        path.append(File.separator).append(pathElement.getString());
      }
      this.files.add(new TorrentFile(new File(this.name, path.toString()),
                                     fileInfo.get("length").getLong()));
    }
  }

  /**
   * Create a new torrent from meta-info binary data.Parses the meta-info data (which should be
   * B-encoded as described in the BitTorrent specification) and create a Torrent object from it.
   *
   *
   * @param torrent The meta-info byte data.
   * @param seeder  Whether we'll be seeding for this torrent or not.
   *
   * @throws IOException              When the info dictionary can't be read or encoded and hashed
   *                                  back to create the torrent's SHA-1 hash.
   * @throws NoSuchAlgorithmException Unable to find specified algorithm
   */
  public Torrent(final byte[] torrent, final boolean seeder) throws IOException,
                                                                    NoSuchAlgorithmException {
    this.encoded = torrent;
    this.seeder = seeder;
    this.decoded = BeDecoder.bdecode(new ByteArrayInputStream(this.encoded)).getMap();
    this.decodedInfo = this.decoded.get("info").getMap();
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BeEncoder.bencode(this.decodedInfo, baos);
    this.encodedInfo = baos.toByteArray();
    this.infoHash = Torrent.hash(this.encodedInfo);
    this.hexInfoHash = Utils.bytesToHex(this.infoHash);

    this.trackers = new ArrayList<>();
    this.allTrackers = new HashSet<>();
    this.init();

    this.creationDate = this.decoded.containsKey("creation date")
                        ? new Date(this.decoded.get("creation date").getLong() * 1000)
                        : null;
    this.comment = this.decoded.containsKey("comment")
                   ? this.decoded.get("comment").getString()
                   : null;
    this.createdBy = this.decoded.containsKey("created by")
                     ? this.decoded.get("created by").getString()
                     : null;
    this.name = this.decodedInfo.get("name").getString();
    this.pieceLength = this.decodedInfo.get("piece length").getInt();

    this.files = new LinkedList<>();

    if (this.decodedInfo.containsKey("files")) {
      this.parseMultiFiles();// Parse multi-file torrent file information structure.
    } else { // For single-file torrents, the name of the torrent is directly the name of the file.
      this.files.add(new TorrentFile(new File(this.name),
                                     this.decodedInfo.get("length").getLong()));
    }

    // Calculate the total size of this torrent from its files' sizes.
    this.size = this.files.stream()
            .map((file) -> file.size)
            .reduce(0L, (accumulator, _item) -> accumulator + _item);

    LOG.info("{}-file torrent information:", this.isMultifile() ? "Multi" : "Single");
    LOG.info("  Torrent name: {}", this.name);
    LOG.info("  Announced at: {}", this.trackers.isEmpty() ? " Seems to be trackerless" : "");
    for (int i = 0; i < this.trackers.size(); i++) {
      final List<URI> tier = this.trackers.get(i);
      for (int j = 0; j < tier.size(); j++) {
        LOG.info("    {}{}", j == 0 ? String.format("%2d. ", i + 1) : "    ", tier.get(j));
      }
    }

    if (this.creationDate != null) {
      LOG.info("  Created on..: {}", this.creationDate);
    }
    if (this.comment != null) {
      LOG.info("  Comment.....: {}", this.comment);
    }
    if (this.createdBy != null) {
      LOG.info("  Created by..: {}", this.createdBy);
    }

    if (this.isMultifile()) {
      LOG.info("  Found {} file(s) in multi-file torrent structure.", this.files.size());
      int i = 0;
      for (TorrentFile file : this.files) {
        LOG.debug("    {}. {} ({} byte(s))",
                  String.format("%2d", ++i),
                  file.file.getPath(),
                  String.format("%,d", file.size));
      }
    }

    LOG.info("  Pieces......: {} piece(s) ({} byte(s)/piece)",
             this.size / this.decodedInfo.get("piece length").getInt() + 1,
             this.decodedInfo.get("piece length").getInt());
    LOG.info("  Total size..: {} byte(s)", String.format("%,d", this.size));
  }

  /**
   * Get the file names from this torrent.
   *
   * @return The list of relative filenames of all the files described in this torrent.
   */
  public List<String> getFilenames() {
    final List<String> filenames = new LinkedList<>();
    for (TorrentFile file : this.files) {
      filenames.add(file.file.getPath());
    }
    return filenames;
  }

  /**
   * Tells whether this torrent is multi-file.
   *
   * @return r not.
   */
  public boolean isMultifile() {
    return this.files.size() > 1;
  }

  /**
   * Return a human-readable representation of this torrent object. <BR>
   * The torrent's name is used.
   *
   * @return Torrent name
   */
  @Override
  public String toString() {
    return this.getName();
  }

  /**
   * Return the trackers for this torrent.
   *
   * @return list of trackers URI
   */
  public List<List<URI>> getAnnounceList() {
    return this.trackers;
  }

  /**
   * Returns the number of trackers for this torrent.
   *
   * @return number of all trackers
   */
  public int getTrackerCount() {
    return this.allTrackers.size();
  }

  /**
   * Tells whether we were an initial seeder for this torrent.
   *
   * @return true if this torrent is seeder
   */
  public boolean isSeeder() {
    return this.seeder;
  }

  /**
   * Save this torrent meta-info structure into a .torrent file.
   *
   * @param output The stream to write to.
   *
   * @throws IOException If an I/O error occurs while writing the file.
   */
  public void save(final OutputStream output) throws IOException {
    output.write(this.getEncoded());
  }

  /**
   * Create SHA-1 hash code for given data.
   *
   * @param data torrent byte array
   *
   * @return SHA-1 byte array
   *
   * @throws NoSuchAlgorithmException Unable to find SHA-1 algorithm
   */
  public static byte[] hash(final byte[] data) throws NoSuchAlgorithmException {
    final MessageDigest crypt = MessageDigest.getInstance("SHA-1");
    crypt.reset();
    crypt.update(data);
    return crypt.digest();
  }

  /**
   * Return an hexadecimal representation of the bytes contained in the given string, following the
   * default, expected byte encoding.
   *
   * @param input The input string.
   *
   * @return bytes in hexadecimal
   */
  public static String toHexString(final String input) {
    try {
      final byte[] bytes = input.getBytes(Torrent.BYTE_ENCODING);
      return Utils.bytesToHex(bytes);
    } catch (final UnsupportedEncodingException uee) {
      return null;
    }
  }

  /**
   * Determine how many threads to use for the piece hashing.
   *
   * <p>
   * If the environment variable TTORRENT_HASHING_THREADS is set to an integer value greater than 0,
   * its value will be used. Otherwise, it defaults to the number of processors detected by the Java
   * Runtime.
   * </p>
   *
   * @return How many threads to use for concurrent piece hashing.
   */
  protected static int getHashingThreadsCount() {
    final String threads = System.getenv("TTORRENT_HASHING_THREADS");

    if (threads != null) {
      try {
        final int count = Integer.parseInt(threads);
        if (count > 0) {
          return count;
        }
      } catch (final NumberFormatException nfe) {
        // Pass
        LOG.error("", nfe);
      }
    }

    return Runtime.getRuntime().availableProcessors();
  }

  /**
   * Torrent loading ----------------------------------------------------
   */
  /**
   * Load a torrent from the given torrent file.
   *
   * <p>
   * This method assumes we are not a seeder and that local data needs to be validated.
   * </p>
   *
   * @param torrent The abstract {@link File} object representing the {@code .torrent} file to load.
   *
   * @return Loaded torrent
   *
   * @throws IOException              When the torrent file cannot be read.
   * @throws NoSuchAlgorithmException Specified algorithm not found
   */
  public static Torrent load(final File torrent) throws IOException, NoSuchAlgorithmException {
    return Torrent.load(torrent, false);
  }

  /**
   * Load a torrent from the given torrent file.
   *
   * @param torrent The abstract {@link File} object representing the {@code .torrent} file to load.
   * @param seeder  Whether we are a seeder for this torrent or not (disables local data
   *                validation).
   *
   * @return loaded torrent
   *
   * @throws IOException              When the torrent file cannot be read.
   * @throws NoSuchAlgorithmException Specified algorithm not found
   */
  public static Torrent load(final File torrent, final boolean seeder)
          throws IOException, NoSuchAlgorithmException {
    final byte[] data = FileUtils.readFileToByteArray(torrent);
    return new Torrent(data, seeder);
  }

  /*
   * Torrent creation ---------------------------------------------------
   */
  /**
   * Create a {@link Torrent} object for a file.
   *
   * <p>
   * Hash the given file to create the {@link Torrent} object representing the Torrent metainfo
   * about this file, needed for announcing and/or sharing said file.
   * </p>
   *
   * @param source    The file to use in the torrent.
   * @param announce  The announce URI that will be used for this torrent.
   * @param createdBy The creator's name, or any string identifying the torrent's creator.
   *
   * @return created torrent
   *
   * @throws InterruptedException     When thread interrupted
   * @throws IOException              Unable to write to file system
   * @throws NoSuchAlgorithmException Specified algorithm not found
   */
  public static Torrent create(final File source, final URI announce, final String createdBy)
          throws InterruptedException, IOException, NoSuchAlgorithmException {
    return Torrent.create(source, null, DEFAULT_PIECE_LENGTH, announce, null, createdBy);
  }

  /**
   * Create a {@link Torrent} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link Torrent} object representing the Torrent
   * meta-info about them, needed for announcing and/or sharing these files. Since we created the
   * torrent, we're considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param parent    The parent directory or location of the torrent files, also used as the
   *                  torrent's name.
   * @param files     The files to add into this torrent.
   * @param announce  The announce URI that will be used for this torrent.
   * @param createdBy The creator's name, or any string identifying the torrent's creator.
   *
   * @return created torrent
   *
   * @throws InterruptedException     When thread interrupted
   * @throws IOException              Unable to write to file system
   * @throws NoSuchAlgorithmException Specified algorithm not found
   */
  public static Torrent create(final File parent,
                               final List<File> files,
                               final URI announce,
                               final String createdBy) throws InterruptedException,
                                                              IOException,
                                                              NoSuchAlgorithmException {
    return Torrent.create(parent, files, DEFAULT_PIECE_LENGTH, announce, null, createdBy);
  }

  /**
   * Create a {@link Torrent} object for a file.
   *
   * <p>
   * Hash the given file to create the {@link Torrent} object representing the Torrent metainfo
   * about this file, needed for announcing and/or sharing said file.
   * </p>
   *
   * @param source       The file to use in the torrent.
   * @param pieceLength  length of data piece
   * @param announceList The announce URIs organized as tiers that will be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the torrent's creator.
   *
   * @return created torrent
   *
   * @throws InterruptedException     When thread interrupted
   * @throws IOException              Unable to write to file system
   * @throws NoSuchAlgorithmException Specified algorithm not found
   */
  public static Torrent create(final File source,
                               final int pieceLength,
                               final List<List<URI>> announceList,
                               final String createdBy) throws InterruptedException,
                                                              IOException,
                                                              NoSuchAlgorithmException {
    return Torrent.create(source, null, pieceLength, null, announceList, createdBy);
  }

  /**
   * Create a {@link Torrent} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link Torrent} object representing the Torrent
   * meta-info about them, needed for announcing and/or sharing these files. Since we created the
   * torrent, we're considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param source       The parent directory or location of the torrent files, also used as the
   *                     torrent's name.
   * @param files        The files to add into this torrent.
   * @param pieceLength  length of piece data
   * @param announceList The announce URIs organized as tiers that will be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the torrent's creator.
   *
   * @return created torrent
   *
   * @throws InterruptedException     When thread interrupted
   * @throws IOException              Unable to write to file system
   * @throws NoSuchAlgorithmException Specified algorithm not found
   */
  public static Torrent create(final File source,
                               final List<File> files,
                               final int pieceLength,
                               final List<List<URI>> announceList,
                               final String createdBy)
          throws InterruptedException, IOException, NoSuchAlgorithmException {
    return Torrent.create(source, files, pieceLength, null, announceList, createdBy);
  }

  /**
   * Helper method to create a {@link Torrent} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link Torrent} object representing the Torrent
   * meta-info about them, needed for announcing and/or sharing these files. Since we created the
   * torrent, we're considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param parent       The parent directory or location of the torrent files, also used as the
   *                     torrent's name.
   * @param files        The files to add into this torrent.
   * @param announce     The announce URI that will be used for this torrent.
   * @param announceList The announce URIs organized as tiers that will be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the torrent's creator.
   */
  private static Torrent create(final File parent,
                                final List<File> files,
                                final int pieceLength,
                                final URI announce,
                                final List<List<URI>> announceList,
                                final String createdBy)
          throws InterruptedException, IOException, NoSuchAlgorithmException {
    if (files == null || files.isEmpty()) {
      LOG.info("Creating single-file torrent for {}...", parent.getName());
    } else {
      LOG.info("Creating {}-file torrent {}...", files.size(), parent.getName());
    }

    final Map<String, BeValue> torrent = new HashMap<>();

    if (announce != null) {
      torrent.put("announce", new BeValue(announce.toString()));
    }
    if (announceList != null) {
      final List<BeValue> tiers = new LinkedList<>();
      for (List<URI> trackers : announceList) {
        final List<BeValue> tierInfo = new LinkedList<>();
        for (URI trackerUri : trackers) {
          tierInfo.add(new BeValue(trackerUri.toString()));
        }
        tiers.add(new BeValue(tierInfo));
      }
      torrent.put("announce-list", new BeValue(tiers));
    }

    torrent.put("creation date", new BeValue(new Date().getTime() / 1000));
    torrent.put("created by", new BeValue(createdBy));

    final Map<String, BeValue> info = new TreeMap<>();
    info.put("name", new BeValue(parent.getName()));
    info.put("piece length", new BeValue(pieceLength));

    if (files == null || files.isEmpty()) {
      info.put("length", new BeValue(parent.length()));
      info.put("pieces", new BeValue(Torrent.hashFile(parent, pieceLength),
                                     Torrent.BYTE_ENCODING));
    } else {
      final List<BeValue> fileInfo = new LinkedList<>();
      for (File file : files) {
        final Map<String, BeValue> fileMap = new HashMap<>();
        fileMap.put("length", new BeValue(file.length()));

        final LinkedList<BeValue> filePath = new LinkedList<>();
        while (file != null) {
          if (file.equals(parent)) {
            break;
          }

          filePath.addFirst(new BeValue(file.getName()));
          file = file.getParentFile();
        }

        fileMap.put("path", new BeValue(filePath));
        fileInfo.add(new BeValue(fileMap));
      }
      info.put("files", new BeValue(fileInfo));
      info.put("pieces", new BeValue(Torrent.hashFiles(files, pieceLength),
                                     Torrent.BYTE_ENCODING));
    }
    torrent.put("info", new BeValue(info));

    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BeEncoder.bencode(new BeValue(torrent), baos);
    return new Torrent(baos.toByteArray(), true);
  }

  /**
   * A {@link Callable} to hash a data chunk.
   *
   * @author mpetazzoni
   */
  private static class CallableChunkHasher implements Callable<String> {

    private final MessageDigest md;

    private final ByteBuffer data;

    CallableChunkHasher(final ByteBuffer buffer) throws NoSuchAlgorithmException {
      this.md = MessageDigest.getInstance("SHA-1");

      this.data = ByteBuffer.allocate(buffer.remaining());
      buffer.mark();
      this.data.put(buffer);
      this.data.clear();
      buffer.reset();
    }

    @Override
    public String call() throws UnsupportedEncodingException {
      this.md.reset();
      this.md.update(this.data.array());
      return new String(this.md.digest(), Torrent.BYTE_ENCODING);
    }
  }

  /**
   * Return the concatenation of the SHA-1 hashes of a file's pieces.
   *
   * <p>
   * Hashes the given file piece by piece using the default Torrent piece length (see
   * {@link #PIECE_LENGTH}) and returns the concatenation of these hashes, as a string.
   * </p>
   *
   * <p>
   * This is used for creating Torrent meta-info structures from a file.
   * </p>
   *
   * @param file The file to hash.
   */
  private static String hashFile(final File file, final int pieceLenght)
          throws InterruptedException, IOException, NoSuchAlgorithmException {
    return Torrent.hashFiles(Arrays.asList(new File[]{file}), pieceLenght);
  }

  private static String hashFiles(final List<File> files, final int pieceLenght)
          throws InterruptedException, IOException, NoSuchAlgorithmException {
    final int threads = getHashingThreadsCount();
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    final ByteBuffer buffer = ByteBuffer.allocate(pieceLenght);
    final List<Future<String>> results = new LinkedList<>();
    final StringBuilder hashes = new StringBuilder();

    long length = 0L;
    int pieces = 0;

    final long start = System.nanoTime();
    for (File file : files) {
      LOG.info("Hashing data from {} with {} threads ({} pieces)...",
               new Object[]{
                 file.getName(),
                 threads,
                 (int) (Math.ceil(
                         (double) file.length() / pieceLenght))
               });

      length += file.length();

      final FileInputStream fis = new FileInputStream(file);
      final FileChannel channel = fis.getChannel();
      int step = 10;

      try {
        while (channel.read(buffer) > 0) {
          if (buffer.remaining() == 0) {
            buffer.clear();
            results.add(executor.submit(new CallableChunkHasher(buffer)));
          }

          if (results.size() >= threads) {
            pieces += accumulateHashes(hashes, results);
          }

          if (channel.position() / (double) channel.size() * 100f > step) {
            LOG.info("  ... {}% complete", step);
            step += 10;
          }
        }
      } finally {
        channel.close();
        fis.close();
      }
    }

    // Hash the last bit, if any
    if (buffer.position() > 0) {
      buffer.limit(buffer.position());
      buffer.position(0);
      results.add(executor.submit(new CallableChunkHasher(buffer)));
    }

    pieces += accumulateHashes(hashes, results);

    // Request orderly executor shutdown and wait for hashing tasks to
    // complete.
    executor.shutdown();
    while (!executor.isTerminated()) {
      Thread.sleep(10);
    }
    final long elapsed = System.nanoTime() - start;

    final int expectedPieces = (int) (Math.ceil((double) length / pieceLenght));
    LOG.info("Hashed {} file(s) ({} bytes) in {} pieces ({} expected) in {}ms.",
             new Object[]{
               files.size(),
               length,
               pieces,
               expectedPieces,
               String.format("%.1f", elapsed / 1e6),});

    return hashes.toString();
  }

  /**
   * Accumulate the piece hashes into a given {@link StringBuilder}.
   *
   * @param hashes  The {@link StringBuilder} to append hashes to.
   * @param results The list of {@link Future}s that will yield the piece hashes.
   */
  private static int accumulateHashes(final StringBuilder hashes,
                                      final List<Future<String>> results)
          throws InterruptedException, IOException {
    try {
      final int pieces = results.size();
      for (Future<String> chunk : results) {
        hashes.append(chunk.get());
      }
      results.clear();
      return pieces;
    } catch (final ExecutionException ee) {
      throw new IOException("Error while hashing the torrent data!", ee);
    }
  }
}
