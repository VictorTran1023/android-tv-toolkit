package com.victor.tvhud;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * 极简 ADB 客户端：连本机 127.0.0.1:5555，用自签名密钥认证，执行 shell 命令。
 *
 * <p>改编自 free-my-tv 的 {@code Adb.java}（MIT License，https://github.com/simbasjungles/free-my-tv）。
 * 只用于一件只读的事：执行 {@code dumpsys thermalservice} 读 CPU 温度，
 * 因为普通 App 身份读 /sys/class/thermal 会被 SELinux 拒绝，而 shell 身份可以。
 *
 * <p>安全约束：{@link #shell(String)} 只接受本类内部拼好的固定命令，不接受任何外部输入。
 */
final class Adb implements Closeable {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private Adb(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** 连接并认证；电视还没信任本 App 的密钥时，最多等 approvalMs 让用户在电视上点“允许”。 */
    static Adb connect(Context c, int approvalMs) throws IOException {
        KeyPair keys = keys(c);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
        } catch (IOException e) {
            s.close();
            throw new IOException("adb off");
        }
        s.setSoTimeout(6000);
        Adb adb = new Adb(s);
        try {
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false, offered = false;
            while (true) {
                Message m;
                try {
                    m = adb.read();
                } catch (SocketTimeoutException e) {
                    throw new IOException(offered ? "not authorized on TV" : "no reply");
                }
                if (m.command == CNXN) {
                    s.setSoTimeout(12000);
                    return adb;
                }
                if (m.command == STLS) throw new IOException("wireless debugging uses TLS");
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply");
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else if (!offered) {
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.getPublic()));
                    offered = true;
                    s.setSoTimeout(approvalMs);
                } else {
                    throw new IOException("key rejected");
                }
            }
        } catch (IOException e) {
            adb.close();
            throw e;
        }
    }

    /** 执行一条固定命令，返回 stdout+stderr。 */
    String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                result.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                if (remote == 0 && m.arg0 == 0) throw new IOException("refused");
                send(CLSE, local, m.arg0, null);
                return result.toString("UTF-8");
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        b.getInt();
        int magic = b.getInt();
        if ((m.command ^ 0xffffffff) != magic) throw new IOException("bad message");
        if (length < 0 || length > 1024 * 1024) throw new IOException("too large");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private static synchronized KeyPair keys(Context c) throws IOException {
        File dir = c.getFilesDir();
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        try {
            KeyFactory f = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                try {
                    return new KeyPair(f.generatePublic(new X509EncodedKeySpec(readFile(pub))),
                            f.generatePrivate(new PKCS8EncodedKeySpec(readFile(priv))));
                } catch (GeneralSecurityException | IOException e) {
                    // 断电写坏：重新生成，电视会再问一次
                }
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair pair = g.generateKeyPair();
            writeFile(priv, pair.getPrivate().getEncoded());
            writeFile(pub, pair.getPublic().getEncoded());
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IOException("key: " + e.getMessage());
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            block[1] = 1;
            int sep = block.length - SHA1_PREFIX.length - token.length - 1;
            for (int i = 2; i < sep; i++) block[i] = (byte) 0xff;
            System.arraycopy(SHA1_PREFIX, 0, block, sep + 1, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, sep + 1 + SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException("sign: " + e.getMessage());
        }
    }

    private static byte[] publicKeyBlob(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(rsa.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvhud@tv\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) little[i] = big[big.length - 1 - i];
        return little;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("save " + f.getName());
    }
}
