package app.talevane.reader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedBookFilesTest {
    @Test
    fun modernDocxIsRecognizedByExtensionEvenWithLegacyMime() {
        assertTrue(
            SupportedBookFiles.isDeclaredDocx(
                "Base_consolidada.docx",
                SupportedBookFiles.LEGACY_WORD_MIME
            )
        )
    }

    @Test
    fun legacyWordMimeWithoutExtensionIsInspectedInsteadOfRejectedImmediately() {
        assertTrue(
            SupportedBookFiles.shouldInspectAsPossibleDocx(
                "Base_consolidada",
                SupportedBookFiles.LEGACY_WORD_MIME
            )
        )
    }

    @Test
    fun oldDocIsNotMistakenForDeclaredDocx() {
        assertFalse(
            SupportedBookFiles.isDeclaredDocx(
                "Base_consolidada.doc",
                SupportedBookFiles.LEGACY_WORD_MIME
            )
        )
        assertTrue(
            SupportedBookFiles.isLegacyDoc(
                "Base_consolidada.doc",
                SupportedBookFiles.LEGACY_WORD_MIME
            )
        )
    }

    @Test
    fun genericBinaryFromOldProviderIsEligibleForDocxPackageInspection() {
        assertTrue(
            SupportedBookFiles.shouldInspectAsPossibleDocx(
                "documento",
                SupportedBookFiles.GENERIC_BINARY_MIME
            )
        )
    }
}
