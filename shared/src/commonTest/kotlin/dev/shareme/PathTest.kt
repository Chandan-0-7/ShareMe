package dev.shareme

import kotlin.test.*

class PathTest {
    @Test fun preservesPortableFolderNames() {
        assertEquals("Photos/旅行/café.jpg", validateRelativePath("Photos/旅行/café.jpg"))
    }
    @Test fun rejectsTraversalAndWindowsAliases() {
        listOf("../secret", "/etc/passwd", "C:/data", "a/../b", "a\\b", "a//b", "CON.txt", "a/NUL", "trailing.", "space ", "bad\u0000file").forEach {
            assertFailsWith<IllegalArgumentException>(it) { validateRelativePath(it) }
        }
    }
}
