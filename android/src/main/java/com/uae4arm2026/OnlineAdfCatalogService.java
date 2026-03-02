package com.uae4arm2026;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

final class OnlineAdfCatalogService {

    private static String sCachedOAuthToken;
    private static long sCachedOAuthTokenExpiryMs;

    static final class ArchiveItem {
        final String identifier;
        final String title;
        String adfFileName;
        String adfDownloadUrl;

        ArchiveItem(String identifier, String title) {
            this.identifier = identifier;
            this.title = title;
        }

        @Override
        public String toString() {
            return (title == null || title.trim().isEmpty()) ? identifier : title;
        }
    }

    static final class IgdbResult {
        final String name;
        final String summary;
        final String coverUrl;

        IgdbResult(String name, String summary, String coverUrl) {
            this.name = name;
            this.summary = summary;
            this.coverUrl = coverUrl;
        }
    }

    private OnlineAdfCatalogService() {
    }

    static IgdbResult searchIgdb(String clientId, String accessToken, String clientSecret, String gameTitle) throws Exception {
        ArrayList<IgdbResult> candidates = searchIgdbCandidates(clientId, accessToken, clientSecret, gameTitle, 1, true);
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.get(0);
    }

    static ArrayList<IgdbResult> searchIgdbCandidates(String clientId, String accessToken, String clientSecret, String gameTitle, int limit, boolean normalizeTerm) throws Exception {
        if (clientId == null || clientId.trim().isEmpty()) return null;
        if (gameTitle == null || gameTitle.trim().isEmpty()) return null;

        String bearer = accessToken == null ? null : accessToken.trim();
        if (bearer == null || bearer.isEmpty()) {
            bearer = getAppAccessToken(clientId.trim(), clientSecret == null ? null : clientSecret.trim());
        }
        if (bearer == null || bearer.isEmpty()) return null;

        String searchTerm = normalizeTerm ? normalizeIgdbSearchTerm(gameTitle) : gameTitle.trim();
        if (searchTerm.isEmpty()) return null;
        int safeLimit = Math.max(1, Math.min(20, limit));
        String query = "search \"" + searchTerm.replace("\"", " ").trim() + "\"; where platforms = (16); fields name,summary,cover.image_id; limit " + safeLimit + ";";
        String body = httpPost("https://api.igdb.com/v4/games", query,
            "Client-ID: " + clientId.trim(),
            "Authorization: Bearer " + bearer);

        JSONArray arr = new JSONArray(body);
        ArrayList<IgdbResult> out = new ArrayList<>();
        if (arr.length() == 0) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim();
            String summary = item.optString("summary", "").trim();
            String coverUrl = null;
            try {
                JSONObject cover = item.optJSONObject("cover");
                if (cover != null) {
                    String imageId = cover.optString("image_id", "").trim();
                    if (!imageId.isEmpty()) {
                        coverUrl = "https://images.igdb.com/igdb/image/upload/t_cover_big/" + imageId + ".jpg";
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!name.isEmpty()) {
                out.add(new IgdbResult(name, summary, coverUrl));
            }
        }
        return out;
    }

    static String normalizeIgdbSearchTerm(String rawTitle) {
        if (rawTitle == null) return "";
        String title = rawTitle.trim();
        if (title.isEmpty()) return "";

        int bracket = title.indexOf('(');
        if (bracket > 0) {
            title = title.substring(0, bracket).trim();
        } else if (bracket == 0) {
            int close = title.indexOf(')');
            if (close >= 0 && close < title.length() - 1) {
                title = title.substring(close + 1).trim();
            }
        }

        title = title.replace('_', ' ').replace('-', ' ').trim();
        title = title.replaceAll("\\s+", " ");
        return title;
    }

    private static synchronized String getAppAccessToken(String clientId, String clientSecret) {
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (sCachedOAuthToken != null && !sCachedOAuthToken.isEmpty() && now < sCachedOAuthTokenExpiryMs) {
            return sCachedOAuthToken;
        }

        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            String url = "https://id.twitch.tv/oauth2/token"
                + "?client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8")
                + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, "UTF-8")
                + "&grant_type=client_credentials";

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", "uae4arm_2026/1.0");
            conn.getOutputStream().write(new byte[0]);
            conn.getOutputStream().flush();
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            JSONObject obj = new JSONObject(new String(readAllBytes(in), "UTF-8"));
            String token = obj.optString("access_token", "").trim();
            long expiresIn = obj.optLong("expires_in", 0L);
            if (token.isEmpty()) return null;
            sCachedOAuthToken = token;
            long safeExpires = expiresIn > 120 ? (expiresIn - 120) : expiresIn;
            sCachedOAuthTokenExpiryMs = System.currentTimeMillis() + (safeExpires * 1000L);
            return sCachedOAuthToken;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (conn != null) conn.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String httpPost(String url, String body, String header1, String header2) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("User-Agent", "uae4arm_2026/1.0");
        if (header1 != null) applyHeader(conn, header1);
        if (header2 != null) applyHeader(conn, header2);
        byte[] data = body == null ? new byte[0] : body.getBytes("UTF-8");
        conn.getOutputStream().write(data);
        conn.getOutputStream().flush();
        conn.getOutputStream().close();
        return readHttpResponse(conn);
    }

    private static void applyHeader(HttpURLConnection conn, String headerLine) {
        int idx = headerLine.indexOf(':');
        if (idx <= 0) return;
        String key = headerLine.substring(0, idx).trim();
        String val = headerLine.substring(idx + 1).trim();
        if (!key.isEmpty()) conn.setRequestProperty(key, val);
    }

    private static String readHttpResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = null;
        try {
            in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("No response body (HTTP " + code + ")");
            byte[] data = readAllBytes(in);
            String text = new String(data, "UTF-8");
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + text);
            }
            return text;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
