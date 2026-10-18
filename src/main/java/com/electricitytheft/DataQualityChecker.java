
package com.electricitytheft;

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
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

public class DataQualityChecker {

    private static final Path INPUT =
            Paths.get("dataset", "sgcc_cleaned.csv");

    private static final Path OUTPUT =
            Paths.get("dataset", "sgcc_data_quality.csv");

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("M/d/uuuu");

    public static void main(String[] args) throws Exception {

        int customers = 0;
        int completelyMissing = 0;
        int below10 = 0;
        int below50 = 0;
        int below80 = 0;
        int above80 = 0;

        long totalValid = 0;
        long totalMissing = 0;
        long totalZeros = 0;

        try (
            Reader reader = Files.newBufferedReader(
                    INPUT, StandardCharsets.UTF_8);

            CSVParser parser =
                    CSVFormat.DEFAULT.parse(reader);

            Writer writer = Files.newBufferedWriter(
                    OUTPUT, StandardCharsets.UTF_8);

            CSVPrinter printer =
                    new CSVPrinter(writer, CSVFormat.DEFAULT)
        ) {

            var iterator = parser.iterator();

            if (!iterator.hasNext()) {
                throw new IllegalStateException("CSV is empty");
            }

            CSVRecord header = iterator.next();

            List<Integer> dateColumns = new ArrayList<>();
            int idIndex = -1;

            for (int i = 0; i < header.size(); i++) {

                String name = header.get(i).trim()
                        .replace("\uFEFF", "");

                if (name.equalsIgnoreCase("CONS_NO")) {
                    idIndex = i;
                }

                try {
                    LocalDate.parse(name, DATE_FORMAT);
                    dateColumns.add(i);
                } catch (DateTimeParseException ignored) {
                }
            }

            if (dateColumns.isEmpty()) {
                throw new IllegalStateException(
                        "No daily consumption columns found");
            }

            int totalDays = dateColumns.size();

            System.out.println("Total daily columns: " + totalDays);

            printer.printRecord(
                    "CONS_NO",
                    "EXCEL_ROW",
                    "VALID_DAYS",
                    "MISSING_DAYS",
                    "ZERO_DAYS",
                    "COMPLETENESS_PERCENT",
                    "MISSING_PERCENT"
            );

            while (iterator.hasNext()) {

                CSVRecord record = iterator.next();
                customers++;

                int valid = 0;
                int missing = 0;
                int zeros = 0;

                for (int col : dateColumns) {

                    String value = col < record.size()
                            ? record.get(col).trim()
                            : "";

                    if (value.isEmpty()
                            || value.equalsIgnoreCase("NaN")
                            || value.equalsIgnoreCase("null")
                            || value.equalsIgnoreCase("NA")) {

                        missing++;
                        continue;
                    }

                    try {
                        double reading =
                                Double.parseDouble(value);

                        if (!Double.isFinite(reading)) {
                            missing++;
                        } else {
                            valid++;

                            if (reading == 0) {
                                zeros++;
                            }
                        }

                    } catch (NumberFormatException e) {
                        missing++;
                    }
                }

                double completeness =
                        100.0 * valid / totalDays;

                double missingPercent =
                        100.0 * missing / totalDays;

                if (valid == 0) {
                    completelyMissing++;
                }

                if (completeness < 10) {
                    below10++;
                } else if (completeness < 50) {
                    below50++;
                } else if (completeness < 80) {
                    below80++;
                } else {
                    above80++;
                }

                totalValid += valid;
                totalMissing += missing;
                totalZeros += zeros;

                String id = idIndex >= 0
                        ? record.get(idIndex)
                        : String.valueOf(customers);

                int excelRow = customers + 1;

                printer.printRecord(
                        id,
                        excelRow,
                        valid,
                        missing,
                        zeros,
                        completeness,
                        missingPercent
                );

                // Inspect the exact Excel rows from your screenshot.
                if (excelRow >= 42340
                        && excelRow <= 42368) {

                    System.out.printf(
                            "Row %d: valid=%d, missing=%d, "
                            + "zero=%d, completeness=%.2f%%%n",
                            excelRow,
                            valid,
                            missing,
                            zeros,
                            completeness);
                }
            }
        }

        System.out.println("\n===== DATA QUALITY SUMMARY =====");

        System.out.println("Total customers: " + customers);
        System.out.println("Completely missing: " + completelyMissing);
        System.out.println("Below 10% complete: " + below10);
        System.out.println("10%-50% complete: " + below50);
        System.out.println("50%-80% complete: " + below80);
        System.out.println("80%-100% complete: " + above80);

        System.out.println("Total valid readings: " + totalValid);
        System.out.println("Total missing readings: " + totalMissing);
        System.out.println("Total zero readings: " + totalZeros);

        System.out.println(
                "\nQuality report saved to: "
                + OUTPUT.toAbsolutePath());
    }
}
