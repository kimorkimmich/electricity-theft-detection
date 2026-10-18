
package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

public class FeatureExtractor {

    private static final Path INPUT =
            Paths.get("dataset", "sgcc_cleaned.csv");

    private static final Path OUTPUT =
            Paths.get("dataset", "sgcc_features.csv");

    private static final int RECENT_DAYS = 30;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("M/d/uuuu");

    // Stores a consumption column and its date.
    private static class DateColumn {
        int index;
        LocalDate date;

        DateColumn(int index, LocalDate date) {
            this.index = index;
            this.date = date;
        }
    }

    public static void main(String[] args) throws IOException {

        System.out.println("Starting SGCC feature extraction...");

        if (!Files.exists(INPUT)) {
            throw new IOException(
                    "Input dataset not found: "
                    + INPUT.toAbsolutePath());
        }

        int processed = 0;
        int skipped = 0;

        try (
            Reader reader = Files.newBufferedReader(
                    INPUT, StandardCharsets.UTF_8);

            CSVParser parser =
                    CSVFormat.DEFAULT.parse(reader);

            Writer writer = Files.newBufferedWriter(
                    OUTPUT, StandardCharsets.UTF_8);

            CSVPrinter printer = new CSVPrinter(
                    writer, CSVFormat.DEFAULT)
        ) {

            // Read the first CSV row as the header.
            java.util.Iterator<CSVRecord> iterator =
                    parser.iterator();

            if (!iterator.hasNext()) {
                throw new IOException("Input CSV is empty.");
            }

            CSVRecord header = iterator.next();

            int idIndex = -1;
            int flagIndex = -1;

            List<DateColumn> dateColumns =
                    new ArrayList<>();

            // Identify metadata and date columns.
            for (int i = 0; i < header.size(); i++) {

                String name = header.get(i).trim()
                        .replace("\uFEFF", "");

                String normalized = name
                        .replace("_", "")
                        .replace(" ", "")
                        .toUpperCase(Locale.ROOT);

                if (normalized.equals("CONSNO")
                        || normalized.equals("CUSTOMERID")) {

                    idIndex = i;
                    continue;
                }

                if (normalized.equals("FLAG")
                        || normalized.equals("LABEL")
                        || normalized.equals("ISTHEFT")) {

                    flagIndex = i;
                    continue;
                }

                try {
                    LocalDate date =
                            LocalDate.parse(name, DATE_FORMAT);

                    dateColumns.add(new DateColumn(i, date));

                } catch (DateTimeParseException ignored) {
                    // Non-date columns are not consumption readings.
                }
            }

            if (flagIndex == -1) {
                throw new IOException(
                        "Theft label column (FLAG) not found.");
            }

            if (dateColumns.isEmpty()) {
                throw new IOException(
                        "No daily consumption date columns found.");
            }

            // Ensure chronological order.
            dateColumns.sort(
                    Comparator.comparing(column -> column.date));

            System.out.println(
                    "Daily consumption columns: "
                    + dateColumns.size());

            System.out.println(
                    "First date: " + dateColumns.get(0).date);

            System.out.println(
                    "Last date: "
                    + dateColumns.get(dateColumns.size() - 1).date);

            System.out.println(
                    "Customer ID column found: " + (idIndex >= 0));

            // Write output CSV header.
            printer.printRecord(
                    "CONS_NO",
                    "FLAG",
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
            );

            // Process every customer.
            while (iterator.hasNext()) {

                CSVRecord record = iterator.next();

                if (record.size() != header.size()) {
                    skipped++;
                    continue;
                }

                String customerId = idIndex >= 0
                        ? record.get(idIndex).trim()
                        : String.valueOf(processed + skipped + 1);

                String flag = record.get(flagIndex).trim();

                double[] readings =
                        new double[dateColumns.size()];

                // Extract daily readings in date order.
                for (int i = 0; i < dateColumns.size(); i++) {

                    String value = record.get(
                            dateColumns.get(i).index).trim();

                    readings[i] = parseConsumption(value);
                }

                double[] features =
                        calculateFeatures(readings);

                printer.printRecord(
                        customerId,
                        flag,
                        features[0],
                        features[1],
                        features[2],
                        features[3],
                        features[4],
                        features[5],
                        features[6],
                        features[7],
                        features[8],
                        features[9]
                );

                processed++;

                if (processed % 5000 == 0) {
                    System.out.println(
                            "Processed " + processed + " customers");
                }
            }

        }

        System.out.println("\nFeature extraction completed!");
        System.out.println("Customers processed: " + processed);
        System.out.println("Rows skipped: " + skipped);
        System.out.println(
                "Output: " + OUTPUT.toAbsolutePath());
    }

