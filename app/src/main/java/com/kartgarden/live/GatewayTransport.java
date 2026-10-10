package com.kartgarden.live;

import org.json.JSONObject;

/** JSON control-plane boundary; test fakes never contact a kart or real gateway. */
public interface GatewayTransport {
    JSONObject request(String path,JSONObject body) throws Exception;
}
