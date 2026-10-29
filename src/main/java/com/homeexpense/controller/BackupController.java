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

    private final Path dataDir;

    public BackupController(JdbcTemplate jdbc, Clock clock,
                            @org.springframework.beans.factory.annotation.Value("${home-expense.data-dir}") Path dataDir) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.dataDir = dataDir;
    }

    /**
     * Receives a backup file and leaves it beside the live database. It is swapped in the next time
     * the application starts, see {@code DataSourceConfig.applyPendingRestore}.
     */
    @org.springframework.web.bind.annotation.PostMapping("/backup/restore")
    public String restore(@org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                          org.springframework.web.servlet.mvc.support.RedirectAttributes flash) throws IOException {
        byte[] header = new byte[16];
        try (java.io.InputStream in = file.getInputStream()) {
            int read = in.readNBytes(header, 0, header.length);
            if (file.isEmpty() || read < 16 || !new String(header, 0, 15, java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3")) {
                throw new com.homeexpense.exception.BusinessRuleException(
                        "That file is not a Home Expense Manager backup. Choose the file you downloaded from this page.");
            }
        }
        try (java.io.InputStream in = file.getInputStream()) {
            Files.copy(in, dataDir.resolve("restore-pending.db"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        flash.addFlashAttribute("ok", "Backup received. Close the program and open it again to finish restoring. "
                + "Your current records are kept as a safety copy.");
        return "redirect:/backup";
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
