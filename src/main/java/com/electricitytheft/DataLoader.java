
package com.electricitytheft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
public class DataLoader {

    public static List<String[]> loadPreview(
        Path filePath, int maxRows) throws IOException {

    List<String[]> rows = new ArrayList<>();

    if (maxRows < 0) {
        throw new IllegalArgumentException(
                "maxRows cannot be negative");
    }

    try (Reader reader = Files.newBufferedReader(
            filePath, StandardCharsets.UTF_8);

         CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {

        int count = 0;

        for (CSVRecord record : parser) {

            if (count > maxRows) {
                break;
            }

            String[] values = new String[record.size()];

            for (int i = 0; i < record.size(); i++) {
                values[i] = record.get(i);
            }

            rows.add(values);
            count++;
        }
    }

    return rows;
}
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean insideQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (insideQuotes && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    insideQuotes = !insideQuotes;
                }
            } else if (ch == ',' && !insideQuotes) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }

        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }
}
