package com.projetoros.launcher;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

/**
 * Shell do próprio projetor pelo adbd local (127.0.0.1:5555): é o que deixa um app comum forçar parada,
 * listar recentes e mexer no keystone. Na primeira vez o Android pergunta se autoriza a chave do launcher.
 */
final class AdbSelf {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    /** DigestInfo do SHA-1: o adbd assina o token como se ele fosse um hash SHA-1. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final File dir;
    private final Runnable onPrompt;
    private KeyPair keys;

    AdbSelf(File dir, Runnable onPrompt) {
        this.dir = dir;
        this.onPrompt = onPrompt;
    }

    /** Roda o comando e devolve a saída. IOException("AUTH") enquanto a chave não for aceita na tela. */
    synchronized String shell(String cmd) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            s.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = s.getOutputStream();
            send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            Msg m;
            try {
                while ((m = read(in)).cmd != CNXN) {
                    if (m.cmd != AUTH || m.arg0 != 1) throw new IOException("adb respondeu algo inesperado");
                    if (!signed) {
                        send(out, AUTH, 2, 0, sign(m.data));
                        signed = true;
                    } else {
                        // chave ainda não autorizada: manda a pública e espera a pessoa aceitar na tela
                        send(out, AUTH, 3, 0, publicKey());
                        onPrompt.run();
                        s.setSoTimeout(60000);
                    }
                }
            } catch (SocketTimeoutException e) {
                throw new IOException("AUTH");
            }
            s.setSoTimeout(20000);
            send(out, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            while ((m = read(in)).cmd != CLSE) {
                if (m.cmd == WRTE) {
                    res.write(m.data);
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                }
            }
            return res.toString("UTF-8");
        }
    }

    private static final class Msg {
        int cmd, arg0;
        byte[] data;
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        b.getInt();
        m.data = new byte[b.getInt()];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            // PKCS#1 v1.5 tipo 1 montado à mão: 00 01 FF..FF 00 DigestInfo token
            byte[] block = new byte[256];
            Arrays.fill(block, (byte) 0xff);
            block[0] = 0;
            block[1] = 1;
            int start = 256 - SHA1_PREFIX.length - token.length;
            block[start - 1] = 0;
            System.arraycopy(SHA1_PREFIX, 0, block, start, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, start + SHA1_PREFIX.length, token.length);
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys().getPrivate());
            return c.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Chave pública no formato do adb (RSAPublicKey do mincrypt em base64) + nome que aparece na janela. */
    private byte[] publicKey() throws IOException {
        RSAPublicKey k = (RSAPublicKey) keys().getPublic();
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(k.getPublicExponent().intValue());
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Projetor-OS@launcher\0").getBytes(StandardCharsets.UTF_8);
    }

    private KeyPair keys() throws IOException {
        if (keys != null) return keys;
        File pri = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        try {
            if (pri.exists() && pub.exists()) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                keys = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))), kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pri))));
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                keys = g.generateKeyPair();
                writeAll(pri, keys.getPrivate().getEncoded());
                writeAll(pub, keys.getPublic().getEncoded());
            }
            return keys;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(b);
        }
        return b;
    }

    private static void writeAll(File f, byte[] b) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b);
        }
    }
}
