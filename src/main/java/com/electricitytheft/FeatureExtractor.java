package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/** Extracts 17 engineered electricity-consumption features per SGCC consumer. */
public final class FeatureExtractor {
    private FeatureExtractor() {}

    private static final int WINDOW = 30;
    private static final int MIN_MONTH_DAYS = 20;
    private static final double LOW_USAGE_FRACTION = 0.50;

    private record DatedColumn(String name, LocalDate day) {}

    public static Path extract(Path input, Path output) throws IOException {
        return extract(input, output, "M/d/uuuu");
    }

    /** Date format must match the format passed to DataCleaner. */
    public static Path extract(Path input, Path output, String datePattern) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source))
            throw new IOException("Cleaned dataset does not exist: " + source);
        if (source.equals(destination))
            throw new IOException("Input and output paths must differ.");

        final DateTimeFormatter dateFormat;
        try {
            dateFormat = DateTimeFormatter.ofPattern(datePattern, Locale.US)
                    .withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid date pattern: " + datePattern, e);
        }

        Files.createDirectories(destination.getParent());
        CSVFormat csvFormat = CSVFormat.DEFAULT;

        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
             CSVParser parser = csvFormat.builder()
                     .setHeader().setSkipHeaderRecord(true).get().parse(reader);
             Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8);
             CSVPrinter csv = new CSVPrinter(writer, csvFormat)) {

            Map<String, Integer> columns = parser.getHeaderMap();
            List<String> originalHeaders = parser.getHeaderNames();
            if (columns.size() != originalHeaders.size())
                throw new IOException("Duplicate column names in CSV header.");
            if (!columns.containsKey("CONS_NO") || !columns.containsKey("FLAG"))
                throw new IOException("Required CONS_NO and FLAG columns are missing.");

            List<DatedColumn> dated = new ArrayList<>();
            Set<LocalDate> uniqueDays = new HashSet<>();
            for (String header : originalHeaders) {
                if (header.equals("CONS_NO") || header.equals("FLAG")) continue;
                LocalDate day;
                try {
                    day = LocalDate.parse(header.trim(), dateFormat);
                } catch (DateTimeParseException e) {
                    throw new IOException("Invalid date header '" + header
                            + "' (expected " + datePattern + ")", e);
                }
                if (!uniqueDays.add(day))
                    throw new IOException("Duplicate calendar date in headers: " + day);
                dated.add(new DatedColumn(header, day));
            }
            if (dated.isEmpty()) throw new IOException("No daily consumption columns found.");
            dated.sort((a, b) -> a.day().compareTo(b.day()));

            int n = dated.size();
            String[] names = new String[n];
            LocalDate[] days = new LocalDate[n];
            int[] lag7Index = new int[n];
            int[] continuityBreaks = new int[n + 1];
            Map<LocalDate, Integer> dayPositions = new HashMap<>();
            for (int i = 0; i < n; i++) {
                names[i] = dated.get(i).name();
                days[i] = dated.get(i).day();
                dayPositions.put(days[i], i);
                if (i > 0) {
                    continuityBreaks[i + 1] = continuityBreaks[i]
                            + (days[i - 1].plusDays(1).equals(days[i]) ? 0 : 1);
                }
            }
            Arrays.fill(lag7Index, -1);
            for (int i = 0; i < n; i++) {
                Integer previousWeek = dayPositions.get(days[i].minusDays(7));
                if (previousWeek != null) lag7Index[i] = previousWeek;
            }

            csv.printRecord("CONS_NO", "FLAG",
                    "MEAN", "STD_DEV", "MIN", "MAX", "MEDIAN", "CV", "PAR",
                    "SKEWNESS", "P10", "P90", "ZERO_READING_RATE",
                    "TREND", "LAG7_AUTOCORR", "WEEKEND_WEEKDAY_RATIO", "MONTHLY_VARIABILITY",
                    "MAX_ROLLING_DROP", "LONGEST_LOW_CONSUMPTION_STREAK");

            Set<String> seenConsumers = new HashSet<>();
            long processed = 0;
            for (CSVRecord row : parser) {
                if (row.size() != originalHeaders.size())
                    throw new IOException("Malformed CSV row " + row.getRecordNumber());
                String id = row.get("CONS_NO").trim();
                String flag = row.get("FLAG").trim();
                if (id.isEmpty() || !(flag.equals("0") || flag.equals("1")))
                    throw new IOException("Invalid consumer or fraud label at row " + row.getRecordNumber());
                if (!seenConsumers.add(id))
                    throw new IOException("Duplicate consumer ID in cleaned dataset: " + id);

                double[] consumption = new double[n];
                for (int i = 0; i < n; i++) {
                    String cell = row.get(names[i]).trim();
                    try {
                        consumption[i] = Double.parseDouble(cell);
                    } catch (NumberFormatException e) {
                        throw new IOException("Non-numeric consumption for consumer " + id
                                + " on " + days[i] + ": " + cell, e);
                    }
                    if (!Double.isFinite(consumption[i]) || consumption[i] < 0)
                        throw new IOException("Invalid consumption for consumer " + id
                                + " on " + days[i] + ": " + cell);
                }

                double[] f = calculateFeatures(consumption, days, lag7Index, continuityBreaks);
                List<Object> record = new ArrayList<>(f.length + 2);
                record.add(id);
                record.add(flag);
                for (double value : f) record.add(value);
                csv.printRecord(record);

                processed++;
                if (processed % 5000 == 0)
                    System.out.printf(Locale.US, "Processed %,d consumers%n", processed);
            }
            System.out.printf(Locale.US, "Completed %,d consumers; wrote %s%n",
                    processed, destination);
        }
        return destination;
    }

    // Package-private for testing. Feature order must match the CSV header above.
    static double[] calculateFeatures(double[] v, LocalDate[] days,
                                      int[] lag7Index, int[] continuityBreaks) {
        int n = v.length;
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        int zeroCount = 0;
        for (double x : v) {
            sum += x;
            min = Math.min(min, x);
            max = Math.max(max, x);
            if (x == 0.0) zeroCount++;
        }
        double mean = sum / n;
        double m2 = 0, m3 = 0;
        for (double x : v) {
            double diff = x - mean;
            m2 += diff * diff;
            m3 += diff * diff * diff;
        }
        m2 /= n;
        m3 /= n;
        double sd = Math.sqrt(m2);
        double median, p10, p90;
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        median = percentile(sorted, 0.50);
        p10 = percentile(sorted, 0.10);
        p90 = percentile(sorted, 0.90);
        double cv = mean > 0 ? sd / mean : 0;
        double par = mean > 0 ? max / mean : 0;
        double skewness = sd > 0 ? m3 / (sd * sd * sd) : 0;
        double zeroRate = (double) zeroCount / n;

        // Least-squares trend per actual calendar day, not merely per column position.
        double timeMean = 0;
        long origin = days[0].toEpochDay();
        for (LocalDate day : days) timeMean += day.toEpochDay() - origin;
        timeMean /= n;
        double trendNum = 0, trendDen = 0;
        for (int i = 0; i < n; i++) {
            double dx = days[i].toEpochDay() - origin - timeMean;
            trendNum += dx * (v[i] - mean);
            trendDen += dx * dx;
        }
        double trend = trendDen > 0 ? trendNum / trendDen : 0;

        // Pearson correlation of observed dates exactly 7 calendar days apart.
        double lagX = 0, lagY = 0;
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            if (lag7Index[i] >= 0) {
                lagX += v[i];
                lagY += v[lag7Index[i]];
                pairs++;
            }
        }
        double lag = 0;
        if (pairs >= 2) {
            lagX /= pairs;
            lagY /= pairs;
            double cov = 0, vx = 0, vy = 0;
            for (int i = 0; i < n; i++) {
                if (lag7Index[i] < 0) continue;
                double x = v[i] - lagX;
                double y = v[lag7Index[i]] - lagY;
                cov += x * y;
                vx += x * x;
                vy += y * y;
            }
            if (vx > 0 && vy > 0) lag = cov / Math.sqrt(vx * vy);
        }

        // Calendar-based weekend/weekday consumption ratio.
        double weekdaySum = 0, weekendSum = 0;
        int weekdayDays = 0, weekendDays = 0;
        Map<YearMonth, double[]> monthly = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            DayOfWeek dayOfWeek = days[i].getDayOfWeek();
            if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                weekendSum += v[i];
                weekendDays++;
            } else {
                weekdaySum += v[i];
                weekdayDays++;
            }
            double[] bucket = monthly.computeIfAbsent(YearMonth.from(days[i]), m -> new double[2]);
            bucket[0] += v[i];
            bucket[1]++;
        }
        double weekdayMean = weekdayDays > 0 ? weekdaySum / weekdayDays : 0;
        double weekendMean = weekendDays > 0 ? weekendSum / weekendDays : 0;
        // Zero denotes unavailable when weekday mean is zero; keep this convention documented.
        double weekendWeekdayRatio = weekdayMean > 0 && weekendDays > 0
                ? weekendMean / weekdayMean : 0;

        // Coefficient of variation across approximate calendar-month means.
        // Require at least 20 available days per included month to reduce edge bias.
        List<Double> monthMeans = new ArrayList<>();
        for (double[] bucket : monthly.values()) {
            if (bucket[1] >= MIN_MONTH_DAYS)
                monthMeans.add(bucket[0] / bucket[1]);
        }
        double monthlyVariability = 0;
        if (monthMeans.size() >= 2) {
            double mu = 0;
            for (double x : monthMeans) mu += x;
            mu /= monthMeans.size();
            double var = 0;
            for (double x : monthMeans) var += (x - mu) * (x - mu);
            var /= monthMeans.size();
            if (mu > 0) monthlyVariability = Math.sqrt(var) / mu;
        }

        // Largest relative drop from one 30-day period to the immediately following 30 days,
        // anywhere in the sequence. Skip 60-day windows containing missing calendar dates.
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + v[i];
        double maxRollingDrop = 0;
        for (int start = 0; start + 2 * WINDOW <= n; start++) {
            int end = start + 2 * WINDOW;
            if (continuityBreaks[end] != continuityBreaks[start + 1]) continue;
            double previousMean = (prefix[start + WINDOW] - prefix[start]) / WINDOW;
            double followingMean = (prefix[end] - prefix[start + WINDOW]) / WINDOW;
            if (previousMean > 0) {
                double drop = (previousMean - followingMean) / previousMean;
                maxRollingDrop = Math.max(maxRollingDrop, drop);
            }
        }

        // Longest calendar-consecutive streak below 50% of the consumer's median.
        // Median=0: no meaningful below-median threshold, hence return 0.
        int currentStreak = 0, longestStreak = 0;
        if (median > 0) {
            double lowThreshold = LOW_USAGE_FRACTION * median;
            for (int i = 0; i < n; i++) {
                boolean consecutive = i == 0 || days[i - 1].plusDays(1).equals(days[i]);
                if (v[i] < lowThreshold) {
                    currentStreak = consecutive ? currentStreak + 1 : 1;
                    longestStreak = Math.max(longestStreak, currentStreak);
                } else {
                    currentStreak = 0;
                }
            }
        }

        return new double[]{mean, sd, min, max, median, cv, par,
                skewness, p10, p90, zeroRate,
                trend, lag, weekendWeekdayRatio, monthlyVariability,
                maxRollingDrop, longestStreak};
    }

    /** Linear interpolation between adjacent sorted observations. */
    private static double percentile(double[] sorted, double fraction) {
        double position = (sorted.length - 1) * fraction;
        int lo = (int) Math.floor(position);
        int hi = (int) Math.ceil(position);
        double part = position - lo;
        return sorted[lo] + part * (sorted[hi] - sorted[lo]);
    }

    public static void main(String[] args) {
        Path input = Path.of(args.length > 0 ? args[0] : "dataset/sgcc_cleaned_uncapped.csv");
        Path output = Path.of(args.length > 1 ? args[1] : "dataset/sgcc_features.csv");
        String format = args.length > 2 ? args[2] : "M/d/uuuu";
        try {
            System.out.println("FEATURE EXTRACTION — 17 FEATURES");
            extract(input, output, format);
            System.out.println("Feature extraction completed successfully.");
        } catch (IOException e) {
            System.err.println("Feature extraction failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