    // Converts a CSV value into a numeric reading.
    private static double parseConsumption(String value) {

        if (value.isEmpty()
                || value.equalsIgnoreCase("NaN")
                || value.equalsIgnoreCase("null")
                || value.equalsIgnoreCase("NA")) {

            return Double.NaN;
        }

        try {
            double result = Double.parseDouble(value);

            return Double.isFinite(result)
                    ? result
                    : Double.NaN;

        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // Calculates all 10 numerical features.
    private static double[] calculateFeatures(double[] readings) {

        List<Double> valid = new ArrayList<>();

        for (double value : readings) {
            if (Double.isFinite(value)) {
                valid.add(value);
            }
        }

        int n = valid.size();

        double[] result = new double[10];

        if (n == 0) {
            return result;
        }

        // FEATURE 1: Mean consumption
        double total = 0;

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (double value : valid) {

            total += value;

            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double mean = total / n;

        // FEATURE 2: Population standard deviation
        double squaredDifferenceSum = 0;

        for (double value : valid) {
            squaredDifferenceSum +=
                    Math.pow(value - mean, 2);
        }

        double stdDev =
                Math.sqrt(squaredDifferenceSum / n);

        // FEATURE 3: Minimum consumption
        // Already calculated as min.

        // FEATURE 4: Maximum consumption
        // Already calculated as max.

        // FEATURE 5: Coefficient of variation
        double cv = mean != 0
                ? stdDev / Math.abs(mean)
                : 0;

        // FEATURE 6: Peak-to-average ratio
        double par = mean != 0
                ? max / mean
                : 0;

        // FEATURE 7: Total consumption
        // Already calculated as total.

        // FEATURE 8: Linear consumption trend
        // Uses actual day positions to preserve time gaps.
        double trend = calculateTrend(readings);

        // FEATURE 9: Lag-7 autocorrelation
        double lag7 = calculateAutocorrelation(readings, 7);

        // FEATURE 10: Recent consumption deviation
        double deviation =
                calculateDeviation(readings, RECENT_DAYS);

        result[0] = finiteOrZero(mean);
        result[1] = finiteOrZero(stdDev);
        result[2] = finiteOrZero(min);
        result[3] = finiteOrZero(max);
        result[4] = finiteOrZero(cv);
        result[5] = finiteOrZero(par);
        result[6] = finiteOrZero(total);
        result[7] = finiteOrZero(trend);
        result[8] = finiteOrZero(lag7);
        result[9] = finiteOrZero(deviation);

        return result;
    }

    // Linear regression slope of consumption over time.
    private static double calculateTrend(double[] readings) {

        int n = 0;

        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumXY = 0;

        for (int i = 0; i < readings.length; i++) {

            double y = readings[i];

            if (!Double.isFinite(y)) {
                continue;
            }

            double x = i;

            n++;

            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
        }

        if (n < 2) {
            return 0;
        }

        double denominator =
                n * sumXX - sumX * sumX;

        if (denominator == 0) {
            return 0;
        }

        return (n * sumXY - sumX * sumY)
                / denominator;
    }

    // Pearson autocorrelation at a specified lag.
    private static double calculateAutocorrelation(
            double[] readings, int lag) {

        if (readings.length <= lag) {
            return 0;
        }

        int count = 0;

        double sumX = 0;
        double sumY = 0;

        // Only include pairs where both readings exist.
        for (int i = lag; i < readings.length; i++) {

            double x = readings[i - lag];
            double y = readings[i];

            if (Double.isFinite(x)
                    && Double.isFinite(y)) {

                sumX += x;
                sumY += y;
                count++;
            }
        }

        if (count < 2) {
            return 0;
        }

        double meanX = sumX / count;
        double meanY = sumY / count;

        double numerator = 0;
        double denominatorX = 0;
        double denominatorY = 0;

        for (int i = lag; i < readings.length; i++) {

            double x = readings[i - lag];
            double y = readings[i];

            if (!Double.isFinite(x)
                    || !Double.isFinite(y)) {
                continue;
            }

            double dx = x - meanX;
            double dy = y - meanY;

            numerator += dx * dy;
            denominatorX += dx * dx;
            denominatorY += dy * dy;
        }

        double denominator =
                Math.sqrt(denominatorX * denominatorY);

        if (denominator == 0) {
            return 0;
        }

        return numerator / denominator;
    }

    // Compare the latest 30 valid readings
    // with all earlier valid readings.
    private static double calculateDeviation(
            double[] readings, int recentDays) {

        List<Double> valid = new ArrayList<>();

        for (double value : readings) {
            if (Double.isFinite(value)) {
                valid.add(value);
            }
        }

        int n = valid.size();

        if (n <= recentDays) {
            return 0;
        }

        double recentSum = 0;
        double historicalSum = 0;

        int split = n - recentDays;

        for (int i = 0; i < split; i++) {
            historicalSum += valid.get(i);
        }

        for (int i = split; i < n; i++) {
            recentSum += valid.get(i);
        }

        double historicalMean =
                historicalSum / split;

        double recentMean =
                recentSum / recentDays;

        if (historicalMean == 0) {
            return 0;
        }

        return (recentMean - historicalMean)
                / Math.abs(historicalMean);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0;
    }
}
