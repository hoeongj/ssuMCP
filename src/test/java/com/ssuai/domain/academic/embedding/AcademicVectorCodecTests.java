package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AcademicVectorCodecTests {

    @Test
    void roundTripsVectorExactly() {
        float[] vector = {0.0f, 1.0f, -0.5f, 0.123456f, -0.987654f};

        float[] restored = AcademicVectorCodec.decode(AcademicVectorCodec.encode(vector));

        // float32 -> bytes -> base64 -> bytes -> float32 is lossless.
        assertThat(restored).containsExactly(vector);
    }

    @Test
    void roundTripsEmptyVector() {
        assertThat(AcademicVectorCodec.decode(AcademicVectorCodec.encode(new float[0]))).isEmpty();
    }
}
