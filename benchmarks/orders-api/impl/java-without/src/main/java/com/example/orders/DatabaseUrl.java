package com.example.orders;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Converts a {@code postgres://user:pass@host:port/db?params} URL into a JDBC URL plus credentials.
 * A URL that already starts with {@code jdbc:} is passed through unchanged.
 */
record DatabaseUrl(String jdbcUrl, String username, String password) {

    static DatabaseUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL is not set");
        }
        if (raw.startsWith("jdbc:")) {
            return new DatabaseUrl(raw, null, null);
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            // Do not include the URL itself: it contains the password.
            throw new IllegalArgumentException("DATABASE_URL is not a valid URL (percent-encode special characters in credentials)");
        }
        if (!"postgres".equals(uri.getScheme()) && !"postgresql".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must use the postgres:// or postgresql:// scheme");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("DATABASE_URL has no host");
        }

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = uri.getRawPath();
        String database = path == null || path.length() <= 1 ? "" : path.substring(1);
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost()).append(':').append(port).append('/').append(database);
        if (uri.getRawQuery() != null) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }

        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        }
        return new DatabaseUrl(jdbcUrl.toString(), username, password);
    }

    private static String decode(String s) {
        // URLDecoder implements form decoding; '+' is a literal plus in URL userinfo.
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "DatabaseUrl[jdbcUrl=" + jdbcUrl + ", username=" + username + "]";
    }
}
