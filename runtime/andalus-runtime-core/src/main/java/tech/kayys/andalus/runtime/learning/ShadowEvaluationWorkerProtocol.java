package tech.kayys.andalus.runtime.learning;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Small, versioned, bounded binary envelope for the isolated shadow worker transport.
 *
 * The digest detects truncation/corruption; it is not an authentication mechanism. Production
 * transports MUST additionally authenticate the worker endpoint and independently verify
 * evaluator artifact digests inside the worker. Payloads are opaque version-specific bytes;
 * this class does not deserialize them.
 */
public final class ShadowEvaluationWorkerProtocol {
    private static final int MAGIC = 0x414E4453; // ANDS
    public static final short VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    /** Fixed envelope size: magic + version + type + UUID + payload length + SHA-256 digest. */
    public static final int FRAME_OVERHEAD_BYTES = 4 + 2 + 1 + 16 + 4 + 32;
    public static final int MAX_FRAME_BYTES = MAX_PAYLOAD_BYTES + FRAME_OVERHEAD_BYTES;
    private static final int DIGEST_BYTES = 32;

    public enum MessageType {
        EXECUTE_REQUEST(1), EXECUTE_RESPONSE(2), EXECUTE_FAILURE(3);
        private final int wire;
        MessageType(int wire) { this.wire = wire; }
        int wire() { return wire; }
        static MessageType fromWire(int value) throws IOException {
            for (MessageType type : values()) if (type.wire == value) return type;
            throw new IOException("unsupported shadow worker message type");
        }
    }

    public record Frame(UUID requestId, MessageType type, byte[] payload) {
        public Frame {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("worker payload exceeds protocol limit");
            }
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private ShadowEvaluationWorkerProtocol() { }

    public static byte[] encode(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] payload = frame.payload();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(FRAME_OVERHEAD_BYTES + payload.length);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeByte(frame.type().wire());
            out.writeLong(frame.requestId().getMostSignificantBits());
            out.writeLong(frame.requestId().getLeastSignificantBits());
            out.writeInt(payload.length);
            out.write(sha256(payload));
            out.write(payload);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory worker frame encoding failed", impossible);
        }
    }

    public static Frame decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length < FRAME_OVERHEAD_BYTES) {
            throw new IOException("truncated shadow worker frame");
        }
        if (encoded.length > MAX_FRAME_BYTES) {
            throw new IOException("shadow worker frame exceeds protocol limit");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid shadow worker frame magic");
            short version = in.readShort();
            if (version != VERSION) throw new IOException("unsupported shadow worker protocol version");
            MessageType type = MessageType.fromWire(in.readUnsignedByte());
            UUID requestId = new UUID(in.readLong(), in.readLong());
            int length = in.readInt();
            if (length < 0 || length > MAX_PAYLOAD_BYTES) throw new IOException("invalid shadow worker payload length");
            byte[] expectedDigest = in.readNBytes(DIGEST_BYTES);
            if (expectedDigest.length != DIGEST_BYTES) throw new IOException("truncated shadow worker digest");
            byte[] payload = in.readNBytes(length);
            if (payload.length != length) throw new IOException("truncated shadow worker payload");
            if (in.read() != -1) throw new IOException("trailing bytes after shadow worker frame");
            if (!MessageDigest.isEqual(expectedDigest, sha256(payload))) {
                throw new IOException("shadow worker payload digest mismatch");
            }
            return new Frame(requestId, type, payload);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
