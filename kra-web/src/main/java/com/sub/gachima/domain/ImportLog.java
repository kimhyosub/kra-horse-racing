package com.sub.gachima.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/** 불러온 JSON 파일 기록. 파일이 바뀌었을 때만 다시 불러오려고 둔다. */
@Entity
@Table(name = "import_log")
public class ImportLog {
    @Id
    @Column(name = "file_name", length = 40)
    private String fileName;

    @Column(name = "last_modified")
    private long lastModified;

    @Column(name = "file_size")
    private long fileSize;

    /** ok / fail */
    @Column(length = 10)
    private String status;

    @Column(length = 500)
    private String message;

    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
    public boolean isOk() { return "ok".equals(status); }
}
