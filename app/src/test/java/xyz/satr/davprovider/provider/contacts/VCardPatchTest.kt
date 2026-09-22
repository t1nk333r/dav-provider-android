package xyz.satr.davprovider.provider.contacts

import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one edited contact puts on the wire.
 *
 * The ownership rule is per property class: a class this app maps is regenerated from the rows, and a
 * class it does not map is copied through as the server wrote it. Both halves of that are quiet when
 * they go wrong — a phone number the user deleted that comes back looks like an edit that did not
 * take, and a dropped `X-ABLabel` is invisible on the phone and gone on the server — so the decision
 * is a pure function of the source text and the rows, and is tested here.
 */
class VCardPatchTest {

    private val now: Instant = Instant.parse("2026-09-22T06:00:00Z")

    @Test
    fun `a property class the rows do not own survives an edit`() {
        val source = card(
            "FN:Jane Doe",
            "N:Doe;Jane;;;",
            "TEL;TYPE=WORK,VOICE;PREF=1:+1 555 0100",
            "GEO:1.5,2.5",
            "X-ABLabel:castle",
            "item1.ADR;TYPE=HOME:;;1 Main St;Town;CA;90210;US",
            "item1.X-ABLabel:home",
        )

        val text = patch(source, rowsOf(source)).text

        assertTrue("GEO is the server's to keep", text.contains("GEO:1.5,2.5"))
        assertTrue("so is an arbitrary X- property", text.contains("X-ABLabel:castle"))
        assertTrue("a grouped property keeps its group", text.contains("item1.ADR"))
        assertTrue("and the label beside it", text.contains("item1.X-ABLabel:home"))
    }

    @Test
    fun `a row the editor deleted takes its own property and nothing else`() {
        val source = card(
            "FN:Jane Doe",
            "N:Doe;Jane;;;",
            "TEL;TYPE=WORK,VOICE;PREF=1:+1 555 0100",
            "TEL;TYPE=HOME:+1 555 0130",
            "X-ABLabel:castle",
        )
        val rows = rowsOf(source).filterNot { it.handle == "TEL:1" }

        val text = patch(source, rows).text

        assertTrue("the deleted number is gone" + "\n" + text, !text.contains("555 0130"))
        assertTrue("the one next to it is not", text.contains("555 0100"))
        assertTrue("and neither is the property beside it", text.contains("X-ABLabel:castle"))
        assertEquals("exactly one TEL is left", 1, telephoneCount(text))
    }

    @Test
    fun `a changed value is rewritten and keeps the parameters the rows do not own`() {
        val source = card("FN:Jane Doe", "TEL;TYPE=WORK,VOICE;PREF=1:+1 555 0100")
        val rows = rowsOf(source).map { row ->
            if (row.handle == "TEL:0") row.copy(Phone.NUMBER, "+1 555 0199") else row
        }

        val text = patch(source, rows).text

        assertTrue("the new number is on the wire" + "\n" + text, text.contains("+1 555 0199"))
        assertTrue("the old one is not", !text.contains("555 0100"))
        assertTrue("a value-only change keeps the TYPE list as the server spelled it", text.contains("TYPE=WORK,VOICE"))
        assertTrue("and its PREF", text.contains("pref"))
    }

