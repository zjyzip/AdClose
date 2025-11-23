package com.close.hook.ads.data.model

sealed class ResponseBodyContent {
    data class Text(val content: String, val mimeType: String?) : ResponseBodyContent()
    data class Image(val bytes: ByteArray, val mimeType: String?) : ResponseBodyContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Image
            if (!bytes.contentEquals(other.bytes)) return false
            if (mimeType != other.mimeType) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }
    data class Error(val message: String) : ResponseBodyContent()
    object Loading : ResponseBodyContent()
}