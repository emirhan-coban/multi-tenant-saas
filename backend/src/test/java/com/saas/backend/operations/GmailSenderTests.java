package com.saas.backend.operations;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmailSenderTests {
    @Test void defaultTestModeOnlyAllowsSenderAddress() {
        var transport=mock(JavaMailSender.class);
        var sender=new GmailSender(transport,"sender@example.test","fake-app-password",true,"test");
        assertTrue(sender.allowed("SENDER@example.test"));
        assertFalse(sender.allowed("customer@example.test"));
        assertThrows(IllegalStateException.class,()->sender.send(UUID.randomUUID(),"customer@example.test","Test","Body"));
        verifyNoInteractions(transport);
    }
    @Test void disabledAndMissingCredentialsNeverSend() {
        var transport=mock(JavaMailSender.class);
        assertFalse(new GmailSender(transport,"sender@example.test","fake",false,"live").ready());
        var sender=new GmailSender(transport,"sender@example.test","",true,"live");
        assertFalse(sender.ready());
        assertThrows(IllegalStateException.class,()->sender.send(UUID.randomUUID(),"customer@example.test","Test","Body"));
        verifyNoInteractions(transport);
    }
    @Test void configurationStatusNeverExposesSecrets() {
        var sender=new GmailSender(mock(JavaMailSender.class),"sender@example.test","private-secret",true,"test");
        assertFalse(sender.status().toString().contains("private-secret"));
        assertFalse(sender.status().toString().contains("sender@example.test"));
    }
    @Test void invalidModeIsRejectedInsteadOfEnablingLiveDelivery() {
        assertThrows(IllegalArgumentException.class,()->new GmailSender(mock(JavaMailSender.class),"a","b",true,"typo"));
    }
    @Test void realSmtpTransportProducesUtf8MessageWithoutExternalDelivery() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var pool=Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var captured=pool.submit(()->{
                try(var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    var writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                    writer.write("220 localhost test SMTP\r\n");writer.flush();
                    String message="",line;
                    while((line=reader.readLine())!=null) {
                        if(line.startsWith("DATA")) {
                            writer.write("354 Send data\r\n");writer.flush();
                            var body=new StringBuilder();
                            while((line=reader.readLine())!=null && !line.equals("."))body.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
                            message=body.toString();writer.write("250 Accepted\r\n");
                        } else if(line.startsWith("QUIT")) {writer.write("221 Bye\r\n");writer.flush();return message;}
                        else writer.write("250 OK\r\n");
                        writer.flush();
                    }
                    return message;
                }
            });
            var transport=new JavaMailSenderImpl();transport.setHost(server.getInetAddress().getHostAddress());transport.setPort(server.getLocalPort());
            transport.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout","3000");
            transport.getJavaMailProperties().setProperty("mail.smtp.timeout","3000");
            var sender=new GmailSender(transport,"sender@example.test","test-only",true,"test");
            UUID event=UUID.randomUUID();
            sender.send(event,"sender@example.test","Randevunuz onaylandı","Merhaba Ayşe,\nRandevunuz saat 09:00.");
            var mime=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(captured.get(5,TimeUnit.SECONDS).getBytes(StandardCharsets.UTF_8)));
            assertEquals("Randevunuz onaylandı",mime.getSubject());
            assertTrue(mime.getContent().toString().contains("Merhaba Ayşe"));
            assertEquals("sender@example.test",mime.getAllRecipients()[0].toString());
            assertEquals(event.toString(),mime.getHeader("X-TenantOS-Event-ID")[0]);
        }
    }
}
