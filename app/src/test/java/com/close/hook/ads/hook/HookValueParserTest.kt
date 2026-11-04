package com.close.hook.ads.hook

import com.close.hook.ads.hook.util.HookValueParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookValueParserTest {
    private val loader = javaClass.classLoader!!

    @Test
    fun resolvesPrimitivesJavaArraysAndJvmDescriptorsWithoutDroppingParameters() {
        val types = HookValueParser.parameterTypes(listOf("int", "java.lang.String[]", "long[][]", "[Z"), loader)
        assertArrayEquals(arrayOf(Int::class.javaPrimitiveType, arrayOf<String>().javaClass,
            arrayOf(longArrayOf()).javaClass, booleanArrayOf().javaClass), types)
        assertTrue(HookValueParser.parameterTypes(null, loader).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unresolvedTypesRejectTheWholeSignature() {
        HookValueParser.parameterTypes(listOf("int", "missing.ClassThatMustNotBecomeAZeroArgOverload"), loader)
    }

    @Test(expected = IllegalArgumentException::class)
    fun voidCannotBeAParameter() {
        HookValueParser.parameterTypes(listOf("void"), loader)
    }

    @Test
    fun numericLookingStringsRemainStringsAndLongsAreBoxedAsLongs() {
        assertEquals("1", HookValueParser.coerce("1", String::class.java))
        assertEquals("false", HookValueParser.coerce("false", CharSequence::class.java))
        val value = HookValueParser.coerce("1", Long::class.javaPrimitiveType!!)
        assertTrue(value is Long)
        assertEquals(1L, value)
        assertEquals(0.12345678901234568,
            HookValueParser.coerce("0.12345678901234568", Double::class.javaPrimitiveType!!))
    }

    @Test
    fun nullProducesValidPrimitiveDefaultsButKeepsReferenceNulls() {
        assertEquals(0L, HookValueParser.coerce("null", Long::class.javaPrimitiveType!!))
        assertEquals(false, HookValueParser.coerce(null, Boolean::class.javaPrimitiveType!!))
        assertEquals('\u0000', HookValueParser.coerce(null, Char::class.javaPrimitiveType!!))
        assertEquals(0.toByte(), HookValueParser.coerce(null, Byte::class.javaPrimitiveType!!))
        assertNull(HookValueParser.coerce("null", String::class.java))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeNumbersAreRejected() {
        HookValueParser.coerce("128", Byte::class.javaPrimitiveType!!)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidReferenceValuesAreRejectedBeforeTheyReachTheTarget() {
        HookValueParser.coerce("anything", Runnable::class.java)
    }
}
