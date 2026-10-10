package com.kartgarden.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.kartgarden.live.protocol.ClientConfig;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** Keys encrypted at rest with a non-exportable Android Keystore AES key. */
public final class CredentialVault {
    private static final String ALIAS="kart-live-client-v1";
    private final Context context;
    private final SharedPreferences prefs;
    public CredentialVault(Context context) { this.context=context; prefs=context.getSharedPreferences("private-enrollment",Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    public void save(String json) throws Exception {
        ClientConfig.parse(json);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] data=c.doFinal(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!prefs.edit().putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).putString("data",Base64.encodeToString(data,Base64.NO_WRAP)).commit())throw new java.io.IOException("Could not save enrollment");
    }
    public ClientConfig load() throws Exception {
        String data=prefs.getString("data",null);if(data==null)return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return ClientConfig.parse(new String(c.doFinal(Base64.decode(data,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
    }
    private File caFile() { return new File(context.getFilesDir(),"gateway-ca.der"); }
    public boolean hasCa() { return caFile().exists(); }
    public String importCa(byte[] data) throws Exception {
        X509Certificate ca=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(data));
        ca.checkValidity();if(ca.getBasicConstraints()<0)throw new IllegalArgumentException("Choose the public gateway CA certificate, not a server certificate");
        Files.write(caFile().toPath(),ca.getEncoded());
        return ca.getSubjectX500Principal().getName();
    }
    public SSLSocketFactory tls() throws Exception {
        if(!hasCa())throw new IllegalStateException("Import openkart-ca.crt before connecting");
        X509Certificate cert;try(var in=new java.io.FileInputStream(caFile())){cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}cert.checkValidity();
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);trust.setCertificateEntry("gateway",cert);
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,factory.getTrustManagers(),null);return ssl.getSocketFactory();
    }
    public void clear() throws Exception { if(!prefs.edit().clear().commit())throw new java.io.IOException("Could not clear credentials");Files.deleteIfExists(caFile().toPath());KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);s.deleteEntry(ALIAS); }
}