    @Test
    fun `a row the editor added without a handle becomes a property of its own`() {
        val source = card("FN:Jane Doe", "TEL;TYPE=WORK:+1 555 0100")
        val rows = rowsOf(source) +
            DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to "+1 555 0177", Phone.TYPE to "2"), null)

        val text = patch(source, rows).text

        assertTrue("the added number is uploaded" + "\n" + text, text.contains("+1 555 0177"))
        assertTrue("and the one it did not touch is still there", text.contains("555 0100"))
        assertEquals("one property per row, not one per row plus the source", 2, telephoneCount(text))
    }

    @Test
    fun `a birthday the provider cannot hold is never mistaken for a deleted one`() {
        val source = card("FN:Jane Doe", "N:Doe;Jane;;;", "BDAY:1985-04")

        val text = patch(source, rowsOf(source)).text

        // `1985-04` has no day, so the read path derives no row from it: the user never saw it on the
        // phone and therefore cannot have deleted it. It survives this edit, which did not touch it.
        assertTrue("a partial birthday survives" + "\n" + text, text.contains("BDAY:1985-04"))
    }

    @Test
    fun `a source version is written back as itself`() {
        val source = card("KIND:individual", "FN:Jane", "N:Doe;Jane;;;", version = "4.0")

        val text = patch(source, rowsOf(source)).text

        // ez-vcard drops a property the target version does not have, so writing this card as 3.0 would
        // quietly lose KIND.
        assertTrue("a 4.0 card stays 4.0" + "\n" + text, text.contains("VERSION:4.0"))
        assertTrue("which is what keeps a 4.0-only property", text.contains("KIND:individual"))
    }

    @Test
    fun `only the memberships the rows can speak for are dropped`() {
        val source = card("FN:Jane", "CATEGORIES:Friends,Unknown")
        val rows = rowsOf(source).filterNot { it.mimeType == GroupMembership.CONTENT_ITEM_TYPE }

        // `Unknown` names no group in this Collection, so the read path never resolved it and the user
        // never saw it; `Friends` names one, so a group the contact is no longer in is a removal.
        val text = patch(source, rows, categories = CategoryEdit(emptyList(), setOf("Friends"))).text

        assertTrue("the unresolvable value survives" + "\n" + text, text.contains("CATEGORIES:Unknown"))
        assertTrue("the removed membership is gone" + "\n" + text, !text.contains("Friends"))
    }

    @Test
    fun `a photo the rows did not touch is copied through as the server spelled it`() {
        val source = card("FN:Jane", "PHOTO;VALUE=uri:https://dav.example/photo.jpg")

        val text = patch(source, rowsOf(source), photo = PhotoEdit.Kept).text

        // The read path may have fetched that link, but what goes back is the server's own reference:
        // the bytes are never re-encoded on an edit that did not touch the photo.
        assertTrue("the link survives" + "\n" + text, text.contains("VALUE=uri:https://dav.example/photo.jpg"))
        assertTrue("and no image was re-encoded into it", !text.contains("ENCODING=b"))
    }

    @Test
    fun `a photo row that changed is rebuilt from its bytes`() {
        val source = card("FN:Jane", "PHOTO;VALUE=uri:https://dav.example/photo.jpg")

        val text = patch(source, rowsOf(source), photo = PhotoEdit.Rebuilt(ByteArray(4) { 7 })).text

        assertTrue("the image is inline" + "\n" + text, text.contains("PHOTO;ENCODING=b;TYPE=jpeg"))
        assertTrue("and the link is not", !text.contains("dav.example/photo.jpg"))
    }

    @Test
    fun `a name row that is gone empties the name rather than resurrecting the server's`() {
        val source = card("FN:Jane Doe", "N:Doe;Jane;;;", "TEL:+1 555 0100")
        val rows = rowsOf(source).filterNot { it.mimeType == StructuredName.CONTENT_ITEM_TYPE }

        val text = patch(source, rows).text

        assertTrue("the old name is gone" + "\n" + text, !text.contains("Jane") && !text.contains("Doe"))
        assertTrue("the number it did not touch is not", text.contains("555 0100"))
    }

    @Test
    fun `a created contact is a vCard 3 that reads back as what the rows said`() {
        val rows = listOf(
            DataRow(
                StructuredName.CONTENT_ITEM_TYPE,
                mapOf(
                    StructuredName.GIVEN_NAME to "Jane",
                    StructuredName.FAMILY_NAME to "Doe",
                    StructuredName.DISPLAY_NAME to "Jane Doe",
                ),
            ),
            DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to "+1 555 0100", Phone.TYPE to "2"), null),
        )

        val patched = patchContact(null, rows, PhotoEdit.Dropped, CategoryEdit(emptyList(), emptySet()), MINTED, now)

        assertNotNull("a create has bytes", patched)
        assertEquals("the minted UID is the one the bytes carry", MINTED, patched!!.uid)
        assertTrue("a create is 3.0, the version every server must accept", patched.text.contains("VERSION:3.0"))
        assertTrue("and it carries the UID it was given", patched.text.contains("UID:$MINTED"))

        val mapped = mapVCard(readVCard(patched.text)!!)
        assertEquals("the given name reads back", "Jane", mapped.rows.first().values[StructuredName.GIVEN_NAME])
        val number = mapped.rows.single { it.mimeType == Phone.CONTENT_ITEM_TYPE }
        assertEquals("the number reads back", "+1 555 0100", number.values[Phone.NUMBER])
        assertEquals("with the type the row had", "2", number.values[Phone.TYPE])
    }

    @Test
    fun `a created contact with nothing typed into its name is named after what it has`() {
        val rows = listOf(DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to "+1 555 0100", Phone.TYPE to "1"), null))

        val text = patchContact(null, rows, PhotoEdit.Dropped, CategoryEdit(emptyList(), emptySet()), MINTED, now)!!.text

        // `FN` is required in both versions this app writes, and the Contacts app shows a contact with
        // no name as its first number.
        assertTrue("FN carries the number" + "\n" + text, text.contains("FN:+1 555 0100"))
        assertTrue("N is written even with nothing in it", text.contains("N:"))
        assertNotNull("and the card is still parseable", readVCard(text))
    }

    @Test
    fun `a source that does not parse yields no bytes at all`() {
        // Not a card built from the rows: the source is what holds everything the rows cannot, and an
        // upload of the rows alone would delete every property this app does not map.
        assertNull(patchContact("not a vCard", emptyList(), PhotoEdit.Kept, CategoryEdit(emptyList(), emptySet()), MINTED, now))
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * The rows the read path would have written for [text], shaped the way a query returns them: a
     * cursor gives every `data` column, the ones the kind does not use as null, and the patcher has to
     * read a value the user cleared exactly as it reads one that never applied.
     */
    private fun rowsOf(text: String): List<DataRow> {
        val vcard = readVCard(text)
        assertNotNull("the fixture parses", vcard)
        return mapVCard(vcard!!).rows.map { row ->
            DataRow(row.mimeType, DATA_COLUMNS.associateWith { row.values[it] }, row.handle)
        }
    }

    private fun patch(
        source: String?,
        rows: List<DataRow>,
        categories: CategoryEdit = CategoryEdit(emptyList(), emptySet()),
        photo: PhotoEdit = PhotoEdit.Kept,
    ): ContactBytes {
        val bytes = patchContact(source, rows, photo, categories, null, now)
        assertNotNull("the patch produced bytes", bytes)
        return bytes!!
    }

    private fun DataRow.copy(column: String, value: String): DataRow =
        DataRow(mimeType, values + (column to value), handle)

    /** A vCard with the CRLF line endings a server sends, so the fixtures read like real bytes. */
    private fun card(vararg lines: String, version: String = "3.0"): String =
        (listOf("BEGIN:VCARD", "VERSION:$version") + lines + "END:VCARD").joinToString("\r\n", postfix = "\r\n")

    private fun telephoneCount(text: String): Int =
        Regex("(?m)^(item\\d+\\.)?TEL").findAll(text).count()

    private companion object {
        val DATA_COLUMNS = listOf(
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
            Data.DATA4,
            Data.DATA5,
            Data.DATA6,
            Data.DATA7,
            Data.DATA8,
            Data.DATA9,
            Data.DATA10,
        )

        /**
         * A fixture UID. The patcher is handed one rather than minting it: minting and storing it on the
         * row is the mapper's job, and it has happened by the time bytes exist.
         */
        const val MINTED = "1f0d5f2a-0000-4000-8000-000000000001"
    }
}
