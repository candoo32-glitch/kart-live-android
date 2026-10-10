package com.kartgarden.live.protocol;

import org.json.JSONObject;
import java.net.URI;

public record ClientConfig(String key,String permission,String id,String host,int port) {
    public static ClientConfig parse(String json) throws Exception {
        JSONObject obj=new JSONObject(json);
        JSONObject[] candidates={obj,obj.optJSONObject("client"),obj.optJSONObject("auth"),obj.optJSONObject("credentials")};
        String key=value(candidates,"key","client_key","api_key");
        if(key.trim().isEmpty()||key.length()>4096||key.contains("\n")||key.contains("\r"))throw new IllegalArgumentException("Missing or invalid client key");
        String permission=value(candidates,"permission");if(permission.isEmpty())permission="read";
        permission=permission.toLowerCase(java.util.Locale.ROOT);
        if(!permission.equals("read")&&!permission.equals("control"))throw new IllegalArgumentException("Permission must be read or control");
        String host=value(candidates,"host","gateway","server"),url=value(candidates,"api_url");int port=9443;
        if(!url.isEmpty()) { URI u=new URI(url);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException("Enrollment API URL must be HTTPS");host=u.getHost();if(u.getPort()!=-1)port=u.getPort(); }
        if(host.isEmpty())host="kart400.local";
        if(host.contains("://")||host.contains("/")||host.contains("\n")||host.contains(" ")||port<1||port>65535)throw new IllegalArgumentException("Invalid gateway address");
        return new ClientConfig(key,permission,value(candidates,"id","client_id"),host,port);
    }
    private static String value(JSONObject[] candidates,String...names) { for(JSONObject o:candidates)if(o!=null)for(String name:names){Object v=o.opt(name);if(v instanceof String && !((String)v).trim().isEmpty())return ((String)v).trim();}return ""; }
}
