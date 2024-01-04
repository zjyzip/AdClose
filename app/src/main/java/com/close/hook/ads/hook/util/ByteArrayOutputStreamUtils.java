package com.close.hook.ads.hook.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

public class ByteArrayOutputStreamUtils extends OutputStream {
    // 将“字节数组输出流”转换成字节数组。
    private static final byte[] mNullByteArray = new byte[0];
    // 保存“字节数组输出流”数据的数组
    private byte[] mBuffer;
    // “字节数组输出流”的计数
    private int mCount;

    // 构造函数：默认创建的字节数组大小是32。
    public ByteArrayOutputStreamUtils() {
        this(32);
    }

    // 构造函数：创建指定数组大小的“字节数组输出流”
    public ByteArrayOutputStreamUtils(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Negative initial size: " + size);
        }
        mBuffer = new byte[size];
    }

    public static int indexOf(byte[] array, byte b, int start, int indexRange) {
        if (array == null || array.length == 0 || start >= indexRange) {
            return -1;
        } else if (start < 0) {
            start = 0;
        }
        if (indexRange > array.length) {
            indexRange = array.length;
        }
        while (start < indexRange) {
            if (array[start] == b) {
                return start;
            }
            start++;
        }
        return -1;
    }

    public static int lastIndexOf(byte[] array, byte b, int startIndex, int indexRange) {
        if (array == null || array.length == 0 || indexRange > startIndex) {
            return -1;
        } else if (indexRange < 0) {
            indexRange = 0;
        }
        if (startIndex > array.length - 1) {
            startIndex = array.length - 1;
        }
        while (startIndex >= indexRange) {
            if (array[startIndex] == b) {
                return startIndex;
            }
            startIndex--;
        }
        return -1;
    }

    public static int indexOf(byte[] array, byte[] b, int start, int indexRange) {
        if (array == null || array.length == 0 || start > indexRange || b == null || b.length > array.length || b.length == 0 || indexRange - start + 1 < b.length) {
            return -1;
        } else if (start < 0) {
            start = 0;
        }
        if (indexRange > array.length) {
            indexRange = array.length;
        }
        int i, i2;
        for (i = start; i < indexRange; i++) {
            if (array[i] == b[0]) {
                if (indexRange - i < b.length) {
                    break;
                }
                for (i2 = 1; i2 < b.length; i2++) {
                    if (array[i + i2] != b[i2]) {
                        break;
                    }
                }
                if (i2 == b.length) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static int lastIndexOf(byte[] array, byte[] b, int startIndex, int indexRange) {
        if (array == null || array.length == 0 || indexRange > startIndex || b == null || b.length > array.length || b.length == 0 || startIndex - indexRange + 1 < b.length) {
            return -1;
        } else if (indexRange < 0) {
            indexRange = 0;
        }
        if (startIndex > array.length) {
            startIndex = array.length;
        }
        int i, i2;
        for (i = startIndex == array.length ? array.length - 1 : startIndex; i >= indexRange; i--) {
            if (array[i] == b[0]) {
                if (i + b.length > startIndex) {
                    continue;
                }
                for (i2 = 1; i2 < b.length; i2++) {
                    if (array[i + i2] != b[i2]) {
                        break;
                    }
                }
                if (i2 == b.length) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int getSize() {
        return mCount;
    }

    public void setSize(int size) {
        if (size > mBuffer.length) {
            size = mBuffer.length;
        }
        mCount = size;
    }

    public int getBuffSize() {
        return mBuffer.length;
    }

    // 确认“容量”。
    // 若“实际容量 < minCapacity”，则增加“字节数组输出流”的容量
    private void ensureCapacity(int minCapacity) {
        // overflow-conscious code
        if (minCapacity - mBuffer.length > 0) {
            grow(minCapacity);
        }
    }

    // 增加“容量”。
    private void grow(int minCapacity) {
        int oldCapacity = mBuffer.length;
        // “新容量”的初始化 = “旧容量”x2
        int newCapacity = oldCapacity << 1;
        // 比较“新容量”和“minCapacity”的大小，并选取其中较大的数为“新的容量”。
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity;
        }
        if (newCapacity < 0) {
            if (minCapacity < 0) {
                // overflow
                throw new OutOfMemoryError();
            }
            newCapacity = Integer.MAX_VALUE;
        }
        mBuffer = Arrays.copyOf(mBuffer, newCapacity);
    }

    // 写入一个字节b到“字节数组输出流”中，并将计数+1
    public void write(int b) {
        ensureCapacity(mCount + 1);
        mBuffer[mCount] = (byte) b;
        mCount += 1;
    }

    // 写入字节数组b到“字节数组输出流”中。off是“写入字节数组b的起始位置”，len是写入的长度
    @Override
    public void write(byte b[], int off, int len) {
        if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) - b.length > 0)) {
            throw new IndexOutOfBoundsException();
        }
        ensureCapacity(mCount + len);
        System.arraycopy(b, off, mBuffer, mCount, len);
        mCount += len;
    }

    // 写入输出流outb到“字节数组输出流”中。
    public void writeTo(OutputStream out) throws IOException {
        out.write(mBuffer, 0, mCount);
    }

    // 重置“字节数组输出流”的计数。
    public void reset() {
        mCount = 0;
    }

    public byte toByteArray()[] {
        if (mCount == 0) {
            return mNullByteArray;
        }
        return Arrays.copyOf(mBuffer, mCount);
    }

    // 返回“字节数组输出流”当前计数值
    public int size() {
        return mCount;
    }

    public String toString() {
        return new String(mBuffer, 0, mCount);
    }

    public String toString(String charsetName) throws UnsupportedEncodingException {
        return new String(mBuffer, 0, mCount, charsetName);
    }

    @Deprecated
    public String toString(int hibyte) {
        return new String(mBuffer, hibyte, 0, mCount);
    }

    public void close() {
    }

    public void releaseCache() {
        mBuffer = mNullByteArray;
        mCount = 0;
    }

    public byte[] getBuff() {
        return mBuffer;
    }

    public void seekIndex(int index) {
        setSize(index);
    }

    public int getIndex() {
        return mCount;
    }

    public int indexOfBuff(byte b, int start) {
        return indexOf(mBuffer, b, start, mBuffer.length);
    }

    public int indexOfBuff(byte[] b, int start) {
        return indexOf(mBuffer, b, start, mBuffer.length);
    }

    public int indexOfBuff(byte b, int start, int end) {
        return indexOf(mBuffer, b, start, end);
    }

    public int indexOfBuff(byte[] b, int start, int end) {
        return indexOf(mBuffer, b, start, end);
    }

    public int lastIndexOfBuff(byte b, int start) {
        return lastIndexOf(mBuffer, b, 0, start);
    }

    public int lastIndexOfBuff(byte[] b, int start) {
        return lastIndexOf(mBuffer, b, 0, start);
    }

    public int lastIndexOfBuff(byte b, int start, int end) {
        return lastIndexOf(mBuffer, b, start, end);
    }

    public int lastIndexOfBuff(byte[] b, int start, int end) {
        return lastIndexOf(mBuffer, b, start, end);
    }

}
