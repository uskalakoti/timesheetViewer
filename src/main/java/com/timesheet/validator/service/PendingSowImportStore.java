package com.timesheet.validator.service;

import com.timesheet.validator.dto.PendingSowImport;
import org.springframework.stereotype.Component;

@Component
public class PendingSowImportStore {

    private PendingSowImport pendingImport;

    public void store(PendingSowImport pendingImport) {
        this.pendingImport = pendingImport;
    }

    public PendingSowImport get() {
        return pendingImport;
    }

    public void clear() {
        this.pendingImport = null;
    }

    public boolean hasPendingImport() {
        return pendingImport != null;
    }
}