package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    private final ConcurrentHashMap<String, String> map;
    private DnsOverHttps doh;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public void setDoh(Doh item) {
        if (item.getUrl().isEmpty()) return;
        this.doh = new DnsOverHttps.Builder().client(new OkHttpClient()).url(HttpUrl.get(item.getUrl())).bootstrapDnsHosts(item.getHosts()).build();
    }

    public void clear() {
        map.clear();
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        return (doh != null ? doh : Dns.SYSTEM).lookup(toAscii(get(hostname)));
    }

    private static boolean isAscii(String s) {
        for (int i = 0, n = s.length(); i < n; i++) if (s.charAt(i) > 127) return false;
        return true;
    }

    /** 主机含非 ASCII（中文域名 / IDN）时转 punycode；ASCII / IPv6 / 已 punycode 原样返回。 */
    private static String toAscii(String hostname) {
        if (hostname == null || isAscii(hostname)) return hostname;
        try {
            return IDN.toASCII(hostname);
        } catch (Exception e) {
            return hostname;
        }
    }
}
