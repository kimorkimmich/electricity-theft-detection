
package com.electricitytheft;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

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

    private static final String[] OUTPUT_HEADER = {
            "CONS_NO", "FLAG",
            "MEAN", "STD_DEV", "MIN", "MAX",
            "CV", "PAR", "TOTAL",
            "TREND", "LAG7_AUTOCORR",
            "CONSUMPTION_DEVIATION"
    };

    public static void main(String[] args) {

        try (
            Reader reader = Files.newBufferedReader(
                    INPUT, StandardCharsets.UTF_8);

            CSVParser parser = CSVFormat.DEFAULT.parse(reader);

            Writer writer = Files.newBufferedWriter(
                    OUTPUT, StandardCharsets.UTF_8);

            CSVPrinter printer = new CSVPrinter(
                    writer, CSVFormat.DEFAULT)
        ) {

            Iterator<CSVRecord> iterator = parser.iterator();

            if (!iterator.hasNext()) {
                throw new IllegalArgumentException(
                        "Dataset is empty");
            }

            CSVRecord header = iterator.next();

            int idIndex = -1;
            int flagIndex = -1;

            List<Integer> consumptionColumns =
                    new ArrayList<>();

            // Identify metadata and daily readings
            for (int i = 0; i < header.size(); i++) {

                String column = header.get(i)
                        .trim()
                        .replace("\uFEFF", "");

                if (column.equalsIgnoreCase("CONS_NO")) {
                    idIndex = i;
                } else if (column.equalsIgnoreCase("FLAG")) {
                    flagIndex = i;
                } else {
                    consumptionColumns.add(i);
                }
            }

            if (idIndex == -1 || flagIndex == -1) {
                throw new IllegalArgumentException(
                        "CONS_NO or FLAG column missing");
            }

            // Arrange daily readings chronologically.
            // Supports date columns such as 2014/1/1,
            // 2014-01-01, or 1/1/2014.
            consumptionColumns.sort(
                    Comparator.comparing(
                            i -> parseDate(header.get(i).trim())
                    )
            );

            printer.printRecord((Object[]) OUTPUT_HEADER);

            int customerCount = 0;

            while (iterator.hasNext()) {

                CSVRecord record = iterator.next();

                if (record.size() != header.size()) {
                    throw new IllegalArgumentException(
                            "Invalid CSV row: "
                            + record.getRecordNumber());
                }

                String customerId = record.get(idIndex);
                String flag = record.get(flagIndex);

                double[] readings =
                        new double[consumptionColumns.size()];

                for (int i = 0; i < readings.length; i++) {

                    String value = record.get(
                            consumptionColumns.get(i)).trim();

                    if (value.isEmpty()
                            || value.equalsIgnoreCase("NaN")
                            || value.equalsIgnoreCase("null")) {

                        readings[i] = Double.NaN;

                    } else {

                        readings[i] = Double.parseDouble(value);

                        if (!Double.isFinite(readings[i])) {
                            readings[i] = Double.NaN;
                        }
                    }
                }

                double mean = mean(readings);
                double std = standardDeviation(readings, mean);
                double min = minimum(readings);
                double max = maximum(readings);
                double total = total(readings);

                double cv = safeDivide(std, mean);
                double par = safeDivide(max, mean);

                double trend = consumptionTrend(readings);
                double lag7 = lag7Autocorrelation(readings);

                double deviation =
                        consumptionDeviation(readings);

                printer.printRecord(
                        customerId,
                        flag,
                        mean,
                        std,
                        min,
                        max,
                        cv,
                        par,
                        total,
                        trend,
                        lag7,
                        deviation
                );

                customerCount++;
            }

            printer.flush();

            System.out.println(
                    "Feature extraction completed.");
            System.out.println(
                    "Customers processed: " + customerCount);
            System.out.println(
                    "Features per customer: 10");
            System.out.println(
                    "Saved: " + OUTPUT);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Parse SGCC date column names
    private static LocalDate parseDate(String value) {

        String[] formats = {
                "yyyy/M/d",
                "yyyy-M-d",
                "M/d/yyyy",
                "M-d-yyyy",
                "d.M.yyyy"
        };

        for (String format : formats) {
            try {
                return LocalDate.parse(
                        value,
                        DateTimeFormatter.ofPattern(format));
            } catch (Exception ignored) {
                // Try the next format
            }
        }

        throw new IllegalArgumentException(
                "Unrecognized consumption date: " + value);
    }

    // 1. Mean consumption
    private static double mean(double[] values) {

        double sum = 0;
        int count = 0;

        for (double value : values) {
            if (Double.isFinite(value)) {
                sum += value;
                count++;
            }
        }

        return count == 0 ? 0 : sum / count;
    }

    // 2. Population standard deviation
    private static double standardDeviation(
            double[] values, double mean) {

        double sum = 0;
        int count = 0;

        for (double value : values) {
            if (Double.isFinite(value)) {
                sum += Math.pow(value - mean, 2);
                count++;
            }
        }

        return count == 0 ? 0 : Math.sqrt(sum / count);
    }

    // 3. Minimum consumption
    private static double minimum(double[] values) {

        double min = Double.POSITIVE_INFINITY;

        for (double value : values) {
            if (Double.isFinite(value)) {
                min = Math.min(min, value);
            }
        }

        return Double.isInfinite(min) ? 0 : min;
    }

    // 4. Maximum consumption
    private static double maximum(double[] values) {

        double max = Double.NEGATIVE_INFINITY;

        for (double value : values) {
            if (Double.isFinite(value)) {
                max = Math.max(max, value);
            }
        }

        return Double.isInfinite(max) ? 0 : max;
    }

    // 5. CV and 6. PAR use safe division
    private static double safeDivide(
            double numerator, double denominator) {

        if (Math.abs(denominator) < 1e-10) {
            return 0;
        }

        return numerator / denominator;
    }

    // 7. Total consumption
    private static double total(double[] values) {

        double sum = 0;

        for (double value : values) {
            if (Double.isFinite(value)) {
                sum += value;
            }
        }

        return sum;
    }

    // 8. Consumption trend: linear regression slope
    private static double consumptionTrend(double[] values) {

        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumXY = 0;

        int count = 0;

        for (int i = 0; i < values.length; i++) {

            if (!Double.isFinite(values[i])) {
                continue;
            }

            double x = i;
            double y = values[i];

            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;

            count++;
        }

        if (count < 2) {
            return 0;
        }

        double denominator =
                count * sumXX - sumX * sumX;

        if (Math.abs(denominator) < 1e-10) {
            return 0;
        }

        return (count * sumXY - sumX * sumY)
                / denominator;
    }

    // 9. Lag-7 autocorrelation
    private static double lag7Autocorrelation(
            double[] values) {

        List<Double> current = new ArrayList<>();
        List<Double> previous = new ArrayList<>();

        for (int i = 7; i < values.length; i++) {

            if (Double.isFinite(values[i])
                    && Double.isFinite(values[i - 7])) {

                current.add(values[i]);
                previous.add(values[i - 7]);
            }
        }

        if (current.size() < 2) {
            return 0;
        }

        double meanX = 0;
        double meanY = 0;

        for (int i = 0; i < current.size(); i++) {
            meanX += current.get(i);
            meanY += previous.get(i);
        }

        meanX /= current.size();
        meanY /= previous.size();

        double numerator = 0;
        double sumX2 = 0;
        double sumY2 = 0;

        for (int i = 0; i < current.size(); i++) {

            double dx = current.get(i) - meanX;
            double dy = previous.get(i) - meanY;

            numerator += dx * dy;
            sumX2 += dx * dx;
            sumY2 += dy * dy;
        }

        double denominator = Math.sqrt(sumX2 * sumY2);

        return safeDivide(numerator, denominator);
    }

    // 10. Relative deviation: latest 30 calendar days
    // compared with all earlier readings
    private static double consumptionDeviation(
            double[] values) {

        if (values.length <= RECENT_DAYS) {
            return 0;
        }

        int split = values.length - RECENT_DAYS;

        double[] historical =
                Arrays.copyOfRange(values, 0, split);

        double[] recent =
                Arrays.copyOfRange(
                        values, split, values.length);

        double historicalMean = mean(historical);
        double recentMean = mean(recent);

        return safeDivide(
                recentMean - historicalMean,
                historicalMean);
    }
}
