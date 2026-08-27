package com.eazycount.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMailService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    public PasswordResetMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    //Sends the TAC by email. Failures are logged, not thrown — the caller must not let SMTP outages leak account existence.
    public void sendResetCode(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("EazyCount Password Reset Code");
        message.setText("Your password reset verification code is: " + code
                + "\nThis code expires in 15 minutes. If you did not request this, you can ignore this email.");
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("Failed to send password reset email to {}", toEmail, e);
        }
    }
}
