package io.voxtype.mobile;

import java.util.zip.Deflater;
import okio.Buffer;
import okio.ByteString;

/** Independent zlib streams keep resume checkpoints in decoded PCM bytes. */
final class LosslessPcm {
  static final String ENCODING = "pcm_s16le_zlib_v1";
  static final int MAX_PCM_BYTES = 65522;

  static ByteString encode(ByteString pcm) {
    int size = pcm.size();
    if (size == 0 || size % 2 != 0 || size > MAX_PCM_BYTES) {
      throw new IllegalArgumentException("Invalid PCM frame");
    }
    Deflater deflater = new Deflater(1); // zlib wrapper and checksum, independent per frame
    try {
      deflater.setInput(pcm.toByteArray());
      deflater.finish();
      // Only keep compressed output if the whole stream fits below the raw size.
      // Incompressible frames have a raw fallback without growing this buffer.
      byte[] compressed = new byte[size];
      int length = deflater.deflate(compressed);
      Buffer envelope = new Buffer();
      if (deflater.finished() && length < size) {
        return envelope.writeByte(1).writeInt(size).write(compressed, 0, length).readByteString();
      }
      return envelope.writeByte(0).writeInt(size).write(pcm).readByteString();
    } finally {
      deflater.end();
    }
  }

  private LosslessPcm() {}
}
