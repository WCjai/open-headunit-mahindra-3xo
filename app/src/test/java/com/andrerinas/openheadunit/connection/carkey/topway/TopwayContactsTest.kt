package com.andrerinas.openheadunit.connection.carkey.topway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopwayContactsTest {

    @Test
    fun `a number matches with or without its country code and spacing`() {
        val stored = TopwayContacts.key("+91 98765 43210")
        assertEquals(stored, TopwayContacts.key("09876543210"))
        assertEquals(stored, TopwayContacts.key("9876543210"))
        assertEquals(stored, TopwayContacts.key("(98765) 43-210"))
    }

    @Test
    fun `different numbers do not match`() {
        assertEquals(false, TopwayContacts.key("9876543210") == TopwayContacts.key("9876543211"))
    }

    @Test
    fun `short codes match only themselves`() {
        assertEquals("12345", TopwayContacts.key("12345"))
        assertNull(TopwayContacts.key("1234"))
        assertNull(TopwayContacts.key(""))
        assertNull(TopwayContacts.key(null))
    }
}
