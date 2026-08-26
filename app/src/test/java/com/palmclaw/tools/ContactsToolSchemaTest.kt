package com.palmclaw.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsToolSchemaTest {
    private val validator = ToolArgumentsValidator()

    @Test
    fun `legacy scalar contact fields are not accepted`() {
        val arguments = Json.parseToJsonElement(
            """{"action":"update_contact","contact_id":1,"phone":"123"}"""
        ).jsonObject

        val errors = validator.validate(contactsToolSchema(), arguments)

        assertTrue(errors.any { it.contains("arguments.phone is not allowed") })
    }

    @Test
    fun `nested update items require stable data ids`() {
        val arguments = Json.parseToJsonElement(
            """{"action":"update_contact","contact_id":1,"update":{"phones":[{"number":"123"}]}}"""
        ).jsonObject

        val errors = validator.validate(contactsToolSchema(), arguments)

        assertTrue(errors.any { it.contains("data_id is required") })
    }

    @Test
    fun `typed structured contact payload is accepted`() {
        val arguments = Json.parseToJsonElement(
            """{
              "action":"create_contact",
              "contact":{
                "name":{"given":"Ada","family":"Lovelace"},
                "phones":[{"number":"123","type":"custom","label":"Lab"}],
                "addresses":[{"street":"1 Computing Way","city":"London","type":"work"}],
                "events":[{"date":"--12-10","type":"birthday"}]
              }
            }""".trimIndent()
        ).jsonObject

        val errors = validator.validate(contactsToolSchema(), arguments)

        assertTrue(errors.joinToString(), errors.isEmpty())
    }
}
