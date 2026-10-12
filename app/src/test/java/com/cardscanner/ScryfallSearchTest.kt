package com.cardscanner

import com.cardscanner.scryfall.Scryfall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ScryfallSearchTest {
    private fun card(id: String, set: String) = JSONObject()
        .put("id", id).put("name", "Sol Ring").put("set", set).put("collector_number", "1")

    private fun client(respond: (okhttp3.Request) -> JSONObject) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals(Scryfall.USER_AGENT, request.header("User-Agent"))
        assertEquals("application/json", request.header("Accept"))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respond(request).toString().toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test
    fun printingSearchIncludesEveryPageAndPrioritizesRequestedSet() {
        val pages = ArrayList<String>()
        val http = client { request ->
            pages.add(request.url.toString())
            when {
                request.url.encodedPath == "/cards/named" -> card("named", "new")
                request.url.queryParameter("page") == "2" -> JSONObject().put("data", JSONArray().put(card("older", "old"))).put("has_more", false)
                else -> JSONObject().put("data", JSONArray().put(card("newer", "new")))
                    .put("has_more", true).put("next_page", "https://api.scryfall.com/cards/search?page=2")
            }
        }
        assertEquals(listOf("older", "newer"), Scryfall(http).printings("Sol Ring", "old").map { it.id })
        assertEquals(3, pages.size)
    }

    @Test
    fun repeatedNextPageFailsInsteadOfLoopingForever() {
        val http = client { request ->
            if (request.url.encodedPath == "/cards/named") card("named", "new")
            else JSONObject().put("data", JSONArray()).put("has_more", true)
                .put("next_page", "https://api.scryfall.com/cards/search?page=2")
        }
        assertThrows(IOException::class.java) { Scryfall(http).printings("Sol Ring") }
    }

    @Test
    fun paginationRejectsUnexpectedHosts() {
        val http = client { request ->
            if (request.url.encodedPath == "/cards/named") card("named", "new")
            else JSONObject().put("data", JSONArray()).put("has_more", true)
                .put("next_page", "https://other.example/cards/search?page=2")
        }
        assertThrows(IOException::class.java) { Scryfall(http).printings("Sol Ring") }
    }
}
