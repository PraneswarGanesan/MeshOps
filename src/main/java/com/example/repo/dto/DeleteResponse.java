package com.example.repo.dto;

public class DeleteResponse {
    private String message;
    private long deletedCount;

    public DeleteResponse() {}

    public DeleteResponse(String message, long deletedCount) {
        this.message = message;
        this.deletedCount = deletedCount;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(long deletedCount) {
        this.deletedCount = deletedCount;
    }
}