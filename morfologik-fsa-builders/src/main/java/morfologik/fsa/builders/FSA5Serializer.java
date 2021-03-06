package morfologik.fsa.builders;

import static morfologik.fsa.FSAFlags.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

import morfologik.fsa.FSA;
import morfologik.fsa.FSA5;
import morfologik.fsa.FSAFlags;
import morfologik.fsa.FSAHeader;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.IntStack;

/**
 * Serializes in-memory {@link FSA} graphs to a binary format compatible with
 * Jan Daciuk's <code>fsa</code>'s package <code>FSA5</code> format.
 * 
 * <p>
 * It is possible to serialize the automaton with numbers required for perfect
 * hashing. See {@link #withNumbers()} method.
 * </p>
 * 
 * @see FSA5
 * @see FSA#read(java.io.InputStream)
 */
public final class FSA5Serializer implements FSASerializer {
  /**
   * Maximum number of bytes for a serialized arc.
   */
  private final static int MAX_ARC_SIZE = 1 + 5;

  /**
   * Maximum number of bytes for per-node data.
   */
  private final static int MAX_NODE_DATA_SIZE = 16;

  /**
   * Number of bytes for the arc's flags header (arc representation without the
   * goto address).
   */
  private final static int SIZEOF_FLAGS = 1;

  /**
   * Supported flags.
   */
  private final static EnumSet<FSAFlags> flags = EnumSet.of(NUMBERS, SEPARATORS, FLEXIBLE, STOPBIT, NEXTBIT);

  /**
   * @see FSA5#filler
   */
  public byte fillerByte = FSA5.DEFAULT_FILLER;

  /**
   * @see FSA5#annotation
   */
  public byte annotationByte = FSA5.DEFAULT_ANNOTATION;

  /**
   * <code>true</code> if we should serialize with numbers.
   * 
   * @see #withNumbers()
   */
  private boolean withNumbers;

  /**
   * A hash map of [state, offset] pairs.
   */
  private IntIntHashMap offsets = new IntIntHashMap();

  /**
   * A hash map of [state, right-language-count] pairs.
   */
  private IntIntHashMap numbers = new IntIntHashMap();

