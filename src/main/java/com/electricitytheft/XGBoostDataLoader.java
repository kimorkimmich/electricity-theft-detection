
package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class XGBoostDataLoader {

    public static final String[] FEATURE_NAMES = {
        "MEAN",
        "STD_DEV",
        "MIN",
        "MAX",
        "CV",
        "PAR",
        "TOTAL",
        "TREND",
        "LAG7_AUTOCORR",
        "CONSUMPTION_DEVIATION"
    };

    private static final Path FEATURES_PATH =
            Paths.get("dataset", "sgcc_features.csv");

    private static final Path SPLITS_PATH =
            Paths.get("dataset", "sgcc_split_assignments.csv");

    public static class Consumer {

        public final String consumerId;
        public final int label;
        public final float[] features;
        public final boolean development;
        public final int fold;

        public Consumer(
                String consumerId,
                int label,
                float[] features,
                boolean development,
                int fold) {

            this.consumerId = consumerId;
            this.label = label;
            this.features = features;
            this.development = development;
            this.fold = fold;
        }
    }

    private static class SplitInfo {

        final int label;
        final boolean development;
        final int fold;

        SplitInfo(
                int label,
                boolean development,
                int fold) {

            this.label = label;
            this.development = development;
            this.fold = fold;
        }
    }

    public static List<Consumer> load() throws IOException {

        if (!Files.exists(FEATURES_PATH)) {
            throw new IOException(
                    "Missing file: " + FEATURES_PATH.toAbsolutePath());
        }

        if (!Files.exists(SPLITS_PATH)) {
            throw new IOException(
                    "Missing file: " + SPLITS_PATH.toAbsolutePath());
        }

        Map<String, SplitInfo> assignments =
                readSplitAssignments();

        List<Consumer> consumers = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        try (
            Reader reader = Files.newBufferedReader(
                    FEATURES_PATH,
                    StandardCharsets.UTF_8);

            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .get()
                    .parse(reader)
        ) {

            requireColumns(
                    parser,
                    "CONS_NO",
                    "FLAG");

            for (String feature : FEATURE_NAMES) {
                requireColumns(parser, feature);
            }

            for (CSVRecord record : parser) {

                String id = record.get("CONS_NO").trim();

                if (id.isEmpty()) {
                    throw new IOException("Empty consumer ID.");
                }

                if (!seenIds.add(id)) {
                    throw new IOException(
                            "Duplicate feature ID: " + id);
                }

                SplitInfo split = assignments.get(id);

                if (split == null) {
                    throw new IOException(
                            "No split assignment for: " + id);
                }

                int label = parseLabel(record.get("FLAG"));

                if (label != split.label) {
                    throw new IOException(
                            "FLAG mismatch for consumer: " + id);
                }

                float[] features =
                        new float[FEATURE_NAMES.length];

                for (int i = 0; i < FEATURE_NAMES.length; i++) {
                    features[i] = parseFeature(
                            record.get(FEATURE_NAMES[i]));
                }

                consumers.add(new Consumer(
                        id,
                        label,
                        features,
                        split.development,
                        split.fold));
            }
        }

        if (seenIds.size() != assignments.size()) {
            throw new IOException(
                    "Some split assignments have no matching feature row. "
                    + "Features: " + seenIds.size()
                    + ", assignments: " + assignments.size());
        }

        return consumers;
    }

    private static Map<String, SplitInfo>
            readSplitAssignments() throws IOException {

        Map<String, SplitInfo> assignments =
                new HashMap<>();

        try (
            Reader reader = Files.newBufferedReader(
                    SPLITS_PATH,
                    StandardCharsets.UTF_8);

            CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .get()
                    .parse(reader)
        ) {

            requireColumns(
                    parser,
                    "CONS_NO",
                    "FLAG",
                    "SPLIT",
                    "CV_FOLD");

            for (CSVRecord record : parser) {

                String id = record.get("CONS_NO").trim();

                if (id.isEmpty()) {
                    throw new IOException(
                            "Empty consumer ID in split file.");
                }

                int label = parseLabel(
                        record.get("FLAG"));

                String split = record.get("SPLIT")
                        .trim()
                        .toLowerCase();

                boolean development;

                if (split.equals("development")) {
                    development = true;
                } else if (
                        split.equals("test")
                        || split.equals("holdout")
                        || split.equals("final_test")) {
                    development = false;
                } else {
                    throw new IOException(
                            "Unknown SPLIT value: " + split);
                }

                int fold = -1;

                if (development) {

                    try {
                        fold = Integer.parseInt(
                                record.get("CV_FOLD").trim());
                    } catch (NumberFormatException ex) {
                        throw new IOException(
                                "Invalid CV fold for: " + id, ex);
                    }

                    if (fold < 0 || fold > 4) {
                        throw new IOException(
                                "CV_FOLD must be 0 to 4: " + id);
                    }
                }

                SplitInfo previous = assignments.put(
                        id,
                        new SplitInfo(
                                label,
                                development,
                                fold));

                if (previous != null) {
                    throw new IOException(
                            "Duplicate split ID: " + id);
                }
            }
        }

        return assignments;
    }

    private static void requireColumns(
            CSVParser parser,
            String... columns) throws IOException {

        Map<String, Integer> headers =
                parser.getHeaderMap();

        for (String column : columns) {

            if (!headers.containsKey(column)) {
                throw new IOException(
                        "Missing CSV column: " + column);
            }
        }
    }

    private static int parseLabel(
            String value) throws IOException {

        String cleaned = value.trim();

        if (cleaned.equals("0")) {
            return 0;
        }

        if (cleaned.equals("1")) {
            return 1;
        }

        throw new IOException(
                "Invalid FLAG value: " + value);
    }

    private static float parseFeature(
            String value) throws IOException {

        String cleaned = value.trim();

        // Missing feature values remain missing.
        // They are not replaced with zero.
        if (cleaned.isEmpty()
                || cleaned.equalsIgnoreCase("nan")) {
            return Float.NaN;
        }

        try {

            double parsed = Double.parseDouble(cleaned);

            if (!Double.isFinite(parsed)
                    || Math.abs(parsed) > Float.MAX_VALUE) {

                throw new IOException(
                        "Invalid feature value: " + value);
            }

            return (float) parsed;

        } catch (NumberFormatException ex) {

            throw new IOException(
                    "Cannot parse feature: " + value, ex);
        }
    }
}
