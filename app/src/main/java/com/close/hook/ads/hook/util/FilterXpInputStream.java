package com.close.hook.ads.hook.util;

import androidx.annotation.NonNull;

import java.io.InputStream;
import java.util.Arrays;

public class FilterXpInputStream extends InputStream {
    private final byte[] mXposedBytes = "xposed".getBytes(); // {120, 112, 111, 115, 101, 100}
    private final byte[] mReadBuffer = new byte[6];
    private final ByteArrayOutputStreamUtils mBuffer = new ByteArrayOutputStreamUtils();
    private final InputStream mStream;

    public FilterXpInputStream(InputStream stream) {
        this.mStream = stream;
    }

    @Override
    public int read() throws java.io.IOException {
        int read = mStream.read();
        if (read != -1) {
            byte b = (byte) read;
            mReadBuffer[0] = mReadBuffer[1];
            mReadBuffer[1] = mReadBuffer[2];
            mReadBuffer[2] = mReadBuffer[3];
            mReadBuffer[3] = mReadBuffer[4];
            mReadBuffer[4] = mReadBuffer[5];
            mReadBuffer[5] = b;
            if (Arrays.equals(mReadBuffer, mXposedBytes)) {
                return 'a';
            }
        }
        return read;
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) throws java.io.IOException {
        int read = mStream.read(b, off, len);
        if (read != -1) {
            int index;
            mBuffer.write(mReadBuffer);
            mBuffer.write(b, 0, read);
            int l = 0;
            while ((index = mBuffer.indexOfBuff(mXposedBytes, l, mBuffer.size())) > -1) {
                l = index + mXposedBytes.length;
                byte random = 'x';
                mBuffer.getBuff()[index] = random;
                mBuffer.getBuff()[index + 1] = random;
                mBuffer.getBuff()[index + 2] = random;
                mBuffer.getBuff()[index + 3] = random;
                mBuffer.getBuff()[index + 4] = random;
                mBuffer.getBuff()[index + 5] = random;
            }
            if (read == 1) {
                mReadBuffer[0] = mReadBuffer[1];
                mReadBuffer[1] = mReadBuffer[2];
                mReadBuffer[2] = mReadBuffer[3];
                mReadBuffer[3] = mReadBuffer[4];
                mReadBuffer[4] = mReadBuffer[5];
                mReadBuffer[5] = b[read - 1];
            } else if (read == 2) {
                mReadBuffer[0] = mReadBuffer[2];
                mReadBuffer[1] = mReadBuffer[3];
                mReadBuffer[2] = mReadBuffer[4];
                mReadBuffer[3] = mReadBuffer[5];
                mReadBuffer[4] = b[read - 2];
                mReadBuffer[5] = b[read - 1];
            } else if (read == 3) {
                mReadBuffer[0] = mReadBuffer[3];
                mReadBuffer[1] = mReadBuffer[4];
                mReadBuffer[2] = mReadBuffer[5];
                mReadBuffer[3] = b[read - 3];
                mReadBuffer[4] = b[read - 2];
                mReadBuffer[5] = b[read - 1];
            } else if (read == 4) {
                mReadBuffer[0] = mReadBuffer[4];
                mReadBuffer[1] = mReadBuffer[5];
                mReadBuffer[2] = b[read - 4];
                mReadBuffer[3] = b[read - 3];
                mReadBuffer[4] = b[read - 2];
                mReadBuffer[5] = b[read - 1];
            } else if (read == 5) {
                mReadBuffer[0] = mReadBuffer[5];
                mReadBuffer[1] = b[read - 5];
                mReadBuffer[2] = b[read - 4];
                mReadBuffer[3] = b[read - 3];
                mReadBuffer[4] = b[read - 2];
                mReadBuffer[5] = b[read - 1];
            } else if (read == 6 || read > 6) {
                mReadBuffer[0] = b[read - 6];
                mReadBuffer[1] = b[read - 5];
                mReadBuffer[2] = b[read - 4];
                mReadBuffer[3] = b[read - 3];
                mReadBuffer[4] = b[read - 2];
                mReadBuffer[5] = b[read - 1];
            }

            read = mBuffer.size() - 6;
            System.out.println(read);
            if (read > 0) {
                System.arraycopy(mBuffer.getBuff(), 6, b, 0, read);
            }
        }
        mBuffer.releaseCache();
        return read;
    }

    @Override
    public long skip(long n) throws java.io.IOException {
        return mStream.skip(n);
    }

    @Override
    public int available() throws java.io.IOException {
        return mStream.available();
    }

    @Override
    public void close() throws java.io.IOException {
        mStream.close();
    }

    @Override
    public synchronized void mark(int readlimit) {
        mStream.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws java.io.IOException {
        mStream.reset();
    }

    @Override
    public boolean markSupported() {
        return mStream.markSupported();
    }
}

