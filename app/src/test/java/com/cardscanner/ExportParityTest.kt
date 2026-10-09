package com.cardscanner

import com.cardscanner.inventory.Finish
import com.cardscanner.inventory.InventoryEntry
import com.cardscanner.inventory.writeCsv
import com.cardscanner.inventory.writeMoxfield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CSV export is the scanner server's collection CSV (scanner-server games/base.py:
 * write_collection_csv, which its collection page imports): export_reference.json is made by
 * export_reference.py from the same rows.
 */
class ExportParityTest {
    private val reference = JSONObject(javaClass.getResource("/export_reference.json")!!.readText(Charsets.UTF_8))
    private val entries = reference.getJSONArray("rows").let { rows ->
        (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            // The app keeps set codes in capitals; the server's are small letters
            InventoryEntry(i.toLong(), if (r.isNull("card_id")) null else r.getString("card_id"), r.getString("name"),
                r.getString("set_name"), r.getString("set_code").uppercase(), r.getString("number"),
                r.getString("rarity"), r.getString("type_line"), r.getString("mana_cost"), r.getString("colors"),
                r.getString("color_identity"), r.getDouble("price"), r.getInt("quantity"), r.getString("condition"),
                Finish.of(r.getString("finish")), r.getString("timestamp"), null)
        }
    }

    @Test
    fun csvMatchesPython() {
        assertEquals(reference.getString("csv"), writeCsv(entries))
    }

    @Test
    fun moxfieldUsesSetCodes() {
        val lines = writeMoxfield(entries).trimEnd().lines()
        assertEquals("Count,Name,Edition,Condition,Language,Foil,Collector Number,Purchase Price,Tag", lines[0].trim())
        assertEquals("1,\"Thorin, King of Durin's Folk\",hoc,Near Mint,English,,3,0,", lines[1].trim())
        assertEquals("3,\"The \"\"Quoted\"\" Card\",tst,Lightly Played,English,foil,12a,0,", lines[2].trim())
        assertEquals("2,Smaug,hob,Near Mint,English,foil,109,0,", lines[3].trim())
    }
}
