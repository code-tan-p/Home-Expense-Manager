package com.homeexpense.controller;

import java.io.IOException;
import java.io.InputStream;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.service.BackupService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Backup" screen: download a copy of all records, or restore one. */
@Controller
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/backup")
    public String backup(Model model) {
        model.addAttribute("page", "backup");
        return "backup";
    }

    @PostMapping("/backup/download")
    public ResponseEntity<Resource> download() throws IOException {
        byte[] copy = backupService.snapshot();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(copy.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(backupService.downloadName()).build().toString())
                .body(new ByteArrayResource(copy));
    }

    /** Checks the file, then stages it. It is applied the next time the program starts. */
    @PostMapping("/backup/restore")
    public String restore(@RequestParam("file") MultipartFile file, RedirectAttributes flash) throws IOException {
        if (file.isEmpty()) {
            throw new BusinessRuleException("Choose the backup file first.");
        }
        try (InputStream in = file.getInputStream()) {
            backupService.stageRestore(in);
        }
        flash.addFlashAttribute("ok", "Backup checked and received. Close the program and open it again to finish restoring. "
                + "Your current records are kept as a safety copy.");
        return "redirect:/backup";
    }
}
