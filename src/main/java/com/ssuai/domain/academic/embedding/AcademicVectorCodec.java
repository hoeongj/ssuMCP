package com.ssuai.domain.academic.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Encodes an embedding vector as base64 of its little-endian float32 bytes, so a
 * 768-d vector persists as ~4 KB of portable TEXT. The round-trip is exact (no
 * decimal formatting), and storing bytes avoids a pgvector-typed column.
 */
final class AcademicVectorCodec {

    private AcademicVectorCodec() {
    }

    static String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
