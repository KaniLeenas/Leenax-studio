package com.leenax.studio.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends activation links only; provider API keys and passwords never enter email. */
@Component
public class ActivationEmailSender {

    private final ObjectProvider<JavaMailSender> senders;
    private final String host;
    private final String from;
    private final String publicBaseUrl;

    public ActivationEmailSender(ObjectProvider<JavaMailSender> senders,
                                 @Value("${spring.mail.host:}") String host,
                                 @Value("${leenax.auth.email-from:}") String from,
                                 @Value("${leenax.social.public-base-url:http://localhost:8088}") String publicBaseUrl) {
        this.senders = senders;
        this.host = host;
        this.from = from;
        this.publicBaseUrl = publicBaseUrl;
    }

    public boolean isConfigured() {
        return !host.isBlank() && !from.isBlank() && senders.getIfAvailable() != null;
    }

    public void sendActivation(String to, String displayName, String token) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || host.isBlank() || from.isBlank()) {
            throw new IllegalStateException("Email delivery is not configured");
        }
        String link = publicBaseUrl.replaceAll("/+$", "") + "/activate?token=" + token;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Verify your Leenax Studio account");
        message.setText("Hello " + displayName + ",\n\nVerify your email and activate your Leenax Studio account using this one-time link (valid for 7 days):\n\n"
                + link + "\n\nIf you did not request this account, ignore this message.");
        sender.send(message);
    }
}
