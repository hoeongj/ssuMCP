package com.ssuai.domain.lms.util;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.lms.dto.LmsMaterial;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialFileFilterTests {

    @Test
    void testIsIncluded_pdfPasses() {
        LmsMaterial material = new LmsMaterial("id1", 1L, "Course", "lecture.pdf", "pdf", 100L, "Week 1", "Lecture Note");
        assertThat(MaterialFileFilter.isIncluded(material)).isTrue();
    }

    @Test
    void testIsIncluded_nullFileNameExcluded() {
        LmsMaterial material = new LmsMaterial("id1", 1L, "Course", null, "", 100L, "Week 1", "Lecture Note");
        assertThat(MaterialFileFilter.isIncluded(material)).isFalse();
    }

    @Test
    void testIsIncluded_mp4FilenameExcludedEvenIfContentTypeMissing() {
        LmsMaterial material = new LmsMaterial("id1", 1L, "Course", "lecture.mp4", "mp4", 100L, "Week 1", "Lecture Note");
        assertThat(MaterialFileFilter.isIncluded(material)).isFalse();
    }

    @Test
    void testIsIncluded_unknownExtensionExcludedByDefault() {
        LmsMaterial material = new LmsMaterial("id1", 1L, "Course", "lecture.xyz", "xyz", 100L, "Week 1", "Lecture Note");
        assertThat(MaterialFileFilter.isIncluded(material)).isFalse();
    }

    @Test
    void testIsIncluded_blacklistContentTypeExcluded() {
        // Even if extension is pdf, if content_type is everlec, exclude it
        LmsMaterial material = new LmsMaterial("id1", 1L, "Course", "lecture.pdf", "pdf", 100L, "Week 1", "Lecture Note", "everlec");
        assertThat(MaterialFileFilter.isIncluded(material)).isFalse();
    }

    @Test
    void testExtensionOf() {
        assertThat(MaterialFileFilter.extensionOf("file.PDF")).isEqualTo("pdf");
        assertThat(MaterialFileFilter.extensionOf("file.tar.gz")).isEqualTo("gz");
        assertThat(MaterialFileFilter.extensionOf("file")).isEqualTo("");
        assertThat(MaterialFileFilter.extensionOf("file.")).isEqualTo("");
        assertThat(MaterialFileFilter.extensionOf(null)).isEqualTo("");
    }
}
