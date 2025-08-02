package com.close.hook.ads.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ResponseBodyContentProvider extends ContentProvider {

    private static final String TAG = "ResponseBodyProvider";
    public static final String AUTHORITY = "com.close.hook.ads.provider.responsebody";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/response_bodies");

    private static final ConcurrentHashMap<String, Pair<String, String>> responseBodyStore = new ConcurrentHashMap<>();

    private static final int RESPONSE_BODIES = 1;
    private static final int RESPONSE_BODY_ID = 2;
    private static final UriMatcher uriMatcher;

    static {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(AUTHORITY, "response_bodies", RESPONSE_BODIES);
        uriMatcher.addURI(AUTHORITY, "response_bodies/*", RESPONSE_BODY_ID);
    }

    @Override
    public boolean onCreate() {
        Log.d(TAG, "ContentProvider created.");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String id;
        switch (uriMatcher.match(uri)) {
            case RESPONSE_BODIES:
                Log.w(TAG, "Querying without ID is not supported.");
                return null;
            case RESPONSE_BODY_ID:
                id = uri.getLastPathSegment();
                break;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        Pair<String, String> data = responseBodyStore.get(id);

        if (data != null) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"body_content", "mime_type"});
            cursor.addRow(new Object[]{data.first, data.second});
            return cursor;
        }

        return new MatrixCursor(new String[]{"body_content", "mime_type"});
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case RESPONSE_BODIES:
                return "vnd.android.cursor.dir/vnd.com.close.hook.ads.response_body";
            case RESPONSE_BODY_ID:
                return "vnd.android.cursor.item/vnd.com.close.hook.ads.response_body";
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (uriMatcher.match(uri) != RESPONSE_BODIES) {
            throw new IllegalArgumentException("Cannot insert into URI: " + uri);
        }

        String bodyContent = values.getAsString("body_content");
        String mimeType = values.getAsString("mime_type");

        if (bodyContent == null || mimeType == null) {
            Log.e(TAG, "ContentValues must contain 'body_content' and 'mime_type'.");
            return null;
        }

        String id = UUID.randomUUID().toString();
        responseBodyStore.put(id, new Pair<>(bodyContent, mimeType));
        Log.d(TAG, "Inserted new response body with ID: " + id);

        return Uri.withAppendedPath(CONTENT_URI, id);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        switch (uriMatcher.match(uri)) {
            case RESPONSE_BODY_ID:
                String id = uri.getLastPathSegment();
                if (responseBodyStore.containsKey(id)) {
                    responseBodyStore.remove(id);
                    Log.d(TAG, "Deleted response body with ID: " + id);
                    return 1;
                }
                return 0;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Log.w(TAG, "Update operation not supported.");
        return 0;
    }
}
