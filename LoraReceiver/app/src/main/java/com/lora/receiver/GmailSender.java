package com.lora.receiver;

import android.util.Log;

import java.io.File;
import java.util.Date;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Gmail 邮件发送（应用专用密码方式）
 * 必须在子线程中调用，不能在主线程执行网络操作
 */
public class GmailSender {

    private static final String TAG = "GmailSender";

    private final String mFrom;
    private final String mPassword;
    private final String mTo;

    public GmailSender(String from, String password, String to) {
        this.mFrom     = from;
        this.mPassword = password.replace(" ", ""); // 去除Google生成时的空格
        this.mTo       = to;
    }

    /**
     * 发送带二进制附件的邮件
     *
     * @param subject    邮件主题
     * @param body       邮件正文
     * @param attachment 附件文件
     * @return 错误信息，null表示成功
     */
    public String send(String subject, String body, File attachment) {
        Properties props = new Properties();
        props.put("mail.smtp.host",            "smtp.gmail.com");
        props.put("mail.smtp.port",            "587");
        props.put("mail.smtp.auth",            "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.ssl.protocols",   "TLSv1.2");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout",           "15000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(mFrom, mPassword);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(mFrom));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(mTo));
            message.setSubject(subject, "UTF-8");
            message.setSentDate(new Date());

            // 正文部分
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "UTF-8");

            // 附件部分
            MimeBodyPart filePart = new MimeBodyPart();
            filePart.attachFile(attachment);
            filePart.setFileName(attachment.getName());

            Multipart mp = new MimeMultipart();
            mp.addBodyPart(textPart);
            mp.addBodyPart(filePart);
            message.setContent(mp);

            Transport.send(message);
            Log.d(TAG, "发送成功: " + subject);
            return null; // 成功

        } catch (Exception e) {
            Log.e(TAG, "发送失败: " + e.getMessage(), e);
            return e.getMessage();
        }
    }
}
