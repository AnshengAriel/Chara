// ***LICENSE*** This file is licensed under GPLv2 with Classpath Exception. See LICENSE file under project root for more info

package net.cassite.desktop.chara.chat.tianxing;

import net.cassite.desktop.chara.ThreadUtils;
import net.cassite.desktop.chara.chat.AbstractChatbot;
import net.cassite.desktop.chara.chat.Chatbot;
import net.cassite.desktop.chara.manager.ConfigManager;
import net.cassite.desktop.chara.util.Logger;
import vclient.HttpClient;
import vclient.impl.Http1ClientImpl;
import vfd.IP;
import vfd.IPPort;
import vjson.JSON;
import vproxybase.dns.Resolver;
import vproxybase.util.Callback;

import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public class TianxingChatbot extends AbstractChatbot implements Chatbot {
    private static final String HOSTNAME = "api.tianapi.com";

    public enum APIType {
        tuling,
        robot,
        ;
    }

    public static APIType apiType = APIType.robot;

    public static void switchType() {
        if (apiType == APIType.tuling) {
            apiType = APIType.robot;
        } else if (apiType == APIType.robot) {
            apiType = APIType.tuling;
        }
    }

    private String apiKey;
    private HttpClient httpClient;

    public TianxingChatbot() {
        super("tianxing");
    }

    @Override
    public void init(String config) throws Exception {
        if (config.isBlank()) {
            throw new Exception("chatbot config should be \"" + name() + ":your_api_key\"");
        }
        apiKey = config;
    }

    @Override
    public void takeMessage(String msg) {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    Resolver.getDefault().resolve(HOSTNAME, new Callback<>() {
                        @Override
                        protected void onSucceeded(IP ip) {
                            httpClient = new Http1ClientImpl(new IPPort(ip, 80), ThreadUtils.get().getLoop(), 5000);
                            sendRequest(msg);
                        }

                        @Override
                        protected void onFailed(UnknownHostException err) {
                            Logger.error("cannot resolve " + HOSTNAME, err);
                        }
                    });
                    return;
                }
            }
        }
        sendRequest(msg);
    }

    private void sendRequest(String msg) {
        String url;
        if (apiType == APIType.tuling) {
            url = "/txapi/tuling/index";
        } else if (apiType == APIType.robot) {
            url = "/txapi/robot/index";
        } else {
            Logger.shouldNotReachHere(new Exception("invalid apiType: " + apiType));
            return;
        }
        String req = url + "?key=" + apiKey
            + "&userid=" + ConfigManager.get().getUuid()
            + "&question=" + URLEncoder.encode(msg, StandardCharsets.UTF_8);
        Logger.info("Tianxing request: " + req);
        httpClient.get(req)
            .header("Host", HOSTNAME)
            .send((err, resp) -> {
                if (err != null) {
                    Logger.error("request Tianxing failed", err);
                    return;
                }
                //noinspection rawtypes
                JSON.Instance inst;
                try {
                    inst = JSON.parse(new String(resp.body().toJavaArray(), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    Logger.error("Tianxing response is not json: " + resp.bodyAsString(), e);
                    return;
                }
                Logger.info("Tianxing return " + inst.stringify());
                try {
                    JSON.Object o = (JSON.Object) inst;
                    if (o.getInt("code") != 200) {
                        Logger.error("Tianxing response code is not 200");
                        return;
                    }
                    var arr = o.getArray("newslist");
                    String[] ret = new String[arr.length()];
                    for (int i = 0; i < ret.length; ++i) {
                        ret[i] = arr.getObject(i).getString("reply");
                        ret[i] = ret[i].replaceAll("<br/>", "\n"); // the response may contain <br/>
                    }
                    sendMessage(ret);
                } catch (RuntimeException e) {
                    Logger.error("Tianxing response is not valid: " + resp.bodyAsString(), e);
                    //noinspection UnnecessaryReturnStatement
                    return;
                }
            });
    }
}
