package com.homeexpense.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The "Backup" screen: downloads one consistent copy of the whole database. */
@Controller
public class BackupController {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BackupController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/backup")
    public String backup(Model model) {
        model.addAttribute("page", "backup");
        return "backup";
    }

    /** VACUUM INTO writes a complete copy that already includes the WAL, so one file is a full backup. */
    @org.springframework.web.bind.annotation.PostMapping("/backup/download")
    public ResponseEntity<Resource> download() throws IOException {
        Path folder = Files.createTempDirectory("home-expense-backup");
        Path copy = folder.resolve("home-expense-" + LocalDate.now(clock) + ".db");
        jdbc.execute("VACUUM INTO '" + copy.toString().replace("'", "''") + "'");
        FileSystemResource resource = new FileSystemResource(copy) {
            @Override
            public java.io.InputStream getInputStream() throws IOException {
                return new java.io.FilterInputStream(super.getInputStream()) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        Files.deleteIfExists(copy);
                        Files.deleteIfExists(folder);
                    }
                };
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(copy))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(copy.getFileName().toString()).build().toString())
                .body(resource);
    }
}
