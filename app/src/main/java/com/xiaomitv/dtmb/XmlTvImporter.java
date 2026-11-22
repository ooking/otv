package com.xiaomitv.dtmb;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Xml;

import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

final class XmlTvImporter {
    static final String HONG_KONG_FEED = "https://epg.pw/xmltv/epg_HK.xml.gz";
    private static final ZoneOffset HONG_KONG_OFFSET = ZoneOffset.ofHours(8);
    private static final long MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_MATCHED_PROGRAMS = 50_000;
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

    static Map<String, List<Program>> read(ContentResolver resolver, Uri uri, List<Channel> channels,
                                            long now) throws IOException, XmlPullParserException {
        InputStream source = resolver.openInputStream(uri);
        if (source == null) throw new IOException("Cannot open XMLTV");
        return read(source, channels, now, false);
    }

    static Map<String, List<Program>> download(URL url, List<Channel> channels, long now)
            throws IOException, XmlPullParserException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("XMLTV download returned a non-OK response");
            }
            return read(connection.getInputStream(), channels, now,
                    HONG_KONG_FEED.equals(url.toExternalForm()));
        } finally {
            connection.disconnect();
        }
    }

    private static Map<String, List<Program>> read(InputStream source, List<Channel> channels,
                                                   long now, boolean hongKongFeed) throws IOException, XmlPullParserException {
        Map<String, String> names = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        for (Channel channel : channels) {
            String name = normalize(channel.name());
            if (name.isEmpty()) continue;
            String previous = names.put(name, channel.guideKey());
            if (previous != null) ambiguousNames.add(name);
        }
        for (String name : ambiguousNames) names.remove(name);

        Map<String, String> feedChannels = new HashMap<>();
        Map<String, List<Program>> result = new HashMap<>();

        try (BufferedInputStream buffer = new BufferedInputStream(source)) {
            buffer.mark(2);
            int first = buffer.read();
            int second = buffer.read();
            buffer.reset();
            try (InputStream stream = new BoundedStream(
                    first == 0x1f && second == 0x8b ? new GZIPInputStream(buffer) : buffer)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
                parser.setInput(stream, null);
                parser.nextTag();
                if (!"tv".equals(parser.getName())) throw new IOException("Invalid XMLTV root");

                int matchedPrograms = 0;
                for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT;
                     event = parser.next()) {
                    if (event != XmlPullParser.START_TAG || parser.getDepth() != 2) continue;
                    if ("channel".equals(parser.getName())) {
                        String id = parser.getAttributeValue(null, "id");
                        String key = readChannel(parser, names);
                        if (id != null && key != null) feedChannels.putIfAbsent(id, key);
                    } else if ("programme".equals(parser.getName())) {
                        String key = feedChannels.get(parser.getAttributeValue(null, "channel"));
                        if (key == null) continue;
                        long start = time(parser.getAttributeValue(null, "start"), hongKongFeed);
                        long end = time(parser.getAttributeValue(null, "stop"), hongKongFeed);
                        if (start == Long.MIN_VALUE || start >= now + GuideCache.WINDOW
                                || end <= now || end <= start) continue;
                        Program program = readProgram(parser, start, end);
                        if (program.title().isEmpty()) continue;
                        if (++matchedPrograms > MAX_MATCHED_PROGRAMS) {
                            throw new IOException("Too many matched XMLTV programmes");
                        }
                        result.computeIfAbsent(key, ignored -> new ArrayList<>())
                                .add(program);
                    }
                }
            }
        }
        return result;
    }

    private static String readChannel(XmlPullParser parser, Map<String, String> names)
            throws IOException, XmlPullParserException {
        int depth = parser.getDepth();
        Set<String> keys = new HashSet<>();
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            if (parser.getEventType() == XmlPullParser.START_TAG && parser.getDepth() == depth + 1
                    && "display-name".equals(parser.getName())) {
                String key = names.get(normalize(parser.nextText()));
                if (key != null) keys.add(key);
            }
        }
        return keys.size() == 1 ? keys.iterator().next() : null;
    }

    private static Program readProgram(XmlPullParser parser, long start, long end)
            throws IOException, XmlPullParserException {
        int depth = parser.getDepth();
        String title = "";
        String description = "";
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            if (parser.getEventType() != XmlPullParser.START_TAG || parser.getDepth() != depth + 1) continue;
            if (title.isEmpty() && "title".equals(parser.getName())) {
                String value = parser.nextText().trim();
                title = value.substring(0, Math.min(value.length(), 300));
            } else if (description.isEmpty() && "desc".equals(parser.getName())) {
                description = parser.nextText();
            }
        }
        return new Program(title, start, end, description);
    }

    private static long time(String value, boolean hongKongFeed) {
        if (value == null) return Long.MIN_VALUE;
        try {
            String[] parts = value.trim().split("\\s+", 2);
            DateTimeFormatter formatter = parts[0].length() == 12 ? MINUTES : SECONDS;
            ZoneOffset offset = parts.length == 1 ? ZoneOffset.UTC : ZoneOffset.of(parts[1]);
            // epg.pw HK publishes Hong Kong wall times with a literal +0000 suffix.
            if (hongKongFeed && parts.length == 2 && "+0000".equals(parts[1])) {
                offset = HONG_KONG_OFFSET;
            }
            return LocalDateTime.parse(parts[0], formatter).toInstant(offset).toEpochMilli();
        } catch (java.time.DateTimeException invalid) {
            return Long.MIN_VALUE;
        }
    }

    private static String normalize(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
        return switch (normalized) {
            case "港台電視31", "港台电视31", "rthktv31" -> "港台电视31";
            case "港台電視32", "港台电视32", "rthktv32" -> "港台电视32";
            case "cgtn中國環球電視網英語頻道", "cgtn英語頻道" -> "cgtn英語頻道";
            case "cgtn中國環球電視網記錄頻道", "中國環球電視網紀錄頻道cgtn紀錄頻道" -> "cgtn紀錄頻道";
            default -> normalized;
        };
    }

    private static final class BoundedStream extends FilterInputStream {
        private long remaining = MAX_BYTES;

        BoundedStream(InputStream source) {
            super(source);
        }

        @Override
        public int read() throws IOException {
            if (remaining-- <= 0) throw new IOException("XMLTV exceeds size limit");
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) throw new IOException("XMLTV exceeds size limit");
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read >= 0) remaining -= read;
            return read;
        }
    }
}
