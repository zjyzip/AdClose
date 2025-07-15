package com.close.hook.ads.util

import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import java.util.regex.Pattern

object ClipboardHookParser {

    private val METHOD_NO_PARAM_PATTERN = Pattern.compile("L([\\w/$]+);->([a-zA-Z_\\$][a-zA-Z0-9_\\$]*)\\(\\)([ZBCSIFJDV]|L[\\w/$]+;|\\[+(?:[ZBCSIFJD]|L[\\w/$]+;))")
    private val METHOD_WITH_PARAMS_PATTERN = Pattern.compile("L([\\w/$]+);->([a-zA-Z_\\$<>][a-zA-Z0-9_\\$]*)\\((.*)\\)([ZBCSIFJDV]|L[\\w/$]+;|\\[+(?:[ZBCSIFJD]|L[\\w/$]+;))")
    private val FIELD_PATTERN = Pattern.compile("L([\\w/$]+);->([a-zA-Z_\\$][a-zA-Z0-9_\\$]*):([ZBCSIFJD]|L[\\w/$]+;|\\[+(?:[ZBCSIFJD]|L[\\w/$]+;))")

    fun parseClipboardContent(content: String, targetPackageName: String? = null): CustomHookInfo? {
        val methodNoParamMatcher = METHOD_NO_PARAM_PATTERN.matcher(content)
        if (methodNoParamMatcher.matches()) {
            val className = methodNoParamMatcher.group(1)?.replace('/', '.') ?: ""
            val methodName = methodNoParamMatcher.group(2) ?: ""
            val returnTypeDalvik = methodNoParamMatcher.group(3) ?: ""
            val returnValue = dalvikTypeToJavaType(returnTypeDalvik)

            return CustomHookInfo(
                className = className,
                methodNames = listOf(methodName),
                returnValue = if (returnValue == "void") null else returnValue,
                hookMethodType = HookMethodType.HOOK_MULTIPLE_METHODS,
                packageName = targetPackageName,
                hookPoint = "after"
            )
        }

        val methodWithParamsMatcher = METHOD_WITH_PARAMS_PATTERN.matcher(content)
        if (methodWithParamsMatcher.matches()) {
            val className = methodWithParamsMatcher.group(1)?.replace('/', '.') ?: ""
            val methodName = methodWithParamsMatcher.group(2) ?: ""
            val paramsDalvik = methodWithParamsMatcher.group(3) ?: ""
            val returnTypeDalvik = methodWithParamsMatcher.group(4) ?: ""

            val parameterTypes = parseMethodParameters(paramsDalvik)
            val returnValue = dalvikTypeToJavaType(returnTypeDalvik)

            return CustomHookInfo(
                className = className,
                methodNames = listOf(methodName),
                returnValue = if (returnValue == "void") null else returnValue,
                hookMethodType = HookMethodType.FIND_AND_HOOK_METHOD,
                parameterTypes = parameterTypes,
                packageName = targetPackageName,
                hookPoint = "after"
            )
        }

        val fieldMatcher = FIELD_PATTERN.matcher(content)
        if (fieldMatcher.matches()) {
            val className = fieldMatcher.group(1)?.replace('/', '.') ?: ""
            val fieldName = fieldMatcher.group(2) ?: ""
            val fieldTypeDalvik = fieldMatcher.group(3) ?: ""
            val fieldValue = dalvikTypeToJavaType(fieldTypeDalvik)

            return CustomHookInfo(
                className = className,
                fieldName = fieldName,
                fieldValue = null,
                hookMethodType = HookMethodType.SET_STATIC_OBJECT_FIELD,
                packageName = targetPackageName
            )
        }

        return null
    }

    private fun parseMethodParameters(paramsDalvik: String): List<String>? {
        if (paramsDalvik.isBlank()) return null

        val params = mutableListOf<String>()
        var i = 0
        while (i < paramsDalvik.length) {
            val (javaType, newIndex) = parseSingleDalvikType(paramsDalvik, i)
            if (javaType == null) return null
            params.add(javaType)
            i = newIndex
        }
        return params.takeIf { it.isNotEmpty() }
    }

    private fun parseSingleDalvikType(dalvikString: String, startIndex: Int): Pair<String?, Int> {
        var i = startIndex
        if (i >= dalvikString.length) return null to i

        val typeChar = dalvikString[i]
        when (typeChar) {
            'Z' -> return "boolean" to i + 1
            'B' -> return "byte" to i + 1
            'C' -> return "char" to i + 1
            'S' -> return "short" to i + 1
            'I' -> return "int" to i + 1
            'J' -> return "long" to i + 1
            'F' -> return "float" to i + 1
            'D' -> return "double" to i + 1
            'V' -> return "void" to i + 1
            '[' -> {
                var arrayDims = 0
                while (i < dalvikString.length && dalvikString[i] == '[') {
                    arrayDims++
                    i++
                }
                val (baseType, newIndex) = parseSingleDalvikType(dalvikString, i)
                if (baseType == null) return null to newIndex
                return baseType + "[]".repeat(arrayDims) to newIndex
            }
            'L' -> {
                val end = dalvikString.indexOf(';', i)
                if (end == -1) return null to i
                val className = dalvikString.substring(i + 1, end).replace('/', '.')
                return className to end + 1
            }
            else -> return null to i
        }
    }

    private fun dalvikTypeToJavaType(dalvikType: String): String? {
        if (dalvikType.isBlank()) return null
        val (javaType, _) = parseSingleDalvikType(dalvikType, 0)
        return javaType
    }
}
