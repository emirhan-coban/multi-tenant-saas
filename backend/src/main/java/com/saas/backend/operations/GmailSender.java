package com.saas.backend.operations;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
public class GmailSender {
    private final JavaMailSender mail;
    private final String address, password, mode;
    private final boolean enabled;
    public GmailSender(JavaMailSender mail,
        @Value("${spring.mail.username:}") String address,
        @Value("${spring.mail.password:}") String password,
        @Value("${app.email.enabled:false}") boolean enabled,
        @Value("${app.email.mode:test}") String mode) {
        this.mail=mail;this.address=address.trim();this.password=password;this.enabled=enabled;this.mode=mode;
        if(!mode.equals("test") && !mode.equals("live")) throw new IllegalArgumentException("MAIL_DELIVERY_MODE must be test or live");
    }
    public boolean ready() { return enabled && !address.isBlank() && !password.isBlank(); }
    public boolean allowed(String recipient) {return "live".equals(mode) || address.equalsIgnoreCase(recipient);}
    public Map<String,Object> status() {return Map.of("enabled",enabled,"configured",!address.isBlank()&&!password.isBlank(),"mode",mode,"ready",ready());}
    public void send(UUID event,String recipient,String subject,String body) {
        if(!ready() || !allowed(recipient)) throw new IllegalStateException("Email delivery is not authorized by configuration");
        try {
            var addresses=InternetAddress.parse(recipient,true);
            if(addresses.length!=1) throw new IllegalArgumentException("One recipient required");
            addresses[0].validate();
            var message=mail.createMimeMessage();
            var helper=new MimeMessageHelper(message,false,"UTF-8");
            helper.setFrom(address);helper.setTo(addresses[0]);helper.setSubject(subject);
            helper.setText(body,false);
            // Correlation only: Gmail SMTP does not offer an exactly-once idempotency API.
            message.setHeader("X-TenantOS-Event-ID",event.toString());
            mail.send(message);
        } catch(jakarta.mail.MessagingException ex) {throw new IllegalStateException("Email message could not be prepared");}
    }
}