  /**
   * Serialize the automaton with the number of right-language sequences in each
   * node. This is required to implement perfect hashing. The numbering also
   * preserves the order of input sequences.
   * 
   * @return Returns the same object for easier call chaining.
   */
  public FSA5Serializer withNumbers() {
    withNumbers = true;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FSA5Serializer withFiller(byte filler) {
    this.fillerByte = filler;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public FSA5Serializer withAnnotationSeparator(byte annotationSeparator) {
    this.annotationByte = annotationSeparator;
    return this;
  }

  /**
   * Serialize root state <code>s</code> to an output stream in
   * <code>FSA5</code> format.
   * 
   * @see #withNumbers()
   * @return Returns <code>os</code> for chaining.
   */
  @Override
  public <T extends OutputStream> T serialize(final FSA fsa, T os) throws IOException {

    // Prepare space for arc offsets and linearize all the states.
    int[] linearized = linearize(fsa);

    /*
     * Calculate the number of bytes required for the node data, if
     * serializing with numbers.
     */
    int nodeDataLength = 0;
    if (withNumbers) {
      this.numbers = FSAUtils.rightLanguageForAllStates(fsa);
      int maxNumber = numbers.get(fsa.getRootNode());
      while (maxNumber > 0) {
        nodeDataLength++;
        maxNumber >>>= 8;
      }
    }

    // Calculate minimal goto length.
    int gtl = 1;
    while (true) {
      // First pass: calculate offsets of states.
      if (!emitArcs(fsa, null, linearized, gtl, nodeDataLength)) {
        gtl++;
        continue;
      }

      // Second pass: check if goto overflows anywhere.
      if (emitArcs(fsa, null, linearized, gtl, nodeDataLength))
        break;

      gtl++;
    }

    /*
     * Emit the header.
     */
    FSAHeader.write(os, FSA5.VERSION);
    os.write(fillerByte);
    os.write(annotationByte);
    os.write((nodeDataLength << 4) | gtl);

    /*
     * Emit the automaton.
     */
    boolean gtlUnchanged = emitArcs(fsa, os, linearized, gtl, nodeDataLength);
    assert gtlUnchanged : "gtl changed in the final pass.";

    return os;
  }

  /**
   * Return supported flags.
   */
  @Override
  public Set<FSAFlags> getFlags() {
    return flags;
  }

  /**
   * Linearization of states.
   */
  private int[] linearize(final FSA fsa) {
    int[] linearized = new int[0];
    int last = 0;

    BitSet visited = new BitSet();
    IntStack nodes = new IntStack();
    nodes.push(fsa.getRootNode());

    while (!nodes.isEmpty()) {
      final int node = nodes.pop();
      if (visited.get(node)) {
        continue;
      }

      if (last >= linearized.length) {
        linearized = Arrays.copyOf(linearized, linearized.length + 100000);
      }

      visited.set(node);
      linearized[last++] = node;

      for (int arc = fsa.getFirstArc(node); arc != 0; arc = fsa.getNextArc(arc)) {
        if (!fsa.isArcTerminal(arc)) {
          int target = fsa.getEndNode(arc);
          if (!visited.get(target))
            nodes.push(target);
        }
      }
    }

    return Arrays.copyOf(linearized, last);
  }

  /**
   * Update arc offsets assuming the given goto length.
   */
  private boolean emitArcs(FSA fsa, OutputStream os, int[] linearized, int gtl, int nodeDataLength) throws IOException {
    final ByteBuffer bb = ByteBuffer.allocate(Math.max(MAX_NODE_DATA_SIZE, MAX_ARC_SIZE));

    int offset = 0;

    // Add dummy terminal state.
    offset += emitNodeData(bb, os, nodeDataLength, 0);
    offset += emitArc(bb, os, gtl, 0, (byte) 0, 0);

    // Add epsilon state.
    offset += emitNodeData(bb, os, nodeDataLength, 0);
    if (fsa.getRootNode() != 0)
      offset += emitArc(bb, os, gtl, FSA5.BIT_LAST_ARC | FSA5.BIT_TARGET_NEXT, (byte) '^', 0);
    else
      offset += emitArc(bb, os, gtl, FSA5.BIT_LAST_ARC, (byte) '^', 0);

    int maxStates = linearized.length;
    for (int j = 0; j < maxStates; j++) {
      final int s = linearized[j];

      if (os == null) {
        offsets.put(s, offset);
      } else {
        assert offsets.get(s) == offset : s + " " + offsets.get(s) + " " + offset;
      }

      offset += emitNodeData(bb, os, nodeDataLength, withNumbers ? numbers.get(s) : 0);

      for (int arc = fsa.getFirstArc(s); arc != 0; arc = fsa.getNextArc(arc)) {
        int targetOffset;
        final int target;
        if (fsa.isArcTerminal(arc)) {
          targetOffset = 0;
          target = 0;
        } else {
          target = fsa.getEndNode(arc);
          targetOffset = offsets.get(target);
        }

        int flags = 0;
        if (fsa.isArcFinal(arc)) {
          flags |= FSA5.BIT_FINAL_ARC;
        }

        if (fsa.getNextArc(arc) == 0) {
          flags |= FSA5.BIT_LAST_ARC;

          if (j + 1 < maxStates && target == linearized[j + 1] && targetOffset != 0) {
            flags |= FSA5.BIT_TARGET_NEXT;
            targetOffset = 0;
          }
        }

        int bytes = emitArc(bb, os, gtl, flags, fsa.getArcLabel(arc), targetOffset);
        if (bytes < 0)
          // gtl too small. interrupt eagerly.
          return false;

        offset += bytes;
      }
    }

    return true;
  }

  /** */
  private int emitArc(ByteBuffer bb, OutputStream os, int gtl, int flags, byte label, int targetOffset)
      throws IOException {
    int arcBytes = (flags & FSA5.BIT_TARGET_NEXT) != 0 ? SIZEOF_FLAGS : gtl;

    flags |= (targetOffset << 3);
    bb.put(label);
    for (int b = 0; b < arcBytes; b++) {
      bb.put((byte) flags);
      flags >>>= 8;
    }

    if (flags != 0) {
      // gtl too small. interrupt eagerly.
      return -1;
    }

    bb.flip();
    int bytes = bb.remaining();
    if (os != null) {
      os.write(bb.array(), bb.position(), bb.remaining());
    }
    bb.clear();

    return bytes;
  }

  /** */
  private int emitNodeData(ByteBuffer bb, OutputStream os, int nodeDataLength, int number) throws IOException {
    if (nodeDataLength > 0 && os != null) {
      for (int i = 0; i < nodeDataLength; i++) {
        bb.put((byte) number);
        number >>>= 8;
      }

      bb.flip();
      os.write(bb.array(), bb.position(), bb.remaining());
      bb.clear();
    }

    return nodeDataLength;
  }
}
