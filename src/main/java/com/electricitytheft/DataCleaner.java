package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/** Cleans SGCC data and produces uncapped, capped and strictly outlier-free variants. */
public final class DataCleaner {
    private DataCleaner() {}

    public static final class CleaningResult {
        public Path input, cleaned, uncapped, noOutliers, report, log, duplicateConflicts;
        public long inputRows, retainedRows, removedEmpty, removedDuplicates, removedMetadata;
        public long identicalDuplicates, conflictingDuplicates, conflictingLabels, conflictingReadings;
        public long missingFound, invalidFound, missingFilled, invalidFilled;
        public long outliersCapped, normalRows, fraudRows, outlierFreeRows;
        public long rowsWithMissing, rowsWithInvalid, rowsWithOutliers;

        public String summary() {
            return String.format(Locale.US,
                    "Input: %,d | Retained: %,d | Removed: %,d | Duplicate conflicts: %,d | " +
                    "Missing: %,d (repaired %,d) | Invalid: %,d (repaired %,d) | " +
                    "High outliers: %,d | Outlier-free consumers: %,d",
                    inputRows, retainedRows, inputRows - retainedRows, conflictingDuplicates,
                    missingFound, missingFilled, invalidFound, invalidFilled,
                    outliersCapped, outlierFreeRows);
        }
    }

    private static final class ConsumerSignature {
        final String flag;
        final String readingDigest;
        final long firstRow;

        ConsumerSignature(String flag, String readingDigest, long firstRow) {
            this.flag = flag;
            this.readingDigest = readingDigest;
            this.firstRow = firstRow;
        }
    }

    /** Default date format is month/day/year, e.g. 1/31/2014. */
    public static CleaningResult clean(Path input, Path outputDirectory) throws IOException {
        return clean(input, outputDirectory, "M/d/uuuu");
    }

    /** Supply the exact date-header format if the source CSV uses a different convention. */
    public static CleaningResult clean(Path input, Path outputDirectory, String datePattern)
            throws IOException {
        if (!Files.isRegularFile(input))
            throw new IOException("Raw SGCC file not found: " + input);

        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(datePattern, Locale.US)
                    .withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid date format pattern: " + datePattern, ex);
        }

        Files.createDirectories(outputDirectory);
        CleaningResult stats = new CleaningResult();
        stats.input = input.toAbsolutePath().normalize();
        stats.cleaned = outputDirectory.resolve("sgcc_cleaned.csv").toAbsolutePath().normalize();
        stats.uncapped = outputDirectory.resolve("sgcc_cleaned_uncapped.csv").toAbsolutePath().normalize();
        stats.noOutliers = outputDirectory.resolve("sgcc_no_outliers.csv").toAbsolutePath().normalize();
        stats.report = outputDirectory.resolve("cleaning_report.txt").toAbsolutePath().normalize();
        stats.log = outputDirectory.resolve("cleaning_changes.csv").toAbsolutePath().normalize();
        stats.duplicateConflicts = outputDirectory.resolve("duplicate_conflicts.csv").toAbsolutePath().normalize();

        for (Path target : new Path[] { stats.cleaned, stats.uncapped, stats.noOutliers,
                stats.report, stats.log, stats.duplicateConflicts }) {
            if (stats.input.equals(target))
                throw new IOException("Input file must not be used as an output: " + target);
        }

