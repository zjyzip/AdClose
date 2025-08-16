package com.close.hook.ads.service;

import com.close.hook.ads.data.model.LogEntry;

interface ILoggerService {
    /**
     * Called by the client to send a log message to the service.
     */
    void log(in LogEntry entry);
}
