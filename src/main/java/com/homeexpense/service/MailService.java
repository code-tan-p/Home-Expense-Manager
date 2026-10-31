package com.homeexpense.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends email through the server owner's mail account. It is on only when the server is given mail settings
 * (SPRING_MAIL_HOST, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD and HOME_EXPENSE_MAIL_FROM); the login is
 * never kept in the code or the database.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> sender, @Value("${home-expense.mail.from:}") String from) {
        this.sender = sender;
        this.from = from == null ? "" : from.strip();
    }

    public boolean enabled() {
        return !from.isEmpty() && sender.getIfAvailable() != null;
    }

    /** Sends a plain-text message. Returns false (and logs) if it could not be sent; the reason is never shown to visitors. */
    public boolean send(String to, String subject, String text) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null || from.isEmpty()) {
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mail.send(message);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not send an email: {}", e.getMessage());
            return false;
        }
    }
}
