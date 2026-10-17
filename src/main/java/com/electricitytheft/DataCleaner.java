
package com.electricitytheft;

import java.io.BufferedWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

public class DataCleaner {

    public static void main(String[] args) {
        Path input = Paths.get("dataset", "data set.csv");
        Path output = Paths.get("dataset", "sgcc_cleaned.csv");
        Path report = Paths.get("dataset", "cleaning_report.txt");

        long totalRows = 0;
        long retainedRows = 0;
        long emptyRows = 0;
        long duplicateRows = 0;
        long invalidMetadataRows = 0;
        long missingReadings = 0;
        long invalidReadings = 0;
        long normalRows = 0;
        long fraudRows = 0;

        Set<String> customerIds = new HashSet<>();

        try {
            if (!Files.exists(input)) {
                System.out.println("Input file not found: "
                        + input.toAbsolutePath());
                return;
            }

            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setIgnoreEmptyLines(true)
                    .get();

            try (Reader reader = Files.newBufferedReader(
                        input, StandardCharsets.UTF_8);
                 CSVParser parser = format.parse(reader);
                 BufferedWriter writer = Files.newBufferedWriter(
                        output, StandardCharsets.UTF_8);
                 CSVPrinter printer = new CSVPrinter(writer, format)) {

                java.util.Iterator<CSVRecord> iterator =
                        parser.iterator();

                if (!iterator.hasNext()) {
                    throw new IllegalArgumentException("CSV is empty.");
                }

                CSVRecord headerRecord = iterator.next();
                int columns = headerRecord.size();
                String[] headers = new String[columns];

                int idIndex = -1;
                int labelIndex = -1;

                for (int i = 0; i < columns; i++) {
                    headers[i] = headerRecord.get(i).trim()
                            .replace("\uFEFF", "");

                    if (headers[i].equalsIgnoreCase("CONS_NO")) {
                        idIndex = i;
                    }
                    if (headers[i].equalsIgnoreCase("FLAG")) {
                        labelIndex = i;
                    }
                }

                if (idIndex < 0 || labelIndex < 0) {
                    throw new IllegalArgumentException(
                            "Required CONS_NO or FLAG column missing.");
                }

                printer.printRecord((Object[]) headers);

                while (iterator.hasNext()) {
                    CSVRecord record = iterator.next();
                    totalRows++;

                    if (record.size() != columns) {
                        invalidMetadataRows++;
                        continue;
                    }

                    String[] values = new String[columns];

                    for (int i = 0; i < columns; i++) {
                        values[i] = record.get(i).trim();
                    }

                    String customerId = values[idIndex];
                    String label = values[labelIndex];

                    if (customerId.isEmpty()
                            || !(label.equals("0")
                            || label.equals("1"))) {
                        invalidMetadataRows++;
                        continue;
                    }

                    boolean hasReading = false;

                    for (int i = 0; i < columns; i++) {
                        if (i == idIndex || i == labelIndex) {
                            continue;
                        }

                        if (values[i].isEmpty()
                                || values[i].equalsIgnoreCase("null")
                                || values[i].equalsIgnoreCase("nan")) {
                            values[i] = "";
                            missingReadings++;
                            continue;
                        }

                        try {
                            double reading = Double.parseDouble(values[i]);

                            if (!Double.isFinite(reading)
                                    || reading < 0) {
                                values[i] = "";
                                invalidReadings++;
                            } else {
                                hasReading = true;
                            }

                        } catch (NumberFormatException e) {
                            values[i] = "";
                            invalidReadings++;
                        }
                    }

                    if (!hasReading) {
                        emptyRows++;
                        continue;
                    }

                    if (!customerIds.add(customerId)) {
                        duplicateRows++;
                        continue;
                    }

                    printer.printRecord((Object[]) values);
                    retainedRows++;

                    if (label.equals("0")) {
                        normalRows++;
                    } else {
                        fraudRows++;
                    }

                    if (totalRows % 5000 == 0) {
                        System.out.println(
                                "Processed " + totalRows + " rows...");
                    }
                }
            }

            try (BufferedWriter reportWriter =
                    Files.newBufferedWriter(
                            report, StandardCharsets.UTF_8)) {

                reportWriter.write("SGCC DATA CLEANING REPORT\n");
                reportWriter.write("=========================\n");
                reportWriter.write("Total input rows: " + totalRows + "\n");
                reportWriter.write("Retained rows: " + retainedRows + "\n");
                reportWriter.write("Empty consumption rows: "
                        + emptyRows + "\n");
                reportWriter.write("Duplicate customer IDs: "
                        + duplicateRows + "\n");
                reportWriter.write("Invalid metadata/row width: "
                        + invalidMetadataRows + "\n");
                reportWriter.write("Missing reading cells encountered: "
                        + missingReadings + "\n");
                reportWriter.write("Invalid reading cells encountered: "
                        + invalidReadings + "\n");
                reportWriter.write("Normal consumers retained: "
                        + normalRows + "\n");
                reportWriter.write("Fraud consumers retained: "
                        + fraudRows + "\n");
                reportWriter.write("\nNo imputation, scaling or class "
                        + "balancing was applied.\n");
            }

            System.out.println("\nSGCC cleaning completed!");
            System.out.println("Input rows: " + totalRows);
            System.out.println("Retained rows: " + retainedRows);
            System.out.println("Normal: " + normalRows);
            System.out.println("Fraud: " + fraudRows);
            System.out.println("Output: " + output.toAbsolutePath());
            System.out.println("Report: " + report.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("Cleaning failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
