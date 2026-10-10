package com.kartgarden.live;

import com.kartgarden.live.protocol.ClientConfig;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

public final class GatewayApi implements GatewayTransport {
    private final ClientConfig config;
    private final String host;
    private final SSLSocketFactory tls;
    public GatewayApi(ClientConfig config,String host,SSLSocketFactory tls) { this.config=config;this.host=host;this.tls=tls; }
    public JSONObject request(String path,JSONObject body) throws Exception {
        URI uri=new URI("https",null,host,config.port(),path,null,null);
        HttpsURLConnection c=(HttpsURLConnection)uri.toURL().openConnection();
        c.setSSLSocketFactory(tls); // Default HTTPS hostname verification remains enabled.
        c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestProperty("X-OpenKart-Key",config.key());
        try {
            if(body!=null) { c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(var out=c.getOutputStream()){out.write(bytes);} }
            int status=c.getResponseCode();
            if(status<200||status>=300)throw new java.io.IOException("Gateway HTTP "+status+" (check credential permission and kart availability)");
            try(InputStream in=c.getInputStream()){return new JSONObject(new String(readLimited(in,262144),StandardCharsets.UTF_8));}
        } finally { c.disconnect(); }
    }
    public static byte[] readLimited(InputStream in,int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new java.io.IOException("File or response too large");out.write(buf,0,n);}return out.toByteArray();
    }
}
