package com.close.hook.ads.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ResponseBodyContentProvider extends ContentProvider {

    private static final String TAG = "ResponseBodyProvider";
    public static final String AUTHORITY = "com.close.hook.ads.provider.responsebody";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/response_bodies");

    private static final ConcurrentHashMap<String, String> responseBodyStore = new ConcurrentHashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String id = uri.getLastPathSegment();
        if (id == null) {
            return null;
        }

        String body = responseBodyStore.get(id);
        if (body == null) {
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "body_content"});
        cursor.addRow(new Object[]{id, body});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + AUTHORITY + ".response_body";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!uri.getPathSegments().contains("response_bodies")) {
            return null;
        }

        String bodyContent = values.getAsString("body_content");
        if (bodyContent == null) {
            return null;
        }

        String id = UUID.randomUUID().toString();
        responseBodyStore.put(id, bodyContent);

        if (getContext() != null) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return uri.buildUpon().appendPath(id).build();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        String id = uri.getLastPathSegment();
        if (id == null || id.equals("response_bodies")) {
            if (id != null && id.equals("response_bodies")) {
                int count = responseBodyStore.size();
                responseBodyStore.clear();
                if (getContext() != null) {
                    getContext().getContentResolver().notifyChange(uri, null);
                }
                return count;
            }
            return 0;
        }

        String removed = responseBodyStore.remove(id);
        if (removed != null) {
            if (getContext() != null) {
                getContext().getContentResolver().notifyChange(uri, null);
            }
            return 1;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = uri.getLastPathSegment();
        if (id == null) {
            throw new FileNotFoundException("Invalid URI for openFile: " + uri);
        }

        String body = responseBodyStore.get(id);
        if (body == null) {
            throw new FileNotFoundException("Response body not found for ID: " + id);
        }

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);

            new Thread(() -> {
                try {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.e(TAG, "Error writing response body to pipe for ID: " + id, e);
                } finally {
                    try {
                        output.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing pipe output stream for ID: " + id, e);
                    }
                }
            }).start();

            return pipe[0];
        } catch (IOException e) {
            Log.e(TAG, "Error creating pipe for response body: " + id, e);
            throw new FileNotFoundException("Error creating pipe: " + e.getMessage());
        }
    }
}
