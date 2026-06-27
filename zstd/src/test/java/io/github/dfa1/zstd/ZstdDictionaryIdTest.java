package io.github.dfa1.zstd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ZstdDictionaryIdTest {

    @Test
    void ofZeroIsTheNoneSingleton() {
        // When wrapping the zero sentinel
        ZstdDictionaryId sut = ZstdDictionaryId.of(0);

        // Then it is the shared NONE and reports no id
        assertThat(sut).isEqualTo(ZstdDictionaryId.NONE);
        assertThat(sut.isPresent()).isFalse();
        assertThat(sut.value()).isZero();
    }

    @Test
    void ofNonZeroKeepsTheRawValueAndIsPresent() {
        // When wrapping a positive id
        ZstdDictionaryId sut = ZstdDictionaryId.of(42);

        // Then it is present and exposes the value
        assertThat(sut.isPresent()).isTrue();
        assertThat(sut.raw()).isEqualTo(42);
        assertThat(sut.value()).isEqualTo(42L);
    }

    @Test
    void valueReadsTheRawPatternAsUnsigned() {
        // Given an id whose top bit is set (negative as a signed int)
        ZstdDictionaryId sut = ZstdDictionaryId.of(0xFFFFFFFF);

        // Then raw stays the signed pattern while value widens without sign extension
        assertThat(sut.raw()).isEqualTo(-1);
        assertThat(sut.value()).isEqualTo(4_294_967_295L);
        assertThat(sut.isPresent()).isTrue();
    }

    @Test
    void equalIdsAreEqual() {
        // Then records with the same raw value compare equal
        assertThat(ZstdDictionaryId.of(7)).isEqualTo(ZstdDictionaryId.of(7));
        assertThat(ZstdDictionaryId.of(7)).isNotEqualTo(ZstdDictionaryId.of(8));
    }
}
