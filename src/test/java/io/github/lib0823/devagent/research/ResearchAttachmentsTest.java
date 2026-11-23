package io.github.lib0823.devagent.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchAttachmentsTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};
    private static final byte[] JPG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1};

    @Test
    void recognisesImagesByContentNotName() {
        assertEquals("png", ResearchAttachments.imageType(PNG));
        assertEquals("jpg", ResearchAttachments.imageType(JPG));
        assertEquals("gif", ResearchAttachments.imageType("GIF89a......".getBytes()));
        assertEquals("webp", ResearchAttachments.imageType("RIFF\0\0\0\0WEBPVP8 ".getBytes()));
        assertNull(ResearchAttachments.imageType("<svg onload=alert(1)>....".getBytes()));
        assertNull(ResearchAttachments.imageType("#!/bin/sh\nrm -rf /\n".getBytes()));
        assertNull(ResearchAttachments.imageType(new byte[3]));
    }

    @Test
    void stagedImagesMoveIntoTheJobFolderOnClaim(@TempDir Path dir) throws Exception {
        ResearchAttachments a = new ResearchAttachments(dir);
        String s1 = a.stage(PNG);
        String s2 = a.stage(JPG);
        assertTrue(s1.endsWith(".png") && s2.endsWith(".jpg"));
        assertEquals(List.of(s1, s2), a.check(List.of(s1, " " + s2, s1, "")));   // 중복·빈 값은 정리

        List<String> names = a.claim(7, List.of(s1, s2));
        assertEquals(List.of("1.png", "2.jpg"), names);
        assertNotNull(a.file(7, "1.png"));
        assertTrue(Files.notExists(dir.resolve("tmp").resolve(s1)));           // 임시 파일은 옮겨졌다

        ResearchJob j = new ResearchJob();
        j.setId(7);
        j.setAttachments("1.png,2.jpg");
        String note = a.promptNote(j);
        assertTrue(note.contains("Read 도구") && note.contains("/7/1.png") && note.contains("/7/2.jpg"), note);
        j.setAttachments(null);
        assertEquals("", a.promptNote(j));
    }

    @Test
    void listsAndDeletesJobFolders(@TempDir Path dir) {
        ResearchAttachments a = new ResearchAttachments(dir);
        assertTrue(a.jobIds().isEmpty());
        a.claim(7, List.of(a.stage(PNG)));
        a.claim(12, List.of(a.stage(PNG), a.stage(JPG)));
        a.stage(PNG);                                          // 접수 전 임시 파일(tmp)은 질의 폴더가 아니다
        assertEquals(List.of(7L, 12L), a.jobIds().stream().sorted().toList());
        assertTrue(a.delete(12));
        assertNull(a.file(12, "1.png"));
        assertTrue(Files.notExists(dir.resolve("12")));
        assertEquals(List.of(7L), a.jobIds());
        assertNotNull(a.file(7, "1.png"));                     // 다른 질의 것은 그대로
        assertTrue(a.delete(999));                             // 없는 폴더는 이미 정리된 것으로
    }

    @Test
    void rejectsNonImagesForgedNamesAndTooMany(@TempDir Path dir) {
        ResearchAttachments a = new ResearchAttachments(dir);
        assertThrows(IllegalArgumentException.class, () -> a.stage("hello world, not an image".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> a.stage(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> a.check(List.of("../../.env")));
        assertThrows(IllegalArgumentException.class, () -> a.check(List.of("00000000-0000-0000-0000-000000000000.png")));   // 올린 적 없는 이름
        assertNull(a.file(7, "../1.png"));
        assertNull(a.file(7, "1.exe"));
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i <= ResearchAttachments.MAX_FILES; i++) {
            many.add(a.stage(PNG));
        }
        assertThrows(IllegalArgumentException.class, () -> a.check(many));
    }
}
