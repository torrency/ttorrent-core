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

package com.turn.ttorrent.common.protocol;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.BitSet;

import com.turn.ttorrent.client.SharedTorrent;
import lombok.Value;

/**
 * BitTorrent peer protocol messages representations.
 *
 * <p>
 * This class and its <em>*Messages</em> subclasses provide POJO representations of the peer
 * protocol messages, along with easy parsing from an input ByteBuffer to quickly get a usable
 * representation of an incoming message.
 * </p>
 *
 * @author mpetazzoni
 * @see
 * <a href="http://wiki.theory.org/BitTorrentSpecification#Peer_wire_protocol_.28TCP.29">BitTorrent
 * peer wire protocol</a>
 */
public abstract class PeerMessage {

  /**
   * The size, in bytes, of the length field in a message (one 32-bit integer).
   */
  public static final int MESSAGE_LENGTH_FIELD_SIZE = 4;

  /**
   * Message type.
   *
   * <p>
   * Note that the keep-alive messages don't actually have an type ID defined in the protocol as
   * they are of length 0.
   * </p>
   */
  public enum Type {
    KEEP_ALIVE(-1),
    CHOKE(0),
    UNCHOKE(1),
    INTERESTED(2),
    NOT_INTERESTED(3),
    HAVE(4),
    BITFIELD(5),
    REQUEST(6),
    PIECE(7),
    CANCEL(8);

    private final byte id;

    Type(final int id) {
      this.id = (byte) id;
    }

    public boolean equals(final byte c) {
      return this.id == c;
    }

    public byte getTypeByte() {
      return this.id;
    }

    /**
     * Get enum type.
     *
     * @param c byte number
     *
     * @return type of enum
     */
    public static Type get(final byte c) {
      for (Type t : Type.values()) {
        if (t.equals(c)) {
          return t;
        }
      }
      return null;
    }
  }

  private final Type type;

  private final ByteBuffer data;

  private PeerMessage(final Type type, final ByteBuffer data) {
    this.type = type;
    this.data = data;
    this.data.rewind();
  }

  public Type getType() {
    return this.type;
  }

  /**
   * Returns a {@link ByteBuffer} backed by the same data as this message.
   *
   * <p>
   * This method returns a duplicate of the buffer stored in this {@link
   * PeerMessage} object to allow for multiple consumers to read from the same message without
   * conflicting access to the buffer's position, mark and limit.
   * </p>
   *
   * @return get buffer data
   */
  public ByteBuffer getData() {
    return this.data.duplicate();
  }

  /**
   * Validate that this message makes sense for the torrent it's related to.
   *
   * <p>
   * This method is meant to be overloaded by distinct message types, where it makes sense.
   * Otherwise, it defaults to true.
   * </p>
   *
   * @param torrent The torrent this message is about.
   *
   * @return Validated PeerMessage
   *
   * @throws MessageValidationException Unable to validate message
   */
  public PeerMessage validate(final SharedTorrent torrent) throws MessageValidationException {
    return this;
  }

  @Override
  public String toString() {
    return this.getType().name();
  }

  /**
   * Parse the given buffer into a peer protocol message.
   *
   * <p>
   * Parses the provided byte array and builds the corresponding PeerMessage subclass object.
   * </p>
   *
   * @param buffer  The byte buffer containing the message data.
   * @param torrent The torrent this message is about.
   *
   * @return A PeerMessage subclass instance.
   *
   * @throws ParseException When the message is invalid, can't be parsed or does not match the
   *                        protocol requirements.
   */
  public static PeerMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
          throws ParseException {
    final int length = buffer.getInt();
    if (length == 0) {
      return KeepAliveMessage.parse(buffer, torrent);
    } else if (length != buffer.remaining()) {
      throw new ParseException("Message size did not match announced size!", 0);
    }

    final Type type = Type.get(buffer.get());
    if (type == null) {
      throw new ParseException("Unknown message ID!", buffer.position() - 1);
    }

    switch (type) {
      case CHOKE:
        return ChokeMessage.parse(buffer.slice(), torrent);
      case UNCHOKE:
        return UnchokeMessage.parse(buffer.slice(), torrent);
      case INTERESTED:
        return InterestedMessage.parse(buffer.slice(), torrent);
      case NOT_INTERESTED:
        return NotInterestedMessage.parse(buffer.slice(), torrent);
      case HAVE:
        return HaveMessage.parse(buffer.slice(), torrent);
      case BITFIELD:
        return BitfieldMessage.parse(buffer.slice(), torrent);
      case REQUEST:
        return RequestMessage.parse(buffer.slice(), torrent);
      case PIECE:
        return PieceMessage.parse(buffer.slice(), torrent);
      case CANCEL:
        return CancelMessage.parse(buffer.slice(), torrent);
      default:
        throw new IllegalStateException("Message type should have been properly defined by now.");
    }
  }