        CSVFormat fmt = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).get();
        Map<String, ConsumerSignature> seen = new HashMap<>();

        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             CSVParser parser = fmt.parse(reader);
             Writer cappedOut = Files.newBufferedWriter(stats.cleaned, StandardCharsets.UTF_8);
             CSVPrinter cappedPrinter = new CSVPrinter(cappedOut, fmt);
             Writer uncappedOut = Files.newBufferedWriter(stats.uncapped, StandardCharsets.UTF_8);
             CSVPrinter uncappedPrinter = new CSVPrinter(uncappedOut, fmt);
             Writer noOutlierOut = Files.newBufferedWriter(stats.noOutliers, StandardCharsets.UTF_8);
             CSVPrinter noOutlierPrinter = new CSVPrinter(noOutlierOut, fmt);
             Writer logOut = Files.newBufferedWriter(stats.log, StandardCharsets.UTF_8);
             CSVPrinter log = new CSVPrinter(logOut, fmt);
             Writer conflictsOut = Files.newBufferedWriter(stats.duplicateConflicts, StandardCharsets.UTF_8);
             CSVPrinter conflicts = new CSVPrinter(conflictsOut, fmt)) {

            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) throw new IOException("Raw CSV is empty.");
            CSVRecord header = it.next();
            String[] names = new String[header.size()];
            int idCol = -1, flagCol = -1;
            for (int j = 0; j < names.length; j++) {
                names[j] = header.get(j).replace("\uFEFF", "").trim();
                if (names[j].equalsIgnoreCase("CONS_NO")) {
                    if (idCol >= 0) throw new IOException("Duplicate CONS_NO header");
                    idCol = j;
                }
                if (names[j].equalsIgnoreCase("FLAG")) {
                    if (flagCol >= 0) throw new IOException("Duplicate FLAG header");
                    flagCol = j;
                }
            }
            if (idCol < 0 || flagCol < 0)
                throw new IOException("Required CONS_NO and FLAG columns not found.");
            if (names.length <= 2) throw new IOException("No consumption columns found.");

            // Fail fast if a header cannot be parsed, or two headers refer to the same day.
            List<Integer> columns = new ArrayList<>();
            Map<Integer, LocalDate> parsedDates = new HashMap<>();
            Set<LocalDate> uniqueDates = new HashSet<>();
            for (int j = 0; j < names.length; j++) {
                if (j == idCol || j == flagCol) continue;
                LocalDate date;
                try {
                    date = LocalDate.parse(names[j], formatter);
                } catch (DateTimeParseException ex) {
                    throw new IOException("Invalid date header '" + names[j] +
                            "'. Expected format " + datePattern, ex);
                }
                if (!uniqueDates.add(date))
                    throw new IOException("Duplicate date header: " + names[j] + " (" + date + ")");
                columns.add(j);
                parsedDates.put(j, date);
            }
            columns.sort((x, y) -> parsedDates.get(x).compareTo(parsedDates.get(y)));
            int[] positions = columns.stream().mapToInt(Integer::intValue).toArray();

            // Retain source column order in all outputs; use chronological order for interpolation.
            cappedPrinter.printRecord((Object[]) names);
            uncappedPrinter.printRecord((Object[]) names);
            noOutlierPrinter.printRecord((Object[]) names);
            log.printRecord("CONS_NO", "COLUMN", "ORIGINAL", "CLEANED", "ACTION");
            conflicts.printRecord("CONS_NO", "FIRST_INPUT_ROW", "DUPLICATE_INPUT_ROW",
                    "ORIGINAL_FLAG", "DUPLICATE_FLAG", "CONFLICT_TYPE");

            while (it.hasNext()) {
                CSVRecord rec = it.next();
                stats.inputRows++;
                if (rec.size() != names.length) {
                    stats.removedMetadata++;
                    continue;
                }
                String[] cells = new String[names.length];
                for (int j = 0; j < cells.length; j++) cells[j] = rec.get(j).trim();
                String id = cells[idCol], flag = cells[flagCol];
                if (id.isBlank() || !(flag.equals("0") || flag.equals("1"))) {
                    stats.removedMetadata++;
                    continue;
                }

                // Compare duplicates against the first retained original row (not its cleaned version).
                // SHA-256 keeps memory usage modest even with thousands of date columns.
                String fingerprint = readingsDigest(cells, positions);
                ConsumerSignature previous = seen.get(id);
                if (previous != null) {
                    stats.removedDuplicates++;
                    boolean flagConflict = !previous.flag.equals(flag);
                    boolean readingConflict = !previous.readingDigest.equals(fingerprint);
                    if (!flagConflict && !readingConflict) {
                        stats.identicalDuplicates++;
                    } else {
                        stats.conflictingDuplicates++;
                        if (flagConflict) stats.conflictingLabels++;
                        if (readingConflict) stats.conflictingReadings++;
                        String kind = flagConflict && readingConflict ? "FLAG_AND_READINGS" :
                                flagConflict ? "FLAG" : "READINGS";
                        conflicts.printRecord(id, previous.firstRow, stats.inputRows,
                                previous.flag, flag, kind);
                    }
                    continue;  // Never silently merge conflicting labels or readings.
                }

                int k = positions.length;
                double[] a = new double[k];
                boolean[] gaps = new boolean[k], invalids = new boolean[k];
                int valid = 0;
                boolean anyMissing = false, anyInvalid = false, anyOutlier = false;
                for (int j = 0; j < k; j++) {
                    String s = cells[positions[j]];
                    if (s.isBlank() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nan")) {
                        a[j] = Double.NaN;
                        gaps[j] = true;
                        stats.missingFound++;
                        anyMissing = true;
                    } else {
                        try {
                            double value = Double.parseDouble(s);
                            if (!Double.isFinite(value) || value < 0)
                                throw new NumberFormatException();
                            a[j] = value;
                            valid++;
                        } catch (NumberFormatException ex) {
                            a[j] = Double.NaN;
                            invalids[j] = true;
                            stats.invalidFound++;
                            anyInvalid = true;
                        }
                    }
                }
                if (anyMissing) stats.rowsWithMissing++;
                if (anyInvalid) stats.rowsWithInvalid++;
                if (valid == 0) {
                    stats.removedEmpty++;
                    continue;
                }
                seen.put(id, new ConsumerSignature(flag, fingerprint, stats.inputRows));

                // Fill internal gaps by linear interpolation; edges by nearest valid reading.
                int prev = -1;
                for (int j = 0; j < k; j++) {
                    if (!Double.isFinite(a[j])) continue;
                    if (prev < 0) {
                        for (int z = 0; z < j; z++) a[z] = a[j];
                    } else if (j - prev > 1) {
                        double from = a[prev], to = a[j];
                        for (int z = prev + 1; z < j; z++)
                            a[z] = from + (to - from) * (z - prev) / (j - prev);
                    }
                    prev = j;
                }
                for (int z = prev + 1; z < k; z++) a[z] = a[prev];
                for (int j = 0; j < k; j++) {
                    if (!gaps[j] && !invalids[j]) continue;
                    if (gaps[j]) stats.missingFilled++; else stats.invalidFilled++;
                    log.printRecord(id, names[positions[j]], cells[positions[j]],
                            Double.toString(a[j]), gaps[j] ? "INTERPOLATED_MISSING" : "INTERPOLATED_INVALID");
                }

                // The uncapped dataset preserves repaired values, including all high spikes.
                String[] uncappedCells = cells.clone();
                for (int j = 0; j < k; j++)
                    uncappedCells[positions[j]] = Double.toString(a[j]);
                uncappedPrinter.printRecord((Object[]) uncappedCells);

                // Calculate per-consumer high outlier threshold on the repaired sequence.
                double mean = 0;
                for (double v : a) mean += v;
                mean /= k;
                double variance = 0;
                for (double v : a) variance += (v - mean) * (v - mean);
                variance /= k;
                double threshold = mean + 2 * Math.sqrt(variance);

                // Keep a capped version matching the original cleaner's behavior.
                String[] cappedCells = uncappedCells.clone();
                for (int j = 0; j < k; j++) {
                    if (a[j] > threshold) {
                        cappedCells[positions[j]] = Double.toString(threshold);
                        stats.outliersCapped++;
                        anyOutlier = true;
                        log.printRecord(id, names[positions[j]], Double.toString(a[j]),
                                Double.toString(threshold), "CAPPED_HIGH_OUTLIER");
                    }
                }
                cappedPrinter.printRecord((Object[]) cappedCells);
                if (anyOutlier) stats.rowsWithOutliers++;
                else {
                    // Truly no-outlier consumer dataset: omit any consumer having a high spike.
                    noOutlierPrinter.printRecord((Object[]) uncappedCells);
                    stats.outlierFreeRows++;
                }

                stats.retainedRows++;
                if (flag.equals("0")) stats.normalRows++; else stats.fraudRows++;
            }
        }

        List<String> report = Arrays.asList(
                "SGCC DATA CLEANING REPORT", "========================",
                "Input: " + stats.input,
                "Capped cleaned CSV: " + stats.cleaned,
                "Uncapped cleaned CSV (retains outliers): " + stats.uncapped,
                "No-outlier consumer CSV (excludes consumers with high outliers): " + stats.noOutliers,
                "Change log: " + stats.log,
                "Duplicate conflict log: " + stats.duplicateConflicts,
                "Date-header format: " + datePattern,
                "Total input consumer rows: " + stats.inputRows,
                "Retained consumer rows: " + stats.retainedRows,
                "Removed consumer rows: " + (stats.inputRows - stats.retainedRows),
                "Removed invalid metadata/row width: " + stats.removedMetadata,
                "Removed duplicate consumer IDs: " + stats.removedDuplicates,
                "Identical duplicates: " + stats.identicalDuplicates,
                "Conflicting duplicate rows: " + stats.conflictingDuplicates,
                "Duplicate rows with conflicting fraud labels: " + stats.conflictingLabels,
                "Duplicate rows with conflicting readings: " + stats.conflictingReadings,
                "Removed rows with no usable readings: " + stats.removedEmpty,
                "Missing reading cells found: " + stats.missingFound,
                "Invalid reading cells found: " + stats.invalidFound,
                "Missing readings repaired in retained rows: " + stats.missingFilled,
                "Invalid readings repaired in retained rows: " + stats.invalidFilled,
                "High outlier readings capped in capped CSV: " + stats.outliersCapped,
                "Retained rows with high outliers: " + stats.rowsWithOutliers,
                "Consumers in no-outlier CSV: " + stats.outlierFreeRows,
                "Normal consumers retained: " + stats.normalRows,
                "Fraud-labelled consumers retained: " + stats.fraudRows,
                "Method: linear interpolation for internal gaps; nearest-value fill at edges.",
                "Outlier threshold: per-consumer repaired mean + 2 population SD.",
                "The uncapped CSV preserves spikes; the capped CSV replaces high spikes with the threshold.",
                "The no-outlier CSV excludes whole consumers having at least one high spike.",
                "No normalization or class balancing applied. Raw input remains unchanged.",
                "Missing/invalid counts may include subsequently removed all-empty rows.");
        Files.write(stats.report, report, StandardCharsets.UTF_8);
        return stats;
    }

    private static String readingsDigest(String[] cells, int[] positions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int position : positions) {
                byte[] bytes = cells[position].getBytes(StandardCharsets.UTF_8);
                int len = bytes.length;
                digest.update(new byte[] { (byte)(len >>> 24), (byte)(len >>> 16),
                        (byte)(len >>> 8), (byte)len });
                digest.update(bytes);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static void main(String[] args) throws Exception {
        Path input = Paths.get(args.length > 0 ? args[0] : "dataset/data set.csv");
        Path output = Paths.get(args.length > 1 ? args[1] : "dataset");
        String datePattern = args.length > 2 ? args[2] : "M/d/uuuu";
        CleaningResult result = clean(input, output, datePattern);
        System.out.println(result.summary());
        System.out.println("Capped CSV: " + result.cleaned);
        System.out.println("Uncapped CSV: " + result.uncapped);
        System.out.println("No-outlier CSV: " + result.noOutliers);
        System.out.println("Duplicate conflicts: " + result.duplicateConflicts);
        System.out.println("Report: " + result.report);
    }
}
