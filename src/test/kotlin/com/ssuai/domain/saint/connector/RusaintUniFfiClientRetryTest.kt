package com.ssuai.domain.saint.connector

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

class RusaintUniFfiClientRetryTest {

    private val client = RusaintUniFfiClient()

    private val withRetry: Method = RusaintUniFfiClient::class.java
        .declaredMethods
        .first { it.name == "withRetry" }
        .also { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokeRetry(maxAttempts: Int, label: String, action: () -> T): T =
        try {
            withRetry.invoke(client, maxAttempts, label, action) as T
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }

    @Test
    fun `succeeds on first attempt without retry`() {
        val calls = AtomicInteger()
        val result = invokeRetry(3, "test") { calls.incrementAndGet(); "ok" }
        assertThat(result).isEqualTo("ok")
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `retries on transient failure and returns result on second attempt`() {
        val calls = AtomicInteger()
        val result = invokeRetry(3, "test") {
            if (calls.incrementAndGet() < 2) throw RuntimeException("transient")
            "recovered"
        }
        assertThat(result).isEqualTo("recovered")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `throws original exception after exhausting all attempts`() {
        val calls = AtomicInteger()
        assertThatThrownBy {
            invokeRetry<String>(3, "test") {
                calls.incrementAndGet()
                throw RuntimeException("always fails")
            }
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessage("always fails")
        assertThat(calls.get()).isEqualTo(3)
    }

    @Test
    fun `single attempt does not retry on failure`() {
        val calls = AtomicInteger()
        assertThatThrownBy {
            invokeRetry<String>(1, "test") {
                calls.incrementAndGet()
                throw RuntimeException("fail")
            }
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(calls.get()).isEqualTo(1)
    }
}