  public static class MessageValidationException extends ParseException {

    static final long serialVersionUID = -1;

    public MessageValidationException(final PeerMessage m) {
      super("Message " + m + " is not valid!", 0);
    }

  }

  /**
   * Keep alive message.
   *
   * <code>&lt;len=0000&gt;</code>
   */
  public static class KeepAliveMessage extends PeerMessage {

    private static final int BASE_SIZE = 0;

    private KeepAliveMessage(final ByteBuffer buffer) {
      super(Type.KEEP_ALIVE, buffer);
    }

    /**
     * Parse the given buffer into a peer protocol message.
     *
     * <p>
     * Parses the provided byte array and builds the corresponding PeerMessage subclass object.
     * </p>
     *
     * @param buffer  The byte buffer containing the message data.
     * @param torrent The torrent this message is about.
     *
     * @return A PeerMessage subclass instance.
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static KeepAliveMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      return (KeepAliveMessage) new KeepAliveMessage(buffer).validate(torrent);
    }

    /**
     * Create keep alive message.
     *
     * @return message
     */
    public static KeepAliveMessage craft() {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + KeepAliveMessage.BASE_SIZE);
      buffer.putInt(KeepAliveMessage.BASE_SIZE);
      return new KeepAliveMessage(buffer);
    }
  }

  /**
   * Choke message.
   *
   * <code>&lt;len=0001&gt;&lt;id=0&gt;</code>
   */
  public static class ChokeMessage extends PeerMessage {

    private static final int BASE_SIZE = 1;

    private ChokeMessage(final ByteBuffer buffer) {
      super(Type.CHOKE, buffer);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return choke message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static ChokeMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      return (ChokeMessage) new ChokeMessage(buffer).validate(torrent);
    }

    /**
     * Create chock message.
     *
     * @return message
     */
    public static ChokeMessage craft() {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + ChokeMessage.BASE_SIZE);
      buffer.putInt(ChokeMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.CHOKE.getTypeByte());
      return new ChokeMessage(buffer);
    }
  }

  /**
   * Unchoke message.
   *
   * <code>&lt;len=0001&gt;&lt;id=1&gt;</code>
   */
  public static class UnchokeMessage extends PeerMessage {

    private static final int BASE_SIZE = 1;

    private UnchokeMessage(final ByteBuffer buffer) {
      super(Type.UNCHOKE, buffer);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return unchoke message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static UnchokeMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      return (UnchokeMessage) new UnchokeMessage(buffer).validate(torrent);
    }

    /**
     * Create unchock message.
     *
     * @return message
     */
    public static UnchokeMessage craft() {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + UnchokeMessage.BASE_SIZE);
      buffer.putInt(UnchokeMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.UNCHOKE.getTypeByte());
      return new UnchokeMessage(buffer);
    }
  }

  /**
   * Interested message.
   *
   * <code>&lt;len=0001&lt;&gt;id=2&gt;</code>
   */
  public static class InterestedMessage extends PeerMessage {

    private static final int BASE_SIZE = 1;

    private InterestedMessage(final ByteBuffer buffer) {
      super(Type.INTERESTED, buffer);
    }

    public static InterestedMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      return (InterestedMessage) new InterestedMessage(buffer).validate(torrent);
    }

    /**
     * Create interested message.
     *
     * @return message
     */
    public static InterestedMessage craft() {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + InterestedMessage.BASE_SIZE);
      buffer.putInt(InterestedMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.INTERESTED.getTypeByte());
      return new InterestedMessage(buffer);
    }
  }

  /**
   * Not interested message.
   *
   * <code>&lt;len=0001&gt;&lt;id=3&gt;</code>
   */
  public static class NotInterestedMessage extends PeerMessage {

    private static final int BASE_SIZE = 1;

    private NotInterestedMessage(final ByteBuffer buffer) {
      super(Type.NOT_INTERESTED, buffer);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return bit field message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static NotInterestedMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      return (NotInterestedMessage) new NotInterestedMessage(buffer).validate(torrent);
    }

    /**
     * Create not interested message.
     *
     * @return message
     */
    public static NotInterestedMessage craft() {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + NotInterestedMessage.BASE_SIZE);
      buffer.putInt(NotInterestedMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.NOT_INTERESTED.getTypeByte());
      return new NotInterestedMessage(buffer);
    }
  }

  /**
   * Have message.
   *
   * <code>&lt;len=0005&gt;&lt;id=4&gt;&lt;piece index=xxxx&gt;</code>
   */
  public static class HaveMessage extends PeerMessage {

    private static final int BASE_SIZE = 5;

    private int piece;

    private HaveMessage(final ByteBuffer buffer, final int piece) {
      super(Type.HAVE, buffer);
      this.piece = piece;
    }

    public int getPieceIndex() {
      return this.piece;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HaveMessage validate(final SharedTorrent torrent) throws MessageValidationException {
      if (this.piece >= 0 && this.piece < torrent.getPieceCount()) {
        return this;
      }

      throw new MessageValidationException(this);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return bit field message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static HaveMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      return new HaveMessage(buffer, buffer.getInt()).validate(torrent);
    }

    /**
     * Create have message.
     *
     * @param piece data piece
     *
     * @return have message
     */
    public static HaveMessage craft(final int piece) {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + HaveMessage.BASE_SIZE);
      buffer.putInt(HaveMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.HAVE.getTypeByte());
      buffer.putInt(piece);
      return new HaveMessage(buffer, piece);
    }

    @Override
    public String toString() {
      return super.toString() + " #" + this.getPieceIndex();
    }
  }

  /**
   * Bitfield message.
   *
   * <code>&lt;len=0001+X&gt;&lt;id=5&gt;&lt;bitfield&gt;</code>
   */
  public static class BitfieldMessage extends PeerMessage {

    private static final int BASE_SIZE = 1;

    private BitSet bitfield;

    private BitfieldMessage(final ByteBuffer buffer, final BitSet bitfield) {
      super(Type.BITFIELD, buffer);
      this.bitfield = bitfield;
    }

    public BitSet getBitfield() {
      return this.bitfield;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BitfieldMessage validate(final SharedTorrent torrent) throws MessageValidationException {
      if (this.bitfield.length() <= torrent.getPieceCount()) {
        return this;
      }

      throw new MessageValidationException(this);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return bit field message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static BitfieldMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      final BitSet bitfield = new BitSet(buffer.remaining() * 8);
      for (int i = 0; i < buffer.remaining() * 8; i++) {
        if ((buffer.get(i / 8) & (1 << (7 - (i % 8)))) > 0) {
          bitfield.set(i);
        }
      }

      return new BitfieldMessage(buffer, bitfield).validate(torrent);
    }

    /**
     * Create bit field message.
     *
     * @param availablePieces mostly available piece set
     * @param pieceCount      number of piece
     *
     * @return bit field message
     */
    public static BitfieldMessage craft(final BitSet availablePieces, final int pieceCount) {
      final BitSet bitfield = new BitSet();
      final int bitfieldBufferSize = (pieceCount + 8 - 1) / 8;
      final byte[] bitfieldBuffer = new byte[bitfieldBufferSize];

      for (int i = availablePieces.nextSetBit(0);
           0 <= i && i < pieceCount;
           i = availablePieces.nextSetBit(i + 1)) {
        bitfieldBuffer[i / 8] |= 1 << (7 - (i % 8));
        bitfield.set(i);
      }

      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + BitfieldMessage.BASE_SIZE + bitfieldBufferSize);
      buffer.putInt(BitfieldMessage.BASE_SIZE + bitfieldBufferSize);
      buffer.put(PeerMessage.Type.BITFIELD.getTypeByte());
      buffer.put(ByteBuffer.wrap(bitfieldBuffer));

      return new BitfieldMessage(buffer, bitfield);
    }

    @Override
    public String toString() {
      return super.toString() + " " + this.getBitfield().cardinality();
    }
  }

  /**
   * Request message.
   *
   * <code>&lt;len=00013&gt;&lt;id=6&gt;&lt;piece index&gt;&lt;block offset&gt;&lt;block length&gt;
   * </code>
   */
  @Value
  public static class RequestMessage extends PeerMessage {

    private static final int BASE_SIZE = 13;

    /**
     * Default block size is 2^14 bytes, or 16kB.
     */
    public static final int DEFAULT_REQUEST_SIZE = 16384;

    /**
     * Max block request size is 2^17 bytes, or 131kB.
     */
    public static final int MAX_REQUEST_SIZE = 131072;

    private int piece;

    private int offset;

    private int length;

    private RequestMessage(final ByteBuffer buffer, final int piece,
                           final int offset, final int length) {
      super(Type.REQUEST, buffer);
      this.piece = piece;
      this.offset = offset;
      this.length = length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RequestMessage validate(final SharedTorrent torrent) throws MessageValidationException {
      if (this.piece >= 0 && this.piece < torrent.getPieceCount()
          && this.offset + this.length <= torrent.getPiece(this.piece).size()) {
        return this;
      }

      throw new MessageValidationException(this);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return request message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static RequestMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      final int piece = buffer.getInt();
      final int offset = buffer.getInt();
      final int length = buffer.getInt();
      return new RequestMessage(buffer, piece,
                                offset, length).validate(torrent);
    }

    /**
     * Create request message.
     *
     * @param piece  target piece
     * @param offset offset position
     * @param length piece length
     *
     * @return message request
     */
    public static RequestMessage craft(final int piece, final int offset, final int length) {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + RequestMessage.BASE_SIZE);
      buffer.putInt(RequestMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.REQUEST.getTypeByte());
      buffer.putInt(piece);
      buffer.putInt(offset);
      buffer.putInt(length);
      return new RequestMessage(buffer, piece, offset, length);
    }

    @Override
    public String toString() {
      return String.format("%s #%d (%d@%d)",
                           super.toString(),
                           this.getPiece(),
                           this.getLength(),
                           this.getOffset());
    }
  }

  /**
   * Piece message.
   *
   * <code>&lt;len=0009+X&gt;&lt;id=7&gt;&lt;piece index&gt;&lt;block offset&gt;&lt;block data&gt;
   * </code>
   */
  @Value
  public static class PieceMessage extends PeerMessage {

    private static final int BASE_SIZE = 9;

    private int piece;

    private int offset;

    private ByteBuffer block;

    private PieceMessage(final ByteBuffer buffer, final int piece,
                         final int offset, final ByteBuffer block) {
      super(Type.PIECE, buffer);
      this.piece = piece;
      this.offset = offset;
      this.block = block;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PieceMessage validate(final SharedTorrent torrent) throws MessageValidationException {
      if (this.piece >= 0 && this.piece < torrent.getPieceCount()
          && this.offset + this.block.limit()
             <= torrent.getPiece(this.piece).size()) {
        return this;
      }

      throw new MessageValidationException(this);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return piece message
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static PieceMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      final int piece = buffer.getInt();
      final int offset = buffer.getInt();
      final ByteBuffer block = buffer.slice();
      return new PieceMessage(buffer, piece, offset, block).validate(torrent);
    }

    /**
     * Create piece message.
     *
     * @param piece  piece to include
     * @param offset offset position
     * @param block  file bock
     *
     * @return piece message
     */
    public static PieceMessage craft(final int piece, final int offset, final ByteBuffer block) {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + PieceMessage.BASE_SIZE + block.capacity());
      buffer.putInt(PieceMessage.BASE_SIZE + block.capacity());
      buffer.put(PeerMessage.Type.PIECE.getTypeByte());
      buffer.putInt(piece);
      buffer.putInt(offset);
      buffer.put(block);
      return new PieceMessage(buffer, piece, offset, block);
    }

    @Override
    public String toString() {
      return String.format("%s #%d (%d@%d)",
                           super.toString(),
                           this.getPiece(),
                           this.getBlock().capacity(),
                           this.getOffset());
    }
  }

  /**
   * Cancel message.
   *
   * <code>&lt;len=00013&gt;&lt;id=8&gt;&lt;piece index&gt;&lt;block offset&gt;&lt;block length&gt;
   * </code>
   */
  @Value
  public static class CancelMessage extends PeerMessage {

    private static final int BASE_SIZE = 13;

    private int piece;

    private int offset;

    private int length;

    private CancelMessage(final ByteBuffer buffer, final int piece,
                          final int offset, final int length) {
      super(Type.CANCEL, buffer);
      this.piece = piece;
      this.offset = offset;
      this.length = length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CancelMessage validate(final SharedTorrent torrent) throws MessageValidationException {
      if (this.piece >= 0 && this.piece < torrent.getPieceCount()
          && this.offset + this.length <= torrent.getPiece(this.piece).size()) {
        return this;
      }

      throw new MessageValidationException(this);
    }

    /**
     * Parse inbound buffer.
     *
     * @param buffer  input data
     * @param torrent target torrent
     *
     * @return message to cancel
     *
     * @throws MessageValidationException unable to parse buffer
     */
    public static CancelMessage parse(final ByteBuffer buffer, final SharedTorrent torrent)
            throws MessageValidationException {
      final int piece = buffer.getInt();
      final int offset = buffer.getInt();
      final int length = buffer.getInt();
      return new CancelMessage(buffer, piece, offset, length).validate(torrent);
    }

    /**
     * Create peer message.
     *
     * @param piece  message piece
     * @param offset offset of position
     * @param length message length
     *
     * @return message to cancel
     */
    public static CancelMessage craft(final int piece, final int offset, final int length) {
      final ByteBuffer buffer = ByteBuffer.allocateDirect(
              MESSAGE_LENGTH_FIELD_SIZE + CancelMessage.BASE_SIZE);
      buffer.putInt(CancelMessage.BASE_SIZE);
      buffer.put(PeerMessage.Type.CANCEL.getTypeByte());
      buffer.putInt(piece);
      buffer.putInt(offset);
      buffer.putInt(length);
      return new CancelMessage(buffer, piece, offset, length);
    }

    /**
     * Human readable string representation.
     *
     * @return peer message in human readable manner
     */
    @Override
    public String toString() {
      return String.format("%s #%d (%d@%d)",
                           super.toString(),
                           this.getPiece(),
                           this.getLength(),
                           this.getOffset());
    }
  }
}
